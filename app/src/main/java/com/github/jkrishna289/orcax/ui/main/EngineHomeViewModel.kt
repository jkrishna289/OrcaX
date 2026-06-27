package com.github.jkrishna289.orcax.ui.main

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.TelemetryEvent
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.ImageUrlService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.TrailerService
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
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
 * authenticated user id and (for the spotlight) an inline trailer. Falls back to a vanilla layout
 * via [EngineHomeState.Unavailable] when the engine can't serve a bundle.
 */
@HiltViewModel
class EngineHomeViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val preferences: DataStore<AppPreferences>,
        private val trailerService: TrailerService,
        private val navigationManager: NavigationManager,
        private val backdropService: BackdropService,
        private val imageUrlService: ImageUrlService,
        private val api: ApiClient,
        private val favoriteWatchManager: FavoriteWatchManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<EngineHomeState>(EngineHomeState.Loading)
        val state: StateFlow<EngineHomeState> = _state.asStateFlow()

        // Optimistic favorite/watchlist state for the spotlight's "+ Watchlist" button. Reflects
        // whichever spotlight item is currently showing as the billboard rotates.
        private val _heroFavorite = MutableStateFlow(false)
        val heroFavorite: StateFlow<Boolean> = _heroFavorite.asStateFlow()

        // Inline trailer for the active spotlight item; updated as the billboard rotates.
        private val _activeTrailerUrl = MutableStateFlow<String?>(null)
        val activeTrailerUrl: StateFlow<String?> = _activeTrailerUrl.asStateFlow()

        // Resolved local-trailer URLs keyed by item id, so rotating back to an item doesn't refetch.
        // A present key with a null value means "resolved, no local trailer".
        private val trailerCache = mutableMapOf<UUID, String?>()
        private var heroes: List<RenderItem> = emptyList()
        private var activeHeroIndex = 0

        // The signed-in user id, resolved on load; needed to attribute engine-proxied requests.
        private var currentUserId: UUID? = null

        // One-shot user-facing messages (e.g. request results), surfaced as a toast by the page.
        private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val message: SharedFlow<String> = _message.asSharedFlow()

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
                _state.value = EngineHomeState.Loading

                val userId =
                    runCatching {
                        preferences.data.firstOrNull()?.currentUserId?.toUUIDOrNull()
                    }.getOrNull()
                currentUserId = userId

                val bootstrap = client.getBootstrap(userId = userId, inlineVideo = true)
                val bundle = bootstrap?.home
                // Fallback breadcrumb (§3): make "why is the legacy/sample home showing?" diagnosable.
                when {
                    bundle == null ->
                        useFallback("bootstrap returned no bundle (engine unreachable/not installed)")
                    bootstrap?.settings?.enabled == false ->
                        useFallback("disabled via settings (Enabled=false)")
                    bundle.rows.isEmpty() ->
                        useFallback("bundle had zero rows")
                    else ->
                        applyBundle(bundle)
                }
            }
        }

        /**
         * Renders the given engine bundle: splits the spotlight row from the content rows and primes
         * the first hero. Shared by the real-server path and the built-in [SampleEngineBundle].
         */
        private fun applyBundle(bundle: com.github.jkrishna289.orcax.engine.RenderBundle) {
            val spotlight = bundle.rows.firstOrNull { it.id == SPOTLIGHT_ROW_ID }
            heroes = spotlight?.items.orEmpty()
            val rows = bundle.rows.filterNot { it.id == SPOTLIGHT_ROW_ID }

            trailerCache.clear()
            _activeTrailerUrl.value = null
            _heroFavorite.value = false

            _state.value = EngineHomeState.Success(heroes = heroes, rows = rows)

            // Resolve trailer / favorite / ambient for the first spotlight item.
            if (heroes.isNotEmpty()) onHeroActive(0)
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
         * Called by the billboard pager when a spotlight item becomes active (rotation or first
         * load). Resolves that item's inline trailer (local Jellyfin trailers only, cached), seeds
         * the watchlist toggle, and drives the focus-following ambient backdrop.
         */
        fun onHeroActive(index: Int) {
            val hero = heroes.getOrNull(index) ?: return
            activeHeroIndex = index
            val heroId = hero.media.jellyfinId?.toUUIDOrNull()

            // Ambient + favorite immediately; trailer may need a network round-trip.
            onCardFocused(hero)
            if (heroId != null) refreshHeroFavorite(heroId) else _heroFavorite.value = false

            if (hero.card.wantsTrailer != true || heroId == null) {
                _activeTrailerUrl.value = null
                return
            }
            if (trailerCache.containsKey(heroId)) {
                _activeTrailerUrl.value = trailerCache[heroId]
                return
            }
            _activeTrailerUrl.value = null
            viewModelScope.launch {
                val url = runCatching { trailerService.getLocalTrailerStreamUrl(heroId) }.getOrNull()
                trailerCache[heroId] = url
                // Only apply if this item is still the active one (guard against fast rotation).
                if (activeHeroIndex == index) _activeTrailerUrl.value = url
            }
        }

        /**
         * Update the focus-following ambient backdrop to the given item's artwork, so the app's
         * background "illuminates" with the colors of whatever the user is looking at.
         */
        fun onCardFocused(item: RenderItem) {
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
        fun trailerUrlFor(item: RenderItem): String? = client.trailerUrl(item.media.tmdbId, item.media.mediaType)

        /** A 16:9 backdrop URL for the instant-details / trailer preview (#10/#11). */
        fun backdropUrlFor(item: RenderItem): String? {
            item.card.backdropImageUrl?.let { return it }
            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return item.card.imageUrl
            return imageUrlService.getItemImageUrl(itemId = id, imageType = ImageType.BACKDROP, fillHeight = 1080)
        }

        fun onItemClick(item: RenderItem) {
            item.media.jellyfinId?.let { recordTelemetry(it, "CardClicked", 1.0) }

            // Requestable (Discover) items have no Jellyfin id — clicking them submits a request
            // instead of opening a (non-existent) details page.
            if (item.media.availability == AvailabilityState.REQUEST && item.media.jellyfinId == null) {
                onRequest(item)
            } else {
                navigateToDetail(item)
            }
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
            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return
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
             * When the engine can't serve a bundle, render the built-in [SampleEngineBundle] instead
             * of degrading to the legacy on-device home. Lets the cinematic layout ship and be seen
             * before the server-side `/OrcaEngine` plugin exists. Flip to `false` to restore the
             * silent legacy fallback.
             */
            private const val SAMPLE_HOME_WHEN_UNAVAILABLE = true

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
