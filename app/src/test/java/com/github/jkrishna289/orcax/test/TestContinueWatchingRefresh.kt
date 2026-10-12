package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.MediaId
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.ui.main.replaceContinueWatchingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TestContinueWatchingRefresh {
    private val discovery = RenderRow(id = "discover", title = "Discover")
    private val recent = RenderRow(id = "recent", title = "Recently added")

    @Test
    fun `returning from playback updates progress while preserving other rows and order`() {
        val previous = continueRow(progress = 0.1)
        val fresh = continueRow(progress = 0.4)
        val result =
            replaceContinueWatchingRow(
                listOf(discovery, previous, recent),
                listOf(fresh, discovery.copy(title = "New recommendations")),
            )

        assertEquals(listOf("discover", "continue", "recent"), result.map { it.id })
        assertEquals(0.4, result[1].items.single().card.progress!!, 0.0001)
        assertSame(discovery, result[0])
        assertSame(recent, result[2])
    }

    @Test
    fun `the first streamed movie creates Continue Watching and completion removes it`() {
        val fresh = continueRow(progress = 0.4)
        val withProgress = replaceContinueWatchingRow(listOf(discovery, recent), listOf(fresh, discovery))
        assertEquals(listOf("continue", "discover", "recent"), withProgress.map { it.id })

        val completed = replaceContinueWatchingRow(withProgress, listOf(discovery))
        assertEquals(listOf(discovery, recent), completed)
    }

    private fun continueRow(progress: Double): RenderRow =
        RenderRow(
            id = "continue",
            items = listOf(RenderItem(media = MediaId(tmdbId = 45745), card = CardDescriptor(progress = progress))),
        )
}
