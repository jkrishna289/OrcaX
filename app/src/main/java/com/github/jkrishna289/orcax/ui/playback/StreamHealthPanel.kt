package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.ui.window.DialogWindowProvider
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.StreamSessionStatus

private val GlassBgTop = Color(0xD90F0F0F)
private val GlassBgBottom = Color(0xCC0A0A0A)
private val LabelColor = Color(0x99FFFFFF)
private val ValueColor = Color(0xFFFFFFFF)
private val GoodColor = Color(0xFF7ED08F)
private val WarnColor = Color(0xFFE0C56B)
private val BadColor = Color(0xFFE08B7E)

/**
 * Read-only readout of how a torrent stream is actually doing, shown in place of quality selection.
 *
 * This exists because a torrent has nothing to *choose* — the file is the release, served
 * byte-for-byte, so a quality ladder is meaningless. What a viewer genuinely wants when playback
 * hesitates is whether anyone is sharing, how fast it is arriving, and how much runway the buffer
 * has. There are deliberately no controls: nothing here can be acted on mid-stream, and offering a
 * button that tears down a working session would be worse than showing nothing.
 *
 * @param status latest engine reading, or null before the first poll returns.
 * @param bufferAheadMs how far the player has buffered past the play head.
 * @param onDismiss close the panel; playback is never interrupted.
 */
@Composable
fun StreamHealthPanel(
    status: StreamSessionStatus?,
    bufferAheadMs: Long,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Match the quality panel: no scrim, so the film stays visible behind the readout — the
        // whole point is watching the numbers *while* it plays.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Column(
                modifier = Modifier
                    .width(288.dp)
                    .padding(bottom = 88.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(0f to GlassBgTop, 1f to GlassBgBottom))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.stream_health_title),
                    color = ValueColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                val seeds = status?.seeds ?: 0
                val peers = status?.peers ?: 0
                val rate = status?.downloadRateBps ?: 0L

                HealthRow(
                    label = stringResource(R.string.stream_health_sharing),
                    value = seeds.toString(),
                    // Seeds hold the whole file; peers hold part of it. Both can serve pieces, so a
                    // swarm with no seeds but several peers is not necessarily dead.
                    detail = stringResource(R.string.stream_health_seeds_leeches, seeds, peers),
                    valueColor = if (seeds > 0) GoodColor else if (peers > 0) WarnColor else BadColor,
                )

                HealthRow(
                    label = stringResource(R.string.stream_health_speed),
                    value = formatRate(rate),
                    detail = if (rate <= 0L) stringResource(R.string.stream_health_stalled) else null,
                    valueColor = if (rate > 0L) GoodColor else BadColor,
                )

                HealthRow(
                    label = stringResource(R.string.stream_health_buffer),
                    value = formatBuffer(bufferAheadMs),
                    detail = stringResource(
                        R.string.stream_health_buffer_seconds,
                        (bufferAheadMs / 1000L).toInt().coerceAtLeast(0),
                    ),
                    // Under ~10s of runway a stall is imminent; that is the number worth colouring.
                    valueColor = when {
                        bufferAheadMs >= 30_000L -> GoodColor
                        bufferAheadMs >= 10_000L -> WarnColor
                        else -> BadColor
                    },
                )

                status?.let {
                    HealthRow(
                        label = stringResource(R.string.stream_health_loaded),
                        value = String.format("%.1f%%", it.progress),
                        detail = formatBytes(it.downloadedBytes),
                        valueColor = ValueColor,
                    )
                    if (it.fileName.isNotBlank()) {
                        HealthRow(
                            label = stringResource(R.string.stream_health_file),
                            value = it.fileName,
                            detail = null,
                            valueColor = ValueColor,
                            valueSize = 11.sp,
                        )
                    }
                } ?: Text(
                    text = stringResource(R.string.stream_health_waiting),
                    color = LabelColor,
                    fontSize = 11.sp,
                )

                Text(
                    text = stringResource(R.string.stream_health_hint),
                    color = LabelColor,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    label: String,
    value: String,
    detail: String?,
    valueColor: Color,
    valueSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label.uppercase(),
            color = LabelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(74.dp).padding(top = 3.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = value,
                color = valueColor,
                fontSize = valueSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(text = it, color = LabelColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One-line swarm summary for the toolbar chip, e.g. "12 · 1.4 MB/s".
 *
 * Deliberately terse — it sits in a row of chips next to AUD and SUB, and a viewer glancing at it
 * mid-film wants the two numbers that predict a stall, not a sentence.
 */
fun streamHealthChipLabel(status: StreamSessionStatus?): String {
    if (status == null) return "—"
    val sharing = maxOf(status.seeds, 0)
    return "$sharing · ${formatRate(status.downloadRateBps)}"
}

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> "0 B/s"
    bytesPerSecond >= 1_000_000L -> String.format("%.1f MB/s", bytesPerSecond / 1_000_000.0)
    bytesPerSecond >= 1_000L -> "${bytesPerSecond / 1_000L} kB/s"
    else -> "$bytesPerSecond B/s"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
    bytes >= 1_000L -> "${bytes / 1_000L} kB"
    else -> "$bytes B"
}

private fun formatBuffer(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return if (seconds >= 60L) "${seconds / 60L}m ${seconds % 60L}s" else "${seconds}s"
}
