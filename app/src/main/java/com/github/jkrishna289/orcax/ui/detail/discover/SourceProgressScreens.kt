package com.github.jkrishna289.orcax.ui.detail.discover

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.StreamSessionStatus
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.playback.PlaybackColors
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The project's motion curve. No spring, no bounce — the same easing the seek bar and the toolbar use,
 * so a wait feels like part of the app rather than a widget bolted onto it.
 */
private val OrcaEase = CubicBezierEasing(0.22f, 0.9f, 0.32f, 1f)

/** A search that settles this fast never gets a dialog at all — the picker just opens. */
const val SOURCE_DIALOG_DELAY_MS = 900L

/** Quiet until here. Past it the wait is genuinely unusual and the viewer is offered a way out. */
const val CONNECT_ESCALATE_MS = 12_000L


private val CardColor = Color(0xFF241F30)
private val PlayerBlack = Color(0xFF0A0810)

/**
 * Screen 04 — the wait, made honest.
 *
 * Nothing is shown for the first [SOURCE_DIALOG_DELAY_MS]: a dialog that flashes up and vanishes is
 * worse than no dialog. Cancel holds focus the entire time the dialog is up, so there is never a
 * moment where a D-pad press does nothing.
 */
@Composable
internal fun FindingSourcesDialog(
    title: String,
    startedAtMs: Long,
    onCancel: () -> Unit,
) {
    val visible by produceState(initialValue = false, startedAtMs) {
        delay((startedAtMs + SOURCE_DIALOG_DELAY_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        value = true
    }
    if (!visible) return

    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.tryRequestFocus() }

    FullScreenStage(onDismiss = onCancel) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier =
                    Modifier
                        .width(520.dp)
                        .background(CardColor, RoundedCornerShape(9.dp))
                        .padding(horizontal = 36.dp, vertical = 30.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    SonarMark()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.sources_finding_title),
                            color = OnStageBright,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (title.isNotBlank()) {
                            Text(
                                text = title,
                                color = OnStage.copy(alpha = 0.62f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                SegmentedMeter()

                Text(
                    text = stringResource(R.string.sources_finding_duration),
                    color = OnStage.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f)),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.sources_finding_background),
                        color = OnStage.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    StageButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancel,
                        primary = true,
                        modifier = Modifier.focusRequester(cancelFocus),
                    )
                }
            }
        }
    }
}

/**
 * Three rings expanding out of a still core — a search radiating outward, not a spinner going
 * nowhere. The distinction matters on a TV, where a spinner reads as "stuck".
 */
@Composable
private fun SonarMark() {
    val transition = rememberInfiniteTransition(label = "sonar")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(31.dp),
    ) {
        repeat(3) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 2400, easing = OrcaEase),
                        initialStartOffset =
                            androidx.compose.animation.core.StartOffset(offsetMillis = index * 800),
                    ),
                label = "ring$index",
            )
            Box(
                modifier =
                    Modifier
                        .size(31.dp)
                        .scale(0.35f + progress * 1.55f)
                        .alpha((0.85f * (1f - progress)).coerceAtLeast(0f))
                        .border(1.dp, PlaybackColors.Purple, CircleShape),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .background(Color(0xFFD2BCFF), CircleShape),
        )
    }
}

/**
 * Ten segments snapping from squat and dim to full on a staggered delay.
 *
 * Segments read as discrete places being checked. The count of places actually checked is *not*
 * printed alongside them: the engine returns a search as one answer, so any number here would be
 * invented, and a meter that lies is the thing this screen exists to avoid.
 */
@Composable
private fun SegmentedMeter() {
    val transition = rememberInfiniteTransition(label = "meter")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(7.dp),
    ) {
        repeat(10) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 2800, easing = OrcaEase),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset =
                            androidx.compose.animation.core.StartOffset(offsetMillis = index * 160),
                    ),
                label = "seg$index",
            )
            // 0 → 22% of the cycle is the rise; the rest holds full, which is what makes the row read
            // as filling up rather than shimmering.
            val rise = (progress / 0.22f).coerceAtMost(1f)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .scale(scaleX = 1f, scaleY = 0.55f + 0.45f * rise)
                        .alpha(0.16f + 0.84f * rise)
                        .background(
                            Brush.horizontalGradient(
                                listOf(PlaybackColors.Purple, PlaybackColors.SeekProgressEnd),
                            ),
                            RoundedCornerShape(50),
                        ),
            )
        }
    }
}

/**
 * Screen 06 — connecting.
 *
 * This is already the player: same black, same safe area, same type. It is not a dialog on top of the
 * app, it is the first frame of playback with no picture yet, which is what makes the handover to
 * video feel like nothing happened.
 *
 * The escalation ladder is time-driven, and the picture changes before the words do — at
 * [CONNECT_ESCALATE_MS] the title's breathing slows and the meter turns gold, so the state is felt
 * before it is read.
 */
@Composable
internal fun ConnectingScreen(
    state: SourceSearchState.Opening,
    title: String,
    logoUrl: String? = null,
    onSwitchToNext: () -> Unit,
    onKeepWaiting: () -> Unit,
    onDismiss: () -> Unit,
) {
    val elapsed by produceState(initialValue = 0L, state.startedAtMs) {
        while (true) {
            value = SystemClock.elapsedRealtime() - state.startedAtMs
            delay(500)
        }
    }
    // "Slow" now only offers a choice; it never takes one. Nothing here gives up on the stream —
    // a swarm that is still finding peers at two minutes is still a swarm that might play.
    val slow = elapsed >= CONNECT_ESCALATE_MS && !state.waitAcknowledged
    val accent = if (slow) AppColors.GoldenYellow else PlaybackColors.Purple

    val recoveryFocus = remember { FocusRequester() }
    // Focus is only claimed once there is something worth pressing, and only after the escalation —
    // grabbing it earlier would steal an in-flight press.
    LaunchedEffect(slow) { if (slow) recoveryFocus.tryRequestFocus() }

    FullScreenStage(onDismiss = onDismiss) {
        Box(modifier = Modifier.fillMaxSize().background(PlayerBlack)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.58f),
            ) {
                // The title's own artwork carries the wait when the engine has it, breathing rather
                // than blinking. The film's name, not the release name, is the fallback: a dotted
                // release string here would be the machinery leaking into the one moment the design
                // keeps clean.
                BreathingHero(
                    logoUrl = logoUrl,
                    title = title.ifBlank { state.source.title },
                    slow = slow,
                )

                Text(
                    text =
                        stringResource(
                            if (slow) R.string.connect_still_preparing_title else R.string.connect_preparing_title,
                        ),
                    color = OnStage.copy(alpha = 0.72f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ConnectMeter(accent = accent, slow = slow)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(connectPhaseLabel(state, slow)),
                            color = if (slow) accent else OnStage.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = connectingSpecLine(state),
                            color = OnStage.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                        )
                    }
                    SwarmReadout(status = state.status, accent = accent)
                }

                if (slow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StageButton(
                            text = stringResource(R.string.connect_switch_now),
                            onClick = onSwitchToNext,
                            primary = true,
                            modifier = Modifier.focusRequester(recoveryFocus),
                        )
                        StageButton(
                            text = stringResource(R.string.connect_keep_waiting),
                            onClick = onKeepWaiting,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.connect_starts_on_its_own),
                        color = OnStage.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                text = stringResource(R.string.connect_back_hint),
                color = OnStage.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 58.dp, bottom = 27.dp),
            )
        }
    }
}

/**
 * The title carrying the wait, breathing rather than blinking.
 *
 * The design hangs this on the title's own clearlogo. Seerr's movie payload has no clearlogo field,
 * so the release title takes the hero position under the same breathe; the shine pass is dropped with
 * it, since a light band sweeping across text lights the whole box rather than just the artwork.
 */
/**
 * The measured state of the swarm, printed plainly.
 *
 * This is the one place OrcaX shows its machinery, and it earns it: a viewer staring at a wait with
 * no information cannot tell a dead source from a slow one, and now that nothing gives up on their
 * behalf they are the one making that call. Every figure is measured server-side — none is smoothed,
 * predicted or invented, so "0 peers" stays 0 until a peer actually connects.
 *
 * Renders nothing until the first poll lands, rather than flashing a row of zeroes.
 */
@Composable
private fun SwarmReadout(
    status: StreamSessionStatus?,
    accent: Color,
) {
    if (status == null) return
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(top = 3.dp),
    ) {
        // Peers first: it is the number that actually explains the wait. Coloured only while zero,
        // because that is the state worth noticing.
        SwarmStat(
            label = stringResource(R.string.connect_stat_peers),
            value = "${status.peers}",
            detail = stringResource(R.string.connect_stat_seeds, status.seeds),
            valueColor = if (status.peers == 0) accent else OnStageBright,
        )
        SwarmStat(
            label = stringResource(R.string.connect_stat_speed),
            value = formatRate(context, status.downloadRateBps),
            detail = Formatter.formatShortFileSize(context, status.downloadedBytes),
        )
        SwarmStat(
            label = stringResource(R.string.connect_stat_buffered),
            value = String.format(Locale.getDefault(), "%.1f%%", status.progress),
            detail =
                if (status.hasMetadata) {
                    status.torrentState
                } else {
                    stringResource(R.string.connect_stat_awaiting_metadata)
                },
        )
    }
}

/** One measured figure: what it is, the number, and the supporting detail under it. */
@Composable
private fun SwarmStat(
    label: String,
    value: String,
    detail: String,
    valueColor: Color = OnStageBright,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            color = OnStage.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            color = OnStage.copy(alpha = 0.4f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

private fun formatRate(
    context: android.content.Context,
    bytesPerSecond: Long,
): String = "${Formatter.formatShortFileSize(context, bytesPerSecond)}/s"

/** What the connection is actually doing, said in the viewer's words rather than the torrent's. */
private fun connectPhaseLabel(
    state: SourceSearchState.Opening,
    slow: Boolean,
): Int {
    val status = state.status
    return when {
        state.autoSwitched -> R.string.connect_switched
        status != null && !status.hasMetadata -> R.string.connect_finding_peers
        slow -> R.string.connect_slow
        else -> R.string.connect_building
    }
}

/**
 * The hero that carries the wait.
 *
 * Uses the title's own artwork when the engine supplied any, falling back to its name. Either way it
 * breathes on the project's motion curve, and the loop stretches when the source turns slow — the
 * picture literally slows down before a word changes.
 */
@Composable
private fun BreathingHero(
    logoUrl: String?,
    title: String,
    slow: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "hero")
    val breath by transition.animateFloat(
        initialValue = 0.66f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = if (slow) 4200 else 2600, easing = OrcaEase),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "heroBreath",
    )

    if (logoUrl != null) {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .height(85.dp)
                    .alpha(breath),
        )
    } else {
        BreathingTitle(text = title, slow = slow)
    }
}

@Composable
private fun BreathingTitle(
    text: String,
    slow: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "breathe")
    val breath by transition.animateFloat(
        initialValue = 0.66f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                // The loop stretches from 2.6s to 4.2s when the source turns slow: the picture
                // literally slows down before a word changes.
                animation = tween(durationMillis = if (slow) 4200 else 2600, easing = OrcaEase),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "breath",
    )
    Text(
        text = text,
        color = OnStageBright,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.alpha(breath),
    )
}

/**
 * Genuinely indeterminate, and it looks it: the bar breathes rather than creeping to 99% and stopping.
 * A fake percentage that stalls is the fastest way to lose a viewer's trust.
 */
@Composable
private fun ConnectMeter(
    accent: Color,
    slow: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "connect")
    val fraction by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = if (slow) 0.31f else 0.74f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = if (slow) 4200 else 3400, easing = OrcaEase),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "fill",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.65f))),
                        RoundedCornerShape(50),
                    ),
        )
    }
}

/**
 * The one-line "what you are about to watch" under the meter. Only facts the engine actually carries
 * appear — no provider, no peer count, no release group. Those belong on the picker, where the choice
 * was made; once the film is starting they are history.
 */
private fun connectingSpecLine(state: SourceSearchState.Opening): String =
    listOfNotNull(
        state.source.resolutionHeight.takeIf { it > 0 }?.let { "${it}p" },
        state.source.audio?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
