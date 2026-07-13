package com.github.jkrishna289.orcax.ui.detail.movie

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ExtrasItem
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.DiscoverItem
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.data.model.Trailer
import com.github.jkrishna289.orcax.data.model.aspectRatioFloat
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.TrailerService
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.RequestOrRestoreFocus
import com.github.jkrishna289.orcax.ui.cards.ChapterRow
import com.github.jkrishna289.orcax.ui.cards.ExtrasRow
import com.github.jkrishna289.orcax.ui.cards.PersonRow
import com.github.jkrishna289.orcax.ui.components.ConfirmDeleteDialog
import com.github.jkrishna289.orcax.ui.components.DialogParams
import com.github.jkrishna289.orcax.ui.components.DialogPopup
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.ExpandablePlayButtons
import com.github.jkrishna289.orcax.ui.components.HeaderUtils
import com.github.jkrishna289.orcax.ui.components.LoadingPage
import com.github.jkrishna289.orcax.ui.components.Optional
import com.github.jkrishna289.orcax.ui.components.chooseStream
import com.github.jkrishna289.orcax.ui.components.chooseVersionParams
import com.github.jkrishna289.orcax.ui.data.AddPlaylistViewModel
import com.github.jkrishna289.orcax.ui.data.ItemDetailsDialog
import com.github.jkrishna289.orcax.ui.data.ItemDetailsDialogInfo
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarRow
import com.github.jkrishna289.orcax.ui.detail.MoreDialogActions
import com.github.jkrishna289.orcax.ui.detail.PlaylistDialog
import com.github.jkrishna289.orcax.ui.detail.PlaylistLoadingState
import com.github.jkrishna289.orcax.ui.detail.buildMoreDialogItems
import com.github.jkrishna289.orcax.ui.detail.buildMoreDialogItemsForHome
import com.github.jkrishna289.orcax.ui.detail.buildMoreDialogItemsForPerson
import com.github.jkrishna289.orcax.ui.discover.DiscoverRow
import com.github.jkrishna289.orcax.ui.discover.DiscoverRowData
import com.github.jkrishna289.orcax.ui.letNotEmpty
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.rememberInt
import com.github.jkrishna289.orcax.util.DataLoadingState
import com.github.jkrishna289.orcax.util.DiscoverRequestType
import com.github.jkrishna289.orcax.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.time.Duration

@Composable
fun MovieDetails(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: MovieViewModel =
        hiltViewModel<MovieViewModel, MovieViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.init()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsState()

    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var moreDialog by remember { mutableStateOf<DialogParams?>(null) }
    var chooseVersion by remember { mutableStateOf<DialogParams?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    val playlistState by playlistViewModel.playlistState.observeAsState(PlaylistLoadingState.Pending)
    var showDeleteDialog by remember { mutableStateOf<BaseItem?>(null) }

    val preferredSubtitleLanguage =
        viewModel.serverRepository.currentUserDto
            .observeAsState()
            .value
            ?.configuration
            ?.subtitleLanguagePreference

    val moreActions =
        MoreDialogActions(
            navigateTo = viewModel::navigateTo,
            onClickWatch = { itemId, watched ->
                viewModel.setWatched(itemId, watched)
            },
            onClickFavorite = { itemId, favorite ->
                viewModel.setFavorite(itemId, favorite)
            },
            onClickAddPlaylist = { itemId ->
                playlistViewModel.loadPlaylists(MediaType.VIDEO)
                showPlaylistDialog.makePresent(itemId)
            },
            onSendMediaInfo = viewModel.mediaReportService::sendReportFor,
            onClickDelete = { showDeleteDialog = it },
        )

    when (val s = state.loading) {
        is DataLoadingState.Error -> {
            ErrorMessage(s, modifier)
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Success -> {
            val unknownStr = stringResource(R.string.unknown)
            val movie by rememberUpdatedState(s.data)
            val chosenStreams by rememberUpdatedState(state.chosenStreams)
            LifecycleResumeEffect(destination.itemId) {
                viewModel.maybePlayThemeSong(
                    destination.itemId,
                    preferences.appPreferences.interfacePreferences.playThemeSongs,
                )
                onPauseOrDispose {
                    viewModel.release()
                }
            }
            MovieDetailsContent(
                preferences = preferences,
                movie = movie,
                state = state,
                onClickItem = { index, item ->
                    viewModel.navigateTo(item.destination())
                },
                onClickPerson = {
                    viewModel.navigateTo(
                        Destination.MediaItem(
                            it.id,
                            BaseItemKind.PERSON,
                        ),
                    )
                },
                playOnClick = {
                    viewModel.navigateTo(
                        Destination.Playback(
                            movie.id,
                            it.inWholeMilliseconds,
                        ),
                    )
                },
                overviewOnClick = {
                    overviewDialog =
                        ItemDetailsDialogInfo(movie)
                },
                moreOnClick = {
                    moreDialog =
                        DialogParams(
                            fromLongClick = false,
                            title = movie.name + " (${movie.data.productionYear ?: ""})",
                            items =
                                buildMoreDialogItems(
                                    context = context,
                                    item = movie,
                                    watched = movie.data.userData?.played ?: false,
                                    favorite = movie.data.userData?.isFavorite ?: false,
                                    seriesId = null,
                                    sourceId = chosenStreams?.source?.id?.toUUIDOrNull(),
                                    canClearChosenStreams = chosenStreams.let { it?.itemPlayback != null || it?.plc != null },
                                    canDelete = state.canDelete,
                                    actions = moreActions,
                                    onChooseVersion = {
                                        chooseVersion =
                                            chooseVersionParams(
                                                context,
                                                movie.data.mediaSources!!,
                                                chosenStreams?.source?.id?.toUUIDOrNull(),
                                            ) { idx ->
                                                val source = movie.data.mediaSources!![idx]
                                                viewModel.savePlayVersion(
                                                    movie,
                                                    source.id!!.toUUID(),
                                                )
                                            }
                                        moreDialog = null
                                    },
                                    onChooseTracks = { type ->
                                        viewModel.streamChoiceService
                                            .chooseSource(
                                                movie.data,
                                                chosenStreams?.itemPlayback,
                                            )?.let { source ->
                                                chooseVersion =
                                                    chooseStream(
                                                        context = context,
                                                        streams = source.mediaStreams.orEmpty(),
                                                        type = type,
                                                        currentIndex =
                                                            if (type == MediaStreamType.AUDIO) {
                                                                chosenStreams?.audioStream?.index
                                                            } else {
                                                                chosenStreams?.subtitleStream?.index
                                                            },
                                                        onClick = { trackIndex ->
                                                            viewModel.saveTrackSelection(
                                                                movie,
                                                                chosenStreams?.itemPlayback,
                                                                trackIndex,
                                                                type,
                                                            )
                                                        },
                                                        preferredSubtitleLanguage = preferredSubtitleLanguage,
                                                    )
                                            }
                                    },
                                    onShowOverview = {
                                        overviewDialog = ItemDetailsDialogInfo(movie)
                                    },
                                    onClearChosenStreams = {
                                        viewModel.clearChosenStreams(chosenStreams)
                                    },
                                ),
                        )
                },
                watchOnClick = {
                    viewModel.setWatched(movie.id, !movie.played)
                },
                favoriteOnClick = {
                    viewModel.setFavorite(movie.id, !movie.favorite)
                },
                onLongClickPerson = { index, person ->
                    val items =
                        buildMoreDialogItemsForPerson(
                            context = context,
                            person = person,
                            actions = moreActions,
                        )
                    moreDialog =
                        DialogParams(
                            fromLongClick = true,
                            title = person.name ?: "",
                            items = items,
                        )
                },
                onLongClickSimilar = { index, similar ->
                    val items =
                        buildMoreDialogItemsForHome(
                            context = context,
                            item = similar,
                            seriesId = null,
                            playbackPosition = similar.playbackPosition,
                            watched = similar.played,
                            favorite = similar.favorite,
                            canDelete = false,
                            actions = moreActions,
                        )
                    moreDialog =
                        DialogParams(
                            fromLongClick = true,
                            title = similar.title ?: "",
                            items = items,
                        )
                },
                trailerOnClick = {
                    TrailerService.onClick(context, it, viewModel::navigateTo)
                },
                onClickExtra = { index, extra ->
                    viewModel.navigateTo(extra.destination)
                },
                onClickDiscover = { index, item ->
                    viewModel.navigateTo(item.destination)
                },
                canDelete = state.canDelete,
                deleteOnClick = { showDeleteDialog = state.movie },
                requestOnClick = viewModel::requestMedia,
                modifier = modifier,
            )
        }
    }
    overviewDialog?.let { info ->
        ItemDetailsDialog(
            info = info,
            showFilePath =
                viewModel.serverRepository.currentUserDto.value
                    ?.policy
                    ?.isAdministrator == true,
            onDismissRequest = { overviewDialog = null },
        )
    }
    moreDialog?.let { params ->
        DialogPopup(
            showDialog = true,
            title = params.title,
            dialogItems = params.items,
            onDismissRequest = { moreDialog = null },
            dismissOnClick = true,
            waitToLoad = params.fromLongClick,
        )
    }
    chooseVersion?.let { params ->
        DialogPopup(
            showDialog = true,
            title = params.title,
            dialogItems = params.items,
            onDismissRequest = { chooseVersion = null },
            dismissOnClick = true,
            waitToLoad = params.fromLongClick,
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
    showDeleteDialog?.let { item ->
        ConfirmDeleteDialog(
            itemTitle = item.title ?: "",
            onCancel = { showDeleteDialog = null },
            onConfirm = {
                viewModel.deleteItem(item)
                showDeleteDialog = null
            },
        )
    }
}

private const val HEADER_ROW = 0
private const val PEOPLE_ROW = HEADER_ROW + 1
private const val TRAILER_ROW = PEOPLE_ROW + 1
private const val CHAPTER_ROW = TRAILER_ROW + 1
private const val EXTRAS_ROW = CHAPTER_ROW + 1
private const val SIMILAR_ROW = EXTRAS_ROW + 1
private const val DISCOVER_ROW = SIMILAR_ROW + 1

private const val DETAILS_ROW_PARENT_FRACTION = 0.18f

/**
 * Keeps the focused row ~18% down from the top of the screen instead of the default flush-edge
 * scroll, so the row that follows (Chapters under People, Extras under Chapters, etc.) always
 * peeks in at the bottom instead of being scrolled fully offscreen.
 */
@OptIn(ExperimentalFoundationApi::class)
private val DetailsRowFocusSpec =
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float {
            val target = DETAILS_ROW_PARENT_FRACTION * containerSize
            val clampedTarget =
                if (size <= containerSize && (containerSize - target) < size) {
                    containerSize - size // near the end of the list — don't overscroll past the last row
                } else {
                    target
                }
            return offset - clampedTarget
        }
    }

@Composable
fun MovieDetailsContent(
    preferences: UserPreferences,
    movie: BaseItem,
    state: MovieState,
    playOnClick: (Duration) -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    overviewOnClick: () -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    onClickItem: (Int, BaseItem) -> Unit,
    onClickPerson: (Person) -> Unit,
    onLongClickPerson: (Int, Person) -> Unit,
    onLongClickSimilar: (Int, BaseItem) -> Unit,
    onClickExtra: (Int, ExtrasItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
    canDelete: Boolean,
    deleteOnClick: () -> Unit,
    requestOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var position by rememberInt(0)
    val focusRequesters = remember { List(DISCOVER_ROW + 1) { FocusRequester() } }
    val dto = movie.data
    val resumePosition = dto.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO

    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    RequestOrRestoreFocus(focusRequesters.getOrNull(position))

    Box(modifier = modifier) {
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides DetailsRowFocusSpec) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(bringIntoViewRequester),
                    ) {
                        MovieDetailsHeader(
                            preferences = preferences,
                            movie = movie,
                            chosenStreams = state.chosenStreams,
                            availability = state.availability,
                            qualityPreview = state.qualityPreview,
                            requestInFlight = state.requestInFlight,
                            requestOnClick = requestOnClick,
                            bringIntoViewRequester = bringIntoViewRequester,
                            overviewOnClick = overviewOnClick,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = HeaderUtils.topPadding, bottom = 16.dp),
                        )
                        ExpandablePlayButtons(
                            resumePosition = resumePosition,
                            watched = dto.userData?.played ?: false,
                            favorite = dto.userData?.isFavorite ?: false,
                            playOnClick = {
                                position = HEADER_ROW
                                playOnClick.invoke(it)
                            },
                            moreOnClick = moreOnClick,
                            watchOnClick = watchOnClick,
                            favoriteOnClick = favoriteOnClick,
                            buttonOnFocusChanged = {
                                if (it.isFocused) {
                                    position = HEADER_ROW
                                    scope.launch(ExceptionHandler()) {
                                        bringIntoViewRequester.bringIntoView()
                                    }
                                }
                            },
                            trailers = state.trailers,
                            trailerOnClick = {
                                position = TRAILER_ROW
                                trailerOnClick.invoke(it)
                            },
                            canDelete = canDelete,
                            deleteOnClick = deleteOnClick,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                                    .focusRequester(focusRequesters[HEADER_ROW]),
                        )
                    }
                }
                state.people.letNotEmpty { people ->
                    item {
                        PersonRow(
                            people = people,
                            onClick = {
                                position = PEOPLE_ROW
                                onClickPerson.invoke(it)
                            },
                            onLongClick = { index, person ->
                                position = PEOPLE_ROW
                                onLongClickPerson.invoke(index, person)
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[PEOPLE_ROW]),
                        )
                    }
                }
                state.chapters.letNotEmpty { chapters ->
                    item {
                        ChapterRow(
                            chapters = chapters,
                            aspectRatio = movie.data.aspectRatioFloat ?: AspectRatios.WIDE,
                            onClick = {
                                position = CHAPTER_ROW
                                playOnClick.invoke(it.position)
                            },
                            onLongClick = {},
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[CHAPTER_ROW]),
                        )
                    }
                }
                state.extras.letNotEmpty { extras ->
                    item {
                        ExtrasRow(
                            extras = extras,
                            onClickItem = { index, item ->
                                position = EXTRAS_ROW
                                onClickExtra.invoke(index, item)
                            },
                            onLongClickItem = { _, _ -> },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[EXTRAS_ROW]),
                        )
                    }
                }
                // "More Like This" powered by the Orca Engine's content-based similarity (replaces
                // Jellyfin's native similar list). Self-contained + fail-soft: renders nothing when the
                // engine is unavailable or has no results.
                item(key = "engineSimilar") {
                    EngineSimilarRow(
                        itemId = movie.id,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.discovered.letNotEmpty { discovered ->
                    item {
                        DiscoverRow(
                            row =
                                DiscoverRowData(
                                    stringResource(R.string.discover),
                                    DataLoadingState.Success(discovered),
                                    type = DiscoverRequestType.UNKNOWN,
                                ),
                            onClickItem = { index: Int, item: DiscoverItem ->
                                position = DISCOVER_ROW
                                onClickDiscover.invoke(index, item)
                            },
                            onLongClickItem = { _, _ -> },
                            onCardFocus = {},
                            focusRequester = focusRequesters[DISCOVER_ROW],
                        )
                    }
                }
            }
        }
    }
}
