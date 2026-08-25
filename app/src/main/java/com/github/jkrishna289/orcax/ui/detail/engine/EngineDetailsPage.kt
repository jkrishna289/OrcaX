package com.github.jkrishna289.orcax.ui.detail.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
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
    val requesting by viewModel.requesting.collectAsState()
    val sourceSearch by viewModel.sources.state.collectAsState()
    val canFindSources by viewModel.sources.enabled.collectAsState()
    val keepPrompt by viewModel.keepPrompt.collectAsState()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(availability) { firstFocus.tryRequestFocus() }

    Box(modifier = modifier.fillMaxSize()) {
        val backdrop =
            LocalImageUrlService.current.engineImageUrl(card.backdropImageUrl ?: card.imageUrl)
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The same left-to-bottom scrim the library details page uses, so an unowned title sits on
        // exactly the same stage as an owned one.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to StageColor,
                            0.2f to StageColor.copy(alpha = 0.94f),
                            0.75f to StageColor.copy(alpha = 0.35f),
                        ),
                    ),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.66f)
                    .padding(start = 40.dp, bottom = 30.dp, end = 20.dp),
        ) {
            LibraryStatusChip(availability)

            Text(
                text = card.title.orEmpty(),
                color = OnStageBright,
                fontSize = 41.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            metaLine(item)?.let {
                Text(text = it, color = OnStage, fontSize = 13.sp)
            }

            card.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = OnStage.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.padding(top = 9.dp),
            ) {
                when {
                    // Already yours — the fork is over, and the only useful action is to go watch it.
                    availability == AvailabilityState.WATCH_NOW ->
                        DecisionCard(
                            title = R.string.play,
                            subtitle = R.string.in_your_library,
                            iconStringRes = R.string.fa_play,
                            onClick = viewModel::navigateToLibraryItem,
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
                if (canFindSources && availability != AvailabilityState.WATCH_NOW) {
                    DecisionCard(
                        title = R.string.find_streaming_sources,
                        subtitle = R.string.find_streaming_sources_subtitle,
                        iconStringRes = null,
                        onClick = viewModel.sources::findSources,
                    )
                }
            }

            Text(
                text = stringResource(R.string.request_destination_note),
                color = OnStage.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }

    SourcePickerDialog(
        state = sourceSearch,
        title = card.title.orEmpty(),
        // The engine's own logo art when it sent any; the wait falls back to the title otherwise.
        logoUrl = LocalImageUrlService.current.engineImageUrl(card.logoImageUrl),
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
            runtimeMinutes = runtimeMinutes(viewModel.item),
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

/** Year · runtime · genres, assembled from whichever badges the engine actually sent. */
private fun metaLine(item: com.github.jkrishna289.orcax.engine.RenderItem): String? {
    val badges = item.card.badges
    fun of(kind: String) =
        badges.firstOrNull { it.kind.equals(kind, ignoreCase = true) }?.text?.takeIf { it.isNotBlank() }

    val genres =
        badges.filter { it.kind.equals("GENRE", ignoreCase = true) }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .take(3)

    return (listOfNotNull(of("YEAR"), of("RUNTIME"), of("CERT")) + genres)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
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

private val StageColor = Color(0xFF15121C)
private val OnStage = Color(0xFFE8DFF0)
private val OnStageBright = Color(0xFFF5F1FA)
private val InLibraryTeal = Color(0xFF2DE0C0)
private val AddingLavender = Color(0xFFD2BCFF)
private val ComingSoonPeriwinkle = Color(0xFF8B8BEC)
