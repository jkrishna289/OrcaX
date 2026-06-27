package com.github.jkrishna289.orcax.services

import com.github.jkrishna289.orcax.engine.BootstrapResponse
import com.github.jkrishna289.orcax.engine.EngineJson
import com.github.jkrishna289.orcax.engine.EngineRequestBody
import com.github.jkrishna289.orcax.engine.RenderBundle
import com.github.jkrishna289.orcax.engine.RequestResult
import com.github.jkrishna289.orcax.engine.SUPPORTED_CARD_TYPES_QUERY
import com.github.jkrishna289.orcax.engine.SimilarResponse
import com.github.jkrishna289.orcax.engine.TelemetryBatch
import com.github.jkrishna289.orcax.engine.TelemetryEvent
import com.github.jkrishna289.orcax.services.hilt.AuthOkHttpClient
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the server-side Orca Engine plugin (the `/OrcaEngine` endpoints).
 *
 * Reuses the authenticated OkHttp client (the Jellyfin token is injected automatically) and
 * derives the base URL from the Jellyfin [ApiClient], so it targets whichever server the user
 * is signed into. All calls fail soft (return null/false) so the app degrades gracefully when
 * the engine isn't installed.
 */
@Singleton
class OrcaEngineClient
    @Inject
    constructor(
        @AuthOkHttpClient private val okHttpClient: OkHttpClient,
        private val apiClient: ApiClient,
    ) {
        private fun baseUrl(): String? = apiClient.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }

        /** Fetches the preview render bundle, advertising this build's supported card types. */
        suspend fun getCardPreview(): RenderBundle? {
            val base = baseUrl() ?: return null
            val url = "$base$ENGINE_PATH/Cards/Preview?supported=$SUPPORTED_CARD_TYPES_QUERY"
            return get(url) { body -> EngineJson.decodeFromString<RenderBundle>(body) }
        }

        /**
         * Fetches the home render bundle (catalog-backed). When [userId] is non-null the engine
         * personalizes the result (leads with a "For You" row); otherwise it returns global defaults.
         */
        suspend fun getHome(
            userId: UUID? = null,
            rowSize: Int = 20,
            inlineVideo: Boolean = false,
        ): RenderBundle? {
            val base = baseUrl() ?: return null
            val url = buildString {
                append(base).append(ENGINE_PATH).append("/Home")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&rowSize=").append(rowSize)
                if (userId != null) append("&userId=").append(userId)
                if (inlineVideo) append("&inlineVideo=true")
            }
            return get(url) { body -> EngineJson.decodeFromString<RenderBundle>(body) }
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
            val base = baseUrl() ?: return null
            val url = buildString {
                append(base).append(ENGINE_PATH).append("/Bootstrap")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&rowSize=").append(rowSize)
                if (userId != null) append("&userId=").append(userId)
                if (inlineVideo) append("&inlineVideo=true")
            }
            return get(url) { body -> EngineJson.decodeFromString<BootstrapResponse>(body) }
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
            val base = baseUrl() ?: return null
            val body = EngineJson.encodeToString(EngineRequestBody(userId.toString(), tmdbId, mediaType, title))
            return post("$base$ENGINE_PATH/Requests", body) { EngineJson.decodeFromString<RequestResult>(it) }
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
            val base = baseUrl() ?: return null
            val url = buildString {
                append(base).append(ENGINE_PATH).append("/Similar")
                append("?supported=").append(SUPPORTED_CARD_TYPES_QUERY)
                append("&limit=").append(limit)
                if (jellyfinId != null) append("&jellyfinId=").append(jellyfinId)
                if (tmdbId != null) append("&tmdbId=").append(tmdbId)
            }
            return get(url) { body -> EngineJson.decodeFromString<SimilarResponse>(body) }
        }

        /** Batch-sends home telemetry (focus/dwell, clicks) to the engine. Fire-and-forget. */
        suspend fun recordEvents(
            userId: UUID,
            events: List<TelemetryEvent>,
        ): Boolean {
            if (events.isEmpty()) return false
            val base = baseUrl() ?: return false
            val body = EngineJson.encodeToString(TelemetryBatch(userId.toString(), events))
            return post("$base$ENGINE_PATH/Behavior/Events", body) { true } ?: false
        }

        /**
         * Builds the server-side trailer stream URL for a title (the engine serves a cached, low-bitrate
         * clip). Returns null when no TMDB id / server. A 404 from this URL simply means "not cached" —
         * the player falls back silently. The endpoint is anonymous, so no auth header is needed.
         */
        fun trailerUrl(
            tmdbId: Int?,
            mediaType: com.github.jkrishna289.orcax.engine.MediaType,
        ): String? {
            if (tmdbId == null || tmdbId <= 0) return null
            val base = baseUrl() ?: return null
            val type = if (mediaType == com.github.jkrishna289.orcax.engine.MediaType.SERIES) "tv" else "movie"
            return "$base$ENGINE_PATH/Trailer/$tmdbId?type=$type"
        }

        /** Returns true if the engine plugin responds on this server (for graceful degradation). */
        suspend fun isEngineAvailable(): Boolean {
            val base = baseUrl() ?: return false
            return get("$base$ENGINE_PATH/Health") { true } ?: false
        }

        private suspend fun <T> get(
            url: String,
            parse: (String) -> T,
        ): T? =
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Orca Engine request failed: %d %s", response.code, url)
                            return@use null
                        }
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) null else parse(body)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Orca Engine request error: %s", url)
                    null
                }
            }

        private suspend fun <T> post(
            url: String,
            jsonBody: String,
            parse: (String) -> T,
        ): T? =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        Request.Builder()
                            .url(url)
                            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (!response.isSuccessful) {
                            Timber.w("Orca Engine POST failed: %d %s", response.code, url)
                            return@use null
                        }
                        if (body.isNullOrBlank()) null else parse(body)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Orca Engine POST error: %s", url)
                    null
                }
            }

        companion object {
            private const val ENGINE_PATH = "/OrcaEngine"
            private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        }
    }
