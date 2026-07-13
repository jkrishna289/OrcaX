package com.github.jkrishna289.orcax.ui.detail.movie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ChosenStreams
import com.github.jkrishna289.orcax.data.ExtrasItem
import com.github.jkrishna289.orcax.data.ItemPlaybackRepository
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Chapter
import com.github.jkrishna289.orcax.data.model.DiscoverItem
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.data.model.Trailer
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.preferences.ThemeSongVolume
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.ExtrasService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.PeopleFavorites
import com.github.jkrishna289.orcax.services.SeerrService
import com.github.jkrishna289.orcax.services.StreamChoiceService
import com.github.jkrishna289.orcax.services.ThemeSongPlayer
import com.github.jkrishna289.orcax.services.TrailerService
import com.github.jkrishna289.orcax.services.UserPreferencesService
import com.github.jkrishna289.orcax.services.deleteItem
import com.github.jkrishna289.orcax.ui.SlimItemFields
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.letNotEmpty
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.playback.bitrateLabel
import com.github.jkrishna289.orcax.ui.playback.quality.DeviceAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.NetworkAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.QualityResolver
import com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection
import com.github.jkrishna289.orcax.ui.playback.resolutionLabel
import com.github.jkrishna289.orcax.ui.showToast
import com.github.jkrishna289.orcax.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaStreamType
import com.github.jkrishna289.orcax.engine.MediaType as EngineMediaType
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import timber.log.Timber
import java.util.UUID

@HiltViewModel(assistedFactory = MovieViewModel.Factory::class)
class MovieViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        private val seerrService: SeerrService,
        @param:ApplicationContext private val context: Context,
        private val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val itemPlaybackRepository: ItemPlaybackRepository,
        val streamChoiceService: StreamChoiceService,
        val mediaReportService: MediaReportService,
        private val themeSongPlayer: ThemeSongPlayer,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val peopleFavorites: PeopleFavorites,
        private val trailerService: TrailerService,
        private val extrasService: ExtrasService,
        private val userPreferencesService: UserPreferencesService,
        private val backdropService: BackdropService,
        private val mediaManagementService: MediaManagementService,
        private val orcaEngineClient: OrcaEngineClient,
        private val networkAnalyzer: NetworkAnalyzer,
        private val deviceAnalyzer: DeviceAnalyzer,
        @Assisted val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): MovieViewModel
        }

        private val _state = MutableStateFlow(MovieState())
        val state: StateFlow<MovieState> = _state

        init {
            init()
            viewModelScope.launchDefault {
                userPreferencesService.flow.collectLatest { preferences ->
                    _state.update {
                        val canDelete =
                            it.movie?.let {
                                mediaManagementService.canDelete(
                                    it,
                                    preferences.appPreferences,
                                )
                            }
                        it.copy(
                            canDelete = canDelete ?: false,
                        )
                    }
                }
            }
        }

        /**
         * "AUTO: 1080p · 12 Mbps · connection 35 Mbps" from the measurement
         * cache. Returns null (preview hidden) when the cache is cold or the
         * media has no usable profile — never probes from the details page.
         */
        private suspend fun buildQualityPreview(movie: BaseItem): String? {
            val userId = serverRepository.currentUser.value?.id?.toString()
            val cached = networkAnalyzer.cachedMeasurement(userId) ?: return null
            val source = movie.data.mediaSources?.firstOrNull() ?: return null
            val video = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
            val audio = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
            if ((video.bitRate ?: 0) <= 0) return null
            val recommendation = QualityResolver.resolve(
                measurement = cached,
                media = QualityResolver.MediaQualityProfile(
                    videoBitrateBps = video.bitRate ?: 0,
                    audioBitrateBps = audio?.bitRate ?: 0,
                    width = video.width ?: 0,
                    height = video.height ?: 0,
                    videoCodec = video.codec,
                    audioCodec = audio?.codec,
                    audioChannels = audio?.channels,
                ),
                device = deviceAnalyzer.audioCaps(userPreferencesService.getCurrent().appPreferences),
            )
            val recommendedLabel = when (val rec = recommendation.recommended) {
                is QualitySelection.Rung ->
                    "${resolutionLabel(0, rec.rung.maxHeight)} · ${bitrateLabel(rec.rung.maxBitrateBps)}"
                else -> context.getString(R.string.quality_original)
            }
            val connectionLabel = bitrateLabel(cached.bps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return context.getString(R.string.quality_details_preview, recommendedLabel, connectionLabel)
        }

        private suspend fun getMovie(): BaseItem {
            val item =
                api.userLibraryApi.getItem(itemId).content.let {
                    BaseItem(it)
                }
            return item
        }

        fun init(): Job =
            viewModelScope.launchDefault {
                val movie =
                    try {
                        getMovie()
                    } catch (ex: Exception) {
                        Timber.e(ex, "Failed to fetch movie %s", itemId)
                        _state.update { it.copy(loading = DataLoadingState.Error(ex)) }
                        return@launchDefault
                    }
                val chosenStreams =
                    itemPlaybackRepository.getSelectedTracks(
                        itemId,
                        movie,
                        userPreferencesService.getCurrent(),
                    )
                val remoteTrailers = trailerService.getRemoteTrailers(movie)
                val chapters = Chapter.fromDto(movie.data, api)
                _state.update {
                    it.copy(
                        loading = DataLoadingState.Success(movie),
                        chosenStreams = chosenStreams,
                        trailers = remoteTrailers,
                        chapters = chapters,
                    )
                }
                backdropService.submit(movie, resolveTrailer = true)
                viewModelScope.launchIO {
                    buildQualityPreview(movie)?.let { preview ->
                        _state.update { it.copy(qualityPreview = preview) }
                    }
                }
                viewModelScope.launchIO {
                    trailerService.getLocalTrailers(movie).letNotEmpty { localTrailers ->
                        _state.update {
                            it.copy(
                                trailers = localTrailers + remoteTrailers,
                            )
                        }
                    }
                }
                viewModelScope.launchIO {
                    val tmdbId = movie.tmdbId ?: return@launchIO
                    val status = orcaEngineClient.getRequestStatus(tmdbId, EngineMediaType.MOVIE)
                    _state.update { it.copy(availability = status?.availability) }
                }
                viewModelScope.launchIO {
                    val people = peopleFavorites.getPeopleFor(movie)
                    _state.update {
                        it.copy(
                            people = people,
                        )
                    }
                }
                viewModelScope.launchIO {
                    val extras = extrasService.getExtras(itemId)
                    _state.update {
                        it.copy(
                            extras = extras,
                        )
                    }
                }
                viewModelScope.launchIO {
                    val results = seerrService.similar(movie).orEmpty()
                    _state.update {
                        it.copy(
                            discovered = results,
                        )
                    }
                }

                if (state.value.similar.isEmpty()) {
                    val similar =
                        api.libraryApi
                            .getSimilarItems(
                                GetSimilarItemsRequest(
                                    userId = serverRepository.currentUser.value?.id,
                                    itemId = itemId,
                                    fields = SlimItemFields,
                                    limit = 25,
                                ),
                            ).content.items
                            .map { BaseItem(it) }

                    _state.update { it.copy(similar = similar) }
                }
            }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launchDefault {
            try {
                favoriteWatchManager.setWatched(itemId, played)
                getMovie().let { movie ->
                    _state.update {
                        it.copy(loading = DataLoadingState.Success(movie))
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error updating watch status for movie %s", itemId)
                showToast(context, "Something went wrong...")
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launchDefault {
            try {
                favoriteWatchManager.setFavorite(itemId, favorite)
                val movie = getMovie()
                _state.update {
                    it.copy(loading = DataLoadingState.Success(movie))
                }
                if (itemId != movie.id) {
                    viewModelScope.launchIO {
                        val people = peopleFavorites.getPeopleFor(movie)
                        _state.update { it.copy(people = people) }
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error updating favorite  %s", itemId)
                showToast(context, "Something went wrong...")
            }
        }

        fun savePlayVersion(
            item: BaseItem,
            sourceId: UUID,
        ) {
            viewModelScope.launchIO {
                val prefs = userPreferencesService.getCurrent()
                val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
                val result = itemPlaybackRepository.savePlayVersion(item.id, sourceId)
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result, plc, prefs)
                    }
                _state.update { it.copy(chosenStreams = chosen) }
            }
        }

        fun saveTrackSelection(
            item: BaseItem,
            itemPlayback: ItemPlayback?,
            trackIndex: Int,
            type: MediaStreamType,
        ) {
            viewModelScope.launchIO {
                val prefs = userPreferencesService.getCurrent()
                val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
                val result =
                    itemPlaybackRepository.saveTrackSelection(
                        item = item,
                        itemPlayback = itemPlayback,
                        trackIndex = trackIndex,
                        type = type,
                    )
                val chosen =
                    result?.let {
                        itemPlaybackRepository.getChosenItemFromPlayback(item, result, plc, prefs)
                    }
                _state.update { it.copy(chosenStreams = chosen) }
            }
        }

        fun maybePlayThemeSong(
            seriesId: UUID,
            playThemeSongs: ThemeSongVolume,
        ) {
            viewModelScope.launchIO {
                themeSongPlayer.playThemeFor(seriesId, playThemeSongs)
                addCloseable {
                    themeSongPlayer.stop()
                }
            }
        }

        fun release() {
            themeSongPlayer.stop()
        }

        fun navigateTo(destination: Destination) {
            release()
            navigationManager.navigateTo(destination)
        }

        fun clearChosenStreams(chosenStreams: ChosenStreams?) {
            viewModelScope.launchIO {
                itemPlaybackRepository.deleteChosenStreams(chosenStreams)
                state.value.movie?.let { item ->
                    val result =
                        itemPlaybackRepository.getSelectedTracks(
                            itemId,
                            item,
                            userPreferencesService.getCurrent(),
                        )
                    _state.update { it.copy(chosenStreams = result) }
                }
            }
        }

        fun deleteItem(item: BaseItem) {
            deleteItem(context, mediaManagementService, item) {
                navigationManager.goBack()
            }
        }

        /**
         * Submits an engine-proxied (Jellyseerr) request for this title — only meaningful when the
         * engine reported [AvailabilityState.REQUEST]. Reflects the engine's response rather than
         * toggling optimistically.
         */
        fun requestMedia() {
            val movie = state.value.movie ?: return
            val tmdbId = movie.tmdbId ?: return
            val userId = serverRepository.currentUser.value?.id ?: return
            if (state.value.requestInFlight) return
            _state.update { it.copy(requestInFlight = true) }
            viewModelScope.launchIO {
                val result =
                    runCatching {
                        orcaEngineClient.requestMedia(userId, tmdbId, "movie", movie.name)
                    }.getOrNull()
                _state.update {
                    it.copy(
                        requestInFlight = false,
                        availability =
                            if (result?.success == true) result.availability else it.availability,
                    )
                }
                if (result?.success != true) {
                    val message = result?.message?.takeIf { m -> m.isNotBlank() }
                    showToast(context, message ?: "Couldn't request ${movie.name}")
                }
            }
        }
    }

private val BaseItem.tmdbId: Int?
    get() = data.providerIds?.get("Tmdb")?.toIntOrNull()

data class MovieState(
    val loading: DataLoadingState<BaseItem> = DataLoadingState.Pending,
    val trailers: List<Trailer> = emptyList(),
    val people: List<Person> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val extras: List<ExtrasItem> = emptyList(),
    val similar: List<BaseItem> = emptyList(),
    val discovered: List<DiscoverItem> = emptyList(),
    val chosenStreams: ChosenStreams? = null,
    val canDelete: Boolean = false,
    /** Orca-Engine request status for this title; null = engine disabled / no TMDB match. */
    val availability: AvailabilityState? = null,
    /** True while an engine request submitted from this screen is awaiting its response. */
    val requestInFlight: Boolean = false,
    /**
     * AUTO quality preview line ("AUTO: 1080p · 12 Mbps · connection 35 Mbps").
     * Computed from CACHED measurements + the media profile only — no probe is
     * ever fired from the details page. Null (hidden) on a cold cache.
     */
    val qualityPreview: String? = null,
) {
    val movie: BaseItem? = (loading as? DataLoadingState.Success<BaseItem>)?.data
}
