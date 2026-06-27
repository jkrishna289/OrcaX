package com.github.jkrishna289.orcax.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.HomeRowViewOptions
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.FavoriteWatchManager
import com.github.jkrishna289.orcax.services.MediaManagementService
import com.github.jkrishna289.orcax.services.MediaReportService
import com.github.jkrishna289.orcax.services.MusicService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.deleteItem
import com.github.jkrishna289.orcax.ui.OneTimeLaunchedEffect
import com.github.jkrishna289.orcax.ui.data.AddPlaylistViewModel
import com.github.jkrishna289.orcax.ui.data.RowColumn
import com.github.jkrishna289.orcax.ui.detail.MoreDialogActions
import com.github.jkrishna289.orcax.ui.detail.PlaylistDialog
import com.github.jkrishna289.orcax.ui.detail.PlaylistLoadingState
import com.github.jkrishna289.orcax.ui.detail.buildMoreDialogItemsForHome
import com.github.jkrishna289.orcax.ui.detail.music.addToQueue
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.main.HomePageContent
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.rememberPosition
import com.github.jkrishna289.orcax.util.ApiRequestPager
import com.github.jkrishna289.orcax.util.HomeRowLoadingState
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaType
import java.util.UUID

/**
 * Abstract [ViewModel] for the "Recommended" tab for a library
 */
abstract class RecommendedViewModel(
    @param:ApplicationContext val context: Context,
    val api: ApiClient,
    val navigationManager: NavigationManager,
    val favoriteWatchManager: FavoriteWatchManager,
    val mediaReportService: MediaReportService,
    private val musicService: MusicService,
    private val backdropService: BackdropService,
    private val mediaManagementService: MediaManagementService,
) : ViewModel() {
    abstract fun init()

    abstract val rows: MutableStateFlow<List<HomeRowLoadingState>>

    val loading = MutableLiveData<LoadingState>(LoadingState.Loading)

    fun refreshItem(
        position: RowColumn,
        itemId: UUID,
    ) {
        viewModelScope.launchIO {
            val row = rows.value.getOrNull(position.row)
            if (row is HomeRowLoadingState.Success) {
                (row.items as? ApiRequestPager<*>)?.refreshItem(position.column, itemId)
            }
        }
    }

    fun setWatched(
        position: RowColumn,
        itemId: UUID,
        watched: Boolean,
    ) {
        viewModelScope.launchIO {
            favoriteWatchManager.setWatched(itemId, watched)
            refreshItem(position, itemId)
        }
    }

    fun setFavorite(
        position: RowColumn,
        itemId: UUID,
        watched: Boolean,
    ) {
        viewModelScope.launchIO {
            favoriteWatchManager.setFavorite(itemId, watched)
            refreshItem(position, itemId)
        }
    }

    fun updateBackdrop(item: BaseItem) {
        viewModelScope.launchIO {
            backdropService.submit(item)
        }
    }

    abstract fun update(
        @StringRes title: Int,
        row: HomeRowLoadingState,
    ): HomeRowLoadingState

    fun update(
        @StringRes title: Int,
        viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
        block: suspend () -> List<BaseItem>,
    ): Deferred<HomeRowLoadingState> =
        viewModelScope.async(Dispatchers.IO) {
            val titleStr = context.getString(title)
            val row =
                try {
                    HomeRowLoadingState.Success(titleStr, block.invoke(), viewOptions)
                } catch (ex: Exception) {
                    HomeRowLoadingState.Error(titleStr, null, ex)
                }
            update(title, row)
        }

    fun deleteItem(
        position: RowColumn,
        item: BaseItem,
    ) {
        deleteItem(context, mediaManagementService, item) {
            viewModelScope.launchDefault {
                val row = rows.value.getOrNull(position.row)
                if (row is HomeRowLoadingState.Success) {
                    (row.items as? ApiRequestPager<*>)?.refreshPagesAfter(position.column)
                }
            }
        }
    }

    fun canDelete(
        item: BaseItem,
        appPreferences: AppPreferences,
    ): Boolean = mediaManagementService.canDelete(item, appPreferences)

    fun addToQueue(
        item: BaseItem,
        index: Int,
    ) = addToQueue(api, musicService, item, index)
}

@Composable
fun RecommendedContent(
    preferences: UserPreferences,
    viewModel: RecommendedViewModel,
    modifier: Modifier = Modifier,
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    onFocusPosition: ((RowColumn) -> Unit)? = null,
) {
    val context = LocalContext.current
    var moreDialog by remember { mutableStateOf<Optional<RowColumnItem>>(Optional.absent()) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    var showDeleteDialog by remember { mutableStateOf<RowColumnItem?>(null) }
    val playlistState by playlistViewModel.playlistState.observeAsState(PlaylistLoadingState.Pending)

    OneTimeLaunchedEffect {
        viewModel.init()
    }
    val loading by viewModel.loading.observeAsState(LoadingState.Loading)
    val rows by viewModel.rows.collectAsState()

    when (val state = loading) {
        is LoadingState.Error -> {
            ErrorMessage(state, modifier)
        }

        LoadingState.Loading,
        LoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            var position by rememberPosition()
            HomePageContent(
                homeRows = rows,
                position = position,
                onClickItem = { _, item ->
                    viewModel.navigationManager.navigateTo(item.destination())
                },
                onLongClickItem = { position, item ->
                    moreDialog.makePresent(RowColumnItem(position, item))
                },
                onClickPlay = { _, item ->
                    viewModel.navigationManager.navigateTo(Destination.Playback(item))
                },
                onFocusPosition = {
                    position = it
                    val nonEmptyRowBefore =
                        rows
                            .subList(0, it.row)
                            .count {
                                it is HomeRowLoadingState.Success && it.items.isEmpty()
                            }
                    onFocusPosition?.invoke(
                        RowColumn(
                            it.row - nonEmptyRowBefore,
                            it.column,
                        ),
                    )
                },
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                modifier = modifier,
            )
        }
    }
    moreDialog.compose { (position, item) ->
        DialogPopup(
            showDialog = true,
            title = item.title ?: "",
            dialogItems =
                buildMoreDialogItemsForHome(
                    context = context,
                    item = item,
                    seriesId = null,
                    playbackPosition = item.playbackPosition,
                    watched = item.played,
                    favorite = item.favorite,
                    canDelete = viewModel.canDelete(item, preferences.appPreferences),
                    actions =
                        MoreDialogActions(
                            navigateTo = { viewModel.navigationManager.navigateTo(it) },
                            onClickWatch = { itemId, watched ->
                                viewModel.setWatched(position, itemId, watched)
                            },
                            onClickFavorite = { itemId, watched ->
                                viewModel.setFavorite(position, itemId, watched)
                            },
                            onClickAddPlaylist = {
                                playlistViewModel.loadPlaylists(MediaType.VIDEO)
                                showPlaylistDialog.makePresent(it)
                            },
                            onSendMediaInfo = viewModel.mediaReportService::sendReportFor,
                            onClickDelete = { showDeleteDialog = RowColumnItem(position, item) },
                            onClickAddToQueue = {
                                viewModel.addToQueue(it, 0)
                            },
                        ),
                ),
            onDismissRequest = { moreDialog.makeAbsent() },
            dismissOnClick = true,
            waitToLoad = true,
        )
    }
    showPlaylistDialog.compose { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { showPlaylistDialog.makeAbsent() },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                showPlaylistDialog.makeAbsent()
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                showPlaylistDialog.makeAbsent()
            },
            elevation = 3.dp,
        )
    }
    showDeleteDialog?.let { (position, item) ->
        ConfirmDeleteDialog(
            itemTitle = listOfNotNull(item.title, item.subtitle).joinToString(" - "),
            onCancel = { showDeleteDialog = null },
            onConfirm = {
                viewModel.deleteItem(position, item)
                showDeleteDialog = null
            },
        )
    }
}

data class RowColumnItem(
    val position: RowColumn,
    val item: BaseItem,
)
