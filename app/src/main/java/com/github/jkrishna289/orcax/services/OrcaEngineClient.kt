package com.github.jkrishna289.orcax.services

import android.os.SystemClock
import com.github.jkrishna289.orcax.engine.AvailabilityStatusResponse
import com.github.jkrishna289.orcax.engine.BootstrapResponse
import com.github.jkrishna289.orcax.engine.ContentWarningsResponse
import com.github.jkrishna289.orcax.engine.EngineJson
import com.github.jkrishna289.orcax.engine.EngineRequestBody
import com.github.jkrishna289.orcax.engine.FeatureFlags
import com.github.jkrishna289.orcax.engine.FeedbackBody
import com.github.jkrishna289.orcax.engine.RenderBundle
import com.github.jkrishna289.orcax.engine.SourceGroups
import com.github.jkrishna289.orcax.engine.RequestResult
import com.github.jkrishna289.orcax.engine.TriviaResponse
import com.github.jkrishna289.orcax.engine.SUPPORTED_CARD_TYPES_QUERY
import com.github.jkrishna289.orcax.engine.SimilarResponse
import com.github.jkrishna289.orcax.engine.StreamSessionBody
import com.github.jkrishna289.orcax.engine.StreamSessionResult
import com.github.jkrishna289.orcax.engine.StreamSessionStatus
import com.github.jkrishna289.orcax.engine.TelemetryBatch
import com.github.jkrishna289.orcax.engine.TelemetryEvent
import com.github.jkrishna289.orcax.engine.TrailerStatus
import com.github.jkrishna289.orcax.services.hilt.AuthOkHttpClient
import com.github.jkrishna289.orcax.services.torrent.TorrentStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.Volatile

/**
 * Talks to the server-side engine plugin ("Orca Engine").
 *
 * Reuses the authenticated OkHttp client (the Jellyfin token is injected automatically) and
 * derives the base URL from the Jellyfin [ApiClient], so it targets whichever server the user
 * is signed into. All calls fail soft (return null/false) so the app degrades gracefully when
 * the engine isn't installed.
 *
 * The plugin has shipped under two controller roots across renames — the current `/OrcaEngine`
 * and the older `/WholphinEngine`. Rather than hard-code one (and 404 against the other), every
 * request tries the candidate paths in order and caches whichever the connected server actually
 * serves ([resolvedPath]), so the client works against either deployment with no rebuild.
 */
@Singleton
class OrcaEngineClient
    @Inject
    constructor(
        @AuthOkHttpClient private val okHttpClient: OkHttpClient,
        private val apiClient: ApiClient,
    ) {
        private fun baseUrl(): String? = apiClient.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }

        /**
         * A long-timeout twin of the shared client, used only for the home fetch. The default 30s
         * read / 60s call bounds exist so a hung host can't stall the trailer-status polls, but a
         * cold personalized home legitimately takes longer to build — and being cut off there is
         * exactly what used to drop the viewer onto the legacy home. The home request is background
         * work behind an already-painted screen now, so it can afford to wait for the real bundle.
         * Shares the parent's connection pool and dispatcher, so this costs nothing to hold.
         */
        private val homeHttpClient: OkHttpClient by lazy {
            okHttpClient
                .newBuilder()
                .readTimeout(HOME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(HOME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /**
         * A long-timeout twin for opening a torrent stream session. Sibling of [homeHttpClient] and
         * needed for the same reason, only more so: the engine has to fetch torrent metadata from
         * peers, prebuffer the first and last piece, and then ffprobe the container (which alone has a
         * 90s budget server-side). The shared client's 30s read / 60s call bounds cut that off
         * mid-flight, and the viewer saw "couldn't open a stream" while the server was still working
         * and about to succeed.
         *
         * ponytail: a multi-minute blocking POST is the honest shape of the current design, not a good
         * one. If startup latency needs to be visible (progress, or cancel-and-pick-another), this
         * wants splitting into a create-then-poll pair — a bigger change than the timeout it replaces.
         */
        private val streamHttpClient: OkHttpClient by lazy {
            okHttpClient
                .newBuilder()
                .readTimeout(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /**
         * A long-timeout twin for source discovery. A cold search fans out across every configured
         * indexer and legitimately takes tens of seconds; the shared client's 30s read bound cut it
         * off and the viewer saw "no sources" for a search that was about to succeed. Only fast when
         * the engine's cache is warm, which is exactly when the shorter bound looked fine in testing.
         *
         * Shorter than [streamHttpClient] on purpose: a search that hasn't answered in two minutes is
         * not going to, and the viewer should be told rather than left waiting.
         */
        private val searchHttpClient: OkHttpClient by lazy {
            okHttpClient
                .newBuilder()
                .readTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        // The engine controller root the connected server actually serves, learned on the first
        // successful call and reused thereafter. Reset to null when a probe set stops responding.
        @Volatile
        private var resolvedPath: String? = null

        /** Fetches the preview render bundle, advertising this build's supported card types. */
        suspend fun getCardPreview(): RenderBundle? =
            get("/Cards/Preview?supported=$SUPPORTED_CARD_TYPES_QUERY") { EngineJson.decodeFromString<RenderBundle>(it) }

        /**
         * Fetches the home render bundle (catalog-backed). When [userId] is non-null the engine
         * personalizes the result (leads with a "For You" row); otherwise it returns global defaults.
         */
        suspend fun getHome(
            userId: UUID? = null,
            rowSize: Int = 20,
            inlineVideo: Boolean = false,
        ): RenderBundle? {
            val suffix = buildString {
                append("/Home")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&rowSize=").append(rowSize)
                if (userId != null) append("&userId=").append(userId)
                if (inlineVideo) append("&inlineVideo=true")
            }
            return get(suffix, homeHttpClient) { EngineJson.decodeFromString<RenderBundle>(it) }
        }

        /**
         * One-call launch payload (contract metadata + first home rows). Pass [userId] to
         * personalize. Returns null on failure so the caller can fall back gracefully.
         */
        suspend fun getBootstrap(
            userId: UUID? = null,
            rowSize: Int = 20,
            inlineVideo: Boolean = false,
        ): BootstrapResponse? {
            val suffix = buildString {
                append("/Bootstrap")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&rowSize=").append(rowSize)
                if (userId != null) append("&userId=").append(userId)
                if (inlineVideo) append("&inlineVideo=true")
            }
            return get(suffix, homeHttpClient) { EngineJson.decodeFromString<BootstrapResponse>(it) }
        }

        /**
         * Engine-proxied media request (Jellyseerr). Records request affinity server-side and
         * reflects the new availability. [mediaType] is "movie" or "tv". Returns null on failure.
         */
        suspend fun requestMedia(
            userId: UUID,
            tmdbId: Int,
            mediaType: String,
            title: String?,
        ): RequestResult? {
            val body = EngineJson.encodeToString(EngineRequestBody(userId.toString(), tmdbId, mediaType, title))
            return post("/Requests", body) { EngineJson.decodeFromString<RequestResult>(it) }
        }

        /**
         * "More Like This" for a title (identified by Jellyfin id or TMDB id). Returns a
         * directly-renderable row, or null on failure.
         */
        suspend fun getSimilar(
            jellyfinId: UUID? = null,
            tmdbId: Int? = null,
            limit: Int = 20,
        ): SimilarResponse? {
            val suffix = buildString {
                append("/Similar")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&limit=").append(limit)
                if (jellyfinId != null) append("&jellyfinId=").append(jellyfinId)
                if (tmdbId != null) append("&tmdbId=").append(tmdbId)
            }
            return get(suffix) { EngineJson.decodeFromString<SimilarResponse>(it) }
        }

        /**
         * "Did You Know?" trivia facts for a title (engine resolves by Jellyfin or TMDB id, served
         * from its permanent cache). Returns null when neither id is given or the engine is absent.
         */
        suspend fun getTrivia(
            jellyfinId: UUID? = null,
            tmdbId: Int? = null,
        ): TriviaResponse? {
            if (jellyfinId == null && tmdbId == null) return null
            val suffix = buildString {
                append("/Metadata/Trivia")
                val params =
                    buildList {
                        if (jellyfinId != null) add("jellyfinId=$jellyfinId")
                        if (tmdbId != null) add("tmdbId=$tmdbId")
                    }
                append("?").append(params.joinToString("&"))
            }
            return get(suffix) { EngineJson.decodeFromString<TriviaResponse>(it) }
        }

        /**
         * Content advisories for a title, shown as a passive overlay at playback start. The engine
         * generates them per movie/series via Groq and serves from its durable cache. For an episode,
         * pass the SERIES Jellyfin id (warnings are never per-episode). Returns null on failure / no server.
         */
        suspend fun getContentWarnings(
            jellyfinId: UUID? = null,
            tmdbId: Int? = null,
        ): ContentWarningsResponse? {
            if (jellyfinId == null && tmdbId == null) return null
            val suffix =
                buildString {
                    append("/Metadata/Warnings")
                    val params =
                        buildList {
                            if (jellyfinId != null) add("jellyfinId=$jellyfinId")
                            if (tmdbId != null) add("tmdbId=$tmdbId")
                        }
                    append("?").append(params.joinToString("&"))
                }
            return get(suffix) { EngineJson.decodeFromString<ContentWarningsResponse>(it) }
        }

        /**
         * Records an explicit thumbs up/down for a title — a high-weight personalization signal the
         * engine folds into future recommendations. Fire-and-forget; returns false on failure.
         */
        suspend fun sendFeedback(
            userId: UUID,
            itemId: UUID,
            thumbsUp: Boolean,
        ): Boolean {
            val body = EngineJson.encodeToString(FeedbackBody(userId.toString(), itemId.toString(), thumbsUp))
            return post("/Behavior/Feedback", body) { true } ?: false
        }

        /**
         * Current availability of a requested title (was it fulfilled since the user asked?). [mediaType]
         * is mapped to the engine's "movie"/"tv". Returns null on failure / no server.
         */
        suspend fun getRequestStatus(
            tmdbId: Int,
            mediaType: com.github.jkrishna289.orcax.engine.MediaType,
        ): AvailabilityStatusResponse? {
            val type = if (mediaType == com.github.jkrishna289.orcax.engine.MediaType.SERIES) "tv" else "movie"
            return get("/Requests/Status?tmdbId=$tmdbId&type=$type") { EngineJson.decodeFromString<AvailabilityStatusResponse>(it) }
        }

        /** Batch-sends home telemetry (focus/dwell, clicks) to the engine. Fire-and-forget. */
        suspend fun recordEvents(
            userId: UUID,
            events: List<TelemetryEvent>,
        ): Boolean {
            if (events.isEmpty()) return false
            val body = EngineJson.encodeToString(TelemetryBatch(userId.toString(), events))
            return post("/Behavior/Events", body) { true } ?: false
        }

        /**
         * Builds the server-side trailer stream URL for a title (the engine serves a cached, low-bitrate
         * clip). Returns null when no TMDB id / server. A 404 from this URL simply means "not cached" —
         * the player falls back silently. The endpoint is anonymous, so no auth header is needed.
         *
         * This is synchronous (returns a URL, doesn't fetch), so it can't probe: it uses the
         * already-[resolvedPath] when known, else the preferred candidate.
         */
        fun trailerUrl(
            tmdbId: Int?,
            mediaType: com.github.jkrishna289.orcax.engine.MediaType,
            lang: String? = null,
        ): String? {
            if (tmdbId == null || tmdbId <= 0) return null
            val base = baseUrl() ?: return null
            val path = resolvedPath ?: ENGINE_PATHS.first()
            val type = if (mediaType == com.github.jkrishna289.orcax.engine.MediaType.SERIES) "tv" else "movie"
            // The user's preferred trailer audio language: a production-time hint the engine uses
            // when the clip isn't cached yet (it picks a matching-language TMDB trailer).
            val langSuffix = lang?.takeIf { it.isNotBlank() }?.let { "&lang=$it" } ?: ""
            return "$base$path/Trailer/$tmdbId?type=$type$langSuffix"
        }

        /**
         * Queries the trailer state machine for a title (the trailer redesign's status protocol).
         * Returns the current lifecycle state, or null when the engine is unreachable / too old to
         * expose the endpoint (callers then fall back to a best-effort direct play attempt). The
         * endpoint always answers 200 (state "Unknown" on a miss), so it never trips the 404-based
         * root probing.
         */
        suspend fun getTrailerStatus(
            tmdbId: Int?,
            mediaType: com.github.jkrishna289.orcax.engine.MediaType,
            lang: String? = null,
        ): TrailerStatus? {
            if (tmdbId == null || tmdbId <= 0) return null
            val type = if (mediaType == com.github.jkrishna289.orcax.engine.MediaType.SERIES) "tv" else "movie"
            val langSuffix = lang?.takeIf { it.isNotBlank() }?.let { "&lang=$it" } ?: ""
            return get("/Trailer/$tmdbId/status?type=$type$langSuffix") { EngineJson.decodeFromString<TrailerStatus>(it) }
        }

        /**
         * Predictively enqueues trailer production for titles the user is likely to reach next (row
         * neighbours, the next hero, a detail page just opened) at a below-focus [priority]. Fire-and-
         * forget: the engine bounds concurrency and coalesces duplicates, so the client can prefetch
         * liberally. No-op when there's nothing to send / no engine.
         */
        suspend fun prefetchTrailers(
            items: List<com.github.jkrishna289.orcax.engine.TrailerPrefetchItem>,
            priority: String,
            lang: String? = null,
        ): Boolean {
            if (items.isEmpty()) return false
            val body = EngineJson.encodeToString(items)
            val langSuffix = lang?.takeIf { it.isNotBlank() }?.let { "&lang=$it" } ?: ""
            return post("/Trailer/Prefetch?priority=$priority$langSuffix", body) { true } ?: false
        }

        /**
         * The engine's effective feature flags, used to decide what the UI may offer at all. Returns
         * null when there's no engine, in which case callers treat every flag as off.
         */
        suspend fun getFeatures(): FeatureFlags? =
            get("/Settings/Features") { EngineJson.decodeFromString<FeatureFlags>(it) }

        /**
         * Finds streamable sources for a title. Called only in response to an explicit user action —
         * never while browsing — so indexer load stays proportional to actual intent.
         *
         * Returns null when the engine is absent, source streaming is disabled (the endpoint 404s by
         * design), or no indexer is configured. An empty [SourceGroups.all] means the search ran and
         * genuinely found nothing, which the UI words differently.
         */
        suspend fun getSources(
            title: String,
            year: Int? = null,
            mediaType: com.github.jkrishna289.orcax.engine.MediaType =
                com.github.jkrishna289.orcax.engine.MediaType.MOVIE,
            season: Int? = null,
            episode: Int? = null,
            preferredHeight: Int? = null,
        ): SourceGroups? {
            if (title.isBlank()) return null
            val type = if (mediaType == com.github.jkrishna289.orcax.engine.MediaType.SERIES) "tv" else "movie"
            val suffix =
                buildString {
                    append("/Sources?title=").append(java.net.URLEncoder.encode(title, "UTF-8"))
                    append("&type=").append(type)
                    if (year != null && year > 1900) append("&year=").append(year)
                    if (season != null) append("&season=").append(season)
                    if (episode != null) append("&episode=").append(episode)
                    if (preferredHeight != null && preferredHeight > 0) {
                        append("&preferredHeight=").append(preferredHeight)
                    }
                }
            return get(suffix, searchHttpClient) { EngineJson.decodeFromString<SourceGroups>(it) }
        }

        /**
         * Opens a torrent streaming session and returns the absolute URL the player should fetch.
         *
         * Returns null when the engine is absent, the operator hasn't enabled source streaming (the
         * endpoint 404s by design), the magnet holds no playable video, or the session cap is hit —
         * all of which the caller surfaces the same way: this source didn't work, try another.
         *
         * The returned URL carries the session's capability token, so it plays without an auth header
         * (which matters: the ExoPlayer data source doesn't attach one).
         */
        suspend fun openStreamSession(
            sourceId: String? = null,
            magnet: String? = null,
            season: Int? = null,
            episode: Int? = null,
        ): TorrentStream? {
            if (sourceId.isNullOrBlank() && magnet.isNullOrBlank()) return null
            val base = baseUrl() ?: return null
            val body =
                EngineJson.encodeToString(
                    StreamSessionBody(sourceId = sourceId, magnet = magnet, season = season, episode = episode),
                )
            val result =
                post("/Stream/Sessions", body, streamHttpClient) {
                    EngineJson.decodeFromString<StreamSessionResult>(it)
                } ?: return null

            if (result.path.isBlank()) return null
            return TorrentStream(
                url = base + result.path,
                token = result.token,
                fileName = result.fileName,
                sizeBytes = result.length.takeIf { it > 0 },
                mediaInfo = result.mediaInfo,
            )
        }

        /**
         * Reads a session's progress and live swarm health.
         *
         * Returns null when the session is gone — failed and swept, or evicted for idleness — which
         * the caller treats as a terminal "this stream stopped responding" rather than retrying.
         */
        suspend fun getStreamStatus(token: String): StreamSessionStatus? {
            if (token.isBlank()) return null
            return get("/Stream/Sessions/$token/status") {
                EngineJson.decodeFromString<StreamSessionStatus>(it)
            }
        }

        /**
         * Asks the engine to keep a streamed title: finish the download, copy it into the library
         * folder and scan it in.
         *
         * Returns true once the engine has *scheduled* the work, not when the film has landed —
         * completing a torrent takes minutes and the viewer has already left the prompt behind. False
         * means the session expired or no keep folder is configured, which the caller reports the
         * same way: this one couldn't be kept.
         */
        suspend fun keepStream(token: String): Boolean {
            if (token.isBlank()) return false
            return post("/Stream/Sessions/$token/keep", "{}") { true } ?: false
        }

        // ponytail: no explicit session close yet — that needs a DELETE verb in the shared
        // get/post plumbing below, and touching that risks every existing engine call for a
        // behaviour the server's idle sweep already covers. The engine tears a session down after
        // StreamSessionIdleMinutes, so nothing leaks; it just keeps seeding a little longer than
        // necessary. Wire DELETE /Stream/{token} up when playback teardown has a real UI path.

        /** Returns true if the engine plugin responds on this server (for graceful degradation). */
        suspend fun isEngineAvailable(): Boolean = get("/Health") { true } ?: false

        /**
         * The engine's LAN app-update endpoint (GitHub-release-shaped JSON whose asset URLs point at
         * the server's disk-cached APKs), or null when no server is connected / the engine doesn't
         * respond. Probing /Health first also resolves the controller root, so the returned URL
         * matches whichever plugin deployment is live.
         */
        suspend fun updateLatestUrl(): String? {
            if (!isEngineAvailable()) return null
            val base = baseUrl() ?: return null
            val path = resolvedPath ?: ENGINE_PATHS.first()
            return "$base$path/Update/Latest"
        }

        // ── Request plumbing ────────────────────────────────────────────────────
        // get/post take a path suffix (e.g. "/Home?…") and resolve the engine root across the
        // candidate paths, caching the winner. A 404 means "wrong root, try the next candidate";
        // any other non-2xx or a network error is a real failure and stops the attempt.

        private suspend fun <T> get(
            suffix: String,
            client: OkHttpClient = okHttpClient,
            parse: (String) -> T,
        ): T? = execute(suffix, jsonBody = null, client, parse)

        private suspend fun <T> post(
            suffix: String,
            jsonBody: String,
            client: OkHttpClient = okHttpClient,
            parse: (String) -> T,
        ): T? = execute(suffix, jsonBody, client, parse)

        private suspend fun <T> execute(
            suffix: String,
            jsonBody: String?,
            client: OkHttpClient,
            parse: (String) -> T,
        ): T? =
            withContext(Dispatchers.IO) {
                val base = baseUrl() ?: return@withContext null
                val candidates = resolvedPath?.let { listOf(it) } ?: ENGINE_PATHS
                for (path in candidates) {
                    when (val outcome = attempt("$base$path$suffix", jsonBody, client, parse)) {
                        is Attempt.Ok -> {
                            resolvedPath = path
                            return@withContext outcome.value
                        }
                        Attempt.WrongPath -> Unit // try the next candidate root
                        Attempt.Failed -> return@withContext null
                    }
                }
                // Neither candidate served this path set — clear so the next call re-probes both,
                // in case the server was mid-restart or the plugin gets (re)deployed.
                resolvedPath = null
                null
            }

        private fun <T> attempt(
            url: String,
            jsonBody: String?,
            client: OkHttpClient,
            parse: (String) -> T,
        ): Attempt<T> =
            try {
                val builder = Request.Builder().url(url)
                val request =
                    if (jsonBody == null) {
                        builder.get().build()
                    } else {
                        builder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE)).build()
                    }
                val sentAt = SystemClock.elapsedRealtime()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> Attempt.WrongPath
                        !response.isSuccessful -> {
                            Timber.w("Orca Engine request failed: %d %s", response.code, url)
                            Attempt.Failed
                        }
                        else -> {
                            // Transfer and parse are timed apart because the performance budget
                            // treats them apart (<30ms network, <10ms parse) — lumped together
                            // there's no way to tell a slow LAN from a slow decode.
                            val body = response.body.string()
                            val transferredAt = SystemClock.elapsedRealtime()
                            val parsed = if (body.isBlank()) null else parse(body)
                            Timber.i(
                                "Orca Engine timing: transfer=%dms parse=%dms bytes=%d %s",
                                transferredAt - sentAt,
                                SystemClock.elapsedRealtime() - transferredAt,
                                body.length,
                                url,
                            )
                            Attempt.Ok(parsed)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Orca Engine request error: %s", url)
                Attempt.Failed
            }

        /** The outcome of a single HTTP attempt, classified for engine-root resolution. */
        private sealed interface Attempt<out T> {
            /** 2xx response; [value] is the parsed body (null when the body was empty). */
            data class Ok<T>(val value: T?) : Attempt<T>

            /** 404 — this controller root doesn't exist here; try the next candidate. */
            data object WrongPath : Attempt<Nothing>

            /** Non-2xx (other than 404) or a network error — a real failure; stop. */
            data object Failed : Attempt<Nothing>
        }

        companion object {
            // Controller roots to probe, preferred first. The current plugin serves "/OrcaEngine";
            // older ("Wholphin Engine") deployments serve "/WholphinEngine". Whichever responds is
            // cached in [resolvedPath] so subsequent calls skip straight to it.
            private val ENGINE_PATHS = listOf("/OrcaEngine", "/WholphinEngine")
            private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

            /** Read/call bound for the home fetch — see [homeHttpClient] for why it's this generous. */
            private const val HOME_TIMEOUT_SECONDS = 120L

            /** Read/call bound for opening a stream session — see [streamHttpClient]. */
            private const val STREAM_TIMEOUT_SECONDS = 300L

            /** Read/call bound for a cold source search — see [searchHttpClient]. */
            private const val SEARCH_TIMEOUT_SECONDS = 120L
        }
    }
