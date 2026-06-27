package com.github.jkrishna289.orcax.ui.detail.episode

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.data.ChosenStreams
import com.github.jkrishna289.orcax.data.ItemPlaybackRepository
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.ItemPlayback
import com.github.jkrishna289.orcax.preferences.ThemeSongVolume
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.StreamChoiceService
import com.github.jkrishna289.orcax.services.ThemeSongPlayer
import com.github.jkrishna289.orcax.services.UserPreferencesService
import com.github.jkrishna289.orcax.services.deleteItem
import com.github.jkrishna289.orcax.ui.combinePair
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.setValueOnMain
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.LoadingExceptionHandler
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

@HiltViewModel(assistedFactory = EpisodeViewModel.Factory::class)
class EpisodeViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        @param:ApplicationContext private val context: Context,
        private val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val itemPlaybackRepository: ItemPlaybackRepository,
        val streamChoiceService: StreamChoiceService,
        val mediaReportService: MediaReportService,
        private val themeSongPlayer: ThemeSongPlayer,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val userPreferencesService: UserPreferencesService,
        private val backdropService: BackdropService,
        private val mediaManagementService: MediaManagementService,
        @Assisted val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): EpisodeViewModel
        }

        val loading = MutableLiveData<LoadingState>(LoadingState.Pending)
        val item = MutableLiveData<BaseItem?>(null)
        val chosenStreams = MutableLiveData<ChosenStreams?>(null)

        val canDelete = MutableStateFlow(false)

        init {
            init()
            viewModelScope.launchDefault {
                item
                    .asFlow()
                    .filterNotNull()
                    .combinePair(userPreferencesService.flow.map { it.appPreferences })
                    .collectLatest { (item, preferences) ->
                        canDelete.update { mediaManagementService.canDelete(item, preferences) }
                    }
            }
        }

        private fun fetchAndSetItem(): Deferred<BaseItem> =
            viewModelScope.async(
                Dispatchers.IO +
                    LoadingExceptionHandler(
                        loading,
                        "Error fetching movie",
                    ),
            ) {
                val item =
                    api.userLibraryApi.getItem(itemId).content.let {
                        BaseItem.from(it, api)
                    }
                this@EpisodeViewModel.item.setValueOnMain(item)
                item
            }

        fun init(): Job =
            viewModelScope.launch(
                Dispatchers.IO +
                    LoadingExceptionHandler(
                        loading,
                        "Error fetching movie",
                    ),
            ) {
                val prefs = userPreferencesService.getCurrent()
                val item = fetchAndSetItem().await()
                val result = itemPlaybackRepository.getSelectedTracks(item.id, item, prefs)
                withContext(Dispatchers.Main) {
                    this@EpisodeViewModel.item.value = item
                    chosenStreams.value = result
                    loading.value = LoadingState.Success
                    backdropService.submit(item)
                }
            }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
            fetchAndSetItem()
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            fetchAndSetItem()
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
                withContext(Dispatchers.Main) {
                    chosenStreams.value = chosen
                }
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
                withContext(Dispatchers.Main) {
                    chosenStreams.value = chosen
                }
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
                item.value?.let { item ->
                    val result =
                        itemPlaybackRepository.getSelectedTracks(
                            itemId,
                            item,
                            userPreferencesService.getCurrent(),
                        )
                    this@EpisodeViewModel.chosenStreams.setValueOnMain(result)
                }
            }
        }

        fun deleteItem(item: BaseItem) {
            deleteItem(context, mediaManagementService, item) {
                navigationManager.goBack()
            }
        }
    }
