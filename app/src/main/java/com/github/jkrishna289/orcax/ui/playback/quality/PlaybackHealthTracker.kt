package com.github.jkrishna289.orcax.ui.playback.quality

import java.util.ArrayDeque

/**
 * Session-local playback health bookkeeping. Telemetry + starvation verdicts
 * only — it NEVER switches anything itself; the rescue path in QualityManager
 * acts on its verdicts.
 *
 * Guard windows (the false-starve bug classes from docs/quality-audit.md
 * #3/#4/#6 that the reactive rescue design resurrects):
 *  - post-seek grace: rebuffering right after a seek is normal
 *  - post-rebuild grace: the buffer must prime after any stream (re)build
 *  - switching: never count while a stream switch is in progress
 *  - end-of-item: the forward buffer naturally drains near the end
 *
 * Clock injectable for tests.
 */
class PlaybackHealthTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Verdict { IGNORED, COUNTED, RESCUE }

    private var startedAtMs = 0L
    private var lastSeekAtMs = 0L
    private var lastRebuildAtMs = 0L
    private val countedRebuffers = ArrayDeque<Long>()

    /** Telemetry (debug overlay). */
    var totalRebuffers = 0
        private set

    fun reset() {
        startedAtMs = 0L
        lastSeekAtMs = 0L
        lastRebuildAtMs = 0L
        countedRebuffers.clear()
        totalRebuffers = 0
    }

    fun onPlaybackStarted() {
        val now = nowMs()
        if (startedAtMs == 0L) startedAtMs = now
        // First frame after any (re)build — buffer needs to prime before
        // starvation counts again.
        lastRebuildAtMs = now
    }

    fun onSeek() {
        lastSeekAtMs = nowMs()
    }

    fun onStreamRebuilt() {
        lastRebuildAtMs = nowMs()
    }

    /**
     * A rebuffer started (STATE_BUFFERING while playWhenReady, after playback
     * had begun). Returns RESCUE when starvation is real: ≥ [RESCUE_THRESHOLD]
     * counted rebuffers within [WINDOW_MS].
     */
    fun onRebuffer(positionMs: Long, durationMs: Long, isSwitchingStream: Boolean): Verdict {
        totalRebuffers++
        val now = nowMs()
        if (guarded(now, positionMs, durationMs, isSwitchingStream)) return Verdict.IGNORED
        countedRebuffers.addLast(now)
        while (countedRebuffers.isNotEmpty() && now - countedRebuffers.first() > WINDOW_MS) {
            countedRebuffers.removeFirst()
        }
        return if (countedRebuffers.size >= RESCUE_THRESHOLD) {
            countedRebuffers.clear()
            Verdict.RESCUE
        } else {
            Verdict.COUNTED
        }
    }

    /** A single stall exceeded [STALL_LIMIT_MS] — immediate rescue unless guarded. */
    fun onStallExceeded(positionMs: Long, durationMs: Long, isSwitchingStream: Boolean): Verdict {
        val now = nowMs()
        if (guarded(now, positionMs, durationMs, isSwitchingStream)) return Verdict.IGNORED
        countedRebuffers.clear()
        return Verdict.RESCUE
    }

    private fun guarded(
        now: Long,
        positionMs: Long,
        durationMs: Long,
        isSwitchingStream: Boolean,
    ): Boolean = when {
        startedAtMs == 0L -> true // never played yet — initial buffering
        isSwitchingStream -> true
        now - lastSeekAtMs < SEEK_GRACE_MS -> true
        now - lastRebuildAtMs < REBUILD_GRACE_MS -> true
        durationMs > 0 && durationMs - positionMs < END_GUARD_MS -> true
        else -> false
    }

    companion object {
        const val SEEK_GRACE_MS = 6_000L
        const val REBUILD_GRACE_MS = 8_000L
        const val END_GUARD_MS = 15_000L
        const val WINDOW_MS = 60_000L
        const val RESCUE_THRESHOLD = 2
        const val STALL_LIMIT_MS = 3_000L
    }
}
