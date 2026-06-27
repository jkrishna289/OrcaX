package com.github.jkrishna289.orcax.ui.playback

import android.content.Context
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.ui.tryRequestFocus

// ─── Glassmorphic tokens ──────────────────────────────────────────────────────
private val GlassBgTop    = Color(0xD90F0F0F)
private val GlassBgBottom = Color(0xCC0A0A0A)
private val LabelColor    = Color(0xFF888888)
private val AccentGreen   = Color(0xFF4ADE80)
private val AccentRed     = Color(0xFFE50914)

// ─── TV capability probe ──────────────────────────────────────────────────────
@Immutable
private data class TvCapabilities(val maxWidth: Int, val maxHeight: Int, val supportsHdr: Boolean)

private fun probeTvCapabilities(context: Context): TvCapabilities = try {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
    val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
    val modes = display?.supportedModes ?: emptyArray()
    val maxMode = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
    val hdrCaps = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
    TvCapabilities(
        maxWidth = maxMode?.physicalWidth ?: 1920,
        maxHeight = maxMode?.physicalHeight ?: 1080,
        supportsHdr = hdrCaps.isNotEmpty(),
    )
} catch (t: Throwable) {
    TvCapabilities(1920, 1080, false)
}

// ─── Tier helpers ─────────────────────────────────────────────────────────────
private fun resolutionLabel(width: Int, height: Int): String = when {
    height >= 2000 || width >= 3600 -> "4K"
    height >= 1400                  -> "1440p"
    height >= 1000                  -> "1080p"
    height >= 700                   -> "720p"
    height >= 400                   -> "480p"
    height > 0                      -> "${height}p"
    else                            -> "SD"
}

private fun bitrateLabel(bps: Int): String = when {
    bps <= 0         -> "—"
    bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000     -> "${bps / 1_000} Kbps"
    else             -> "$bps bps"
}

@Immutable
private data class DynamicTier(
    val tier: QualityTier,
    val label: String,
    val description: String,
    val bitrateText: String,
    val resolutionBadge: String = "",
    val isDirectPlay: Boolean = false,
)

private fun buildDynamicTiers(
    width: Int, height: Int, bitrateBps: Int, hdr: Boolean,
    caps: TvCapabilities,
): List<DynamicTier> {
    val tiers = mutableListOf<DynamicTier>()

    val tvDesc = if (caps.supportsHdr) "HDR TV" else "${caps.maxHeight}p TV"
    tiers += DynamicTier(QualityTier.AUTO, "Auto", "Adapts to connection · $tvDesc", "—", resolutionBadge = "", isDirectPlay = false)

    if (bitrateBps > 0 || height > 0) {
        val hdrFlag = if (hdr && caps.supportsHdr) " HDR" else ""
        val resLabel = resolutionLabel(width, height)
        tiers += DynamicTier(
            tier = QualityTier.CINEMA,
            label = "Original",
            description = "Direct · $resLabel$hdrFlag",
            bitrateText = bitrateLabel(bitrateBps),
            resolutionBadge = resLabel,
            isDirectPlay = true,
        )
    }

    if (bitrateBps == 0 || bitrateBps > 8_200_000) {
        val res = if (height >= 1000) "1080p" else resolutionLabel(width, height)
        tiers += DynamicTier(
            tier = QualityTier.BALANCED,
            label = "Balanced",
            description = "Optimised · $res",
            bitrateText = bitrateLabel(8_200_000),
            resolutionBadge = res,
            isDirectPlay = false,
        )
    }

    if (bitrateBps == 0 || bitrateBps > 2_500_000) {
        tiers += DynamicTier(
            tier = QualityTier.DATA_SAVER,
            label = "Data Saver",
            description = "Low bandwidth · 720p",
            bitrateText = bitrateLabel(2_500_000),
            resolutionBadge = "720p",
            isDirectPlay = false,
        )
    }

    return tiers
}

// ─── Quality list row ─────────────────────────────────────────────────────────
@Composable
private fun QualityListRow(
    tier: DynamicTier,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.05f) else Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.09f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.Transparent, elevation = 0.dp),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            // Selected: red accent bar — Unselected: invisible placeholder
            Box(modifier = Modifier.size(width = 2.5.dp, height = 18.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = if (isSelected) AccentRed else Color.Transparent,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }

            // Tier name + "DIRECT" badge + description
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tier.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected || isFocused) Color.White else Color.White.copy(alpha = 0.60f),
                    )
                    if (tier.isDirectPlay) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(AccentGreen.copy(alpha = 0.12f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "DIRECT",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen.copy(alpha = 0.80f),
                            )
                        }
                    }
                }
                Text(
                    text = tier.description,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.28f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Resolution badge on the right
            if (tier.resolutionBadge.isNotEmpty()) {
                Text(
                    text = tier.resolutionBadge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.40f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// QualitySelectionPanel — compact flat list, anchored bottom-right above toolbar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun QualitySelectionPanel(
    selectedTier: QualityTier,
    resolvedTier: QualityTier?,
    isMeasuring: Boolean,
    videoStream: SimpleVideoStream?,
    onSelectTier: (QualityTier) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val caps = remember { probeTvCapabilities(ctx) }
    val dynamicTiers = remember(videoStream, caps) {
        buildDynamicTiers(
            width = videoStream?.width ?: 0,
            height = videoStream?.height ?: 0,
            bitrateBps = videoStream?.bitrateBps ?: 0,
            hdr = videoStream?.hdr ?: false,
            caps = caps,
        )
    }

    val frs = remember(dynamicTiers.size) { List(dynamicTiers.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        val idx = dynamicTiers.indexOfFirst { it.tier == selectedTier }.coerceAtLeast(0)
        frs.getOrNull(idx)?.tryRequestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Remove system scrim if possible; not critical if it fails
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.setDimAmount(0f)

        // Full-screen transparent container — panel anchored bottom-end above toolbar
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Column(
                modifier = Modifier
                    .width(272.dp)
                    .padding(bottom = 88.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(0f to GlassBgTop, 1f to GlassBgBottom)),
            ) {
                // Header: ⚡ VIDEO QUALITY
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Canvas(modifier = Modifier.size(9.dp)) {
                        val path = Path().apply {
                            moveTo(size.width * 0.65f, 0f)
                            lineTo(size.width * 0.25f, size.height * 0.55f)
                            lineTo(size.width * 0.55f, size.height * 0.55f)
                            lineTo(size.width * 0.35f, size.height)
                            lineTo(size.width * 0.75f, size.height * 0.45f)
                            lineTo(size.width * 0.45f, size.height * 0.45f)
                            close()
                        }
                        drawPath(path, color = LabelColor)
                    }
                    Text(
                        "VIDEO QUALITY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.10.sp,
                        color = LabelColor,
                    )
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))

                dynamicTiers.forEachIndexed { i, tier ->
                    QualityListRow(
                        tier = tier,
                        isSelected = tier.tier == selectedTier,
                        focusRequester = frs[i],
                        onClick = { onSelectTier(tier.tier); onDismiss() },
                    )
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))

                // Footer: playback mode indicator
                val selectedInfo = dynamicTiers.firstOrNull { it.tier == selectedTier }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                if (selectedInfo?.isDirectPlay == true) AccentGreen else Color(0xFF555555),
                                CircleShape,
                            ),
                    )
                    Text(
                        if (selectedInfo?.isDirectPlay == true) "Playing natively — no transcoding"
                        else "Transcoding to selected quality",
                        fontSize = 9.sp,
                        color = LabelColor,
                    )
                }
            }
        }
    }
}
