package com.github.jkrishna289.orcax.ui.playback

/** Network environment detected at probe time — drives the safety factor. */
enum class NetworkType {
    /** Private IP (10/172/192 range) — safety_factor 0.90 */
    LAN,
    /** Public IP or cellular — safety_factor 0.65 */
    INTERNET,
    /** High probe-to-probe variance (>30%) — safety_factor 0.50 */
    UNSTABLE,
}

/**
 * Represents a stream quality tier selectable by the user.
 *
 * AUTO runs a real speed-test against the Jellyfin server then resolves
 * to the highest tier whose effective safe bitrate supports it.
 *
 * Safe bitrate = avgThroughput × safetyFactor(networkType) × 0.85
 * (Step 3 × Step 6 from the AUTO engine spec)
 *
 * maxBitrateBps is passed directly to PlaybackViewModel.changeStreams as the
 * maxBitrate cap (converted to Int as Jellyfin API expects).
 */
enum class QualityTier(
    val label: String,
    val description: String,
    val bitrateLabel: String,
    val maxBitrateBps: Int,
    val isDirectPlay: Boolean,
) {
    AUTO(
        label = "Auto",
        description = "Speed test · adaptive",
        bitrateLabel = "—",
        maxBitrateBps = Int.MAX_VALUE,
        isDirectPlay = true,
    ),
    CINEMA(
        label = "Direct Playback",
        description = "Original Bitrate · 4K HDR",
        bitrateLabel = "25.4 Mbps",
        maxBitrateBps = 25_400_000,
        isDirectPlay = true,
    ),
    BALANCED(
        label = "Balanced",
        description = "Optimised · 1080p",
        bitrateLabel = "8.2 Mbps",
        maxBitrateBps = 8_200_000,
        isDirectPlay = false,
    ),
    DATA_SAVER(
        label = "Data Saver",
        description = "Low Bandwidth · 720p",
        bitrateLabel = "2.5 Mbps",
        maxBitrateBps = 2_500_000,
        isDirectPlay = false,
    ),
    ;

    companion object {
        fun safetyFactor(networkType: NetworkType): Double = when (networkType) {
            NetworkType.LAN -> 0.90
            NetworkType.INTERNET -> 0.65
            NetworkType.UNSTABLE -> 0.50
        }

        /**
         * Given measured throughput in bps and the detected network type,
         * return the highest tier whose bitrate fits within the safe window.
         *
         * safeBitrate = measuredBps × safetyFactor × 0.85
         */
        fun fromMeasuredBps(
            measuredBps: Long,
            networkType: NetworkType = NetworkType.INTERNET,
        ): QualityTier {
            val safe = (measuredBps * safetyFactor(networkType) * 0.85).toLong()
            return listOf(CINEMA, BALANCED, DATA_SAVER)
                .firstOrNull { it.maxBitrateBps.toLong() <= safe }
                ?: DATA_SAVER
        }
    }
}