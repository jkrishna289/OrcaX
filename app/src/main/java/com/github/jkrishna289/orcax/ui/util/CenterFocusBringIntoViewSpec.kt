package com.github.jkrishna289.orcax.ui.util

import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * A bring-into-view spec that scrolls the requested child toward the **vertical centre** of the viewport,
 * so a focused home row (and the card it enlarges for its trailer, plus the detail beneath it) has room
 * above and below instead of being pinned under the top nav and clipped at the bottom.
 *
 * The centred landing spot is floored at [topInsetPx] so a tall row never lands *under* a fixed top-nav
 * bar: short rows sit dead-centre; a row nearly as tall as the screen lands just below the bar (fully
 * visible, with the extra space falling below it).
 *
 * Like [ScrollToTopBringIntoViewSpec] — and the platform default — this leaves a child that is *already
 * fully visible* where it is. That guard is load-bearing: a focused row fires two bring-into-view requests
 * at the outer LazyColumn, the focus system's automatic one (which centres the enlarging **card**) and the
 * row's own explicit one (which centres the taller **row**). Those two centres disagree, so without a
 * "don't move when visible" guard the column oscillates between them and the row appears to vibrate. With
 * it, both requests report "already visible → 0" once the row is on screen and the scroll settles.
 */
class CenterFocusBringIntoViewSpec(
    val topInsetPx: Float = 0f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // Already fully on-screen below the reserved top inset? Leave it. Actively re-centring a child
        // that is already visible is what makes focused rows vibrate (see the class doc): the card-centred
        // and row-centred requests pull in opposite directions and, with nothing telling them to stop,
        // ping-pong forever. This mirrors the default spec, which the billboard + each row already rely on.
        if (offset >= topInsetPx - EDGE_TOLERANCE_PX &&
            offset + size <= containerSize + EDGE_TOLERANCE_PX
        ) {
            return 0f
        }

        // Otherwise pull the child toward centre, floored below the reserved top inset so a tall row lands
        // just under a fixed top-nav bar rather than beneath it. The LazyColumn bounds the actual scroll,
        // so first/last rows simply stop at the content edge.
        val target = ((containerSize - size) / 2f).coerceAtLeast(topInsetPx)
        val distance = offset - target
        // Ignore sub-pixel corrections so rounding on the measured edges (or the last frame of a card's
        // enlarge animation settling at the top inset) doesn't re-arm a scroll that never quite reaches 0.
        return if (abs(distance) <= EDGE_TOLERANCE_PX) 0f else distance
    }

    private companion object {
        /** Slack (px) absorbing rounding on the measured edges so it doesn't re-trigger a scroll. */
        const val EDGE_TOLERANCE_PX = 1f
    }
}
