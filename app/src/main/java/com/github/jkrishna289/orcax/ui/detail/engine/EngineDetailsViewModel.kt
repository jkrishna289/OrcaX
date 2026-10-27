package com.github.jkrishna289.orcax.ui.detail.engine

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.PlaybackIdentity
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.ServerPlaybackProgress
import com.github.jkrishna289.orcax.engine.TitleDetails
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.StreamWatch
import com.github.jkrishna289.orcax.services.torrent.StreamWatchTracker
import com.github.jkrishna289.orcax.ui.detail.discover.SourceStreamingController
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * The details page for a title the engine knows about but the library doesn't hold.
 *
 * Talks to the engine and nothing else. Requests go through the engine's `/Requests` proxy rather
 * than a client-side Jellyseerr connection, and the stream search is [SourceStreamingController],
 * which has no request-service dependency of its own — so this whole screen works on a server whose
 * operator wired up Jellyseerr once, without every client needing its own credentials.
 */
@HiltViewModel(assistedFactory = EngineDetailsViewModel.Factory::class)
class EngineDetailsViewModel
    @AssistedInject
    constructor(
        private val engineClient: OrcaEngineClient,
        private val api: ApiClient,
        private val navigationManager: NavigationManager,
        private val preferences: DataStore<AppPreferences>,
        private val streamWatchTracker: StreamWatchTracker,
        val sources: SourceStreamingController,
        @Assisted val item: RenderItem,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(item: RenderItem): EngineDetailsViewModel
        }

        /**
         * Live availability. Seeded from the card so the page paints the right status chip on the
         * first frame, then refreshed from the engine — a card can be minutes stale, and the one fact
         * this whole screen turns on should not be.
         */
        val availability = MutableStateFlow(item.media.availability)

        /** The card may predate an import, so the resolved library id must be refreshable too. */
        val libraryItemId = MutableStateFlow(item.media.jellyfinId?.toUUIDOrNull())
        val playbackProgress = MutableStateFlow<ServerPlaybackProgress?>(null)

        private val playbackIdentity =
            item.media.tmdbId?.takeIf { it > 0 && item.media.mediaType == MediaType.MOVIE }?.let {
                PlaybackIdentity(tmdbId = it, title = item.card.title, year = item.yearBadge())
            }
        private var refreshJob: Job? = null

        /** True while a request is in flight, so the button can say so instead of looking dead. */
        val requesting = MutableStateFlow(false)

        /** Non-null while the "Keep it?" prompt is up, after returning from a streamed playback. */
        val keepPrompt = MutableStateFlow<StreamWatch?>(null)

        /**
         * The engine catalog's facts, cast and crew for this title. The card carries only a title and a
         * short synopsis, so without this the page has nothing else to say. Null until loaded, and on an
         * engine too old to serve it — the page then falls back to what the card has.
         */
        val details = MutableStateFlow<TitleDetails?>(null)

        /** The server-cached trailer, built once the viewer's trailer-language preference is read. */
        val trailerUrl = MutableStateFlow<String?>(null)
        private var trailerLang: String? = null

        suspend fun trailerStatus() = engineClient.getTrailerStatus(item.media.tmdbId, item.media.mediaType, trailerLang)

        init {
            viewModelScope.launchIO {
                trailerLang = preferences.data.firstOrNull()?.interfacePreferences?.trailerLanguage?.takeIf { it.isNotBlank() }
                trailerUrl.update { engineClient.trailerUrl(item.media.tmdbId, item.media.mediaType, trailerLang) }
            }
            sources.attach(
                scope = viewModelScope,
                title = item.card.title.orEmpty(),
                year = item.yearBadge(),
                mediaType = item.media.mediaType,
                tmdbId = item.media.tmdbId,
            )
            refreshAvailability()
            item.media.tmdbId?.takeIf { it > 0 }?.let { tmdbId ->
                viewModelScope.launchIO {
                    engineClient.getTitleDetails(tmdbId, item.media.mediaType)?.let { found -> details.update { found } }
                }
            }
        }

        /** Re-reads status on every resume, which is also when a return from the player happens. */
        fun refresh() {
            refreshAvailability()
            checkKeepPrompt()
        }

        private fun refreshAvailability() {
            val tmdbId = item.media.tmdbId ?: return
            refreshJob?.cancel()
            refreshJob = viewModelScope.launchIO {
                val status = engineClient.getRequestStatus(tmdbId, item.media.mediaType)
                val progress = playbackIdentity?.let { engineClient.getPlaybackProgress(it) }
                ensureActive()
                status?.availability?.let { fresh -> availability.update { fresh } }
                updateProgress(progress)
                if (libraryItemId.value == null && availability.value in IN_LIBRARY) {
                    findLibraryItemByTmdb()?.let { id -> libraryItemId.update { id } }
                }
            }
        }

        /**
         * Progress only resolves the library copy for movies on engine 1.3+, so series and older
         * engines would leave Play dead on a title that is already in the library.
         */
        private suspend fun findLibraryItemByTmdb(): UUID? {
            val tmdbId = item.media.tmdbId?.toString() ?: return null
            val title = item.card.title?.takeIf { it.isNotBlank() } ?: return null
            val kind = if (item.media.mediaType == MediaType.SERIES) BaseItemKind.SERIES else BaseItemKind.MOVIE
            // ponytail: title search then match on TMDB id, because servers ignore AnyProviderIdEquals.
            // Misses a library copy filed under a different title; page the whole type if that bites.
            return runCatching {
                api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            searchTerm = title,
                            recursive = true,
                            includeItemTypes = listOf(kind),
                            fields = listOf(ItemFields.PROVIDER_IDS),
                            limit = 25,
                        ),
                    ).content.items
                    .firstOrNull { it.providerIds?.get("Tmdb") == tmdbId }
                    ?.id
            }.getOrNull()
        }

        private fun updateProgress(progress: ServerPlaybackProgress?) {
            // A transient failure must not erase a successfully resolved id or saved position.
            if (progress == null) return
            playbackProgress.update { progress }
            progress.jellyfinItemId?.toUUIDOrNull()?.let { id ->
                libraryItemId.update { id }
                availability.update { AvailabilityState.WATCH_NOW }
            }
        }

        /**
         * Fires the request and reflects the engine's own answer.
         *
         * No confirmation dialog: the design treats this as a decision the viewer already made by
         * pressing the button, and a second "are you sure" on a TV is one more press for nothing.
         */
        fun request() {
            val tmdbId = item.media.tmdbId ?: return
            val mediaType =
                when (item.media.mediaType) {
                    MediaType.MOVIE -> "movie"
                    MediaType.SERIES -> "tv"
                    else -> return
                }
            // The button stays pressable while "Requesting…", so a second press must not send twice
            if (!requesting.compareAndSet(expect = false, update = true)) return
            viewModelScope.launchIO {
                val userId = preferences.data.firstOrNull()?.currentUserId?.toUUIDOrNull()
                val result =
                    if (userId == null) {
                        null
                    } else {
                        runCatching {
                            engineClient.requestMedia(userId, tmdbId, mediaType, item.card.title)
                        }.getOrNull()
                    }
                requesting.update { false }
                // Trust the engine's reported state over an optimistic guess — a request can be
                // auto-approved straight into Downloading, or rejected outright, and the pill should
                // say which. A failure leaves the state alone so the button stays actionable.
                if (result?.success == true) {
                    availability.update { result.availability }
                }
            }
        }

        // ── Keep it? ────────────────────────────────────────────────────────
        // Asked once, on the way back from a streamed film, and only when the answer could plausibly
        // be yes.

        private fun checkKeepPrompt() {
            val watch = streamWatchTracker.consume() ?: return
            if (availability.value == AvailabilityState.WATCH_NOW) return
            if (watch.positionMs < KEEP_MIN_WATCH_MS) return

            // The engine reports this off unless source streaming is on AND a library folder is
            // configured to copy into — without one there is no route to a permanent copy, so the
            // offer would be a dead end.
            viewModelScope.launchIO {
                if (engineClient.getFeatures()?.keepStream == true) {
                    keepPrompt.update { watch }
                }
            }
        }

        /**
         * Keeping is handled by the engine session the film streamed from: it finishes the download,
         * copies the file into the library folder and asks Jellyfin to scan it in. Nothing is
         * requested from anyone — most of the bytes are already on the server.
         */
        fun keepInLibrary() {
            val watch = keepPrompt.value ?: return
            streamWatchTracker.markAnswered(watch.title)
            keepPrompt.update { null }
            viewModelScope.launchIO { engineClient.keepStream(watch.token) }
        }

        /** "Not now", BACK and the timeout all land here. Silence is never read as consent. */
        fun dismissKeepPrompt() {
            keepPrompt.value?.let { streamWatchTracker.markAnswered(it.title) }
            keepPrompt.update { null }
        }

        fun navigateToLibraryItem() {
            viewModelScope.launchIO {
                // Reading progress also reconciles an imported copy with Jellyfin. Its own details
                // page then reads the current resume time, adjusted for the local file's duration.
                updateProgress(playbackIdentity?.let { engineClient.getPlaybackProgress(it) })
                val id = libraryItemId.value ?: return@launchIO
                withContext(Dispatchers.Main) {
                    val destination =
                        if (item.media.mediaType == MediaType.SERIES) {
                            Destination.SeriesOverview(itemId = id, type = BaseItemKind.SERIES)
                        } else {
                            Destination.MediaItem(itemId = id, type = BaseItemKind.MOVIE)
                        }
                    navigationManager.navigateTo(destination)
                }
            }
        }
    }

/**
 * Watch this much of a streamed film and the Keep prompt is worth asking. Below it the viewer hasn't
 * formed an opinion yet, and asking anyway trains them to dismiss it without reading.
 */
private const val KEEP_MIN_WATCH_MS = 15 * 60 * 1000L

/** Engine states that mean a copy is already in the library. */
private val IN_LIBRARY = setOf(AvailabilityState.WATCH_NOW, AvailabilityState.RECENTLY_ADDED)

/** The release year, read back out of the card's own YEAR badge — the only place the engine sends it. */
internal fun RenderItem.yearBadge(): Int? =
    card.badges
        .firstOrNull { it.kind.equals("YEAR", ignoreCase = true) }
        ?.text
        ?.take(4)
        ?.toIntOrNull()
