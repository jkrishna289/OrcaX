package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cinematic depth wheel rendered AROUND the SelectorChip.
 *
 * The chip IS the visual centre and renders the CONFIRMED selection.
 * Items with index < confirmedIndex appear ABOVE the chip; index > confirmedIndex appear BELOW.
 * Visual depth (scale, alpha, blur) is based on distance from confirmedIndex.
 * The `tempIndex` shows the PREVIEW highlight.
 */
@Composable
fun RouletteColumn(
    items: List<RouletteItem>,
    confirmedIndex: Int,
    tempIndex: Int,
    isExpanded: Boolean = true,
    chipHeightDp: Dp = 38.dp,
    modifier: Modifier = Modifier
) {
    val ITEM_SPACING = 5.dp
    val CHIP_GAP     = PlaybackDimens.RouletteChipGap

    // Items above chip (closest first after reversed)
    val aboveItems = (0 until confirmedIndex).reversed().map { items[it] to it }
    // Items below chip (closest first)
    val belowItems = ((confirmedIndex + 1) until items.size).map { items[it] to it }

    // Max 2 visible on each side for cinematic depth
    val visibleAbove = aboveItems.take(2)
    val visibleBelow = belowItems.take(2)

    val expansionProgress by animateFloatAsState(
        targetValue   = if (isExpanded) 1f else 0f,
        animationSpec = if (isExpanded)
            spring(dampingRatio = 0.72f, stiffness = 300f)
        else
            tween(200, easing = FastOutSlowInEasing),
        label = "roulette_expand"
    )

    if (expansionProgress <= 0f) return

    Column(
        modifier            = modifier.graphicsLayer { alpha = expansionProgress },
        horizontalAlignment = Alignment.End
    ) {
        // ABOVE CHIP — render furthest first (top to bottom)
        visibleAbove.reversed().forEachIndexed { displayIdx, (item, itemIndex) ->
            val distanceFromChip = visibleAbove.size - displayIdx
            RouletteRow(
                item              = item,
                isPreviewed       = itemIndex == tempIndex,
                distanceFromChip  = distanceFromChip,
                expansionProgress = expansionProgress
            )
            Spacer(Modifier.height(ITEM_SPACING))
        }

        // Gap to chip top edge
        Spacer(Modifier.height(CHIP_GAP))

        // [CHIP SITS HERE — rendered by SelectorChip in the parent layout]

        // Gap below chip + chip height
        Spacer(Modifier.height(chipHeightDp + CHIP_GAP))

        // BELOW CHIP
        visibleBelow.forEachIndexed { displayIdx, (item, itemIndex) ->
            val distanceFromChip = displayIdx + 1
            if (displayIdx > 0) Spacer(Modifier.height(ITEM_SPACING))
            RouletteRow(
                item              = item,
                isPreviewed       = itemIndex == tempIndex,
                distanceFromChip  = distanceFromChip,
                expansionProgress = expansionProgress
            )
        }
    }
}

@Composable
private fun RouletteRow(
    item: RouletteItem,
    isPreviewed: Boolean,
    distanceFromChip: Int,
    expansionProgress: Float
) {
    // Depth: further = smaller, dimmer, softer
    val depthScale = 1f - (distanceFromChip - 1) * 0.08f
    val depthAlpha = 1f - (distanceFromChip - 1) * 0.28f
    val blurRadius = (distanceFromChip - 1) * 1.2f

    val rowScale by animateFloatAsState(
        targetValue   = depthScale * expansionProgress,
        animationSpec = spring(0.72f, 350f),
        label         = "row_scale_$distanceFromChip"
    )
    val rowAlpha by animateFloatAsState(
        targetValue   = depthAlpha * expansionProgress,
        animationSpec = tween(180),
        label         = "row_alpha_$distanceFromChip"
    )

    Box(
        modifier = Modifier
            .height(46.dp)
            .widthIn(min = 180.dp, max = 240.dp)
            .graphicsLayer {
                scaleX = rowScale
                scaleY = rowScale
                alpha  = rowAlpha
                if (blurRadius > 0f && android.os.Build.VERSION.SDK_INT >= 31) {
                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                        blurRadius, blurRadius,
                        android.graphics.Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (isPreviewed) 0.18f else 0.07f))
            .border(
                1.dp,
                Color.White.copy(alpha = if (isPreviewed) 0.28f else 0.08f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text       = item.title,
                fontSize   = 13.sp,
                fontWeight = if (isPreviewed) FontWeight.SemiBold else FontWeight.Normal,
                color      = Color.White.copy(alpha = if (isPreviewed) 1f else 0.78f),
                maxLines   = 1,
                softWrap   = false,
                overflow   = TextOverflow.Ellipsis
            )
            if (item.meta.isNotEmpty()) {
                Text(
                    text     = item.meta,
                    fontSize = 10.sp,
                    color    = Color.White.copy(alpha = 0.45f),
                    maxLines = 1
                )
            }
        }
    }
}
