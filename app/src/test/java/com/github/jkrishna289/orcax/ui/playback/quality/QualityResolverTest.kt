package com.github.jkrishna289.orcax.ui.playback.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure resolver math — no mocks needed. Bug refs: docs/quality-audit.md.
 */
class QualityResolverTest {
    private val now = 1_000_000_000_000L

    private fun measurement(
        mbps: Double,
        source: MeasurementSource = MeasurementSource.BITRATE_TEST,
        ageMs: Long = 0L,
    ) = BandwidthMeasurement((mbps * 1_000_000).toLong(), source, now - ageMs)

    private fun media(
        videoMbps: Double,
        audioBps: Int = 448_000,
        height: Int = 2160,
        audioCodec: String? = "aac",
    ) = QualityResolver.MediaQualityProfile(
        videoBitrateBps = (videoMbps * 1_000_000).toInt(),
        audioBitrateBps = audioBps,
        width = 3840,
        height = height,
        audioCodec = audioCodec,
    )

    private fun resolve(
        measurement: BandwidthMeasurement?,
        media: QualityResolver.MediaQualityProfile,
        device: QualityResolver.DeviceQualityCaps = QualityResolver.DeviceQualityCaps(),
        burnIn: Boolean = false,
    ) = QualityResolver.resolve(measurement, media, device, burnIn, nowMs = now)

    // ── Core recommendation math ─────────────────────────────────────────────

    @Test
    fun `slow connection with fast movie recommends a rung and pre-empts the start`() {
        val rec = resolve(measurement(10.0), media(20.0))
        // safe = 7.5 Mbps, required = 24.54 Mbps → blocked; target = 6.75 → R4
        assertEquals(QualitySelection.Rung(QualityRung.R4), rec.recommended)
        assertFalse(rec.originalVerdict.playable)
        // BITRATE_TEST fresh = confidence 80 ≥ 70 → warm-cache pre-empt applies
        assertEquals(rec.recommended, rec.startSelection)
    }

    @Test
    fun `fast connection with slow movie recommends Original`() {
        val rec = resolve(measurement(100.0), media(15.0))
        assertEquals(QualitySelection.Original, rec.recommended)
        assertEquals(QualitySelection.Original, rec.startSelection)
        assertTrue(rec.originalVerdict.playable)
        assertFalse(rec.originalVerdict.close)
    }

    @Test
    fun `deficit math carries the real numbers`() {
        val rec = resolve(measurement(10.0), media(20.0))
        assertEquals(24_537_600L, rec.originalVerdict.requiredBps)
        assertEquals(7_500_000L, rec.originalVerdict.safeBps)
        assertEquals(17_037_600L, rec.originalVerdict.deficitBps)
        assertTrue(rec.reasons.any { it is QualityReason.BandwidthShort })
    }

    @Test
    fun `peak-close case offers Original with a close verdict`() {
        val rec = resolve(measurement(35.0), media(20.0, audioBps = 800_000))
        // required = 24.96 Mbps, safe = 26.25 Mbps → playable but close
        assertEquals(QualitySelection.Original, rec.recommended)
        assertTrue(rec.originalVerdict.playable)
        assertTrue(rec.originalVerdict.close)
        assertNull(rec.originalVerdict.deficitBps)
    }

    // ── Subtitle burn-in modifier (audit #11) ────────────────────────────────

    @Test
    fun `PGS burn-in decreases the recommendation and restores when disabled`() {
        val m = measurement(20.0)
        val movie = media(12.0)
        // Without burn-in: safe 15 ≥ required 14.94 → Original
        assertEquals(QualitySelection.Original, resolve(m, movie).recommended)
        // With burn-in: safe 10.5 < 14.94 → target 9.45 → R8
        val penalized = resolve(m, movie, burnIn = true)
        assertEquals(QualitySelection.Rung(QualityRung.R8), penalized.recommended)
        assertTrue(penalized.subtitlePenaltyApplied)
        // Recomputing without the flag restores automatically — no one-way mutation
        assertEquals(QualitySelection.Original, resolve(m, movie).recommended)
    }

    // ── Audio compatibility (audit #13/#14) ──────────────────────────────────

    @Test
    fun `known-unsupported source audio blocks Original and recommends re-encoded rung`() {
        val device = QualityResolver.DeviceQualityCaps(
            knownUnsupportedAudioCodecs = setOf("truehd"),
        )
        val rec = resolve(
            measurement(100.0),
            media(15.0, audioBps = 3_000_000, audioCodec = "truehd"),
            device,
        )
        val original = rec.options.first { it.selection == QualitySelection.Original }
        assertFalse(original.available)
        assertFalse(original.directPlay)
        // Highest available rung (< 18 Mbps media) = R12 — near-original video, re-encoded audio
        assertEquals(QualitySelection.Rung(QualityRung.R12), rec.recommended)
        // Silent-playback guard: start reduced regardless of bandwidth
        assertEquals(rec.recommended, rec.startSelection)
        assertTrue(rec.reasons.any { it is QualityReason.AudioIncompatible })
        assertFalse(rec.audioStreamCopySafe)
    }

    @Test
    fun `unknown audio codec does not block Original but blocks stream-copy`() {
        val rec = resolve(measurement(100.0), media(15.0, audioCodec = "dts"))
        assertTrue(rec.options.first { it.selection == QualitySelection.Original }.available)
        assertFalse(rec.audioStreamCopySafe)
        val rungConstraints = rec.constraintsFor(QualitySelection.Rung(QualityRung.R8))
        assertFalse(rungConstraints.audioStreamCopyAllowed) // transcode re-encodes audio
    }

    @Test
    fun `known-supported audio allows stream-copy while transcoding`() {
        val device = QualityResolver.DeviceQualityCaps(knownSupportedAudioCodecs = setOf("aac"))
        val rec = resolve(measurement(10.0), media(20.0, audioCodec = "aac"), device)
        assertTrue(rec.audioStreamCopySafe)
        assertTrue(rec.constraintsFor(rec.recommended).audioStreamCopyAllowed)
    }

    // ── Unknown / low-confidence connections ─────────────────────────────────

    @Test
    fun `unknown connection recommends Original optimistically`() {
        val rec = resolve(null, media(20.0))
        assertEquals(QualitySelection.Original, rec.recommended)
        assertEquals(QualitySelection.Original, rec.startSelection)
        assertTrue(rec.reasons.any { it is QualityReason.ConnectionUnknown })
        assertNull(rec.connection.bps)
    }

    @Test
    fun `low-confidence measurement never pre-empts the Original start`() {
        val rec = resolve(measurement(5.0, source = MeasurementSource.OS_LINK), media(20.0))
        // OS_LINK = 30: usable for the picker, not confident enough to pre-empt
        assertTrue(rec.recommended is QualitySelection.Rung)
        assertEquals(QualitySelection.Original, rec.startSelection)
    }

    @Test
    fun `stale measurement is treated as unknown`() {
        val rec = resolve(
            measurement(100.0, ageMs = 25 * 60_000L), // past the 20 min TTL
            media(20.0),
        )
        assertTrue(rec.reasons.any { it is QualityReason.ConnectionUnknown })
        assertEquals(QualitySelection.Original, rec.recommended)
    }

    // ── Ladder + data-per-hour ───────────────────────────────────────────────

    @Test
    fun `ladder only offers rungs below the media bitrate`() {
        val rec = resolve(measurement(100.0), media(20.0)) // total 20.448 Mbps
        val rungs = rec.options.mapNotNull { (it.selection as? QualitySelection.Rung)?.rung }
        assertFalse(QualityRung.R40 in rungs)
        assertTrue(QualityRung.R20 in rungs)
        assertTrue(QualityRung.R1_5 in rungs)
    }

    @Test
    fun `data saver rung is recommended on very slow networks`() {
        val rec = resolve(measurement(5.0), media(20.0))
        // safe = 3.75, target = 3.375 → R3 (the 3 Mbps data-saver ceiling)
        assertEquals(QualitySelection.Rung(QualityRung.R3), rec.recommended)
    }

    @Test
    fun `estimated data per hour is bitrate over 8 times 3600`() {
        val rec = resolve(measurement(100.0), media(20.0))
        val r12 = rec.options.first { it.selection == QualitySelection.Rung(QualityRung.R12) }
        assertEquals(12_000_000L * 450, r12.estimatedBytesPerHour) // 5.4 GB/h
        // Original's data rate uses the MEDIA bitrate, never the (uncapped) constraint
        val original = rec.options.first { it.selection == QualitySelection.Original }
        assertEquals(20_448_000L * 450, original.estimatedBytesPerHour)
    }

    // ── Rescue targets (one-way ratchet) ─────────────────────────────────────

    @Test
    fun `rescue prefers the recommended rung when strictly below current`() {
        val rec = resolve(measurement(10.0), media(20.0)) // recommends R4
        assertEquals(QualityRung.R4, QualityResolver.rescueTarget(rec, Int.MAX_VALUE))
    }

    @Test
    fun `rescue falls back to one rung below current`() {
        val rec = resolve(measurement(100.0), media(20.0)) // recommends Original
        assertEquals(
            QualityRung.R3,
            QualityResolver.rescueTarget(rec, QualityRung.R4.maxBitrateBps),
        )
    }

    @Test
    fun `rescue at the ladder bottom yields null`() {
        assertNull(QualityResolver.rescueTarget(null, QualityRung.R1_5.maxBitrateBps))
    }

    @Test
    fun `rescue never moves upward`() {
        val rec = resolve(measurement(10.0), media(20.0)) // recommends R4
        val target = QualityResolver.rescueTarget(rec, QualityRung.R3.maxBitrateBps)
        // Recommendation (R4) is ABOVE current R3 → must pick below R3 instead
        assertEquals(QualityRung.R1_5, target)
    }
}
