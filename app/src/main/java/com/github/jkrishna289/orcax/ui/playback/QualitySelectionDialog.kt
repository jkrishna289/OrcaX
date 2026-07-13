package com.github.jkrishna289.orcax.ui.playback

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.ui.playback.quality.QualityRecommendation
import com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection
import com.github.jkrishna289.orcax.ui.tryRequestFocus

// ─── Glassmorphic tokens ──────────────────────────────────────────────────────
private val GlassBgTop = Color(0xD90F0F0F)
private val GlassBgBottom = Color(0xCC0A0A0A)
private val LabelColor = Color(0xFF888888)
private val AccentGreen = Color(0xFF4ADE80)
private val AccentRed = Color(0xFFE50914)
private val AccentStar = Color(0xFFFACC15)

// ─── Labels (shared with the toolbar) ─────────────────────────────────────────
internal fun resolutionLabel(width: Int, height: Int): String = when {
    height >= 2000 || width >= 3600 -> "4K"
    height >= 1400 -> "1440p"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    height >= 400 -> "480p"
    height > 0 -> "${height}p"
    else -> "SD"
}

internal fun bitrateLabel(bps: Int): String = when {
    bps <= 0 -> "—"
    bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0).replace(".0 ", " ")
    bps >= 1_000 -> "${bps / 1_000} Kbps"
    else -> "$bps bps"
}

private fun gbPerHourLabel(bytesPerHour: Long): String? =
    if (bytesPerHour <= 0) null else String.format("≈%.1f GB/h", bytesPerHour / 1_000_000_000.0)

/** Toolbar chip label for the current quality state. */
fun qualitySelectionLabel(
    mode: QualitySelection,
    recommendation: QualityRecommendation?,
): String = when (mode) {
    QualitySelection.Auto -> when (val start = recommendation?.startSelection) {
        is QualitySelection.Rung -> "Auto · ${bitrateLabel(start.rung.maxBitrateBps)}"
        else -> "Auto"
    }
    QualitySelection.Original -> "Original"
    is QualitySelection.Rung ->
        "${resolutionLabel(0, mode.rung.maxHeight)} · ${bitrateLabel(mode.rung.maxBitrateBps)}"
}

// ─── Row model ────────────────────────────────────────────────────────────────
@Immutable
private data class QualityRow(
    val selection: QualitySelection,
    val label: String,
    val description: String,
    val resolutionBadge: String,
    val isDirectPlay: Boolean,
    val isRecommended: Boolean,
    val enabled: Boolean,
)

@Composable
private fun buildRows(
    recommendation: QualityRecommendation?,
    isMeasuring: Boolean,
): List<QualityRow> {
    val rows = mutableListOf<QualityRow>()

    // AUTO row — describes what AUTO would do and why.
    val autoDesc = when {
        recommendation == null && isMeasuring -> stringResource(R.string.quality_connection_measuring)
        recommendation == null -> stringResource(R.string.quality_auto_desc)
        else -> when (val rec = recommendation.recommended) {
            is QualitySelection.Rung -> stringResource(
                R.string.quality_recommended_max,
                bitrateLabel(rec.rung.maxBitrateBps),
            )
            else -> stringResource(R.string.quality_recommended_max, stringResource(R.string.quality_original))
        }
    }
    rows += QualityRow(
        selection = QualitySelection.Auto,
        label = stringResource(R.string.quality_auto),
        description = autoDesc,
        resolutionBadge = "",
        isDirectPlay = false,
        isRecommended = false,
        enabled = true,
    )

    recommendation?.options?.forEach { option ->
        val isRecommended = option.selection == recommendation.recommended
        when (val sel = option.selection) {
            QualitySelection.Original -> {
                val verdict = recommendation.originalVerdict
                val desc = when {
                    !option.available ->
                        stringResource(
                            R.string.quality_audio_reencoded,
                            (recommendation.reasons
                                .filterIsInstance<com.github.jkrishna289.orcax.ui.playback.quality.QualityReason.AudioIncompatible>()
                                .firstOrNull()?.codec ?: "?").uppercase(),
                        )
                    !verdict.playable && verdict.safeBps != null ->
                        stringResource(
                            R.string.quality_why_not_original,
                            bitrateLabel(verdict.requiredBps.toInt()),
                            bitrateLabel(verdict.safeBps.toInt()),
                            bitrateLabel((verdict.deficitBps ?: 0L).toInt()),
                        )
                    verdict.close -> stringResource(R.string.quality_original_close)
                    else -> listOfNotNull(
                        bitrateLabel(option.bitrateBps).takeIf { option.bitrateBps > 0 },
                        gbPerHourLabel(option.estimatedBytesPerHour),
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.quality_original_desc) }
                }
                rows += QualityRow(
                    selection = sel,
                    label = stringResource(R.string.quality_original),
                    description = desc,
                    resolutionBadge = resolutionLabel(0, option.maxHeight),
                    isDirectPlay = option.directPlay,
                    isRecommended = isRecommended,
                    enabled = true,
                )
            }
            is QualitySelection.Rung -> {
                rows += QualityRow(
                    selection = sel,
                    label = "${resolutionLabel(0, option.maxHeight)} · ${bitrateLabel(option.bitrateBps)}",
                    description = gbPerHourLabel(option.estimatedBytesPerHour)
                        ?: stringResource(R.string.quality_transcoding),
                    resolutionBadge = resolutionLabel(0, option.maxHeight),
                    isDirectPlay = false,
                    isRecommended = isRecommended,
                    enabled = true,
                )
            }
            QualitySelection.Auto -> Unit // never an option row
        }
    }
    return rows
}

// ─── Quality list row ─────────────────────────────────────────────────────────
@Composable
private fun QualityListRow(
    row: QualityRow,
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

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (row.isRecommended) {
                        Text("★", fontSize = 10.sp, color = AccentStar)
                    }
                    Text(
                        text = row.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected || isFocused) Color.White else Color.White.copy(alpha = 0.60f),
                    )
                    if (row.isDirectPlay) {
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
                    text = row.description,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.28f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (row.resolutionBadge.isNotEmpty()) {
                Text(
                    text = row.resolutionBadge,
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
// QualitySelectionPanel — recommendation UI, anchored bottom-right above toolbar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun QualitySelectionPanel(
    mode: QualitySelection,
    recommendation: QualityRecommendation?,
    isMeasuring: Boolean,
    onSelect: (QualitySelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = buildRows(recommendation, isMeasuring)

    val frs = remember(rows.size) { List(rows.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        val idx = rows.indexOfFirst { it.selection == mode }.coerceAtLeast(0)
        frs.getOrNull(idx)?.tryRequestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.setDimAmount(0f)

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Column(
                modifier = Modifier
                    .width(288.dp)
                    .padding(bottom = 88.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(0f to GlassBgTop, 1f to GlassBgBottom)),
            ) {
                // Header: ⚡ VIDEO QUALITY + connection line
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                            stringResource(R.string.quality_panel_title),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.10.sp,
                            color = LabelColor,
                        )
                    }
                    val connection = recommendation?.connection
                    val connectionText = when {
                        connection?.bps != null && connection.isDetected ->
                            stringResource(R.string.quality_connection_detected, bitrateLabel(connection.bps!!.toInt()))
                        connection?.bps != null ->
                            stringResource(R.string.quality_connection_estimated, bitrateLabel(connection.bps!!.toInt()))
                        isMeasuring -> stringResource(R.string.quality_connection_measuring)
                        else -> null
                    }
                    if (connectionText != null) {
                        Text(
                            connectionText,
                            fontSize = 9.sp,
                            color = LabelColor.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))

                rows.forEachIndexed { i, row ->
                    QualityListRow(
                        row = row,
                        isSelected = row.selection == mode,
                        focusRequester = frs[i],
                        onClick = {
                            onSelect(row.selection)
                            onDismiss()
                        },
                    )
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))

                // Footer: playback mode indicator for the active selection
                val activeDirect = when (mode) {
                    QualitySelection.Auto ->
                        recommendation?.let { rec ->
                            (rec.startSelection == QualitySelection.Original) &&
                                rec.options.firstOrNull()?.directPlay == true
                        } ?: true
                    QualitySelection.Original -> true
                    is QualitySelection.Rung -> false
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                if (activeDirect) AccentGreen else Color(0xFF555555),
                                CircleShape,
                            ),
                    )
                    Text(
                        if (activeDirect) {
                            stringResource(R.string.quality_direct_native)
                        } else {
                            stringResource(R.string.quality_transcoding)
                        },
                        fontSize = 9.sp,
                        color = LabelColor,
                    )
                }
            }
        }
    }
}
