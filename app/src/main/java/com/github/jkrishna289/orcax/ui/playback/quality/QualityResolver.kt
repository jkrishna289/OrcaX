package com.github.jkrishna289.orcax.ui.playback.quality

/**
 * Pure, deterministic quality math. No player, network, coroutine, or Android
 * calls — fully unit-testable with plain values.
 *
 * Math:
 *  - safe bandwidth   = measured × 0.75 (the single hedge — audit #25)
 *  - required (orig)  = (video + audio bitrate) × 1.2 peak headroom (audit #12)
 *  - subtitle burn-in = ×0.7 modifier on safe bandwidth, applied at resolve time
 *    so it restores automatically when recomputed without the flag (audit #11)
 *  - audio compat     = a source codec KNOWN unsupported blocks Original and the
 *    recommendation carries re-encoded audio (audit #13); stream-copy while
 *    transcoding requires KNOWN support (audit #14)
 */
object QualityResolver {
    const val SAFETY_FACTOR = 0.75
    const val PEAK_HEADROOM = 1.2
    const val SUBTITLE_BURN_IN_FACTOR = 0.7
    const val RECOMMEND_FRACTION = 0.9
    const val CLOSE_MARGIN = 1.25

    /** Assumed audio bitrate when the container doesn't report one. */
    const val NOMINAL_AUDIO_BPS = 448_000

    data class MediaQualityProfile(
        val videoBitrateBps: Int,
        val audioBitrateBps: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val isHdr: Boolean = false,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
    )

    /**
     * Explicit knowledge only, both ways: a codec in neither set is UNKNOWN —
     * it does not block Original (don't break TrueHD direct play on capable
     * devices) but does block stream-copy while transcoding (conservative).
     */
    data class DeviceQualityCaps(
        val knownSupportedAudioCodecs: Set<String> = emptySet(),
        val knownUnsupportedAudioCodecs: Set<String> = emptySet(),
    )

    fun resolve(
        measurement: BandwidthMeasurement?,
        media: MediaQualityProfile,
        device: DeviceQualityCaps = DeviceQualityCaps(),
        subtitleBurnInRisk: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): QualityRecommendation {
        val mediaKnown = media.videoBitrateBps > 0
        val audioBps = media.audioBitrateBps.takeIf { it > 0 } ?: NOMINAL_AUDIO_BPS
        val totalBps = if (mediaKnown) media.videoBitrateBps + audioBps else 0
        val requiredBps = (totalBps * PEAK_HEADROOM).toLong()

        val usable = measurement?.takeIf {
            it.confidenceAt(nowMs) >= BandwidthMeasurement.USABLE_CONFIDENCE
        }
        val safeBps = usable?.let { (it.bps * SAFETY_FACTOR).toLong() }
        val safeEffective = safeBps?.let {
            if (subtitleBurnInRisk) (it * SUBTITLE_BURN_IN_FACTOR).toLong() else it
        }

        val audioCodec = media.audioCodec?.lowercase()
        val audioBlocked = audioCodec != null && audioCodec in device.knownUnsupportedAudioCodecs
        val audioCopySafe = audioCodec != null && audioCodec in device.knownSupportedAudioCodecs

        val bandwidthBlocksOriginal =
            mediaKnown && safeEffective != null && requiredBps > safeEffective
        val originalPlayable = !audioBlocked && !bandwidthBlocksOriginal
        val close = originalPlayable && mediaKnown && safeEffective != null &&
            safeEffective < (requiredBps * CLOSE_MARGIN).toLong()

        val verdict = OriginalVerdict(
            playable = originalPlayable,
            close = close,
            requiredBps = requiredBps,
            safeBps = safeEffective,
        )

        // ── Ladder ────────────────────────────────────────────────────────────
        val availableRungs = QualityRung.entries.filter { !mediaKnown || it.maxBitrateBps < totalBps }
        val options = buildList {
            add(
                QualityOption(
                    selection = QualitySelection.Original,
                    bitrateBps = totalBps, // the MEDIA's real rate — never the cap
                    maxHeight = media.height,
                    directPlay = !audioBlocked,
                    estimatedBytesPerHour = if (mediaKnown) totalBps.toLong() * 450 else 0L,
                    available = !audioBlocked,
                ),
            )
            availableRungs.forEach { rung ->
                add(
                    QualityOption(
                        selection = QualitySelection.Rung(rung),
                        bitrateBps = rung.maxBitrateBps,
                        maxHeight = if (media.height in 1 until rung.maxHeight) media.height else rung.maxHeight,
                        directPlay = false,
                        estimatedBytesPerHour = rung.maxBitrateBps.toLong() * 450,
                        available = true,
                    ),
                )
            }
        }

        // ── Recommendation (percentage math only picks the rung) ─────────────
        val recommended: QualitySelection = when {
            audioBlocked ->
                // Bandwidth may be fine; the audio codec is the blocker. Recommend
                // the highest rung — near-original video with re-encoded audio.
                availableRungs.firstOrNull()?.let { QualitySelection.Rung(it) }
                    ?: QualitySelection.Original
            safeEffective == null -> QualitySelection.Original // unknown → optimistic
            !bandwidthBlocksOriginal -> QualitySelection.Original
            else -> {
                val target = (safeEffective * RECOMMEND_FRACTION).toLong()
                val rung = availableRungs.firstOrNull { it.maxBitrateBps <= target }
                    ?: availableRungs.lastOrNull()
                rung?.let { QualitySelection.Rung(it) } ?: QualitySelection.Original
            }
        }

        // ── Start decision (rulings 1 + 5) ────────────────────────────────────
        val startSelection: QualitySelection = when {
            audioBlocked && recommended is QualitySelection.Rung -> recommended
            bandwidthBlocksOriginal &&
                usable?.isHighConfidence(nowMs) == true &&
                recommended is QualitySelection.Rung -> recommended
            else -> QualitySelection.Original
        }

        val reasons = buildList {
            if (safeEffective == null) add(QualityReason.ConnectionUnknown)
            if (mediaKnown && safeEffective != null) {
                if (bandwidthBlocksOriginal) {
                    add(QualityReason.BandwidthShort(requiredBps, safeEffective))
                } else {
                    add(QualityReason.BandwidthFits(requiredBps, safeEffective))
                }
            }
            if (subtitleBurnInRisk) add(QualityReason.SubtitleBurnIn)
            if (audioBlocked) add(QualityReason.AudioIncompatible(audioCodec!!))
        }

        return QualityRecommendation(
            options = options,
            recommended = recommended,
            startSelection = startSelection,
            reasons = reasons,
            connection = ConnectionInfo(
                bps = usable?.bps,
                confidence = usable?.confidenceAt(nowMs) ?: 0,
                source = usable?.source,
                networkType = null, // stamped by the caller (needs NetworkAnalyzer env)
            ),
            originalVerdict = verdict,
            audioStreamCopySafe = audioCopySafe,
            subtitlePenaltyApplied = subtitleBurnInRisk && safeBps != null,
        )
    }

    /**
     * Rescue target after observed starvation: the recommendation's rung when it
     * is strictly below the current cap, else one rung below the current cap.
     * Always strictly downward (one-way ratchet), null at the ladder bottom.
     */
    fun rescueTarget(
        recommendation: QualityRecommendation?,
        currentMaxBitrateBps: Int,
    ): QualityRung? {
        val fromRecommendation = (recommendation?.recommended as? QualitySelection.Rung)?.rung
            ?.takeIf { it.maxBitrateBps < currentMaxBitrateBps }
        return fromRecommendation
            ?: QualityRung.entries.firstOrNull { it.maxBitrateBps < currentMaxBitrateBps }
    }
}
