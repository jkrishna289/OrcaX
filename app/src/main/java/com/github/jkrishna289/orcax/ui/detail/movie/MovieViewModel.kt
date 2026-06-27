package com.github.jkrishna289.orcax.ui.detail.movie

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.github.jkrishna289.orcax.preferences.ThemeSongVolume
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.ExtrasService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.NavigationManager
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
                backdropService.submit(movie)
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
    }

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
) {
    val movie: BaseItem? = (loading as? DataLoadingState.Success<BaseItem>)?.data
}
