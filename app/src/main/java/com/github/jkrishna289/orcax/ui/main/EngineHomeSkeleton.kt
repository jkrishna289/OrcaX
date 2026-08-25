package com.github.jkrishna289.orcax.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.cards.ItemRowTitle

/**
 * The pulsing block every skeleton surface paints with. Rigid, linear pulse only (per the design
 * language) — no spring/bounce.
 */
@Composable
private fun skeletonBlock(): Color {
    val transition = rememberInfiniteTransition(label = "engine-home-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.20f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "skeleton-alpha",
    )
    return Color.White.copy(alpha = alpha)
}

/** A billboard-sized placeholder, held until the spotlight heroes resolve. Caller sets the height. */
@Composable
fun BillboardSkeleton(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().background(skeletonBlock()))
}

/**
 * A placeholder for a row whose items haven't arrived yet. It carries the row's REAL title and
 * mirrors [com.github.jkrishna289.orcax.ui.cards.ItemRow]'s metrics, so when the content lands it
 * fills the space already reserved for it instead of shifting the rows around it.
 */
@Composable
fun SkeletonRow(
    title: String,
    modifier: Modifier = Modifier,
) {
    val block = skeletonBlock()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ItemRowTitle(title)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        ) {
            repeat(SKELETON_CARDS) {
                Box(
                    modifier =
                        Modifier
                            .height(Cards.height2x3)
                            .width(Cards.height2x3 * AspectRatios.TALL)
                            .clip(RoundedCornerShape(8.dp))
                            .background(block),
                )
            }
        }
    }
}

/**
 * The whole-page placeholder for [EngineHomePage]: a billboard-sized block plus a couple of shimmer
 * rows. Shown only until the FIRST row arrives (about a second) — from there the page renders real
 * rows with [SkeletonRow] standing in for the ones still loading.
 */
@Composable
fun EngineHomeSkeleton(modifier: Modifier = Modifier) {
    // Focus anchor while loading: D-pad input always has a (non-interactive) home, so a press
    // during the skeleton can't land somewhere arbitrary. The real home takes focus on success.
    val anchor = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { anchor.requestFocus() } }

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(anchor)
                .focusable(),
    ) {
        BillboardSkeleton(modifier = Modifier.fillMaxHeight(0.62f))
        repeat(2) { SkeletonRow(title = "") }
    }
}

/** Cards a placeholder row shows — enough to fill a 1080p row edge to edge. */
private const val SKELETON_CARDS = 6
