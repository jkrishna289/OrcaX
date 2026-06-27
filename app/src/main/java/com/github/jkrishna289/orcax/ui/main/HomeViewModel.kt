package com.github.jkrishna289.orcax.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.HomeRowConfig
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.DatePlayedService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.HomePageResolvedSettings
import com.github.jkrishna289.orcax.services.HomeSettingsService
import com.github.jkrishna289.orcax.services.LatestNextUpService
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.NavDrawerService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.UserPreferencesService
import com.github.jkrishna289.orcax.services.deleteItem
import com.github.jkrishna289.orcax.services.tvAccess
import com.github.jkrishna289.orcax.ui.data.RowColumn
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.showToast
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.HomeRowLoadingState
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val mediaReportService: MediaReportService,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val datePlayedService: DatePlayedService,
        private val backdropService: BackdropService,
        private val userPreferencesService: UserPreferencesService,
        private val mediaManagementService: MediaManagementService,
        private val latestNextUpService: LatestNextUpService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HomeState.EMPTY)
        val state: StateFlow<HomeState> = _state

        init {
            datePlayedService.invalidateAll()
//            init()
        }

        fun init() {
            viewModelScope.launchIO {
                Timber.d("init HomeViewModel")
                try {
                    val preferences = userPreferencesService.getCurrent()
                    val prefs = preferences.appPreferences.homePagePreferences

                    serverRepository.currentUserDto.value?.let { userDto ->
                        val libraries =
                            navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                        val settings =
                            homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }
                        val state = state.value

                        // Refreshing if a load has already occurred and the rows haven't significantly changed
                        val refresh =
                            state.loadingState == LoadingState.Success && state.settings == settings
                        Timber.v("refresh=$refresh, state.loadingState=${state.loadingState}")
                        _state.update {
                            it.copy(
                                loadingState = if (refresh) LoadingState.Success else LoadingState.Loading,
                                refreshState = LoadingState.Loading,
                                settings = settings,
                            )
                        }

                        val semaphore = Semaphore(4)

                        val watchingRowIndexes =
                            settings.rows
                                .mapIndexedNotNull { index, row ->
                                    if (isWatchingRow(row.config)) index else null
                                }
                        val deferred =
                            settings.rows
                                // Load the watching rows first
                                .sortedByDescending { isWatchingRow(it.config) }
                                .map { row ->
                                    viewModelScope.async(Dispatchers.IO) {
                                        semaphore.withPermit {
                                            Timber.v("Fetching row: %s", row)
                                            try {
                                                homeSettingsService.fetchDataForRow(
                                                    row = row.config,
                                                    scope = viewModelScope,
                                                    prefs = prefs,
                                                    userDto = userDto,
                                                    libraries = libraries,
                                                    limit = prefs.maxItemsPerRow,
                                                    isRefresh = refresh,
                                                )
                                            } catch (ex: InvalidStatusException) {
                                                if (ex.status == 404) {
                                                    Timber.w(ex, "404 on row %s", row)
                                                    HomeRowLoadingState.Success(
                                                        row.title,
                                                        emptyList(),
                                                    )
                                                } else {
                                                    Timber.e(
                                                        ex,
                                                        "Error %s on row %s",
                                                        ex.status,
                                                        row,
                                                    )
                                                    HomeRowLoadingState.Error(
                                                        row.title,
                                                        exception = ex,
                                                    )
                                                }
                                            } catch (ex: Exception) {
                                                Timber.e(ex, "Error on row %s", row)
                                                HomeRowLoadingState.Error(row.title, exception = ex)
                                            }
                                        }
                                    }
                                }

                        if (refresh && state.homeRows.isNotEmpty() && watchingRowIndexes.isNotEmpty()) {
                            // Replace watching rows first
                            Timber.v("Refreshing rows: %s", watchingRowIndexes)
                            val rows =
                                deferred
                                    .filterIndexed { index, _ -> index in watchingRowIndexes }
                                    .awaitAll()
                            _state.update {
                                val newRows =
                                    it.homeRows.toMutableList().apply {
                                        rows.forEachIndexed { index, row ->
                                            set(watchingRowIndexes[index], row)
                                        }
                                    }
                                it.copy(
                                    loadingState = LoadingState.Success,
                                    homeRows = newRows,
                                )
                            }
                        }
                        val rows = deferred.awaitAll()
                        Timber.v("Got all rows")
                        _state.update {
                            it.copy(
                                loadingState = LoadingState.Success,
                                refreshState = LoadingState.Success,
                                homeRows = rows,
                            )
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception during home page loading")
                    if (state.value.loadingState == LoadingState.Success) {
                        showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                        _state.update { it.copy(refreshState = LoadingState.Error(ex)) }
                    } else {
                        _state.update {
                            it.copy(loadingState = LoadingState.Error(ex))
                        }
                    }
                }
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
            withContext(Dispatchers.Main) {
                init()
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            withContext(Dispatchers.Main) {
                init()
            }
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        fun deleteItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    val row = state.value.homeRows.getOrNull(position.row)
                    if (row is HomeRowLoadingState.Success) {
                        _state.update {
                            val newRow =
                                row.items.toMutableList().apply {
                                    removeAt(position.column)
                                }
                            it.copy(
                                homeRows =
                                    it.homeRows.toMutableList().apply {
                                        set(position.row, row.copy(items = newRow))
                                    },
                            )
                        }
                    }
                }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun removeFromNextUp(item: BaseItem) {
            if (item.type == BaseItemKind.EPISODE) {
                viewModelScope.launchDefault {
                    serverRepository.currentUser.value?.id?.let { userId ->
                        latestNextUpService.removeFromNextUp(userId, item)
                        init()
                    }
                }
            } else {
                Timber.w("Item is not an episode %s", item.id)
            }
        }
    }

data class HomeState(
    val loadingState: LoadingState,
    val refreshState: LoadingState,
    val homeRows: List<HomeRowLoadingState>,
    val settings: HomePageResolvedSettings,
) {
    companion object {
        val EMPTY =
            HomeState(
                LoadingState.Pending,
                LoadingState.Pending,
                listOf(),
                HomePageResolvedSettings.EMPTY,
            )
    }
}

/**
 * Whether a row is a "is watching" type
 */
private fun isWatchingRow(row: HomeRowConfig) =
    row is HomeRowConfig.ContinueWatching ||
        row is HomeRowConfig.NextUp ||
        row is HomeRowConfig.ContinueWatchingCombined
