package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.ui.main.telemetryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestTelemetryEvent {
    @Test
    fun `a library title is sent by its Jellyfin id`() {
        val event = telemetryEvent("5f1d2c3b4a5968778695a4b3c2d1e0f9", "CardImpression", 0.0)
        assertEquals("5f1d2c3b4a5968778695a4b3c2d1e0f9", event.itemId)
        assertNull(event.tmdbId)
    }

    @Test
    fun `a title outside the library is sent by its TMDB id`() {
        // Without this the engine could never tell such a card was focused or clicked, and once
        // impressions are reported it would look shown-but-ignored and sink.
        val event = telemetryEvent("tmdb:93352", "CardFocused", 4.0)
        assertEquals("00000000-0000-0000-0000-000000000000", event.itemId)
        assertEquals(93352, event.tmdbId)
        assertEquals(4.0, event.value, 0.0)
    }
}
