package com.github.jkrishna289.orcax.ui.util

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Compose TV's row pivot (the focused card's leading edge at 30% of the row, or as far right as it
 * still fits), with a softer glide than the platform's medium spring, which snapped into place in
 * about 130 ms. The prototype eases rows over 0.35 s. One per row: it carries that row's
 * [pendingShiftPx].
 */
class RowPivotBringIntoViewSpec : BringIntoViewSpec {
    /**
     * Width a closing Discover panel left of the focused card still has to give back. The scroll
     * follows the card's live position, and the shrinking panel drags that position left as it
     * closes, so aiming at the live spot overshot and pulled back. Counting the width still to go
     * aims at where the card will rest, as the prototype does with its settled layout.
     */
    var pendingShiftPx = 0f

    // Deprecated, but still what the scroll node animates with: the only way to soften it.
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val settled = offset - pendingShiftPx
        val pivot = PIVOT_FRACTION * containerSize
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return settled - target
    }

    private companion object {
        const val PIVOT_FRACTION = 0.3f
    }
}
