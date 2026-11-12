package com.github.jkrishna289.orcax.ui.detail.engine

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.RequestOrRestoreFocus
import com.github.jkrishna289.orcax.ui.cards.PersonRow
import com.github.jkrishna289.orcax.ui.components.ExpandableFaButton
import com.github.jkrishna289.orcax.ui.components.HeaderUtils
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarRow
import com.github.jkrishna289.orcax.ui.detail.discover.KeepInLibraryPrompt
import com.github.jkrishna289.orcax.ui.detail.discover.SourcePickerDialog
import com.github.jkrishna289.orcax.ui.detail.movie.DetailsRowFocusSpec
import com.github.jkrishna289.orcax.ui.detail.movie.MovieDetailsHeader
import com.github.jkrishna289.orcax.ui.letNotEmpty
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.nav.HeroFrame
import com.github.jkrishna289.orcax.util.ExceptionHandler
import kotlinx.coroutines.launch

/**
 * A title that is not in your library, on the same page a library title gets.
 *
 * A show opens its series page ([EngineSeriesPage]): seasons and episodes from TMDB, OK on an episode
 * finds a stream for it. A film opens the movie page's layout, with Request and Find streams where a
 * library film has Play. There used to be a separate page in between that offered only those two
 * choices; it's gone, so a title you don't have reads like any other.
 *
 * Everything here comes from the engine: the card supplied the artwork, and the engine answers the
 * details, the request and the stream search. No Jellyseerr connection is needed on the client.
 */
@Composable
fun EngineDetailsPage(
    preferences: UserPreferences,
    destination: Destination.EngineItem,
    modifier: Modifier = Modifier,
) {
    val media = destination.item.media
    if (media.mediaType == MediaType.SERIES && (media.tmdbId ?: 0) > 0) {
        EngineSeriesPage(preferences = preferences, item = destination.item, modifier = modifier)
    } else {
        EngineMoviePage(preferences = preferences, destination = destination, modifier = modifier)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EngineMoviePage(
    preferences: UserPreferences,
    destination: Destination.EngineItem,
    modifier: Modifier = Modifier,
    viewModel: EngineDetailsViewModel =
        hiltViewModel<EngineDetailsViewModel, EngineDetailsViewModel.Factory>(
            creationCallback = { it.create(destination.item) },
        ),
) {
    val card = viewModel.item.card
    val tmdbId = viewModel.item.media.tmdbId ?: 0
    val availability by viewModel.availability.collectAsState()
    val libraryItemId by viewModel.libraryItemId.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val canResume = playbackProgress?.let { !it.completed && it.positionMs > 0 } == true
    val requesting by viewModel.requesting.collectAsState()
    val sourceSearch by viewModel.sources.state.collectAsState()
    val canFindSources by viewModel.sources.enabled.collectAsState()
    val keepPrompt by viewModel.keepPrompt.collectAsState()
    val details by viewModel.details.collectAsState()
    val movie = remember(details) { tmdbMovieItem(tmdbId, viewModel.item, details) }
    val people = remember(details) { viewModel.people(details) }
    val logoUrl = LocalImageUrlService.current.engineImageUrl(card.logoImageUrl ?: details?.logoImageUrl)

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val scope = rememberCoroutineScope()
    val buttonsFocus = remember { FocusRequester() }
    val headerInView = remember { BringIntoViewRequester() }
    RequestOrRestoreFocus(buttonsFocus, "engine_movie")

    // Tell the hero backdrop when the page is scrolled down, so it can blur behind the lower rows.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        try {
            snapshotFlow { listState.canScrollBackward }.collect { HeroFrame.scrolled = it }
        } finally {
            HeroFrame.scrolled = false
        }
    }

    // The movie page's own frame, spacing and row scroll (see MovieDetailsContent).
    Box(
        modifier =
            modifier
                .padding(
                    top = HeroFrame.top,
                    start = HeroFrame.side + HeroFrame.border,
                    end = HeroFrame.side + HeroFrame.border,
                ).clip(HeroFrame.contentShape),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides DetailsRowFocusSpec) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(headerInView),
                    ) {
                        LibraryStatusChip(
                            availability,
                            Modifier.padding(start = HeaderUtils.startPadding, top = HeaderUtils.topPadding),
                        )
                        MovieDetailsHeader(
                            preferences = preferences,
                            movie = movie,
                            chosenStreams = null,
                            // The chip above and the Request button below say this already.
                            availability = null,
                            qualityPreview = null,
                            requestInFlight = false,
                            requestOnClick = {},
                            bringIntoViewRequester = headerInView,
                            overviewOnClick = {},
                            logoUrl = logoUrl,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(PaddingValues(8.dp))
                                    .padding(bottom = 8.dp)
                                    .focusGroup()
                                    .focusRequester(buttonsFocus)
                                    .onFocusChanged {
                                        if (it.hasFocus) scope.launch(ExceptionHandler()) { headerInView.bringIntoView() }
                                    },
                        ) {
                            val asked = availability == AvailabilityState.REQUESTED || availability == AvailabilityState.DOWNLOADING
                            when {
                                // Already yours: the only useful action is to go watch it.
                                availability == AvailabilityState.WATCH_NOW && libraryItemId != null ->
                                    ExpandableFaButton(
                                        title = if (canResume) R.string.resume else R.string.play,
                                        iconStringRes = R.string.fa_play,
                                        onClick = viewModel::navigateToLibraryItem,
                                    )

                                // Asked for already: a statement, so pressing it can't queue a duplicate.
                                else ->
                                    ExpandableFaButton(
                                        title =
                                            when {
                                                requesting -> R.string.requesting
                                                availability == AvailabilityState.DOWNLOADING -> R.string.availability_adding
                                                asked -> R.string.requested
                                                else -> R.string.request
                                            },
                                        iconStringRes =
                                            when {
                                                requesting -> R.string.fa_clock
                                                asked -> R.string.fa_check
                                                else -> R.string.fa_plus
                                            },
                                        onClick = { if (!asked && !requesting) viewModel.request() },
                                    )
                            }
                            // Streaming is offered whenever the server does it and the film isn't already yours.
                            // When the operator hasn't enabled it, the viewer never learns it exists.
                            if (canFindSources && libraryItemId == null) {
                                ExpandableFaButton(
                                    title = if (canResume) R.string.resume else R.string.find_streams,
                                    iconStringRes = if (canResume) R.string.fa_play else R.string.fa_magnifying_glass,
                                    onClick = viewModel.sources::findSources,
                                )
                            }
                        }
                    }
                }
                people.letNotEmpty {
                    item {
                        PersonRow(
                            people = it,
                            title = R.string.cast_and_crew,
                            // TMDB names only: there is no person page to open.
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (tmdbId > 0) {
                    item(key = "engineSimilar") {
                        EngineSimilarRow(
                            itemId = null,
                            tmdbId = tmdbId,
                            fallbackFocus = buttonsFocus,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    SourcePickerDialog(
        state = sourceSearch,
        title = card.title.orEmpty(),
        // The engine's own logo art when it sent any; the wait falls back to the title otherwise.
        logoUrl = logoUrl,
        onPick = { viewModel.sources.playSource(it) },
        onRetry = viewModel.sources::retry,
        onRequest = {
            viewModel.sources.dismiss()
            viewModel.request()
        },
        onNextStream = { viewModel.sources.nextStream() },
        onChooseAnother = viewModel.sources::chooseAnother,
        onKeepWaiting = viewModel.sources::keepWaiting,
        onDismiss = viewModel.sources::dismiss,
    )

    keepPrompt?.let { watch ->
        KeepInLibraryPrompt(
            watch = watch,
            runtimeMinutes = details?.runtimeMinutes ?: runtimeMinutes(viewModel.item),
            onKeep = viewModel::keepInLibrary,
            onDismiss = viewModel::dismissKeepPrompt,
        )
    }
}

/**
 * The one fact a not-in-library page turns on, stated before the title.
 *
 * A state of *your library*, never a state of the world: "Not in your library", not "Unavailable".
 * The film exists; you just don't own it yet.
 */
@Composable
internal fun LibraryStatusChip(
    availability: AvailabilityState,
    modifier: Modifier = Modifier,
) {
    val (labelRes, color) =
        when (availability) {
            AvailabilityState.WATCH_NOW -> R.string.in_your_library to InLibraryTeal
            AvailabilityState.REQUESTED -> R.string.requested to InLibraryTeal
            AvailabilityState.DOWNLOADING -> R.string.availability_adding to AddingLavender
            AvailabilityState.UNAVAILABLE -> R.string.coming_soon to ComingSoonPeriwinkle
            else -> R.string.not_in_your_library to AppColors.GoldenYellow
        }
    val shape = RoundedCornerShape(3.dp)
    Text(
        text = stringResource(labelRes).uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier =
            modifier
                .widthIn(max = 300.dp)
                .background(color.copy(alpha = 0.14f), shape)
                .border(1.dp, color.copy(alpha = 0.6f), shape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Runtime for the Keep prompt's progress bar, parsed out of the card's RUNTIME badge ("1h 54m"). */
private fun runtimeMinutes(item: com.github.jkrishna289.orcax.engine.RenderItem): Int? {
    val text =
        item.card.badges
            .firstOrNull { it.kind.equals("RUNTIME", ignoreCase = true) }
            ?.text ?: return null
    val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val mins = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return (hours * 60 + mins).takeIf { it > 0 }
}

private val InLibraryTeal = Color(0xFF2DE0C0)
private val AddingLavender = Color(0xFFD2BCFF)
private val ComingSoonPeriwinkle = Color(0xFF8B8BEC)
