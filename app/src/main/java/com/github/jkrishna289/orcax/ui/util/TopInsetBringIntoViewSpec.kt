package com.github.jkrishna289.orcax.ui.util

import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * A bring-into-view spec that reserves a top inset (e.g. a fixed top-nav bar) so focused items land
 * *below* it — but, unlike [ScrollToTopBringIntoViewSpec], it returns **0 when the item is already
 * fully visible** within `[topInsetPx, containerSize]`. That guard is what keeps a full-bleed first
 * item (the billboard) from being dragged upward when its button auto-focuses, while still pushing
 * scrolled-up row cards clear of the bar (#8 follow-up).
 */
class TopInsetBringIntoViewSpec(
    val topInsetPx: Float = 0f,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val leadingEdge = offset
        val trailingEdge = offset + size
        // Already fully visible within the inset viewport → don't move (fixes the half-shown billboard).
        return if (leadingEdge >= topInsetPx && trailingEdge <= containerSize) {
            0f
        } else {
            // Otherwise align the item's leading edge just below the inset (consistent landing spot).
            leadingEdge - topInsetPx
        }
    }
}
