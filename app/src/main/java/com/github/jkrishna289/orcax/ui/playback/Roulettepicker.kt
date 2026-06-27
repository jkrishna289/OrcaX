package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Roulette-style picker — shows [visibleCount] items (default 3), center is selected.
 *
 * The selection highlight frame is drawn OUTSIDE the gradient-mask layer so it's
 * always fully visible regardless of its position in the scroll list.
 *
 * D-pad UP / DOWN → scroll (live visual only — apply on OK/Enter).
 * D-pad CENTER / ENTER → [onCenterPress] (confirm + dismiss or move column focus).
 * D-pad LEFT / RIGHT → passed to parent (cross-column navigation).
 */
@Composable
fun <T> RoulettePicker(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    itemHeight: Dp,
    width: Dp,
    modifier: Modifier = Modifier,
    visibleCount: Int = 3,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onCenterPress: () -> Unit = {},
    rowContent: @Composable (item: T, isSelected: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val centerSlot = visibleCount / 2

    // Translate so items[selectedIndex] sits in the centre row
    val animatedY by animateFloatAsState(
        targetValue = -selectedIndex * itemHeightPx,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "rouletteY",
    )

    // Track whether this picker has focus — only the FOCUSED column shows the
    // bright highlight frame. Inactive columns fall back to a faint outline.
    var isFocused by remember { mutableStateOf(false) }

    // Animated alpha values for the frame so the focus transition is smooth
    val frameBgAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.18f else 0.04f,
        label = "frameBg",
    )
    val frameBorderAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.90f else 0.10f,
        label = "frameBorder",
    )

    // Outer Box — no compositing effects — hosts the centre highlight + masked items
    Box(
        modifier = modifier
            .width(width)
            .height(itemHeight * visibleCount),
    ) {
        // ── Centre selection frame ───────────────────────────────────────────
        // The frame's brightness reflects focus state:
        //   • Focused  → strong white border + visible fill (clearly the active column)
        //   • Unfocused → faint outline only (still hints at selection but recedes)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = frameBgAlpha))
                .border(2.dp, Color.White.copy(alpha = frameBorderAlpha), RoundedCornerShape(14.dp)),
        )

        // ── Masked items layer ────────────────────────────────────────────────
        // Only the items get the top/bottom gradient fade — NOT the highlight frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.key) {
                        Key.DirectionUp -> {
                            val n = (selectedIndex - 1).coerceAtLeast(0)
                            if (n != selectedIndex) onSelectedIndexChange(n)
                            true
                        }
                        Key.DirectionDown -> {
                            val n = (selectedIndex + 1).coerceAtMost(items.size - 1)
                            if (n != selectedIndex) onSelectedIndexChange(n)
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            onCenterPress()
                            true
                        }
                        // Let LEFT/RIGHT propagate to parent for column switching
                        else -> false
                    }
                }
                // Gradient mask: fades top 30% and bottom 30% to transparent
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f   to Color.Black,
                                0.30f to Color.Transparent,
                                0.70f to Color.Transparent,
                                1f   to Color.Black,
                            ),
                        ),
                        blendMode = BlendMode.DstOut,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = animatedY },
            ) {
                // Top padding spacers — ensures first item can scroll to center
                repeat(centerSlot) {
                    Box(modifier = Modifier.fillMaxWidth().height(itemHeight))
                }
                items.forEachIndexed { i, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        rowContent(item, i == selectedIndex)
                    }
                }
                // Bottom padding spacers — ensures last item can scroll to center
                repeat(centerSlot) {
                    Box(modifier = Modifier.fillMaxWidth().height(itemHeight))
                }
            }
        }
    }
}