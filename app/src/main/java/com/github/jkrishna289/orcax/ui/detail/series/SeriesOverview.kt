@file:UseSerializers(UUIDSerializer::class)

package com.github.jkrishna289.orcax.ui.detail.series

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ChosenStreams
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.TrailerService
import com.github.jkrishna289.orcax.ui.RequestOrRestoreFocus
import com.github.jkrishna289.orcax.ui.components.ConfirmDeleteDialog
import com.github.jkrishna289.orcax.ui.components.ConfirmDialog
import com.github.jkrishna289.orcax.ui.components.DialogItem
import com.github.jkrishna289.orcax.ui.components.DialogParams
import com.github.jkrishna289.orcax.ui.components.DialogPopup
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.LoadingPage
import com.github.jkrishna289.orcax.ui.components.chooseStream
import com.github.jkrishna289.orcax.ui.components.chooseVersionParams
import com.github.jkrishna289.orcax.ui.data.AddPlaylistViewModel
import com.github.jkrishna289.orcax.ui.data.ItemDetailsDialog
import com.github.jkrishna289.orcax.ui.data.ItemDetailsDialogInfo
import com.github.jkrishna289.orcax.ui.detail.MoreDialogActions
import com.github.jkrishna289.orcax.ui.detail.PlaylistDialog
import com.github.jkrishna289.orcax.ui.detail.PlaylistLoadingState
import com.github.jkrishna289.orcax.ui.detail.buildMoreDialogItems
import com.github.jkrishna289.orcax.ui.detail.discover.SourcePickerDialog
import com.github.jkrishna289.orcax.ui.components.ExpandablePlayButtons
import com.github.jkrishna289.orcax.ui.launchDefault
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.rememberInt
import com.github.jkrishna289.orcax.ui.seasonEpisode
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import com.github.jkrishna289.orcax.util.LoadingState
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.time.Duration

@Serializable
data class SeasonEpisode(
    val season: Int,
    val episode: Int,
)

@Serializable
data class SeasonEpisodeIds(
    val seasonId: UUID,
    val seasonNumber: Int?,
    val episodeId: UUID?,
    val episodeNumber: Int?,
)

@Serializable
data class SeriesOverviewPosition(
    val seasonTabIndex: Int,
    val episodeRowIndex: Int,
)

@Composable
fun SeriesOverview(
    preferences: UserPreferences,
    destination: Destination.SeriesOverview,
    initialSeasonEpisode: SeasonEpisodeIds?,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(
            creationCallback = {
                it.create(destination.itemId, initialSeasonEpisode, SeriesPageType.OVERVIEW)
            },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val headerFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val episodeRowFocusRequester = remember { FocusRequester() }
    val castCrewRowFocusRequester = remember { FocusRequester() }

    val loading by viewModel.loading.observeAsState(LoadingState.Loading)

    val series by viewModel.item.observeAsState(null)
    val seasons by viewModel.seasons.observeAsState(listOf())
    val episodes by viewModel.episodes.observeAsState(EpisodeList.Loading)
    val peopleInEpisode by viewModel.peopleInEpisode.observeAsState(PeopleInItem())
    val people by viewModel.people.observeAsState(listOf())
    val trailers by viewModel.trailers.observeAsState(listOf())
    val canDeleteSeries by viewModel.canDeleteSeries.collectAsState()
    val sourceSearch by viewModel.sources.state.collectAsState()
    // What the stream screens are finding streams for, e.g. "Welcome to Waikiki · S1 E3"
    var streaming by remember { mutableStateOf("") }

    val position by viewModel.position.collectAsState(SeriesOverviewPosition(0, 0))
    LaunchedEffect(Unit) {
        if (seasons.isNotEmpty()) {
            seasons.getOrNull(position.seasonTabIndex)?.let {
                viewModel.loadEpisodes(it.id)
            }
        }
    }

    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var moreDialog by remember { mutableStateOf<DialogParams?>(null) }
    var chooseVersion by remember { mutableStateOf<DialogParams?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<UUID?>(null) }
    val playlistState by playlistViewModel.playlistState.observeAsState(PlaylistLoadingState.Pending)

    // Focus starts on an episode: the one opened from, the one you were in the middle of, the next one
    // up, else S1 E1. There's no Play button for the show: the episode row is how you play.
    var rowFocused by rememberInt(EPISODE_ROW)
    var showDeleteDialog by remember { mutableStateOf<BaseItem?>(null) }
    var showWatchConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(position, episodes) {
        val focusedEpisode =
            (episodes as? EpisodeList.Success)
                ?.episodes
                ?.getOrNull(position.episodeRowIndex)

        focusedEpisode?.let {
            viewModel.lookUpChosenTracks(it.id, it)
            viewModel.lookupPeopleInEpisode(it)
        }
    }
    val chosenStreams by viewModel.chosenStreams.observeAsState(null)

    val preferredSubtitleLanguage =
        viewModel.serverRepository.currentUserDto
            .observeAsState()
            .value
            ?.configuration
            ?.subtitleLanguagePreference

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
            series?.let { series ->

                RequestOrRestoreFocus(
                    when (rowFocused) {
                        HEADER_ROW -> headerFocusRequester
                        CAST_AND_CREW_ROW -> castCrewRowFocusRequester
                        else -> episodeRowFocusRequester
                    },
                    "series_overview",
                )
                LifecycleResumeEffect(destination.itemId) {
                    viewModel.onResumePage()

                    onPauseOrDispose {
                        viewModel.release()
                    }
                }

                fun streamEpisode(ep: BaseItem) {
                    rowFocused = EPISODE_ROW
                    streaming = listOfNotNull(series.name, ep.data.seasonEpisode).joinToString(" · ")
                    viewModel.streamEpisode(ep)
                }

                suspend fun buildMoreForEpisode(
                    ep: BaseItem,
                    chosenStreams: ChosenStreams?,
                    fromLongClick: Boolean,
                ): DialogParams =
                    DialogParams(
                        fromLongClick = fromLongClick,
                        title = series.name + " - " + ep.data.seasonEpisode,
                        items =
                            // A missing episode can be streamed from here too
                            listOfNotNull(
                                DialogItem(context.getString(R.string.find_streams), R.string.fa_magnifying_glass_plus) {
                                    streamEpisode(ep)
                                }.takeIf { !ep.hasFile },
                            ) +
                                buildMoreDialogItems(
                                context = context,
                                item = ep,
                                watched = ep.data.userData?.played ?: false,
                                favorite = ep.data.userData?.isFavorite ?: false,
                                seriesId = series.id,
                                sourceId = chosenStreams?.source?.id?.toUUIDOrNull(),
                                canClearChosenStreams = chosenStreams?.itemPlayback != null || chosenStreams?.plc != null,
                                canDelete = viewModel.canDelete(ep),
                                actions =
                                    MoreDialogActions(
                                        navigateTo = viewModel::navigateTo,
                                        onClickWatch = { itemId, watched ->
                                            viewModel.setWatched(
                                                itemId,
                                                watched,
                                                position.episodeRowIndex,
                                            )
                                        },
                                        onClickFavorite = { itemId, favorite ->
                                            viewModel.setFavorite(
                                                itemId,
                                                favorite,
                                                position.episodeRowIndex,
                                            )
                                        },
                                        onClickAddPlaylist = {
                                            playlistViewModel.loadPlaylists(MediaType.VIDEO)
                                            showPlaylistDialog = it
                                        },
                                        onSendMediaInfo = viewModel.mediaReportService::sendReportFor,
                                        onClickDelete = {
                                            showDeleteDialog = it
                                        },
                                    ),
                                onChooseVersion = {
                                    chooseVersion =
                                        chooseVersionParams(
                                            context,
                                            ep.data.mediaSources!!,
                                            chosenStreams?.source?.id?.toUUIDOrNull(),
                                        ) { idx ->
                                            val source = ep.data.mediaSources!![idx]
                                            viewModel.savePlayVersion(
                                                ep,
                                                source.id!!.toUUID(),
                                            )
                                        }
                                    moreDialog = null
                                },
                                onChooseTracks = { type ->
                                    viewModel.streamChoiceService
                                        .chooseSource(
                                            ep.data,
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
                                                            ep,
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
                                    overviewDialog = ItemDetailsDialogInfo(ep)
                                },
                                onClearChosenStreams = {
                                    viewModel.clearChosenStreams(ep, chosenStreams)
                                },
                            ),
                    )

                SeriesOverviewContent(
                    preferences = preferences,
                    series = series,
                    seasons = seasons,
                    episodes = episodes,
                    chosenStreams = chosenStreams,
                    peopleInEpisode = peopleInEpisode,
                    people = people,
                    position = position,
                    headerFocusRequester = headerFocusRequester,
                    firstItemFocusRequester = firstItemFocusRequester,
                    episodeRowFocusRequester = episodeRowFocusRequester,
                    castCrewRowFocusRequester = castCrewRowFocusRequester,
                    onChangeSeason = { index ->
                        if (index != position.seasonTabIndex) {
                            seasons.getOrNull(index)?.let { season ->
                                viewModel.loadEpisodes(season.id)
                                viewModel.position.update {
                                    SeriesOverviewPosition(index, 0)
                                }
                            }
                        }
                    },
                    onFocusEpisode = { episodeIndex ->
                        viewModel.position.update {
                            it.copy(episodeRowIndex = episodeIndex)
                        }
                    },
                    onClick = {
                        rowFocused = EPISODE_ROW
                        if (!it.hasFile) {
                            // Not downloaded, or not even requested: find a stream for it
                            streamEpisode(it)
                            return@SeriesOverviewContent
                        }
                        val resumePosition =
                            it.data.userData
                                ?.playbackPositionTicks
                                ?.ticks ?: Duration.ZERO
                        viewModel.navigateTo(
                            Destination.Playback(
                                it.id,
                                resumePosition.inWholeMilliseconds,
                            ),
                        )
                    },
                    onLongClick = { ep ->
                        // A TMDB episode has nothing in Jellyfin to mark, favorite or delete
                        if (!ep.fromTmdb) {
                            scope.launchDefault {
                                moreDialog = buildMoreForEpisode(ep, chosenStreams, true)
                            }
                        }
                    },
                    // The show's buttons; an episode's actions are on its card's long press
                    headerButtons = { buttonsModifier ->
                        ExpandablePlayButtons(
                            resumePosition = Duration.ZERO,
                            watched = series.played,
                            favorite = series.favorite,
                            canDelete = false,
                            trailers = trailers,
                            playOnClick = {},
                            watchOnClick = { showWatchConfirmation = true },
                            favoriteOnClick = { viewModel.setFavorite(series.id, !series.favorite, null) },
                            moreOnClick = {
                                moreDialog =
                                    DialogParams(
                                        fromLongClick = false,
                                        title = series.name ?: "",
                                        items =
                                            buildList {
                                                add(
                                                    DialogItem(context.getString(R.string.shuffle), R.string.fa_shuffle) {
                                                        viewModel.navigateTo(Destination.PlaybackList(itemId = series.id, shuffle = true))
                                                    },
                                                )
                                                add(
                                                    DialogItem(R.string.add_to_playlist, R.string.fa_list_ul) {
                                                        playlistViewModel.loadPlaylists(MediaType.VIDEO)
                                                        showPlaylistDialog = series.id
                                                    },
                                                )
                                                if (canDeleteSeries) {
                                                    add(
                                                        DialogItem(
                                                            context.getString(R.string.delete),
                                                            Icons.Default.Delete,
                                                            iconColor = Color.Red.copy(alpha = .8f),
                                                        ) { showDeleteDialog = series },
                                                    )
                                                }
                                            },
                                    )
                            },
                            trailerOnClick = {
                                rowFocused = HEADER_ROW
                                TrailerService.onClick(context, it, viewModel::navigateTo)
                            },
                            deleteOnClick = {},
                            buttonOnFocusChanged = {},
                            showPlay = false,
                            modifier = buttonsModifier,
                        )
                    },
                    overviewOnClick = { overviewDialog = ItemDetailsDialogInfo(series) },
                    personOnClick = {
                        rowFocused = CAST_AND_CREW_ROW
                        viewModel.navigateTo(
                            Destination.MediaItem(
                                it.id,
                                BaseItemKind.PERSON,
                            ),
                        )
                    },
                    modifier = modifier,
                )
                if (showWatchConfirmation) {
                    ConfirmDialog(
                        title = series.name ?: "",
                        body =
                            stringResource(
                                if (series.played) R.string.mark_entire_series_as_unplayed else R.string.mark_entire_series_as_played,
                            ),
                        onCancel = { showWatchConfirmation = false },
                        onConfirm = {
                            viewModel.setWatchedSeries(!series.played)
                            showWatchConfirmation = false
                        },
                    )
                }
            }
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
    showPlaylistDialog?.let { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { showPlaylistDialog = null },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                showPlaylistDialog = null
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                showPlaylistDialog = null
            },
            elevation = 3.dp,
        )
    }
    SourcePickerDialog(
        state = sourceSearch,
        title = streaming,
        onPick = { viewModel.sources.playSource(it) },
        onRetry = viewModel.sources::retry,
        // The show is in the library already
        onRequest = null,
        onNextStream = { viewModel.sources.nextStream() },
        onChooseAnother = viewModel.sources::chooseAnother,
        onKeepWaiting = viewModel.sources::keepWaiting,
        onDismiss = viewModel.sources::dismiss,
        forShow = true,
    )
    showDeleteDialog?.let { item ->
        ConfirmDeleteDialog(
            itemTitle = item.subtitle ?: "",
            onCancel = { showDeleteDialog = null },
            onConfirm = {
                viewModel.deleteItem(item)
                episodeRowFocusRequester.tryRequestFocus()
                showDeleteDialog = null
            },
        )
    }
}

private const val EPISODE_ROW = 0
private const val CAST_AND_CREW_ROW = EPISODE_ROW + 1
private const val HEADER_ROW = CAST_AND_CREW_ROW + 1
