package com.github.jkrishna289.orcax.ui.detail.engine

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.github.jkrishna289.orcax.ui.LocalTrailerVolume
import com.github.jkrishna289.orcax.ui.TrailerPhase
import com.github.jkrishna289.orcax.ui.cards.InlineCardTrailer
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.TitleDetails
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.collapseWhitespace
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarRow
import com.github.jkrishna289.orcax.ui.detail.discover.DecisionCard
import com.github.jkrishna289.orcax.ui.detail.discover.KeepInLibraryPrompt
import com.github.jkrishna289.orcax.ui.detail.discover.SourcePickerDialog
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.tryRequestFocus

/**
 * Screen 03 — a title that is not in your library.
 *
 * The fork in the road. Two actions, and the difference between them is stated in plain words
 * underneath each: **for good** versus **this once**. That subtitle is the whole design — without it
 * the viewer has to guess, and guessing is where technical vocabulary creeps back in.
 *
 * Everything here comes from the engine: the card supplied the artwork and copy, and the engine
 * answers both the request and the stream search. No Jellyseerr connection is needed on the client.
 */
@Composable
fun EngineDetailsPage(
    destination: Destination.EngineItem,
    modifier: Modifier = Modifier,
    viewModel: EngineDetailsViewModel =
        hiltViewModel<EngineDetailsViewModel, EngineDetailsViewModel.Factory>(
            creationCallback = { it.create(destination.item) },
        ),
) {
    val item = viewModel.item
    val card = item.card
    val availability by viewModel.availability.collectAsState()
    val libraryItemId by viewModel.libraryItemId.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val canResume = playbackProgress?.let { !it.completed && it.positionMs > 0 } == true
    val requesting by viewModel.requesting.collectAsState()
    val sourceSearch by viewModel.sources.state.collectAsState()
    val canFindSources by viewModel.sources.enabled.collectAsState()
    val keepPrompt by viewModel.keepPrompt.collectAsState()
    val details by viewModel.details.collectAsState()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(availability) { firstFocus.tryRequestFocus() }

    // Trailer autoplay: starts once the page has sat still for TRAILER_DWELL_MS, pauses while
    // "More like this" has focus, and stops for good at TRAILER_STOP_AT of its length, handing the
    // screen back to the backdrop. Leaving the page stops it; coming back waits again.
    val trailerUrl by viewModel.trailerUrl.collectAsState()
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    var trailerArmed by remember { mutableStateOf(false) }
    var trailerDone by remember { mutableStateOf(false) }
    var trailerPhase by remember { mutableStateOf(TrailerPhase.IDLE) }
    var similarFocused by remember { mutableStateOf(false) }
    var wordsAwake by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        trailerDone = false
        onPauseOrDispose { trailerArmed = false }
    }
    LaunchedEffect(lastKeyAt, trailerArmed) {
        if (!trailerArmed) {
            delay(TRAILER_DWELL_MS)
            trailerArmed = true
        }
    }
    // While it plays the fine print fades so the picture leads; any press brings it back for a while.
    LaunchedEffect(lastKeyAt) {
        wordsAwake = true
        delay(WORDS_AWAKE_MS)
        wordsAwake = false
    }
    val trailerPlaying = trailerPhase == TrailerPhase.PLAYING
    val finePrintAlpha by animateFloatAsState(if (trailerPlaying && !wordsAwake) 0f else 1f, tween(600), label = "fine-print")

    Box(
        modifier =
            modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) lastKeyAt = System.nanoTime()
                false
            },
    ) {
        val imageUrls = LocalImageUrlService.current
        // The card's own art; a library title the engine sent without any falls back to its Jellyfin backdrop.
        val backdrop =
            imageUrls.engineImageUrl(card.backdropImageUrl ?: card.imageUrl)
                ?: item.media.jellyfinId?.toUUIDOrNull()?.let {
                    imageUrls.getItemImageUrl(itemId = it, imageType = ImageType.BACKDROP, fillHeight = 1080)
                }
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        InlineCardTrailer(
            trailerUrl = trailerUrl,
            backdropUrl = backdrop,
            play = trailerArmed && !trailerDone,
            paused = similarFocused,
            volume = LocalTrailerVolume.current,
            statusProvider = viewModel::trailerStatus,
            onPhaseChange = { trailerPhase = it },
            onProgress = { if (it >= TRAILER_STOP_AT) trailerDone = true },
            modifier = Modifier.fillMaxSize(),
        )
        // Dark from the left and up from the bottom, so the words hold up over any frame of the trailer
        // while its right side still shows through.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to StageColor,
                            0.3f to StageColor.copy(alpha = 0.92f),
                            0.55f to StageColor.copy(alpha = 0.5f),
                            1f to StageColor.copy(alpha = 0.15f),
                        ),
                    ).background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to StageColor.copy(alpha = 0.9f),
                        ),
                    ),
        )

        // The hero fills 90% of the first screen; "More like this" peeks into the rest, dimmed and
        // softly blurred until Down gives it focus, so it's plain there is more below.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            val heroWidth = maxWidth
            // Two positions only, driven by which half has focus: the hero, or slid up just far enough
            // to show all of "More like this". Not a scroll container, so focus restoration and
            // bring-into-view can't leave the page stopped halfway (as they did on Back from a page
            // opened from the row).
            var contentHeight by remember { mutableIntStateOf(0) }
            val screenHeightPx = with(LocalDensity.current) { screenHeight.roundToPx() }
            val shift by animateIntAsState(
                if (similarFocused) (contentHeight - screenHeightPx).coerceAtLeast(0) else 0,
                tween(300),
                label = "page-shift",
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .onSizeChanged { contentHeight = it.height }
                        .offset { IntOffset(0, -shift) },
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(screenHeight * 0.9f)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 56.dp, bottom = 48.dp, end = 20.dp)
                                .width(min(heroWidth * 0.46f, 620.dp)),
                    ) {
                        LibraryStatusChip(availability)

                        TitleOrLogo(
                            title = card.title.orEmpty(),
                            logoUrl = LocalImageUrlService.current.engineImageUrl(card.logoImageUrl ?: details?.logoImageUrl),
                        )

                        metaLine(item, details)?.let {
                            // One line: a wrapped genre list pushed the status chip off the top of the screen.
                            Text(
                                text = it,
                                color = OnStage,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = Legible,
                            )
                        }

                        // The catalog's full overview when it has one; the card only carries a trimmed synopsis.
                        (details?.overview ?: card.synopsis)?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it.collapseWhitespace(),
                                color = OnStage.copy(alpha = 0.92f),
                                fontSize = 17.sp,
                                lineHeight = 25.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                style = Legible,
                            )
                        }

                        details?.let { d ->
                            peopleLine(d)?.let {
                                Text(
                                    text = it,
                                    color = OnStage.copy(alpha = 0.72f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = Legible,
                                    modifier = Modifier.alpha(finePrintAlpha),
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            when {
                                // Already yours — the fork is over, and the only useful action is to go watch it.
                                availability == AvailabilityState.WATCH_NOW ->
                                    DecisionCard(
                                        title = if (canResume) R.string.resume else R.string.play,
                                        subtitle = R.string.in_your_library,
                                        iconStringRes = R.string.fa_play,
                                        onClick = viewModel::navigateToLibraryItem,
                                        enabled = libraryItemId != null,
                                        modifier = Modifier.focusRequester(firstFocus),
                                    )

                                // Asked for already. Kept as a statement, not a button, so pressing it again
                                // can't queue a duplicate.
                                availability == AvailabilityState.REQUESTED ||
                                    availability == AvailabilityState.DOWNLOADING ->
                                    DecisionCard(
                                        title =
                                            if (availability == AvailabilityState.DOWNLOADING) {
                                                R.string.availability_adding
                                            } else {
                                                R.string.requested
                                            },
                                        subtitle = R.string.request_already_queued,
                                        iconStringRes = R.string.fa_check,
                                        enabled = false,
                                        onClick = {},
                                        modifier = Modifier.focusRequester(firstFocus),
                                    )

                                else ->
                                    DecisionCard(
                                        title = if (requesting) R.string.requesting else R.string.request,
                                        subtitle = R.string.request_subtitle,
                                        iconStringRes = if (requesting) R.string.fa_clock else R.string.fa_plus,
                                        onClick = viewModel::request,
                                        modifier = Modifier.focusRequester(firstFocus),
                                    )
                            }

                            // Streaming is offered whenever the server does it and the title isn't already yours.
                            // When the operator hasn't enabled it, the viewer never learns it exists.
                            if (canFindSources && libraryItemId == null) {
                                DecisionCard(
                                    title = if (canResume) R.string.resume else R.string.find_streaming_sources,
                                    subtitle = if (canResume) R.string.sources_picker_title else R.string.find_streaming_sources_subtitle,
                                    iconStringRes = null,
                                    onClick = viewModel.sources::findSources,
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.request_destination_note),
                            color = OnStage.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 9.dp).alpha(finePrintAlpha),
                        )
                    }
                }

                item.media.tmdbId?.takeIf { it > 0 }?.let { tmdbId ->
                    val peekAlpha by animateFloatAsState(if (similarFocused) 1f else 0.5f, tween(300), label = "peek-alpha")
                    val peekBlur by animateDpAsState(if (similarFocused) 0.dp else 2.dp, tween(300), label = "peek-blur")
                    EngineSimilarRow(
                        itemId = null,
                        tmdbId = tmdbId,
                        placeholderWhileLoading = true,
                        fallbackFocus = firstFocus,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, bottom = 40.dp)
                                .onFocusChanged { similarFocused = it.hasFocus }
                                .alpha(peekAlpha)
                                .blur(peekBlur),
                    )
                }
            }
        }
    }

    SourcePickerDialog(
        state = sourceSearch,
        title = card.title.orEmpty(),
        // The engine's own logo art when it sent any; the wait falls back to the title otherwise.
        logoUrl = LocalImageUrlService.current.engineImageUrl(card.logoImageUrl ?: details?.logoImageUrl),
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
 * The one fact that decides this whole screen, stated before the title.
 *
 * A state of *your library*, never a state of the world: "Not in your library", not "Unavailable".
 * The film exists; you just don't own it yet.
 */
@Composable
private fun LibraryStatusChip(availability: AvailabilityState) {
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
            Modifier
                .widthIn(max = 300.dp)
                .background(color.copy(alpha = 0.14f), shape)
                .border(1.dp, color.copy(alpha = 0.6f), shape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The logo art when there is any (falling back if it fails to load), else the title as text. */
@Composable
private fun TitleOrLogo(
    title: String,
    logoUrl: String?,
) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !logoFailed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier.height(88.dp).widthIn(max = 420.dp),
        )
    } else {
        Text(
            text = title,
            color = OnStageBright,
            fontSize = 44.sp,
            // Room below the baseline, so a descender (the "y" in "Family") isn't clipped.
            lineHeight = 51.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = Legible,
        )
    }
}

/**
 * Year · runtime · certificate · ★ score · genres. The engine catalog's details when they've loaded,
 * else whichever badges the card happened to carry.
 */
private fun metaLine(
    item: com.github.jkrishna289.orcax.engine.RenderItem,
    details: TitleDetails?,
): String? {
    val badges = item.card.badges
    fun of(kind: String) =
        badges.firstOrNull { it.kind.equals(kind, ignoreCase = true) }?.text?.takeIf { it.isNotBlank() }

    val parts =
        if (details != null) {
            listOfNotNull(
                details.year?.toString(),
                details.runtimeMinutes?.let(::formatRuntime),
                details.officialRating,
                details.communityRating?.takeIf { it > 0f }?.let { "★ " + "%.1f".format(it) },
            ) + details.genres.take(3)
        } else {
            listOfNotNull(of("YEAR"), of("RUNTIME"), of("CERT"), of("RATING")?.let { "★ $it" }) +
                badges.filter { it.kind.equals("GENRE", ignoreCase = true) }
                    .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
                    .take(3)
        }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "Starring A, B, C · Directed by D", from whichever of the two the catalog has. */
@Composable
private fun peopleLine(details: TitleDetails): String? {
    val starring = details.cast.take(4).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.starring, it.joinToString(", ")) }
    val directed = details.directors.take(2).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.directed_by, it.joinToString(", ")) }
    return listOfNotNull(starring, directed).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

private fun formatRuntime(minutes: Int): String =
    when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
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

/** Quiet before the trailer starts, so clicking through titles never starts one. */
private const val TRAILER_DWELL_MS = 3_000L

/** Stop here: enough to decide, without running into the ending or the end cards. */
private const val TRAILER_STOP_AT = 0.65f

/** How long a press keeps the faded fine print visible while the trailer plays. */
private const val WORDS_AWAKE_MS = 4_000L

/** A soft shadow under the words, for the frames of the trailer the scrim can't fully darken. */
private val Legible = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 3f))

private val StageColor = Color(0xFF15121C)
private val OnStage = Color(0xFFE8DFF0)
private val OnStageBright = Color(0xFFF5F1FA)
private val InLibraryTeal = Color(0xFF2DE0C0)
private val AddingLavender = Color(0xFFD2BCFF)
private val ComingSoonPeriwinkle = Color(0xFF8B8BEC)
