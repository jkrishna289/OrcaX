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

/**
 * A lightweight loading placeholder for [EngineHomePage]: a billboard-sized block plus a couple of
 * shimmer rows, so launch doesn't flash an empty screen before the engine bundle arrives. Rigid,
 * linear pulse only (per the design language) — no spring/bounce.
 */
@Composable
fun EngineHomeSkeleton(modifier: Modifier = Modifier) {
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
    val block = Color.White.copy(alpha = alpha)

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
        // Spotlight placeholder (roughly the billboard's footprint).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .background(block),
        )

        repeat(2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(start = 48.dp),
            ) {
                repeat(6) {
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
}
