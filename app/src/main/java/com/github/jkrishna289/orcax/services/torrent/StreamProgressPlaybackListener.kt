package com.github.jkrishna289.orcax.services.torrent

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Samples on the player's main thread; the reporter sends immutable snapshots on IO. */
class StreamProgressPlaybackListener(
    private val player: Player,
    private val reporter: StreamProgressReporter,
    private val resumePositionMs: Long,
    private val resumeDurationMs: Long,
    private val onResumeApplied: () -> Unit,
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = player.isPlaying
    private var resumePending = resumePositionMs > 0
    private var durationMs = 0L
    private var positionMs = 0L
    private var ended = false
    private var released = false

    init {
        player.addListener(this)
        applyResume()
        scope.launch {
            while (isActive) {
                delay(5_000)
                capture()
            }
        }
    }

    private fun applyResume() {
        if (!resumePending || player.duration <= 0) return
        resumePending = false
        onResumeApplied()
        player.seekTo(resumePositionForDuration(resumePositionMs, resumeDurationMs, player.duration))
    }

    private fun capture(stopped: Boolean = false) {
        applyResume()
        // Preparing a player at zero must never erase a previous resume position or completed state.
        if (!started || resumePending) {
            if (stopped) reporter.close()
            return
        }
        if (player.duration > 0) durationMs = player.duration
        if (player.currentPosition >= 0) positionMs = player.currentPosition
        reporter.report(positionMs, durationMs, completed = ended, stopped = stopped)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = applyResume()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) started = true
        capture()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) ended = true
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) capture()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            ended = false
            capture()
        }
    }

    fun release(stopped: Boolean = true) {
        if (released) return
        released = true
        capture(stopped)
        player.removeListener(this)
        scope.cancel()
    }
}
