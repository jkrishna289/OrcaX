package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.TrackIndex
import com.github.jkrishna289.orcax.ui.tryRequestFocus

// ─── Glassmorphic tokens ─────────────────────────────────────────────────────
private val GlassBgTop    = Color(0xD90F0F0F)
private val GlassBgBottom = Color(0xCC0A0A0A)
private val HeaderLabel   = Color(0xFF888888)
private val BadgeBg       = Color(0x26FFFFFF)
private val BadgeText     = Color(0xFFAAAAAA)
private val AccentColor   = Color(0xFFE50914)

// Roulette row item height — used both in the picker and the divider height
private val ROW_HEIGHT = 56.dp
private const val VISIBLE_ROWS = 3
private val ROULETTE_HEIGHT = ROW_HEIGHT * VISIBLE_ROWS  // 192.dp

// ─── Helpers ──────────────────────────────────────────────────────────────────
private fun SimpleMediaStream.codecLabel(): String? {
    val t = displayTitle.lowercase()
    return when {
        "dolby atmos" in t || "atmos" in t -> "Dolby Atmos"
        "truehd" in t                       -> "TrueHD"
        "dts:x" in t || "dtsx" in t        -> "DTS:X"
        "dts-hd" in t                       -> "DTS-HD"
        "dts" in t                          -> "DTS"
        "eac3" in t || "dd+" in t ||
                "dolby digital plus" in t       -> "DD+"
        "ac3" in t || "dolby digital" in t  -> "Dolby"
        "aac" in t                          -> "AAC"
        "7.1" in t                          -> "7.1"
        "5.1" in t                          -> "5.1"
        "stereo" in t || "2.0" in t        -> "Stereo"
        else                                -> null
    }
}

private fun SimpleMediaStream.isExternal(): Boolean =
    "external" in (streamTitle ?: displayTitle).lowercase()

// ─── Roulette item models ─────────────────────────────────────────────────────
private data class AudioItem(val index: Int, val label: String, val codec: String?)
private data class SubItem(val index: Int, val label: String, val isExternal: Boolean?)

// ─── Badge ────────────────────────────────────────────────────────────────────
@Composable
private fun Badge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BadgeBg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = BadgeText)
    }
}

// ─── Accent bar (replaces tick/checkmark) ────────────────────────────────────
@Composable
private fun AccentBar(active: Boolean) {
    Box(
        modifier = Modifier
            .width(2.5.dp)
            .height(20.dp)
            .background(
                color = if (active) AccentColor else Color.Transparent,
                shape = RoundedCornerShape(2.dp),
            ),
    )
}

// ─── Audio roulette row ───────────────────────────────────────────────────────
@Composable
private fun AudioRouletteRow(item: AudioItem, isSelected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // Use Modifier.alpha() — simpler and avoids compositing issues
            .alpha(if (isSelected) 1f else 0.40f),
    ) {
        AccentBar(active = isSelected)
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.codec?.let { Spacer(Modifier.width(6.dp)); Badge(it) }
    }
}

// ─── Subtitle roulette row ────────────────────────────────────────────────────
@Composable
private fun SubRouletteRow(item: SubItem, isSelected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .alpha(if (isSelected) 1f else 0.40f),
    ) {
        AccentBar(active = isSelected)
        Spacer(Modifier.width(8.dp))
        if (item.isExternal != null) {
            // Show captions icon only for real subtitle entries (not Off)
            Icon(
                painter = painterResource(R.drawable.captions_svgrepo_com),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = item.label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.isExternal?.let { isExt ->
            Spacer(Modifier.width(6.dp))
            Badge(if (isExt) "EXT" else "EMB")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AudioSubtitlePanel — two roulettes side-by-side
//
// D-pad:  UP/DOWN = scroll within column (visual only)
//         CENTER  = confirm selection
//             • Audio column CENTER → apply audio, move focus to subtitle column
//             • Subtitle column CENTER → apply subtitle, dismiss panel
//         RIGHT   = audio column → subtitle column
//         LEFT    = subtitle column → audio column
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioSubtitlePanel(
    audioStreams: List<SimpleMediaStream>,
    subtitleStreams: List<SimpleMediaStream>,
    selectedAudioIndex: Int?,
    selectedSubtitleIndex: Int?,
    audioPassthrough: Boolean,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onTogglePassthrough: () -> Unit,   // API compat, not rendered
    onDismiss: () -> Unit,
) {
    // Build audio roulette items
    // Engine-driven labels: "<Language> <Channels> [Codec]"
    // The codec is ALREADY part of the engine label, so we don't need a separate
    // codec badge anymore. We pass null to keep the data class shape unchanged.
    val audioItems = remember(audioStreams) {
        audioStreams.map { stream ->
            AudioItem(
                index = stream.index,
                label = StreamLabelEngine.audioLabel(stream),
                codec = null,
            )
        }
    }

    // Build subtitle roulette items — "Off" at index 0, then all streams
    // Engine-driven subtitle labels: just the language. No "Forced" / "SDH" clutter.
    // We DO keep the EXT/EMB badge as a small visual indicator of source.
    val subItems = remember(subtitleStreams) {
        buildList {
            add(SubItem(index = TrackIndex.DISABLED, label = "Off", isExternal = null))
            subtitleStreams
                .filter { "forced" !in (it.streamTitle ?: it.displayTitle).lowercase() }
                .forEach { stream ->
                    val engineLabel = StreamLabelEngine.subtitleLabel(stream)
                    // Always show every subtitle stream. Use the engine label when it
                    // produces a real language name; fall back through raw title fields
                    // to "Track N" so no stream ever appears with a blank label.
                    val displayLabel = when {
                        engineLabel.isNotBlank() && engineLabel != "Unknown" -> engineLabel
                        else -> stream.streamTitle?.takeIf { it.isNotBlank() }
                            ?: stream.displayTitle.takeIf { it.isNotBlank() }
                            ?: "Track ${stream.index + 1}"
                    }
                    add(SubItem(index = stream.index, label = displayLabel, isExternal = stream.isExternal()))
                }
        }
    }

    // Initial scroll positions
    val initialAudioIdx = audioItems
        .indexOfFirst { it.index == selectedAudioIndex }
        .coerceAtLeast(0)
    val initialSubIdx = when {
        selectedSubtitleIndex == null ||
                selectedSubtitleIndex == TrackIndex.DISABLED ||
                selectedSubtitleIndex == TrackIndex.UNSPECIFIED -> 0
        else -> subItems.indexOfFirst { it.index == selectedSubtitleIndex }.coerceAtLeast(0)
    }

    var audioIdx by remember { mutableIntStateOf(initialAudioIdx) }
    var subIdx   by remember { mutableIntStateOf(initialSubIdx) }

    val audioFocus = remember { FocusRequester() }
    val subFocus   = remember { FocusRequester() }

    LaunchedEffect(Unit) { audioFocus.tryRequestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Remove system scrim if possible; not critical if it fails
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.setDimAmount(0f)

        // Full-screen transparent container — panel anchored bottom-center above toolbar
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .padding(bottom = 90.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(0f to GlassBgTop, 1f to GlassBgBottom))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            // ── AUDIO column ─────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "AUDIO",
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.12.sp, color = HeaderLabel,
                )
                Box(
                    modifier = Modifier.onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                            subFocus.tryRequestFocus()
                            true
                        } else false
                    },
                ) {
                    RoulettePicker(
                        items = audioItems,
                        selectedIndex = audioIdx.coerceAtMost((audioItems.size - 1).coerceAtLeast(0)),
                        onSelectedIndexChange = { audioIdx = it },
                        itemHeight = ROW_HEIGHT,
                        width = 220.dp,
                        visibleCount = VISIBLE_ROWS,
                        focusRequester = audioFocus,
                        onCenterPress = {
                            audioItems.getOrNull(audioIdx)?.let { onSelectAudio(it.index) }
                            subFocus.tryRequestFocus()
                        },
                    ) { item, isSelected ->
                        AudioRouletteRow(item = item, isSelected = isSelected)
                    }
                }
            }

            // ── Vertical divider ──────────────────────────────────────────────
            // Explicit height = roulette height only (prevents fillMaxHeight screen bug)
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .padding(top = 30.dp)   // align to roulette, skip header
                    .width(1.dp)
                    .height(ROULETTE_HEIGHT)
                    .background(Color.White.copy(alpha = 0.12f)),
            )

            // ── SUBTITLES column ──────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "SUBTITLES",
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.12.sp, color = HeaderLabel,
                )
                Box(
                    modifier = Modifier.onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                            audioFocus.tryRequestFocus()
                            true
                        } else false
                    },
                ) {
                    RoulettePicker(
                        items = subItems,
                        selectedIndex = subIdx.coerceAtMost((subItems.size - 1).coerceAtLeast(0)),
                        onSelectedIndexChange = { subIdx = it },
                        itemHeight = ROW_HEIGHT,
                        width = 220.dp,
                        visibleCount = VISIBLE_ROWS,
                        focusRequester = subFocus,
                        onCenterPress = {
                            subItems.getOrNull(subIdx)?.let { onSelectSubtitle(it.index) }
                            onDismiss()
                        },
                    ) { item, isSelected ->
                        SubRouletteRow(item = item, isSelected = isSelected)
                    }
                }
            }
        }
        }  // close Box
    }
}