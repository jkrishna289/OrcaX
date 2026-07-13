package com.github.jkrishna289.orcax.ui.playback.quality

/**
 * Fixed quality ladder. Manual picks are rungs — deterministic across sessions
 * (a persisted rung means the same bitrate tomorrow). AUTO's percentage math is
 * only used to choose which rung to recommend or rescue to (hybrid model).
 * Ordered highest → lowest.
 */
enum class QualityRung(val maxBitrateBps: Int, val maxHeight: Int) {
    R40(40_000_000, 2160),
    R20(20_000_000, 1080),
    R12(12_000_000, 1080),
    R8(8_000_000, 1080),
    R4(4_000_000, 720),
    R3(3_000_000, 720),
    R1_5(1_500_000, 480),
    ;

    /** Next rung down the ladder, or null at the bottom. */
    fun below(): QualityRung? = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromName(name: String?): QualityRung? = entries.firstOrNull { it.name == name }
    }
}

/** What the user (or AUTO) has selected in the picker. */
sealed interface QualitySelection {
    data object Auto : QualitySelection

    data object Original : QualitySelection

    data class Rung(val rung: QualityRung) : QualitySelection

    /** Stable key for Room persistence; null = AUTO (not persisted). */
    fun persistKey(): String? = when (this) {
        Auto -> null
        Original -> PERSIST_ORIGINAL
        is Rung -> rung.name
    }

    companion object {
        const val PERSIST_ORIGINAL = "ORIGINAL"

        fun fromPersisted(value: String?): QualitySelection? = when {
            value == null -> null
            value == PERSIST_ORIGINAL -> Original
            else -> QualityRung.fromName(value)?.let { Rung(it) }
        }
    }
}

/**
 * The exact constraints the existing playback path consumes
 * (PlaybackViewModel.changeStreams → getPostedPlaybackInfo):
 * maxBitrateBps → maxStreamingBitrate, directPlayAllowed → enableDirectPlay,
 * audioStreamCopyAllowed → allowAudioStreamCopy. The user's global bitrate
 * pref is applied downstream (changeStreams takes the min), so an uncapped
 * Original here still lands at the user's allowance.
 */
data class PlaybackConstraints(
    val maxBitrateBps: Int,
    val directPlayAllowed: Boolean,
    val audioStreamCopyAllowed: Boolean,
) {
    companion object {
        val ORIGINAL = PlaybackConstraints(
            maxBitrateBps = Int.MAX_VALUE,
            directPlayAllowed = true,
            audioStreamCopyAllowed = true,
        )
    }
}

/** Connection info for the picker header. */
data class ConnectionInfo(
    val bps: Long?,
    val confidence: Int,
    val source: MeasurementSource?,
    val networkType: NetworkType?,
) {
    val isKnown: Boolean get() = bps != null
    val isDetected: Boolean get() = confidence >= BandwidthMeasurement.HIGH_CONFIDENCE
}

/**
 * Why (or why not) Original playback works, with the real numbers so the UI can
 * render "Original needs 28 Mbps · your safe bandwidth is 22 Mbps · short by 6".
 */
data class OriginalVerdict(
    val playable: Boolean,
    /** Playable, but within ~25% of the safe limit — "possible but close". */
    val close: Boolean,
    val requiredBps: Long,
    /** Safe (hedged) bandwidth; null when the connection is unknown. */
    val safeBps: Long?,
) {
    val deficitBps: Long? get() = safeBps?.let { (requiredBps - it).takeIf { d -> d > 0 } }
}

/** Structured reasons — the UI localizes them (strings.xml + fa_strings.xml). */
sealed interface QualityReason {
    data class BandwidthFits(val requiredBps: Long, val safeBps: Long) : QualityReason

    data class BandwidthShort(val requiredBps: Long, val safeBps: Long) : QualityReason

    data class AudioIncompatible(val codec: String) : QualityReason

    data object SubtitleBurnIn : QualityReason

    data object ConnectionUnknown : QualityReason
}

/** One row in the picker ladder. */
data class QualityOption(
    val selection: QualitySelection,
    /** Real number: for Original this is the MEDIA's bitrate, never the cap. */
    val bitrateBps: Int,
    val maxHeight: Int,
    val directPlay: Boolean,
    /** bitrate / 8 × 3600; 0 = unknown. */
    val estimatedBytesPerHour: Long,
    /** False when Original is blocked (e.g. incompatible audio would stream-copy). */
    val available: Boolean,
)

/**
 * Output of QualityResolver: the ladder with real numbers, the starred
 * recommendation, the start decision, and everything the picker needs to
 * explain itself.
 */
data class QualityRecommendation(
    /** Original first, then rungs below the media bitrate, highest → lowest. */
    val options: List<QualityOption>,
    /** What AUTO stars in the picker (Original or a Rung — never Auto). */
    val recommended: QualitySelection,
    /**
     * What AUTO starts playback at. Original unless a fresh HIGH-confidence
     * measurement already proves Original can't play (warm-cache pre-empt),
     * or the source audio is known-incompatible (silent-playback prevention).
     */
    val startSelection: QualitySelection,
    val reasons: List<QualityReason>,
    val connection: ConnectionInfo,
    val originalVerdict: OriginalVerdict,
    /** True when the source audio codec is known-supported → stream-copy is safe while transcoding. */
    val audioStreamCopySafe: Boolean,
    val subtitlePenaltyApplied: Boolean,
) {
    fun constraintsFor(selection: QualitySelection): PlaybackConstraints = when (selection) {
        QualitySelection.Auto -> constraintsFor(recommended)
        QualitySelection.Original -> PlaybackConstraints.ORIGINAL
        is QualitySelection.Rung -> PlaybackConstraints(
            maxBitrateBps = selection.rung.maxBitrateBps,
            directPlayAllowed = false,
            // Transcode must re-encode audio unless the codec is KNOWN supported —
            // the device profile can overclaim, producing silent playback.
            audioStreamCopyAllowed = audioStreamCopySafe,
        )
    }
}
