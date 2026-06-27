package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.data.model.BaseItem
import org.jellyfin.sdk.model.api.PlayMethod

/**
 * A frosted-glass info bar shown at the top-left corner of the playback overlay.
 *
 * Displays:
 *  - Show/series title (muted uppercase label)
 *  - Episode title (prominent)
 *  - Metadata pills: Direct Play badge, resolution, audio codec, runtime
 *
 * Visibility follows [controllerViewState] exactly like the rest of the OSD.
 * It slides in from the top-left and fades out when the controller hides.
 *
 * Integration point: add this composable inside [PlaybackOverlay]'s root [Box],
 * aligned to [Alignment.TopStart], *after* the existing logo AsyncImage block.
 * Pass [showLogo] = false (or check logoImageUrl) so logo and info bar don't overlap.
 */
@Composable
fun PlayerInfoBar(
    item: BaseItem?,
    playMethod: PlayMethod?,
    controllerViewState: ControllerViewState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = controllerViewState.controlsVisible && item != null,
        enter = slideIn { IntOffset(x = -it.width / 2, y = -it.height / 2) } + fadeIn(),
        exit = slideOut { IntOffset(x = -it.width / 2, y = -it.height / 2) } + fadeOut(),
        modifier = modifier,
    ) {
        item ?: return@AnimatedVisibility

        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .padding(start = 24.dp, top = 20.dp),
        ) {
            // Show / series label
            val seriesLabel =
                when {
                    item.data.seriesName != null -> item.data.seriesName
                    item.data.parentIndexNumber != null && item.data.indexNumber != null ->
                        "S${item.data.parentIndexNumber} E${item.data.indexNumber}"
                    else -> null
                }
            seriesLabel?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
                    letterSpacing = 0.12.sp,
                )
            }

            // Episode / movie title
            Text(
                text = item.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            // Metadata pill row
            MetadataPills(item = item, playMethod = playMethod)
        }
    }
}

// ---------------------------------------------------------------------------
// Metadata pills
// ---------------------------------------------------------------------------

@Composable
private fun MetadataPills(
    item: BaseItem,
    playMethod: PlayMethod?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direct Play badge (green accent)
        if (playMethod == PlayMethod.DIRECT_PLAY) {
            MetaPill(
                label = "Direct Play",
                background = Color(0xFF4ADE80).copy(alpha = 0.12f),
                borderColor = Color(0xFF4ADE80).copy(alpha = 0.30f),
                textColor = Color(0xFF4ADE80).copy(alpha = 0.90f),
            )
        }

        // Resolution pill  (e.g. "4K HDR" or "1080p")
        resolutionLabel(item)?.let { MetaPill(it) }

        // Audio codec pill (e.g. "Dolby Atmos", "DTS:X")
        audioCodecLabel(item)?.let { MetaPill(it) }

        // Runtime pill
        runtimeLabel(item)?.let { MetaPill(it) }
    }
}

@Composable
private fun MetaPill(
    label: String,
    background: Color = Color.White.copy(alpha = 0.10f),
    borderColor: Color = Color.White.copy(alpha = 0.12f),
    textColor: Color = Color.White.copy(alpha = 0.62f),
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = textColor,
        letterSpacing = 0.06.sp,
        modifier =
            Modifier
                .background(
                    color = background,
                    shape = RoundedCornerShape(20.dp),
                )
                // Simulate a 1dp border via an outer slightly-larger colored box — simpler than
                // drawBehind for a rounded rect at this size.
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------
// Label helpers  (pure logic, no Compose state)
// ---------------------------------------------------------------------------

private fun resolutionLabel(item: BaseItem): String? {
    val streams = item.data.mediaSources?.firstOrNull()?.mediaStreams ?: return null
    val videoStream = streams.firstOrNull { it.type?.serialName == "Video" } ?: return null
    val width = videoStream.width ?: return null
    val isHdr =
        videoStream.videoRange?.serialName?.contains("HDR", ignoreCase = true) == true ||
                videoStream.videoRangeType?.serialName?.let { it != "SDR" && it != "Unknown" } == true
    val res =
        when {
            width >= 3840 -> "4K"
            width >= 1920 -> "1080p"
            width >= 1280 -> "720p"
            else -> "${width}p"
        }
    return if (isHdr) "$res HDR" else res
}

private fun audioCodecLabel(item: BaseItem): String? {
    val streams = item.data.mediaSources?.firstOrNull()?.mediaStreams ?: return null
    val audioStream =
        streams.firstOrNull { it.type?.serialName == "Audio" && it.isDefault == true }
            ?: streams.firstOrNull { it.type?.serialName == "Audio" }
            ?: return null
    val codec = audioStream.codec?.uppercase() ?: return null
    val channels = audioStream.channels
    return when {
        codec.contains("TRUEHD") && audioStream.profile?.contains("Atmos", ignoreCase = true) == true -> "Dolby Atmos"
        codec.contains("TRUEHD") -> "Dolby TrueHD"
        codec.contains("EAC3") && audioStream.profile?.contains("Atmos", ignoreCase = true) == true -> "Dolby Atmos"
        codec.contains("EAC3") -> "Dolby Digital+"
        codec.contains("AC3") -> "Dolby Digital"
        codec.contains("DTS") && audioStream.profile?.contains("MA", ignoreCase = true) == true -> "DTS-HD MA"
        codec.contains("DTS") && audioStream.profile?.contains("X", ignoreCase = true) == true -> "DTS:X"
        codec.contains("DTS") -> "DTS"
        codec.contains("AAC") -> if (channels != null && channels > 2) "AAC $channels.0" else "AAC"
        else -> codec
    }
}

private fun runtimeLabel(item: BaseItem): String? {
    val ticks = item.data.runTimeTicks ?: return null
    val totalMinutes = ticks / 600_000_000L // ticks → minutes
    if (totalMinutes <= 0) return null
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}