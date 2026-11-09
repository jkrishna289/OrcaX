package com.github.jkrishna289.orcax.ui.detail.engine

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.EpisodeProgress
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.ImageUrlService
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.ThemeSongPlayer
import com.github.jkrishna289.orcax.services.UserPreferencesService
import com.github.jkrishna289.orcax.ui.RequestOrRestoreFocus
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.ExpandableFaButton
import com.github.jkrishna289.orcax.ui.components.LoadingPage
import com.github.jkrishna289.orcax.ui.detail.discover.SourcePickerDialog
import com.github.jkrishna289.orcax.ui.detail.discover.SourceStreamingController
import com.github.jkrishna289.orcax.ui.detail.series.EpisodeList
import com.github.jkrishna289.orcax.ui.detail.series.PeopleInItem
import com.github.jkrishna289.orcax.ui.detail.series.SeriesOverviewContent
import com.github.jkrishna289.orcax.ui.detail.series.SeriesOverviewPosition
import com.github.jkrishna289.orcax.ui.detail.series.notAiredYet
import com.github.jkrishna289.orcax.ui.detail.series.streamedResumeAt
import com.github.jkrishna289.orcax.ui.detail.series.tmdbSeriesItem
import com.github.jkrishna289.orcax.ui.detail.series.toEpisodeItem
import com.github.jkrishna289.orcax.ui.detail.series.toSeasonItem
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.seasonEpisode
import com.github.jkrishna289.orcax.ui.showToast
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind
import java.util.UUID

/**
 * A show that isn't in the library, laid out like a library show's page: its seasons and episodes come
 * from TMDB through the engine, focus starts where you left off streaming it (else S1 E1), and OK on an
 * episode finds a stream for that episode. Reached from Stream now on the show's not-in-your-library
 * page.
 */
@HiltViewModel(assistedFactory = EngineSeriesViewModel.Factory::class)
class EngineSeriesViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val engineClient: OrcaEngineClient,
        private val themeSongPlayer: ThemeSongPlayer,
        private val userPreferencesService: UserPreferencesService,
        private val backdropService: BackdropService,
        private val imageUrlService: ImageUrlService,
        val sources: SourceStreamingController,
        @Assisted val item: RenderItem,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(item: RenderItem): EngineSeriesViewModel
        }

        val tmdbId = item.media.tmdbId ?: 0
        private val title = item.card.title

        val loading = MutableStateFlow<LoadingState>(LoadingState.Loading)
        val series = MutableStateFlow<BaseItem?>(null)
        val seasons = MutableStateFlow<List<BaseItem?>>(emptyList())
        val episodes = MutableStateFlow<EpisodeList>(EpisodeList.Loading)
        val people = MutableStateFlow<List<Person>>(emptyList())
        val position = MutableStateFlow(SeriesOverviewPosition(0, 0))
        private var progress: List<EpisodeProgress> = emptyList()

        init {
            viewModelScope.launchIO {
                val tmdbSeasons = engineClient.getSeasons(tmdbId)
                if (tmdbSeasons == null) {
                    loading.value = LoadingState.Error(context.getString(R.string.engine_cannot_list_seasons))
                    return@launchIO
                }
                val details = engineClient.getTitleDetails(tmdbId, MediaType.SERIES)
                progress = engineClient.getSeriesProgress(tmdbId).orEmpty()
                series.value = tmdbSeriesItem(tmdbId, title, details, item.card.synopsis)
                people.value =
                    details?.cast.orEmpty().map { person(it, PersonKind.ACTOR) } +
                    details?.directors.orEmpty().map { person(it, PersonKind.DIRECTOR) }
                val all = tmdbSeasons.map { it.toSeasonItem(tmdbId) }.sortedBy { it.indexNumber == 0 }
                seasons.value = all

                // Where you left off streaming it, else S1 E1. A finished season's last episode moves
                // on to the next season; the whole show's last one stays put.
                val resume = streamedResumeAt(progress)
                var index = resume?.let { r -> all.indexOfFirst { it.indexNumber == r.season } }?.takeIf { it >= 0 } ?: 0
                var eps = load(index, resume?.takeIf { all.getOrNull(index)?.indexNumber == it.season }?.episode)
                if (resume?.after == true && eps is EpisodeList.Success && eps.episodes.none { it?.indexNumber == resume.episode }) {
                    val next = all.getOrNull(index + 1)?.takeIf { it.indexNumber != 0 }
                    if (next != null) {
                        index++
                        eps = load(index, 1)
                    } else {
                        eps = eps.copy(initialEpisodeIndex = eps.episodes.lastIndex.coerceAtLeast(0))
                    }
                }
                position.value = SeriesOverviewPosition(index, (eps as? EpisodeList.Success)?.initialEpisodeIndex ?: 0)
                episodes.value = eps
                loading.value = LoadingState.Success
            }
        }

        private fun person(
            name: String,
            kind: PersonKind,
        ) = Person(UUID.nameUUIDFromBytes(name.toByteArray()), name, null, kind, null, false)

        private suspend fun load(
            seasonIndex: Int,
            episodeNumber: Int?,
        ): EpisodeList {
            val season = seasons.value.getOrNull(seasonIndex) ?: return EpisodeList.Empty
            val number = season.indexNumber ?: return EpisodeList.Empty
            val list =
                engineClient.getEpisodes(tmdbId, number)
                    ?: return EpisodeList.Error(context.getString(R.string.engine_cannot_list_seasons))
            val items =
                list.map { ep ->
                    ep.toEpisodeItem(
                        tmdbId = tmdbId,
                        seriesName = title,
                        seriesId = null,
                        seasonId = season.id,
                        progress = progress.firstOrNull { it.season == ep.seasonNumber && it.episode == ep.episodeNumber },
                    )
                }
            return EpisodeList.Success(season.id, items, items.indexOfFirst { it.indexNumber == episodeNumber }.coerceAtLeast(0))
        }

        fun changeSeason(index: Int) {
            if (index == position.value.seasonTabIndex) return
            position.value = SeriesOverviewPosition(index, 0)
            episodes.value = EpisodeList.Loading
            viewModelScope.launchIO { episodes.value = load(index, null) }
        }

        fun onResume() {
            viewModelScope.launchIO {
                backdropService.submit("tmdb:$tmdbId", imageUrlService.engineImageUrl(item.card.backdropImageUrl ?: item.card.imageUrl), detail = true)
                val volume = userPreferencesService.getCurrent().appPreferences.interfacePreferences.playThemeSongs
                themeSongPlayer.playThemeFor(null, volume, tmdbId)
            }
        }

        fun onPause() = themeSongPlayer.stop()

        /** OK on an episode: find a stream for it, unless it hasn't aired or the server doesn't stream. */
        fun streamEpisode(episode: BaseItem) {
            val code = episode.data.seasonEpisode ?: episode.name.orEmpty()
            viewModelScope.launchIO {
                when {
                    episode.notAiredYet() ->
                        showToast(context, context.getString(R.string.episode_not_aired, code), Toast.LENGTH_SHORT)

                    engineClient.getFeatures()?.sourceStreaming != true ->
                        showToast(context, context.getString(R.string.episode_not_requested, code), Toast.LENGTH_SHORT)

                    else ->
                        withContext(Dispatchers.Main) {
                            sources.attach(
                                scope = viewModelScope,
                                title = title.orEmpty(),
                                year = series.value?.data?.productionYear,
                                mediaType = MediaType.SERIES,
                                tmdbId = tmdbId,
                                season = episode.data.parentIndexNumber,
                                episode = episode.indexNumber,
                            )
                            sources.findSources()
                        }
                }
            }
        }
    }

@Composable
fun EngineSeriesPage(
    preferences: UserPreferences,
    destination: Destination.EngineSeries,
    modifier: Modifier = Modifier,
    viewModel: EngineSeriesViewModel =
        hiltViewModel<EngineSeriesViewModel, EngineSeriesViewModel.Factory>(
            creationCallback = { it.create(destination.item) },
        ),
    // Requesting the show works exactly as on its not-in-your-library page
    requests: EngineDetailsViewModel =
        hiltViewModel<EngineDetailsViewModel, EngineDetailsViewModel.Factory>(
            creationCallback = { it.create(destination.item) },
        ),
) {
    val loading by viewModel.loading.collectAsState()
    val series by viewModel.series.collectAsState()
    val seasons by viewModel.seasons.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val people by viewModel.people.collectAsState()
    val position by viewModel.position.collectAsState()
    val sourceSearch by viewModel.sources.state.collectAsState()
    val availability by requests.availability.collectAsState()
    val requesting by requests.requesting.collectAsState()
    var streaming by remember { mutableStateOf("") }

    val headerFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }
    val episodeRowFocusRequester = remember { FocusRequester() }
    val castCrewRowFocusRequester = remember { FocusRequester() }

    when (val state = loading) {
        is LoadingState.Error -> ErrorMessage(state, modifier)
        LoadingState.Loading, LoadingState.Pending -> LoadingPage(modifier)
        LoadingState.Success ->
            series?.let { show ->
                LifecycleResumeEffect(destination) {
                    viewModel.onResume()
                    onPauseOrDispose { viewModel.onPause() }
                }
                RequestOrRestoreFocus(episodeRowFocusRequester, "engine_series")
                SeriesOverviewContent(
                    preferences = preferences,
                    series = show,
                    seasons = seasons,
                    episodes = episodes,
                    chosenStreams = null,
                    peopleInEpisode = PeopleInItem(),
                    people = people,
                    position = position,
                    headerFocusRequester = headerFocusRequester,
                    firstItemFocusRequester = firstItemFocusRequester,
                    episodeRowFocusRequester = episodeRowFocusRequester,
                    castCrewRowFocusRequester = castCrewRowFocusRequester,
                    onChangeSeason = viewModel::changeSeason,
                    onFocusEpisode = { index -> viewModel.position.update { it.copy(episodeRowIndex = index) } },
                    onClick = { ep ->
                        streaming = listOfNotNull(show.name, ep.data.seasonEpisode).joinToString(" · ")
                        viewModel.streamEpisode(ep)
                    },
                    onLongClick = {},
                    overviewOnClick = {},
                    personOnClick = {},
                    headerButtons = { buttonsModifier ->
                        val asked = availability == AvailabilityState.REQUESTED || availability == AvailabilityState.DOWNLOADING
                        Row(modifier = buttonsModifier.focusGroup().padding(PaddingValues(8.dp))) {
                            ExpandableFaButton(
                                title =
                                    when {
                                        requesting -> R.string.requesting
                                        asked -> R.string.requested
                                        else -> R.string.request
                                    },
                                iconStringRes = if (asked) R.string.fa_check else R.string.fa_plus,
                                onClick = { if (!asked && !requesting) requests.request() },
                            )
                        }
                    },
                    similarTmdbId = viewModel.tmdbId,
                    modifier = modifier,
                )
            }
    }

    SourcePickerDialog(
        state = sourceSearch,
        title = streaming,
        onPick = { viewModel.sources.playSource(it) },
        onRetry = viewModel.sources::retry,
        onRequest = {
            viewModel.sources.dismiss()
            requests.request()
        },
        onNextStream = { viewModel.sources.nextStream() },
        onChooseAnother = viewModel.sources::chooseAnother,
        onKeepWaiting = viewModel.sources::keepWaiting,
        onDismiss = viewModel.sources::dismiss,
        forShow = true,
    )
}
