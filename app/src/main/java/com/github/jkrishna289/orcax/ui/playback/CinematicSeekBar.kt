package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CinematicSeekBar(
    seekFraction: Float,
    bufferedFraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    currentPositionMs: Long = 0L,
    durationMs: Long = 0L,
    showTimestamps: Boolean = false,
    // When false the bar is a static, non-scrubbing progress indicator (no LEFT/RIGHT
    // seek, no focus thumb/glow) — but it can still hold focus as an inert anchor.
    interactive: Boolean = true,
    // When false the bar is removed from the focus graph entirely (Phase 1 shows it
    // as display-only chrome; Phase 2 COMPACT relies on the default as focus anchor).
    focusable: Boolean = true,
) {
    if (showTimestamps) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SeekBarTimestamps(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs
            )
            SeekBarTrackWithThumb(
                seekFraction = seekFraction,
                bufferedFraction = bufferedFraction,
                onSeek = onSeek,
                focusRequester = focusRequester,
                interactive = interactive,
                focusable = focusable,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        SeekBarTrackWithThumb(
            seekFraction = seekFraction,
            bufferedFraction = bufferedFraction,
            onSeek = onSeek,
            focusRequester = focusRequester,
            interactive = interactive,
            focusable = focusable,
            modifier = modifier
        )
    }
}

@Composable
private fun SeekBarTimestamps(
    currentPositionMs: Long,
    durationMs: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatPlaybackTime(currentPositionMs),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.50f)
        )
        Text(
            text = formatPlaybackTime(durationMs),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.50f)
        )
    }
}

@Composable
private fun SeekBarTrackWithThumb(
    seekFraction: Float,
    bufferedFraction: Float,
    onSeek: (Float) -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    focusable: Boolean = true,
) {
    val density = LocalDensity.current

    val interactionSource = remember { MutableInteractionSource() }
    val rawFocused by interactionSource.collectIsFocusedAsState()
    // Non-interactive bars never show the focus thumb/glow even while anchoring focus.
    val isFocused = rawFocused && interactive

    // Thumb grows when the seek bar is focused — clear signal on 10-foot display
    val thumbSize = if (isFocused) PlaybackDimens.SeekThumbSize * 1.35f
                    else           PlaybackDimens.SeekThumbSize

    BoxWithConstraints(
        modifier = modifier
            .height(thumbSize)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onKeyEvent { event ->
                if (!interactive || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft  -> { onSeek((seekFraction - 0.05f).coerceIn(0f, 1f)); true }
                    Key.DirectionRight -> { onSeek((seekFraction + 0.05f).coerceIn(0f, 1f)); true }
                    else               -> false
                }
            }
            .then(
                if (focusable) Modifier.focusable(interactionSource = interactionSource)
                else Modifier
            )
            .then(
                if (interactive) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek(fraction)
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        val trackWidthDp = maxWidth
        val trackWidthPx = with(density) { trackWidthDp.toPx() }
        val thumbSizePx = with(density) { thumbSize.toPx() }

        // Track + progress canvas (centered vertically)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlaybackDimens.SeekBarHeight)
                .align(Alignment.Center),
        ) {
            drawTrack(
                trackWidth = size.width,
                trackHeight = size.height,
                seekFraction = seekFraction.coerceIn(0f, 1f),
                bufferedFraction = bufferedFraction.coerceIn(0f, 1f),
            )
        }

        // Thumb
        val thumbOffsetX = with(density) {
            val px = (seekFraction.coerceIn(0f, 1f) * trackWidthPx - thumbSizePx / 2f)
            px.toDp()
        }
        Box(
            modifier = Modifier
                .size(thumbSize)
                .align(Alignment.CenterStart)
                .offset(x = thumbOffsetX)
                .drawBehind {
                    val r = size.minDimension / 2f
                    if (isFocused) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.45f),
                            radius = r * 1.85f,
                        )
                    }
                }
                .background(Color.White, CircleShape),
        )
    }
}

private fun DrawScope.drawTrack(
    trackWidth: Float,
    trackHeight: Float,
    seekFraction: Float,
    bufferedFraction: Float,
) {
    val radius = trackHeight / 2f

    drawRoundRect(
        color = PlaybackColors.SeekTrack,
        size = Size(trackWidth, trackHeight),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = PlaybackColors.SeekBuffered,
        size = Size(trackWidth * bufferedFraction, trackHeight),
        cornerRadius = CornerRadius(radius, radius),
    )
    if (seekFraction > 0f) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(PlaybackColors.SeekProgressStart, PlaybackColors.SeekProgressEnd),
            ),
            size = Size(trackWidth * seekFraction, trackHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
