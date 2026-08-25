package com.github.jkrishna289.orcax.ui.main

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.TelemetryEvent
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.HomeBundleCache
import com.github.jkrishna289.orcax.services.ImageUrlService
import com.github.jkrishna289.orcax.services.LocalHomeBundleBuilder
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.DEFAULT_TRAILER_PREVIEW_VOLUME
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.toTrailerVolume
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Loads the engine's personalized home (billboard + rows) for the main screen, resolving the
 * authenticated user id. Falls back to a vanilla layout via [EngineHomeState.Unavailable] when the
 * engine can't serve a bundle.
 */
@HiltViewModel
class EngineHomeViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val localHomeBundleBuilder: LocalHomeBundleBuilder,
        private val homeBundleCache: HomeBundleCache,
        private val preferences: DataStore<AppPreferences>,
        private val navigationManager: NavigationManager,
        private val backdropService: BackdropService,
        private val imageUrlService: ImageUrlService,
        private val api: ApiClient,
        private val favoriteWatchManager: FavoriteWatchManager,
        // Shared, reusable inline-trailer players (Phase 10) — provided to the UI via LocalTrailerPlayerPool.
        val trailerPlayerPool: com.github.jkrishna289.orcax.services.trailer.TrailerPlayerPool,
    ) : ViewModel() {
        private val _state = MutableStateFlow<EngineHomeState>(EngineHomeState.Loading)
        val state: StateFlow<EngineHomeState> = _state.asStateFlow()

        // Optimistic favorite/watchlist state for the spotlight's "+ Watchlist" button. Reflects
        // whichever spotlight item is currently showing as the billboard rotates.
        private val _heroFavorite = MutableStateFlow(false)
        val heroFavorite: StateFlow<Boolean> = _heroFavorite.asStateFlow()

        // Single source of truth for inline-trailer preview volume (Phase 13): the user's
        // preference mapped to a 0f..1f level. The billboard and 16:9 card players both read this
        // (via LocalTrailerVolume), so changing it in Settings applies live to every trailer player.
        val trailerVolume: StateFlow<Float> =
            preferences.data
                .map { it.interfacePreferences.trailerPreviewVolume.toTrailerVolume() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_TRAILER_PREVIEW_VOLUME)

        // Preferred trailer audio language ("" = auto/English-preferred). Forwarded to every engine
        // trailer request as a production-time hint, and to the players for audio-track selection.
        val trailerLanguage: StateFlow<String> =
            preferences.data
                .map { it.interfacePreferences.trailerLanguage }
                .stateIn(viewModelScope, SharingStarted.Eagerly, "")

        /** The effective lang hint for engine trailer calls (null = let the server use its default). */
        private fun langHint(): String? = trailerLanguage.value.takeIf { it.isNotBlank() }

        private var heroes: List<RenderItem> = emptyList()

        // True once the engine's own bundle has landed. The local library home is only a stand-in
        // while the engine builds, so a late local row must never paint over the real thing.
        private var engineApplied = false

        // True once the viewer has focused a row card. From that point a late engine bundle is
        // cached for the next home entry instead of swapped in, because replacing the row list
        // under an active D-pad focus resets both scroll position and focus.
        private var userEngaged = false

        // The signed-in user id, resolved on load; needed to attribute engine-proxied requests.
        private var currentUserId: UUID? = null

        // One-shot user-facing messages (e.g. request results), surfaced as a toast by the page.
        private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val message: SharedFlow<String> = _message.asSharedFlow()

        // Load-phase timing (Phase 1 instrumentation): elapsedRealtime so it survives a clock change.
        private var loadStartedAt: Long = 0
        private var firstPaintLogged = false

        // Feature A telemetry: buffered focus-dwell + click events, flushed in batches.
        private val telemetryBuffer = mutableListOf<TelemetryEvent>()
        private var focusedItemId: String? = null
        private var focusStartMs: Long = 0

        init {
            load()
            // Periodically flush buffered home telemetry to the engine.
            viewModelScope.launch {
                while (true) {
                    delay(TELEMETRY_FLUSH_MS)
                    flushTelemetry()
                }
            }
        }

        fun load() {
            viewModelScope.launch {
                loadStartedAt = SystemClock.elapsedRealtime()
                val userId =
                    runCatching {
                        preferences.data.firstOrNull()?.currentUserId?.toUUIDOrNull()
                    }.getOrNull()
                currentUserId = userId

                // Paint instantly from the last-good bundle, then revalidate in the background
                // (stale-while-revalidate). The skeleton only shows when there's nothing cached.
                val cached =
                    runCatching { homeBundleCache.read(userId) }.getOrNull()
                        ?.takeIf { it.rows.isNotEmpty() }
                val hadCache = cached != null
                Timber.i(
                    "Home timing: prefs+cacheRead=%dms cacheHit=%b",
                    SystemClock.elapsedRealtime() - loadStartedAt,
                    hadCache,
                )
                if (cached != null) applyBundle(cached) else _state.value = EngineHomeState.Loading

                // With nothing cached, the local library home is built ALONGSIDE the engine request
                // rather than after it. The engine's personalized bundle is worth waiting for but not
                // worth an empty screen: this paints real rows in about a second, progressively, while
                // the engine keeps building. (With a cache we already have something better on screen.)
                var paintedLocal = false
                val localJob =
                    if (hadCache) {
                        null
                    } else {
                        launch {
                            runCatching {
                                localHomeBundleBuilder.progressive().collect { partial ->
                                    // Once the engine has answered, its bundle owns the screen —
                                    // a late local row must not overwrite it.
                                    if (engineApplied) return@collect
                                    if (partial.rows.isNotEmpty()) {
                                        paintedLocal = true
                                        applyBundle(partial)
                                    }
                                }
                            }.onFailure { Timber.w(it, "Local home bundle build failed") }
                        }
                    }

                val bootstrapAt = SystemClock.elapsedRealtime()
                val bootstrap = client.getBootstrap(userId = userId, inlineVideo = true)
                val serverBundle = bootstrap?.home?.takeIf { it.rows.isNotEmpty() }
                Timber.i(
                    "Home timing: bootstrap=%dms rows=%d sinceLoad=%dms",
                    SystemClock.elapsedRealtime() - bootstrapAt,
                    serverBundle?.rows?.size ?: 0,
                    SystemClock.elapsedRealtime() - loadStartedAt,
                )

                // Takes the screen from the local stand-in only when the engine's bundle is actually
                // going on it. Set BEFORE any suspending call below, so a local row that lands in the
                // meantime can't paint over the bundle we're about to show.
                fun claimScreenForEngine() {
                    engineApplied = true
                    localJob?.cancel()
                }

                // Fallback breadcrumb (§3): make "why is the legacy/sample home showing?" diagnosable.
                when {
                    bootstrap?.settings?.enabled == false -> {
                        // Engine turned off server-side → drop the stale cache and show the fallback.
                        claimScreenForEngine()
                        runCatching { homeBundleCache.clear(userId) }
                        useFallback("disabled via settings (Enabled=false)")
                    }
                    // Legacy home ONLY when nothing at all could be shown. A slow or failed engine
                    // request is not a reason to abandon the engine home — the local rows already are
                    // it, so let them finish before deciding there's nothing to show.
                    serverBundle == null -> {
                        localJob?.join()
                        if (!hadCache && !paintedLocal) useFallback("no engine bundle and no local library rows")
                    }
                    // Persist every fresh engine bundle so the next launch paints instantly, but only
                    // swap it onto a home the viewer is already browsing when it differs from what's up.
                    serverBundle != cached -> {
                        if (userEngaged) {
                            // Replacing the row list under an active D-pad focus would reset scroll and
                            // focus. Cache it instead — the next visit to Home paints it instantly — and
                            // leave the local build running so its remaining rows still fill in.
                            Timber.i("Orca Engine: bundle arrived while browsing; deferring to next home entry.")
                        } else {
                            claimScreenForEngine()
                            applyBundle(serverBundle)
                        }
                        runCatching { homeBundleCache.write(userId, serverBundle) }
                    }
                }
            }
        }

        /**
         * Renders the given engine bundle: splits the spotlight row from the content rows and primes
         * the first hero. Shared by the real-server path and the built-in [SampleEngineBundle].
         */
        private fun applyBundle(bundle: com.github.jkrishna289.orcax.engine.RenderBundle) {
            val spotlight = bundle.rows.firstOrNull { it.id == SPOTLIGHT_ROW_ID }
            val nextHeroes = spotlight?.items.orEmpty()
            val rows = bundle.rows.filterNot { it.id == SPOTLIGHT_ROW_ID }

            // Progressive loading re-applies the bundle as each row lands, so only re-seed the
            // billboard when the spotlight itself actually changed — otherwise every arriving row
            // would restart the rotation and re-fetch the hero's favorite state.
            val heroesChanged = nextHeroes != heroes
            heroes = nextHeroes
            if (heroesChanged) _heroFavorite.value = false

            _state.value = EngineHomeState.Success(heroes = heroes, rows = rows)

            // The number the budget actually cares about: how long until the viewer saw rows,
            // whatever produced them (disk cache, local library build, or the engine).
            if (!firstPaintLogged) {
                firstPaintLogged = true
                Timber.i(
                    "Home timing: FIRST PAINT at %dms (%d rows, %d heroes)",
                    SystemClock.elapsedRealtime() - loadStartedAt,
                    rows.size,
                    heroes.size,
                )
            }

            // Resolve favorite / ambient for the first hero item.
            if (heroesChanged && heroes.isNotEmpty()) onHeroActive(0)
        }

        /**
         * When the engine can't serve a bundle, either show the built-in cinematic [SampleEngineBundle]
         * (so the new home is visible out of the box) or degrade to the on-device legacy home. A real
         * server bundle always takes precedence; this only runs on the unavailable path.
         */
        private fun useFallback(reason: String) {
            if (SAMPLE_HOME_WHEN_UNAVAILABLE) {
                Timber.i("Orca Engine: %s; showing built-in sample home.", reason)
                applyBundle(SampleEngineBundle.bundle)
            } else {
                Timber.i("Orca Engine: %s; using on-device home.", reason)
                _state.value = EngineHomeState.Unavailable
            }
        }

        /**
         * Called by the billboard when a hero item becomes active (rotation or first load). Seeds
         * the watchlist toggle and drives the focus-following ambient backdrop. The billboard is a
         * static promotional banner, so no trailer is resolved here — trailer playback belongs to
         * the dedicated Spotlight row, which resolves its own URL via [trailerUrlFor].
         */
        fun onHeroActive(index: Int) {
            val hero = heroes.getOrNull(index) ?: return
            val heroId = hero.media.jellyfinId?.toUUIDOrNull()
            // Deliberately not onCardFocused: billboard rotation is automatic, not the viewer browsing.
            trackAndIlluminate(hero)
            if (heroId != null) refreshHeroFavorite(heroId) else _heroFavorite.value = false
        }

        /**
         * Update the focus-following ambient backdrop to the given item's artwork, so the app's
         * background "illuminates" with the colors of whatever the user is looking at.
         */
        fun onCardFocused(item: RenderItem) {
            // A focused row card means the viewer is actively browsing — from here a late engine
            // bundle is cached rather than swapped in, so it can't reset their scroll/focus.
            userEngaged = true
            trackAndIlluminate(item)
        }

        private fun trackAndIlluminate(item: RenderItem) {
            // Feature A: a focus change ends the previous card's dwell — record it as a signal.
            trackFocusChange(item.media.jellyfinId)

            val idStr = item.media.jellyfinId
            if (idStr == null) {
                // Art-only card (sample/demo bundle): no bitmap to sample, so drive the ambient
                // wash straight from the accent hint — same focus-following effect, no network.
                val accent = EngineHomeArt.parseAccent(item.card.accentColorHint)
                val (primary, secondary, tertiary) = EngineHomeArt.ambient(accent)
                val key = item.card.title ?: item.card.accentColorHint ?: "sample"
                viewModelScope.launch { backdropService.submitColors(key, primary, secondary, tertiary) }
                return
            }
            val id = idStr.toUUIDOrNull() ?: return
            val url =
                item.card.backdropImageUrl
                    ?: imageUrlService.getItemImageUrl(itemId = id, imageType = ImageType.BACKDROP, fillHeight = 1080)
            viewModelScope.launch { backdropService.submit(idStr, url) }
        }

        /** The server-cached trailer URL for a card (#11), or null when there's no TMDB id. */
        fun trailerUrlFor(item: RenderItem): String? {
            val url = client.trailerUrl(item.media.tmdbId, item.media.mediaType, langHint())
            // Trailer diagnostics: a null tmdbId here means the item was fetched without
            // ItemFields.PROVIDER_IDS, so the engine can never build a trailer URL for it.
            Timber.d("Engine trailer for '%s': tmdbId=%s → %s", item.card.title, item.media.tmdbId, url ?: "no URL")
            return url
        }

        /**
         * Queries the engine's trailer state machine for a card, so inline previews can start the
         * instant the server reports Ready and stop retrying titles that will never resolve. Null when
         * the engine is unreachable / too old to expose the status endpoint.
         */
        suspend fun trailerStatusFor(item: RenderItem): com.github.jkrishna289.orcax.engine.TrailerStatus? =
            client.getTrailerStatus(item.media.tmdbId, item.media.mediaType, langHint())

        /**
         * Predictively prefetches trailers for likely-next items (Phase 3 client half): row neighbours,
         * the next hero, or a detail page just opened. Fire-and-forget at a below-focus [priority]; the
         * engine bounds concurrency and dedupes, so we can prefetch liberally as the user navigates.
         */
        fun prefetchTrailers(items: List<RenderItem>, priority: String) {
            val payload =
                items.mapNotNull { item ->
                    val tmdb = item.media.tmdbId
                    if (tmdb == null || tmdb <= 0) {
                        null
                    } else {
                        val type = if (item.media.mediaType == MediaType.SERIES) "tv" else "movie"
                        com.github.jkrishna289.orcax.engine.TrailerPrefetchItem(tmdb, type)
                    }
                }
            if (payload.isEmpty()) return
            viewModelScope.launch { runCatching { client.prefetchTrailers(payload, priority, langHint()) } }
        }

        /**
         * "Did You Know?" facts for the instant-details overlay (Feature 4). Resolved lazily from the
         * engine's permanent trivia cache; empty when there's no engine, no id, or no facts.
         */
        suspend fun triviaFor(item: RenderItem): List<String> {
            val jellyfinId = item.media.jellyfinId?.toUUIDOrNull()
            val tmdbId = item.media.tmdbId
            if (jellyfinId == null && tmdbId == null) return emptyList()
            return runCatching { client.getTrivia(jellyfinId = jellyfinId, tmdbId = tmdbId)?.facts }
                .getOrNull()
                .orEmpty()
        }

        /**
         * Records an explicit thumbs up/down for a title (a high-weight personalization signal). Only
         * surfaces a confirmation when the engine actually accepted it (it's absent until redeployed).
         */
        fun recordFeedback(
            item: RenderItem,
            thumbsUp: Boolean,
        ) {
            val userId = currentUserId ?: return
            val itemId = item.media.jellyfinId?.toUUIDOrNull() ?: return
            viewModelScope.launch {
                val ok = runCatching { client.sendFeedback(userId, itemId, thumbsUp) }.getOrDefault(false)
                if (ok) {
                    _message.emit(if (thumbsUp) "Added to your taste profile" else "We'll show less like this")
                }
            }
        }

        /** A 16:9 backdrop URL for the instant-details / trailer preview (#10/#11). */
        fun backdropUrlFor(item: RenderItem): String? {
            item.card.backdropImageUrl?.let { return it }
            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return item.card.imageUrl
            return imageUrlService.getItemImageUrl(itemId = id, imageType = ImageType.BACKDROP, fillHeight = 1080)
        }

        fun onItemClick(item: RenderItem) {
            item.media.jellyfinId?.let { recordTelemetry(it, "CardClicked", 1.0) }

            // The badge never changes what Enter does — every card opens details. Firing the request
            // straight off the card used to be the only option because unowned titles had nowhere to
            // land; they have a page now, and requesting belongs to the button on it, where the
            // viewer can also see what they'd be requesting and choose to stream it instead.
            navigateToDetail(item)
        }

        fun onInfo(item: RenderItem) = navigateToDetail(item)

        fun onPlay(item: RenderItem) {
            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return navigateToDetail(item)

            // Play from the start unless the card offers a Resume action (Continue Watching et al.),
            // in which case start from the saved playback position.
            if (!item.card.actions.contains(CardAction.RESUME)) {
                navigationManager.navigateTo(Destination.Playback(itemId = id, positionMs = 0L))
                return
            }

            viewModelScope.launch {
                val positionMs =
                    runCatching {
                        val ticks = api.userLibraryApi.getItem(id).content.userData?.playbackPositionTicks ?: 0L
                        ticks / TICKS_PER_MS
                    }.getOrDefault(0L)
                navigationManager.navigateTo(Destination.Playback(itemId = id, positionMs = positionMs))
            }
        }

        /** Optimistically toggles the spotlight item's Jellyfin favorite (the "watchlist"). */
        fun onWatchlist(item: RenderItem) {
            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return navigateToDetail(item)
            val target = !_heroFavorite.value
            _heroFavorite.value = target // optimistic; revert below on failure
            viewModelScope.launch {
                val ok = runCatching { favoriteWatchManager.setFavorite(id, target) }.isSuccess
                if (!ok) {
                    _heroFavorite.value = !target
                    Timber.w("Orca Engine: watchlist toggle failed for %s", id)
                }
            }
        }

        /**
         * Submits an engine-proxied (Jellyseerr) request for a requestable item, then surfaces the
         * result as a toast. Falls back to the details page when the item can't be requested.
         */
        fun onRequest(item: RenderItem) {
            val tmdbId = item.media.tmdbId
            val mediaType =
                when (item.media.mediaType) {
                    MediaType.MOVIE -> "movie"
                    MediaType.SERIES -> "tv"
                    else -> null
                }
            val uid = currentUserId
            if (tmdbId == null || mediaType == null || uid == null) {
                navigateToDetail(item)
                return
            }

            val title = item.card.title ?: "this title"
            viewModelScope.launch {
                val result = runCatching { client.requestMedia(uid, tmdbId, mediaType, item.card.title) }.getOrNull()
                val msg =
                    when {
                        result?.success == true -> "Requested $title"
                        result != null && result.message.isNotBlank() -> result.message
                        else -> "Couldn't request $title"
                    }
                _message.emit(msg)
            }
        }

        /** Ends the previously-focused card's dwell (recording it) and starts timing the new one. */
        private fun trackFocusChange(newId: String?) {
            val now = System.currentTimeMillis()
            val prev = focusedItemId
            if (prev != null && prev != newId) {
                val dwellSec = (now - focusStartMs) / 1000.0
                if (dwellSec >= MIN_DWELL_SEC) recordTelemetry(prev, "CardFocused", dwellSec)
            }
            if (newId != prev) {
                focusedItemId = newId
                focusStartMs = now
            }
        }

        private fun recordTelemetry(
            itemId: String,
            type: String,
            value: Double,
        ) {
            synchronized(telemetryBuffer) {
                telemetryBuffer.add(TelemetryEvent(itemId = itemId, eventType = type, value = value))
            }
        }

        private suspend fun flushTelemetry() {
            val uid = currentUserId ?: return
            val batch =
                synchronized(telemetryBuffer) {
                    if (telemetryBuffer.isEmpty()) {
                        emptyList()
                    } else {
                        telemetryBuffer.toList().also { telemetryBuffer.clear() }
                    }
                }
            if (batch.isEmpty()) return
            runCatching { client.recordEvents(uid, batch) }
        }

        private fun refreshHeroFavorite(itemId: UUID) {
            viewModelScope.launch {
                _heroFavorite.value =
                    runCatching { api.userLibraryApi.getItem(itemId).content.userData?.isFavorite }
                        .getOrNull() ?: false
            }
        }

        private fun navigateToDetail(item: RenderItem) {
            val id = item.media.jellyfinId?.toUUIDOrNull()
            if (id == null) {
                // Not in the library, so there is no Jellyfin item to open. The engine still knows
                // the title, and can both request it and find a stream for it, so it gets its own
                // page rather than the silent no-op this used to be — which also left the billboard's
                // Info button dead on every unowned hero.
                if (item.media.tmdbId != null) {
                    navigationManager.navigateTo(Destination.EngineItem(item))
                }
                return
            }
            val kind = item.media.mediaType.toBaseItemKind()
            val destination =
                if (kind == BaseItemKind.SERIES) {
                    Destination.SeriesOverview(itemId = id, type = kind)
                } else {
                    Destination.MediaItem(itemId = id, type = kind)
                }
            navigationManager.navigateTo(destination)
        }

        private fun MediaType.toBaseItemKind(): BaseItemKind =
            when (this) {
                MediaType.MOVIE -> BaseItemKind.MOVIE
                MediaType.SERIES -> BaseItemKind.SERIES
                MediaType.EPISODE -> BaseItemKind.EPISODE
                MediaType.SEASON -> BaseItemKind.SEASON
                MediaType.PERSON -> BaseItemKind.PERSON
                MediaType.COLLECTION -> BaseItemKind.BOX_SET
                else -> BaseItemKind.MOVIE
            }

        companion object {
            private const val SPOTLIGHT_ROW_ID = "spotlight"

            /**
             * Final fallback when neither the server engine NOR the client-side
             * [LocalHomeBundleBuilder] can produce a bundle (e.g. no signed-in user / empty library):
             * show the legacy on-device home — which loads the user's real library with real posters —
             * rather than the decorative gradient [SampleEngineBundle]. Flip to `true` to showcase the
             * cinematic demo layout with no server instead.
             */
            private const val SAMPLE_HOME_WHEN_UNAVAILABLE = false

            /** Jellyfin uses 100-ns ticks; 10,000 ticks per millisecond. */
            private const val TICKS_PER_MS = 10_000L

            /** Minimum focus duration (seconds) worth recording as a dwell signal. */
            private const val MIN_DWELL_SEC = 1.0

            /** How often buffered telemetry is flushed to the engine. */
            private const val TELEMETRY_FLUSH_MS = 10_000L
        }
    }

/** UI state for the engine-driven home. */
sealed interface EngineHomeState {
    /** Request in flight. */
    data object Loading : EngineHomeState

    /** Engine unreachable or returned nothing → caller should render the on-device home. */
    data object Unavailable : EngineHomeState

    /** Engine bundle loaded. [heroes] is the rotating spotlight set (may be empty). */
    data class Success(
        val heroes: List<RenderItem>,
        val rows: List<RenderRow>,
    ) : EngineHomeState
}
