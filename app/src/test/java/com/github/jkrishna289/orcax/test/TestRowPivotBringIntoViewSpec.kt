package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.ui.util.RowPivotBringIntoViewSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class TestRowPivotBringIntoViewSpec {
    private val spec = RowPivotBringIntoViewSpec()

    private fun distance(
        offset: Float,
        size: Float,
    ) = spec.calculateScrollDistance(offset, size, 1000f)

    @Test
    fun `a card's leading edge pins at 30 percent of the row`() {
        assertEquals(500f, distance(offset = 800f, size = 140f), 0f)
        assertEquals(-200f, distance(offset = 100f, size = 140f), 0f)
    }

    @Test
    fun `a card plus its open panel too wide for the pivot lines up with the row's end instead`() {
        // 30% leaves 700 of room; an 800-wide card + panel ends flush with the right edge.
        assertEquals(400f, distance(offset = 600f, size = 800f), 0f)
    }

    @Test
    fun `a closing panel to the card's left is counted as already gone`() {
        // The card sits at 800 now but will rest at 484 once the 316-wide panel has closed.
        spec.pendingShiftPx = 316f
        assertEquals(184f, distance(offset = 800f, size = 140f), 0f)
    }
}
