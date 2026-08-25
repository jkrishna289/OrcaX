package com.github.jkrishna289.orcax.ui.detail.discover

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.StreamSessionStatus
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay

/**
 * What the source flow is currently doing.
 *
 * Every non-idle state renders something the viewer can act on, and every terminal state offers a way
 * forward — none of them is a dead end, which is the one hard rule for this flow.
 */
sealed interface SourceSearchState {
    /** Nothing requested; nothing is shown. */
    data object Idle : SourceSearchState

    /**
     * A search is running. [startedAtMs] is elapsed-realtime, not wall-clock: it drives the
     * "skip the dialog if results land fast" rule, which must not jump when the clock is corrected.
     */
    data class Searching(val startedAtMs: Long) : SourceSearchState

    /**
     * Sources found. [sources] is every playable result in display order (highest resolution first);
     * [focusId] is only where the D-pad starts, never a recommendation the screen states out loud.
     */
    data class Found(
        val sources: List<TorrentSourceDto>,
        val focusId: String?,
    ) : SourceSearchState

    /**
     * A source was picked and its stream is being opened. [startedAtMs] drives the escalation ladder
     * (see [ConnectingScreen]); [autoSwitched] is set when OrcaX moved here on its own after a slow
     * source, so the screen can say so instead of silently changing what is playing.
     */
    data class Opening(
        val source: TorrentSourceDto,
        val startedAtMs: Long,
        /**
         * The most recent measured swarm report, or null before the first poll lands. Shown verbatim
         * on the connecting screen — a wait the viewer can see the mechanics of is a wait they can
         * make an informed decision about.
         */
        val status: StreamSessionStatus? = null,
        val autoSwitched: Boolean = false,
        /**
         * Set once the viewer has said they'd rather wait this one out. The screen drops back to its
         * quiet form so the choice is visibly taken — a "Keep waiting" button that leaves the warning
         * on screen reads as a button that did nothing.
         */
        val waitAcknowledged: Boolean = false,
    ) : SourceSearchState

    /**
     * A terminal stop. [kind] picks the words and the buttons; [remaining] is how many untried
     * streams are left, which decides whether "try the next one" is offered at all.
     */
    data class Stopped(
        val kind: StopKind,
        val remaining: Int = 0,
    ) : SourceSearchState
}

/** Why the flow stopped. Each maps to its own copy and its own set of next steps. */
enum class StopKind {
    /** The search ran and nothing playable came back. */
    NO_SOURCES,

    /** The server stopped answering partway through the search. */
    TIMED_OUT,

    /** The server isn't set up for this — retrying cannot help. */
    SERVER_NOT_READY,

    /** A chosen stream failed to start or stopped responding. */
    STREAM_FAILED,
}

/**
 * The whole source-picking surface, driven off [state].
 *
 * Screens 04 (finding), 05 (choose a stream) and 06 (connecting) are all modal over the details page
 * rather than navigation destinations — BACK always returns to the film, and nothing is left behind.
 */
@Composable
fun SourcePickerDialog(
    state: SourceSearchState,
    title: String,
    logoUrl: String? = null,
    onPick: (TorrentSourceDto) -> Unit,
    onRetry: () -> Unit,
    onRequest: () -> Unit,
    onNextStream: () -> Unit,
    onChooseAnother: () -> Unit,
    onKeepWaiting: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        SourceSearchState.Idle -> Unit

        is SourceSearchState.Searching ->
            FindingSourcesDialog(
                title = title,
                startedAtMs = state.startedAtMs,
                onCancel = onDismiss,
            )

        is SourceSearchState.Found ->
            StreamPickerScreen(
                state = state,
                title = title,
                onPick = onPick,
                onDismiss = onDismiss,
            )

        // BACK here stops the connection and reopens the list rather than leaving the flow: a viewer
        // backing out of a stalled stream almost always wants a different one, not the details page.
        is SourceSearchState.Opening ->
            ConnectingScreen(
                state = state,
                title = title,
                logoUrl = logoUrl,
                onSwitchToNext = onNextStream,
                onKeepWaiting = onKeepWaiting,
                onDismiss = onChooseAnother,
            )

        is SourceSearchState.Stopped ->
            SourceStoppedScreen(
                state = state,
                onRetry = onRetry,
                onRequest = onRequest,
                onNextStream = onNextStream,
                onChooseAnother = onChooseAnother,
                onDismiss = onDismiss,
            )
    }
}

// Design geometry. The 1080p mockups are authored in device pixels; the panel runs at density 2, so
// every measurement below is the mockup's value halved — the same conversion PlaybackDimens uses
// (116px safe area → 58.dp).
private val ScreenPaddingH = 40.dp
private val ScreenPaddingV = 32.dp
private val RowRadius = RoundedCornerShape(7.dp)
private val ChipRadius = RoundedCornerShape(3.dp)

/**
 * Roughly one row's height in pixels, used to keep the default-focused row off the viewport ceiling.
 * Approximate on purpose — it only has to look like there is more above, not land on a boundary.
 */
private const val ROW_PITCH_PX = 150

internal val StageColor = Color(0xFF15121C)
internal val OnStage = Color(0xFFE8DFF0)
internal val OnStageBright = Color(0xFFF5F1FA)

/**
 * Screen 05 — every stream the engine returned, printed in full.
 *
 * Deliberately unranked and uncoloured: no tiers, no adjectives, no "best" chip. The release name is
 * verbatim and wrapped rather than truncated, and each fact gets its own neutral chip, so the screen
 * never makes a judgement the data doesn't already contain. The white focus ring is the only
 * non-neutral treatment, and it says *where you are*, not *which is good*.
 */
@Composable
private fun StreamPickerScreen(
    state: SourceSearchState.Found,
    title: String,
    onPick: (TorrentSourceDto) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenStage(onDismiss = onDismiss) {
        val listState = rememberLazyListState()
        val defaultFocus = remember { FocusRequester() }
        val focusIndex =
            remember(state) {
                state.sources.indexOfFirst { it.id == state.focusId }.coerceAtLeast(0)
            }

        // Start the D-pad on the default row rather than the top of the list, and scroll it into view
        // first so the focus request lands on a composed item. Retried because a request that arrives
        // before the row is attached simply returns false — and a picker with nothing focused is a
        // screen where pressing the D-pad does nothing, which is the failure this flow exists to avoid.
        LaunchedEffect(state.focusId) {
            // Placed one row down from the top edge rather than flush against it, so the row above is
            // visibly there — on a list of twenty, a focused row pinned to the ceiling reads as "this
            // is the first result" when it isn't.
            listState.scrollToItem(focusIndex, scrollOffset = -ROW_PITCH_PX)
            while (!defaultFocus.tryRequestFocus()) {
                delay(50)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ScreenPaddingH, vertical = ScreenPaddingV),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.sources_picker_title),
                        color = OnStageBright,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                            listOf(
                                title,
                                stringResource(R.string.sources_picker_count, state.sources.size),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = OnStage.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.sources_sorted_by_resolution),
                    color = OnStage.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier =
                        Modifier
                            .background(Color.White.copy(alpha = 0.06f), ChipRadius)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(7.dp),
                // Vertical padding inside the viewport, not outside it: a focused row's 2dp ring and
                // its lift are drawn beyond the row's own bounds, and without this the first and last
                // rows have that ring clipped by the viewport edge.
                contentPadding = PaddingValues(vertical = 6.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        // Coming back from a stream that didn't start should land on the row the
                        // viewer was already on, not throw them back to the top of twenty results.
                        .focusRestorer(defaultFocus),
            ) {
                items(state.sources, key = { it.id }) { source ->
                    StreamRow(
                        source = source,
                        onClick = { onPick(source) },
                        modifier =
                            if (source.id == state.focusId) {
                                Modifier.focusRequester(defaultFocus)
                            } else {
                                Modifier
                            },
                    )
                }
            }

            Text(
                text = stringResource(R.string.sources_picker_hint),
                color = OnStage.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )
        }
    }
}

/** One stream: resolution and size on the left, the release verbatim in the middle, provenance right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRow(
    source: TorrentSourceDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RowRadius),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.055f),
                contentColor = OnStage,
                focusedContainerColor = Color.White.copy(alpha = 0.13f),
                focusedContentColor = OnStageBright,
                pressedContainerColor = Color.White.copy(alpha = 0.13f),
                pressedContentColor = OnStageBright,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
                        shape = RowRadius,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
                        shape = RowRadius,
                    ),
            ),
        // A full-width row cannot grow on focus without bleeding off both screen edges, and the design
        // asks for a ring and a small lift rather than a scale in the first place.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.width(66.dp),
            ) {
                Text(
                    text = resolutionLabel(source),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = Formatter.formatShortFileSize(context, source.sizeBytes),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnStage.copy(alpha = 0.45f),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Verbatim, wrapped rather than truncated. Nothing here is shortened or prettified —
                // the release name is the one string a viewer who cares actually reads.
                Text(
                    text = source.title,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sourceChips(source).forEach { SpecChip(it) }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.width(115.dp),
            ) {
                Text(
                    text = stringResource(R.string.sources_sharing, source.seeders),
                    fontSize = 11.sp,
                    color = OnStage.copy(alpha = 0.7f),
                    textAlign = TextAlign.End,
                )
                source.indexer?.let {
                    // Kept to one line: indexer names carry parenthesised sort suffixes that otherwise
                    // wrap to three lines and push the row's own height around.
                    Text(
                        text = it,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = OnStage.copy(alpha = 0.42f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One fact, one chip. Same treatment for a 4K remux and a 720p rip — the screen ranks nothing. */
@Composable
private fun SpecChip(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = OnStage.copy(alpha = 0.82f),
        modifier =
            Modifier
                .background(Color.White.copy(alpha = 0.07f), ChipRadius)
                .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * The left rail's headline. Falls back to "SD" rather than the engine's own `quality` word when no
 * height was parsed: that field carries ranking language ("Low"), and a rail that calls one stream
 * Low while calling another 1080p is exactly the judgement this screen refuses to make.
 */
private fun resolutionLabel(source: TorrentSourceDto): String =
    if (source.resolutionHeight > 0) "${source.resolutionHeight}p" else "SD"

/**
 * The chips for one source: video codec, dynamic range, audio, source type and release group.
 *
 * Source type is read back out of the release name because the engine doesn't carry it as a field —
 * it is the one fact a viewer scanning this list looks for that isn't already structured. Anything
 * not present is simply left out; no chip ever says "unknown".
 */
internal fun sourceChips(source: TorrentSourceDto): List<String> =
    buildList {
        source.videoCodec?.takeIf { it.isNotBlank() }?.let(::add)
        when {
            source.dolbyVision -> add("Dolby Vision")
            source.hdr -> add("HDR")
            else -> Unit
        }
        source.audio?.takeIf { it.isNotBlank() }?.let(::add)
        sourceTypeOf(source.title)?.let(::add)
        source.releaseGroup?.takeIf { it.isNotBlank() }?.let(::add)
    }

// Ordered most specific first: WEB-DL has to be tested before the bare-"WEB" WEBRip pattern, or every
// WEB-DL release would be mislabelled a rip.
private val SourceTypes =
    listOf(
        "BluRay" to Regex("""(?i)blu-?ray|bdrip|brrip|\bbd\b"""),
        "WEB-DL" to Regex("""(?i)web-?dl"""),
        "WEBRip" to Regex("""(?i)web-?rip|\bweb\b"""),
        "HDTV" to Regex("""(?i)hdtv"""),
        "DVD" to Regex("""(?i)dvdrip|\bdvd\b"""),
    )

private val RemuxPattern = Regex("""(?i)\bremux\b""")

/**
 * The one fact a viewer scanning the list looks for that the engine doesn't carry as a field, read
 * back out of the release name. Remux is a modifier on the base type rather than a type of its own —
 * a WEB-DL remux is not a BluRay. Nothing recognisable in the name means no chip at all; the list
 * never says "unknown".
 */
internal fun sourceTypeOf(title: String): String? {
    val base = SourceTypes.firstOrNull { (_, pattern) -> pattern.containsMatchIn(title) }?.first
    val remux = RemuxPattern.containsMatchIn(title)
    return when {
        base != null && remux -> "$base REMUX"
        base != null -> base
        remux -> "REMUX"
        else -> null
    }
}

/**
 * The dark full-bleed stage every source screen sits on.
 *
 * A dialog rather than a navigation destination: BACK dismisses it back to the film for free, and no
 * partial state is written on the way out.
 */
@Composable
internal fun FullScreenStage(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(StageColor),
        ) {
            content()
        }
    }
}

/** A pill button in the stage's own language — the retractable-pill shape, kept expanded. */
@Composable
internal fun StageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(17.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = if (primary) 0.20f else 0.10f),
                contentColor = OnStage,
                focusedContainerColor = Color.White.copy(alpha = 0.24f),
                focusedContentColor = OnStageBright,
                pressedContainerColor = Color.White.copy(alpha = 0.24f),
                pressedContentColor = OnStageBright,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    Border(
                        border =
                            BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = if (primary) 0.45f else 0.16f),
                            ),
                        shape = shape,
                    ),
                focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
            ),
        // The stage says where you are with a ring, not by growing things. Left at the tv-material3
        // default a focused button scales 1.1x, which on a full-width row pushes its neighbours off
        // the screen entirely.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.height(34.dp),
    ) {
        // Height fills, width wraps. fillMaxSize here would make the button claim every pixel the row
        // offers — squeezing the text beside it to nothing and stretching the card to full height.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 17.dp),
        ) {
            Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The terminal stop screens for screens 04 and 06.
 *
 * Every one of them names what OrcaX will do next before it names what went wrong, and the recovery
 * is always one press on an already-focused button. Request is on every variant that can offer it,
 * because the request queue is the path that always eventually works.
 */
@Composable
private fun SourceStoppedScreen(
    state: SourceSearchState.Stopped,
    onRetry: () -> Unit,
    onRequest: () -> Unit,
    onNextStream: () -> Unit,
    onChooseAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title =
        when (state.kind) {
            StopKind.NO_SOURCES -> R.string.sources_none_title
            StopKind.TIMED_OUT -> R.string.sources_timeout_title
            StopKind.SERVER_NOT_READY -> R.string.sources_not_ready_title
            StopKind.STREAM_FAILED -> R.string.sources_stream_failed_title
        }
    val body =
        when (state.kind) {
            StopKind.NO_SOURCES -> R.string.sources_none_message
            StopKind.TIMED_OUT -> R.string.sources_timeout_message
            StopKind.SERVER_NOT_READY -> R.string.sources_unavailable_message
            StopKind.STREAM_FAILED -> R.string.sources_stream_failed_message
        }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state) {
        while (!firstFocus.tryRequestFocus()) {
            delay(50)
        }
    }

    FullScreenStage(onDismiss = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 58.dp)
                    .padding(top = 140.dp),
        ) {
            Text(
                text = stringResource(title),
                color = OnStageBright,
                fontSize = 31.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(body),
                color = OnStage.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(0.55f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // Order is the recovery order: the thing most likely to get a picture on screen sits
                // first and holds focus.
                if (state.kind == StopKind.STREAM_FAILED && state.remaining > 0) {
                    StageButton(
                        text = stringResource(R.string.sources_try_next),
                        onClick = onNextStream,
                        primary = true,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    StageButton(
                        text = stringResource(R.string.sources_choose_another),
                        onClick = onChooseAnother,
                    )
                    StageButton(text = stringResource(R.string.sources_request_instead), onClick = onRequest)
                } else {
                    val retryFirst = state.kind == StopKind.TIMED_OUT
                    val retryable = state.kind != StopKind.SERVER_NOT_READY
                    if (retryFirst && retryable) {
                        StageButton(
                            text = stringResource(R.string.sources_try_again),
                            onClick = onRetry,
                            primary = true,
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        StageButton(text = stringResource(R.string.sources_back_to_film), onClick = onDismiss)
                    } else {
                        StageButton(
                            text = stringResource(R.string.sources_request_instead),
                            onClick = onRequest,
                            primary = true,
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        if (retryable) {
                            StageButton(text = stringResource(R.string.sources_try_again), onClick = onRetry)
                        }
                        StageButton(text = stringResource(R.string.sources_back_to_film), onClick = onDismiss)
                    }
                }
            }
        }
    }
}
