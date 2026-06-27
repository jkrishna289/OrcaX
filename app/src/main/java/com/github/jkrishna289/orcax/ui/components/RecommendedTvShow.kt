package com.github.jkrishna289.orcax.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.LatestNextUpService
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.MusicService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.SuggestionService
import com.github.jkrishna289.orcax.services.SuggestionsResource
import com.github.jkrishna289.orcax.ui.SlimItemFields
import com.github.jkrishna289.orcax.ui.data.RowColumn
import com.github.jkrishna289.orcax.ui.setValueOnMain
import com.github.jkrishna289.orcax.ui.toBaseItems
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.GetItemsRequestHandler
import com.github.jkrishna289.orcax.util.GetNextUpRequestHandler
import com.github.jkrishna289.orcax.util.GetResumeItemsRequestHandler
import com.github.jkrishna289.orcax.util.HomeRowLoadingState
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.util.UUID

@HiltViewModel(assistedFactory = RecommendedTvShowViewModel.Factory::class)
class RecommendedTvShowViewModel
    @AssistedInject
    constructor(
        @ApplicationContext context: Context,
        api: ApiClient,
        musicService: MusicService,
        private val serverRepository: ServerRepository,
        private val preferencesDataStore: DataStore<AppPreferences>,
        private val lastestNextUpService: LatestNextUpService,
        private val suggestionService: SuggestionService,
        @Assisted val parentId: UUID,
        navigationManager: NavigationManager,
        favoriteWatchManager: FavoriteWatchManager,
        mediaReportService: MediaReportService,
        backdropService: BackdropService,
        mediaManagementService: MediaManagementService,
    ) : RecommendedViewModel(
            context,
            api,
            navigationManager,
            favoriteWatchManager,
            mediaReportService,
            musicService,
            backdropService,
            mediaManagementService,
        ) {
        @AssistedFactory
        interface Factory {
            fun create(parentId: UUID): RecommendedTvShowViewModel
        }

        override val rows =
            MutableStateFlow<List<HomeRowLoadingState>>(
                rowTitles.keys.map {
                    HomeRowLoadingState.Pending(
                        context.getString(it),
                    )
                },
            )

        override fun init() {
            viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
                val preferences =
                    preferencesDataStore.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                val combineNextUp = preferences.homePagePreferences.combineContinueNext
                val itemsPerRow = preferences.homePagePreferences.maxItemsPerRow
                val userId = serverRepository.currentUser.value?.id
                try {
                    val resumeItemsDeferred =
                        async(Dispatchers.IO) {
                            val resumeItemsRequest =
                                GetResumeItemsRequest(
                                    userId = userId,
                                    parentId = parentId,
                                    fields = SlimItemFields,
                                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                                    enableUserData = true,
                                    startIndex = 0,
                                    limit = itemsPerRow,
                                    enableTotalRecordCount = false,
                                )
                            GetResumeItemsRequestHandler
                                .execute(api, resumeItemsRequest)
                                .toBaseItems(api, true)
                        }

                    val nextUpItemsDeferred =
                        async(Dispatchers.IO) {
                            val nextUpRequest =
                                GetNextUpRequest(
                                    userId = userId,
                                    fields = SlimItemFields,
                                    imageTypeLimit = 1,
                                    parentId = parentId,
                                    limit = itemsPerRow,
                                    enableResumable = false,
                                    enableUserData = true,
                                    enableRewatching = preferences.homePagePreferences.enableRewatchingNextUp,
                                )
                            GetNextUpRequestHandler
                                .execute(api, nextUpRequest)
                                .toBaseItems(api, true)
                        }

                    val resumeItems = resumeItemsDeferred.await()
                    val nextUpItems = nextUpItemsDeferred.await()

                    if (combineNextUp) {
                        val combined = lastestNextUpService.buildCombined(resumeItems, nextUpItems)
                        update(
                            R.string.continue_watching,
                            HomeRowLoadingState.Success(
                                context.getString(R.string.continue_watching),
                                combined,
                            ),
                        )
                        update(
                            R.string.next_up,
                            HomeRowLoadingState.Success(context.getString(R.string.next_up), listOf()),
                        )
                    } else {
                        update(
                            R.string.continue_watching,
                            HomeRowLoadingState.Success(
                                context.getString(R.string.continue_watching),
                                resumeItems,
                            ),
                        )
                        update(
                            R.string.next_up,
                            HomeRowLoadingState.Success(context.getString(R.string.next_up), nextUpItems),
                        )
                    }

                    if (resumeItems.isNotEmpty() || nextUpItems.isNotEmpty()) {
                        loading.setValueOnMain(LoadingState.Success)
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception fetching tv recommendations")
                    withContext(Dispatchers.Main) {
                        loading.value = LoadingState.Error(ex)
                    }
                }

                val jobs = mutableListOf<Deferred<HomeRowLoadingState>>()

                update(R.string.recently_released) {
                    val request =
                        GetItemsRequest(
                            parentId = parentId,
                            fields = SlimItemFields,
                            includeItemTypes = listOf(BaseItemKind.EPISODE),
                            recursive = true,
                            enableUserData = true,
                            sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            startIndex = 0,
                            limit = itemsPerRow,
                            enableTotalRecordCount = false,
                        )
                    GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                }.also(jobs::add)

                update(R.string.recently_added) {
                    val request =
                        GetItemsRequest(
                            parentId = parentId,
                            fields = SlimItemFields,
                            includeItemTypes = listOf(BaseItemKind.EPISODE),
                            recursive = true,
                            enableUserData = true,
                            sortBy = listOf(ItemSortBy.DATE_CREATED),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            startIndex = 0,
                            limit = itemsPerRow,
                            enableTotalRecordCount = false,
                        )
                    GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                }.also(jobs::add)

                update(R.string.top_unwatched) {
                    val request =
                        GetItemsRequest(
                            parentId = parentId,
                            fields = SlimItemFields,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            recursive = true,
                            enableUserData = true,
                            isPlayed = false,
                            sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            startIndex = 0,
                            limit = itemsPerRow,
                            enableTotalRecordCount = false,
                        )
                    GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                }.also(jobs::add)

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        suggestionService
                            .getSuggestionsFlow(parentId, BaseItemKind.SERIES)
                            .collect { resource ->
                                val state =
                                    when (resource) {
                                        is SuggestionsResource.Loading -> {
                                            HomeRowLoadingState.Loading(
                                                context.getString(R.string.suggestions),
                                            )
                                        }

                                        is SuggestionsResource.Success -> {
                                            HomeRowLoadingState.Success(
                                                context.getString(R.string.suggestions),
                                                resource.items,
                                            )
                                        }

                                        is SuggestionsResource.Empty -> {
                                            HomeRowLoadingState.Success(
                                                context.getString(R.string.suggestions),
                                                emptyList(),
                                            )
                                        }
                                    }
                                update(R.string.suggestions, state)
                            }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Failed to fetch suggestions")
                        update(
                            R.string.suggestions,
                            HomeRowLoadingState.Error(
                                title = context.getString(R.string.suggestions),
                                exception = ex,
                            ),
                        )
                    }
                }

                if (loading.value == LoadingState.Loading || loading.value == LoadingState.Pending) {
                    for (i in 0..<jobs.size) {
                        val result = jobs[i].await()
                        if (result is HomeRowLoadingState.Success) {
                            Timber.v("First success")
                            loading.setValueOnMain(LoadingState.Success)
                        }
                        break
                    }
                }
            }
        }

        override fun update(
            @StringRes title: Int,
            row: HomeRowLoadingState,
        ): HomeRowLoadingState {
            rows.update { current ->
                current.toMutableList().apply { set(rowTitles[title]!!, row) }
            }
            return row
        }

        companion object {
            private val rowTitles =
                listOf(
                    R.string.continue_watching,
                    R.string.next_up,
                    R.string.recently_released,
                    R.string.recently_added,
                    R.string.suggestions,
                    R.string.top_unwatched,
                ).mapIndexed { index, i -> i to index }.toMap()
        }
    }

/**
 * The "recommended" tab of a TV show library
 */
@Composable
fun RecommendedTvShow(
    preferences: UserPreferences,
    parentId: UUID,
    onFocusPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecommendedTvShowViewModel =
        hiltViewModel<RecommendedTvShowViewModel, RecommendedTvShowViewModel.Factory>(
            creationCallback = { it.create(parentId) },
        ),
) {
    RecommendedContent(
        preferences = preferences,
        viewModel = viewModel,
        onFocusPosition = onFocusPosition,
        modifier = modifier,
    )
}
