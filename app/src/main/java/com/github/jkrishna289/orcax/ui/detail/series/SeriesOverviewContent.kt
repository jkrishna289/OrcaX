package com.github.jkrishna289.orcax.ui.detail.series

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ChosenStreams
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.data.model.Trailer
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.cards.BannerCard
import com.github.jkrishna289.orcax.ui.cards.PersonRow
import com.github.jkrishna289.orcax.ui.components.EpisodeName
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.ExpandablePlayButtons
import com.github.jkrishna289.orcax.ui.components.HeaderUtils
import com.github.jkrishna289.orcax.ui.components.LoadingPage
import com.github.jkrishna289.orcax.ui.components.QuickDetails
import com.github.jkrishna289.orcax.ui.components.TabRow
import com.github.jkrishna289.orcax.ui.components.VideoStreamDetails
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarRow
import com.github.jkrishna289.orcax.ui.ifElse
import com.github.jkrishna289.orcax.ui.letNotEmpty
import com.github.jkrishna289.orcax.ui.logTab
import com.github.jkrishna289.orcax.ui.playback.isPlayKeyUp
import com.github.jkrishna289.orcax.ui.rememberInt
import com.github.jkrishna289.orcax.ui.seasonEpisode
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import com.github.jkrishna289.orcax.ui.util.rememberDelayedNestedScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PersonKind
import kotlin.time.Duration

/**
 * The page is about the show: its header, buttons, Cast & Crew and More Like This never change while
 * you browse episodes. The focused episode fills one fixed-height panel under the episode row, so
 * nothing below it moves (the episode's own cast used to fade out and back on every episode, and More
 * Like This jumped with it).
 */
@Composable
fun SeriesOverviewContent(
    preferences: UserPreferences,
    series: BaseItem,
    seasons: List<BaseItem?>,
    episodes: EpisodeList,
    chosenStreams: ChosenStreams?,
    peopleInEpisode: PeopleInItem,
    people: List<Person>,
    playTarget: BaseItem?,
    trailers: List<Trailer>,
    position: SeriesOverviewPosition,
    headerFocusRequester: FocusRequester,
    firstItemFocusRequester: FocusRequester,
    episodeRowFocusRequester: FocusRequester,
    castCrewRowFocusRequester: FocusRequester,
    onChangeSeason: (Int) -> Unit,
    onFocusEpisode: (Int) -> Unit,
    onClick: (BaseItem) -> Unit,
    onLongClick: (BaseItem) -> Unit,
    playOnClick: (Duration) -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    overviewOnClick: () -> Unit,
    personOnClick: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val headerInView = remember { BringIntoViewRequester() }
    val episodesInView = remember { BringIntoViewRequester() }
    var selectedTabIndex by rememberSaveable(position.seasonTabIndex) { mutableIntStateOf(position.seasonTabIndex) }
    LaunchedEffect(selectedTabIndex) {
        logTab("series_overview", selectedTabIndex)
    }
    val similarFocusRequester = remember { FocusRequester() }

    val focusedEpisode =
        (episodes as? EpisodeList.Success)?.episodes?.getOrNull(position.episodeRowIndex)
    var pageHasFocus by remember { mutableStateOf(false) }
    var cardRowHasFocus by remember { mutableStateOf(false) }
    val dimming by animateFloatAsState(if (pageHasFocus && !cardRowHasFocus) .4f else 1f)

    val scrollState = rememberScrollState()
    val scrollConnection = rememberDelayedNestedScroll()
    var requestFocusAfterSeason by remember { mutableStateOf(false) }

    val seasonStr = stringResource(R.string.tv_season)
    val tabs =
        seasons.map { season ->
            season?.name
                ?: season?.data?.indexNumber?.let { "$seasonStr $it" }
                ?: ""
        }
    val focusRequesters = remember(seasons) { List(seasons.size) { FocusRequester() } }

    val currentOnChangeSeason by rememberUpdatedState(onChangeSeason)

    // Focusing a tab switches to its season, no OK press. It waits a beat so passing over tabs doesn't
    // load each one, and leaving the tabs first cancels it.
    var focusedTab by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusedTab) {
        if (focusedTab >= 0 && focusedTab != selectedTabIndex) {
            delay(250)
            selectedTabIndex = focusedTab
            currentOnChangeSeason(focusedTab)
        }
    }

    val castAndCrew = remember(people) { people.filter { it.type != PersonKind.GUEST_STAR } }
    val guestStars =
        remember(peopleInEpisode, focusedEpisode) {
            peopleInEpisode
                .takeIf { it.itemId == focusedEpisode?.id }
                ?.people
                ?.filter { it.type == PersonKind.GUEST_STAR }
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp)
                    .focusGroup()
                    .nestedScroll(scrollConnection)
                    .verticalScroll(scrollState)
                    .onFocusChanged { pageHasFocus = it.hasFocus },
        ) {
            // The show
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .focusGroup()
                        .bringIntoViewRequester(headerInView),
            ) {
                SeriesDetailsHeader(
                    series = series,
                    showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                    overviewOnClick = overviewOnClick,
                    bringIntoViewRequester = headerInView,
                    modifier = Modifier.fillMaxWidth(),
                )
                val playLabel =
                    playTarget?.let { target ->
                        target.data.seasonEpisode?.let {
                            stringResource(
                                if (target.playbackPosition > Duration.ZERO) R.string.resume_episode else R.string.play_episode,
                                it,
                            )
                        }
                    }
                ExpandablePlayButtons(
                    resumePosition = playTarget?.playbackPosition ?: Duration.ZERO,
                    watched = series.played,
                    favorite = series.favorite,
                    canDelete = false,
                    trailers = trailers,
                    playOnClick = playOnClick,
                    watchOnClick = watchOnClick,
                    favoriteOnClick = favoriteOnClick,
                    moreOnClick = moreOnClick,
                    trailerOnClick = trailerOnClick,
                    deleteOnClick = {},
                    buttonOnFocusChanged = {
                        if (it.isFocused) scope.launch { headerInView.bringIntoView() }
                    },
                    playLabel = playLabel,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(headerFocusRequester),
                )
            }

            // Its seasons and episodes
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .focusGroup()
                        .bringIntoViewRequester(episodesInView),
            ) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    tabs = tabs,
                    onClick =
                        remember {
                            {
                                selectedTabIndex = it
                                currentOnChangeSeason(it)
                                requestFocusAfterSeason = true
                            }
                        },
                    onFocus = {
                        focusedTab = it
                        scope.launch { episodesInView.bringIntoView() }
                    },
                    focusRequesters = focusRequesters,
                    modifier =
                        Modifier
                            .onFocusChanged { if (!it.hasFocus) focusedTab = -1 }
                            .padding(start = 8.dp)
                            .fillMaxWidth(),
                )

                val noEpisodes =
                    episodes == EpisodeList.Empty ||
                        (episodes as? EpisodeList.Success)?.episodes?.isEmpty() == true
                if (noEpisodes) {
                    Text(
                        text = stringResource(R.string.no_episodes_yet),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = HeaderUtils.startPadding),
                    )
                    // Focus asked for an episode card that isn't there, so it went nowhere and the
                    // D-pad did nothing until Down was pressed.
                    LaunchedEffect(episodes) {
                        (focusRequesters.getOrNull(selectedTabIndex) ?: similarFocusRequester).tryRequestFocus()
                        requestFocusAfterSeason = false
                    }
                } else {
                    when (val eps = episodes) {
                        EpisodeList.Loading -> {
                            // Same height as the row, so a season change doesn't move what's below
                            LoadingPage(Modifier.fillMaxWidth().height(EpisodeRowHeight), focusEnabled = false)
                        }

                        EpisodeList.Empty -> {}

                        is EpisodeList.Error -> {
                            ErrorMessage(eps.message, eps.exception)
                        }

                        is EpisodeList.Success -> {
                            if (requestFocusAfterSeason) {
                                // Changing seasons, so move focus once the new episodes are loaded
                                LaunchedEffect(Unit) {
                                    firstItemFocusRequester.tryRequestFocus()
                                    requestFocusAfterSeason = false
                                }
                            }
                            val state = rememberLazyListState(position.episodeRowIndex)
                            var epPosition by rememberInt(position.episodeRowIndex)
                            LazyRow(
                                state = state,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier =
                                    Modifier
                                        .height(EpisodeRowHeight)
                                        .focusRestorer(firstItemFocusRequester)
                                        .onFocusChanged {
                                            cardRowHasFocus = it.hasFocus
                                        },
                            ) {
                                itemsIndexed(eps.episodes) { episodeIndex, episode ->
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val aspectRatio =
                                        episode
                                            ?.aspectRatio
                                            ?.coerceAtLeast(AspectRatios.FOUR_THREE)
                                            ?: (AspectRatios.WIDE)
                                    val dimmed = episodeIndex != position.episodeRowIndex
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier =
                                            Modifier
                                                .width(EpisodeCardHeight * aspectRatio)
                                                .graphicsLayer { alpha = if (dimmed) dimming else 1f },
                                    ) {
                                        BannerCard(
                                            name = episode?.name,
                                            item = episode,
                                            aspectRatio = aspectRatio,
                                            cornerText = episode?.ui?.episodeCornerText,
                                            played = episode?.data?.userData?.played ?: false,
                                            playPercent =
                                                episode?.data?.userData?.playedPercentage
                                                    ?: 0.0,
                                            onClick = {
                                                epPosition = episodeIndex
                                                if (episode != null) onClick.invoke(episode)
                                            },
                                            onLongClick = {
                                                epPosition = episodeIndex
                                                if (episode != null) onLongClick.invoke(episode)
                                            },
                                            modifier =
                                                Modifier
                                                    .ifElse(
                                                        episodeIndex == position.episodeRowIndex,
                                                        Modifier
                                                            .focusRequester(firstItemFocusRequester),
                                                    ).ifElse(
                                                        episodeIndex == epPosition,
                                                        Modifier.focusRequester(episodeRowFocusRequester),
                                                    ).background(
                                                        if (dimmed) Color.Black else Color.Transparent,
                                                        shape = RoundedCornerShape(8.dp),
                                                    ).onFocusChanged {
                                                        if (it.isFocused) {
                                                            // Moved out of composition: this used to fire on
                                                            // every recomposition via collectIsFocusedAsState.
                                                            onFocusEpisode.invoke(episodeIndex)
                                                            scope.launch {
                                                                episodesInView.bringIntoView()
                                                            }
                                                        }
                                                    }.onKeyEvent {
                                                        if (episode != null && isPlayKeyUp(it)) {
                                                            onClick.invoke(episode)
                                                            return@onKeyEvent true
                                                        }
                                                        return@onKeyEvent false
                                                    },
                                            interactionSource = interactionSource,
                                            cardHeight = EpisodeCardHeight,
                                            useSeriesForPrimary = false,
                                        )
                                        // Every card's title: the panel only names the focused one
                                        Text(
                                            text = episode?.name ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (episodes == EpisodeList.Loading || focusedEpisode != null) {
                    EpisodePanel(
                        ep = focusedEpisode,
                        chosenStreams = chosenStreams,
                        guestStars = guestStars,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            castAndCrew.letNotEmpty {
                PersonRow(
                    title = R.string.cast_and_crew,
                    people = it,
                    onClick = personOnClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(castCrewRowFocusRequester),
                )
            }

            // "More Like This" powered by the Orca Engine (content-based similar series).
            EngineSimilarRow(
                itemId = series.id,
                // With no seasons it's the only thing to focus, so hold its place while it loads.
                placeholderWhileLoading = seasons.isEmpty(),
                modifier = Modifier.fillMaxWidth().focusRequester(similarFocusRequester),
            )
        }
    }
}

/**
 * The focused episode, in a box that is the same height for every episode. Information only: OK on a
 * card plays it, and a long press has the episode's actions.
 */
@Composable
private fun EpisodePanel(
    ep: BaseItem?,
    chosenStreams: ChosenStreams?,
    guestStars: List<Person>?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(EpisodePanelHeight)
                .clip(shape)
                .background(Color.Black.copy(alpha = .28f))
                .border(1.dp, Color.White.copy(alpha = .08f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (ep != null) EpisodePanelContent(ep, chosenStreams, guestStars)
    }
}

/** Emitted straight into [EpisodePanel]'s column */
@Composable
private fun EpisodePanelContent(
    ep: BaseItem,
    chosenStreams: ChosenStreams?,
    guestStars: List<Person>?,
) {
    val dto = ep.data
    EpisodeName(dto)
    QuickDetails(ep.ui.quickDetails, ep.timeRemainingOrRuntime)
    if (dto.locationType == LocationType.VIRTUAL || dto.mediaSources.isNullOrEmpty()) {
        // Not a "None" audio chip: there's simply no file yet
        Text(
            text = stringResource(R.string.not_downloaded),
            style = MaterialTheme.typography.labelMedium,
            color = NotDownloaded,
            modifier =
                Modifier
                    .border(1.dp, NotDownloaded.copy(alpha = .5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    } else {
        VideoStreamDetails(chosenStreams, dto.mediaSourceCount ?: 0)
    }
    Text(
        text = dto.overview.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .9f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (!guestStars.isNullOrEmpty()) {
        val label = stringResource(R.string.guest_stars).uppercase()
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)) {
                        append(label)
                    }
                    append("  ")
                    append(guestStars.mapNotNull { it.name }.joinToString(", "))
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val NotDownloaded = Color(0xFFFFC700)

private val EpisodeCardHeight = 120.dp

// The card, the gap and two lines of title
private val EpisodeRowHeight = 176.dp

private val EpisodePanelHeight = 176.dp
