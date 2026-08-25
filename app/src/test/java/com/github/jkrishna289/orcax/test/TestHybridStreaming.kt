package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.services.torrent.StreamWatchTracker
import com.github.jkrishna289.orcax.ui.detail.discover.sourceChips
import com.github.jkrishna289.orcax.ui.detail.discover.sourceTypeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stream picker prints every fact it can and invents none.
 *
 * Source type is the one chip derived rather than read from a field, so it is the one that can be
 * silently wrong — a WEB-DL labelled BluRay looks authoritative and isn't.
 */
class TestSourceTypeParsing {
    @Test
    fun `bluray remux keeps both the base type and the modifier`() {
        assertEquals(
            "BluRay REMUX",
            sourceTypeOf("Vantage.Point.II.2026.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FraMeSToR"),
        )
    }

    @Test
    fun `a web remux is not promoted to bluray`() {
        assertEquals("WEB-DL REMUX", sourceTypeOf("Some.Film.2026.2160p.WEB-DL.REMUX.DV.HDR.H.265-FLUX"))
    }

    @Test
    fun `web-dl is matched before the looser webrip pattern`() {
        assertEquals("WEB-DL", sourceTypeOf("Vantage.Point.II.2026.1080p.WEB-DL.DDP5.1.H.264-NTb"))
    }

    @Test
    fun `webrip stays a rip`() {
        assertEquals("WEBRip", sourceTypeOf("Vantage.Point.II.2026.720p.WEBRip.x265.AAC2.0-YTS.MX"))
    }

    @Test
    fun `plain bluray has no modifier`() {
        assertEquals("BluRay", sourceTypeOf("Vantage.Point.II.2026.1080p.BluRay.x264.DTS-HD.MA.5.1-SWTYBLZ"))
    }

    @Test
    fun `an unrecognisable name gets no chip rather than an unknown one`() {
        assertNull(sourceTypeOf("Vantage.Point.II.2026.1080p.x264-NOGRP"))
    }

    @Test
    fun `chips carry every fact the engine supplied and nothing it did not`() {
        val chips =
            sourceChips(
                TorrentSourceDto(
                    title = "Vantage.Point.II.2026.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX",
                    videoCodec = "H.265",
                    dolbyVision = true,
                    hdr = true,
                    audio = "DDP 5.1 Atmos",
                    releaseGroup = "FLUX",
                ),
            )
        assertEquals(listOf("H.265", "Dolby Vision", "DDP 5.1 Atmos", "WEB-DL", "FLUX"), chips)
    }

    @Test
    fun `dolby vision wins over plain hdr so the row never claims both`() {
        val chips = sourceChips(TorrentSourceDto(title = "x", dolbyVision = true, hdr = true))
        assertEquals(listOf("Dolby Vision"), chips)
    }

    @Test
    fun `missing fields are left out, not filled in`() {
        assertEquals(emptyList<String>(), sourceChips(TorrentSourceDto(title = "no.markers.here")))
    }
}

/**
 * The Keep prompt must be asked once and never again. Both halves of that matter: a prompt that
 * reappears on every visit to the page is worse than one that never appears at all.
 */
class TestStreamWatchTracker {
    @Test
    fun `nothing is offered until a stream is actually launched`() {
        assertNull(StreamWatchTracker().consume())
    }

    @Test
    fun `the position the player reported survives exactly one trip home`() {
        val tracker = StreamWatchTracker()
        tracker.begin("Vantage Point II", token = "a1b2c3")
        tracker.report(5_952_000)

        val watch = tracker.consume()
        assertNotNull(watch)
        assertEquals("Vantage Point II", watch!!.title)
        assertEquals(5_952_000, watch.positionMs)
        // Keeping is an instruction to the session the film streamed from, so the token has to come
        // home with the position — without it there is nothing to tell the engine to finish.
        assertEquals("a1b2c3", watch.token)

        assertNull("revisiting the page must not ask again", tracker.consume())
    }

    @Test
    fun `an answered title is never asked about again`() {
        val tracker = StreamWatchTracker()
        tracker.begin("Vantage Point II", token = "a1b2c3")
        tracker.report(5_952_000)
        tracker.consume()
        tracker.markAnswered("Vantage Point II")

        tracker.begin("Vantage Point II", token = "d4e5f6")
        tracker.report(6_000_000)
        assertNull(tracker.consume())
    }

    @Test
    fun `answering one title does not silence another`() {
        val tracker = StreamWatchTracker()
        tracker.markAnswered("Vantage Point II")

        tracker.begin("Silent Current", token = "a1b2c3")
        tracker.report(1_000_000)
        assertNotNull(tracker.consume())
    }

    @Test
    fun `a report with nothing armed is ignored rather than inventing a watch`() {
        val tracker = StreamWatchTracker()
        tracker.report(5_952_000)
        assertNull(tracker.consume())
    }
}
