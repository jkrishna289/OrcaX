package com.github.jkrishna289.orcax.ui.playback.quality

/** Where a bandwidth number came from — determines how much we trust it. */
enum class MeasurementSource(val baseConfidence: Int) {
    /**
     * ExoPlayer transfer estimate observed during DIRECT play/stream.
     * Never recorded during transcodes: transcode segment throughput is capped
     * by the server's transcode speed (and Jellyfin's throttler), not the network.
     */
    EXO_DIRECT(100),

    /** Dedicated /Playback/BitrateTest probe against the server. */
    BITRATE_TEST(80),

    /** OS-reported link capacity — coarse, and says nothing about the server path. */
    OS_LINK(30),
}

/**
 * One bandwidth observation. Confidence decays with age so a stale probe
 * eventually loses to a fresh lower-confidence signal.
 */
data class BandwidthMeasurement(
    val bps: Long,
    val source: MeasurementSource,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** Full confidence for the first [FRESH_MS], then linear decay to zero at [TTL_MS]. */
    fun confidenceAt(nowMs: Long = System.currentTimeMillis()): Int {
        val age = nowMs - timestampMs
        return when {
            age <= FRESH_MS -> source.baseConfidence
            age >= TTL_MS -> 0
            else -> (source.baseConfidence * (TTL_MS - age) / (TTL_MS - FRESH_MS)).toInt()
        }
    }

    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - timestampMs < TTL_MS

    /** True when this measurement is trustworthy enough to base a start decision on. */
    fun isHighConfidence(nowMs: Long = System.currentTimeMillis()): Boolean =
        confidenceAt(nowMs) >= HIGH_CONFIDENCE

    companion object {
        const val FRESH_MS = 5 * 60_000L
        const val TTL_MS = 20 * 60_000L

        /** Below this the connection is treated as UNKNOWN rather than given a fake number. */
        const val USABLE_CONFIDENCE = 30

        /** At or above this the UI may say "detected" instead of "estimated". */
        const val HIGH_CONFIDENCE = 70
    }
}
