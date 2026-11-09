package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.ui.main.heroTitleParts
import org.junit.Assert.assertEquals
import org.junit.Test

class TestHeroTitle {
    @Test
    fun `a title with a colon splits after it`() {
        assertEquals("Law & Order:" to "Organized Crime", heroTitleParts("Law & Order: Organized Crime"))
    }

    @Test
    fun `a plain title splits before its last word`() {
        assertEquals("Project" to "Loki", heroTitleParts("Project Loki"))
    }

    @Test
    fun `a one-word title is all main line`() {
        assertEquals(null to "Bloodhounds", heroTitleParts("  Bloodhounds "))
    }

    @Test
    fun `a trailing colon is not a split point`() {
        assertEquals("Wait" to "What:", heroTitleParts("Wait What:"))
    }
}
