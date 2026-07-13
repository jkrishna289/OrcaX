package com.github.jkrishna289.orcax.ui.playback.quality

import com.github.jkrishna289.orcax.ui.playback.quality.PlaybackHealthTracker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rescue-guard suite: the false-starve bug classes the reactive rescue design
 * resurrects (docs/quality-audit.md #3 end-of-video, #4 post-seek, #6 during
 * own switch) must be IGNORED; real starvation must reach RESCUE.
 */
class PlaybackHealthTrackerTest {
    private var clock = 0L
    private val tracker = PlaybackHealthTracker { clock }

    /** Playing mid-item, all grace windows expired. */
    private fun startedAndSettled() {
        clock = 100_000L
        tracker.onPlaybackStarted()
        clock += PlaybackHealthTracker.REBUILD_GRACE_MS + 1_000L
    }

    private fun rebuffer(
        positionMs: Long = 600_000L,
        durationMs: Long = 7_200_000L,
        switching: Boolean = false,
    ) = tracker.onRebuffer(positionMs, durationMs, switching)

    @Test
    fun `rebuffer before playback ever started is initial buffering`() {
        clock = 5_000L
        assertEquals(Verdict.IGNORED, rebuffer())
    }

    @Test
    fun `two counted rebuffers within the window trigger a rescue`() {
        startedAndSettled()
        assertEquals(Verdict.COUNTED, rebuffer())
        clock += 20_000L
        assertEquals(Verdict.RESCUE, rebuffer())
    }

    @Test
    fun `rebuffers outside the 60s window never accumulate`() {
        startedAndSettled()
        assertEquals(Verdict.COUNTED, rebuffer())
        clock += PlaybackHealthTracker.WINDOW_MS + 10_000L
        assertEquals(Verdict.COUNTED, rebuffer())
        clock += PlaybackHealthTracker.WINDOW_MS + 10_000L
        assertEquals(Verdict.COUNTED, rebuffer())
    }

    @Test
    fun `post-seek rebuffering is ignored`() {
        startedAndSettled()
        tracker.onSeek()
        clock += PlaybackHealthTracker.SEEK_GRACE_MS - 1_000L
        assertEquals(Verdict.IGNORED, rebuffer())
        // After the grace window it counts again
        clock += 2_000L
        assertEquals(Verdict.COUNTED, rebuffer())
    }

    @Test
    fun `rebuffering right after a stream rebuild is ignored`() {
        startedAndSettled()
        tracker.onStreamRebuilt()
        clock += PlaybackHealthTracker.REBUILD_GRACE_MS - 1_000L
        assertEquals(Verdict.IGNORED, rebuffer())
    }

    @Test
    fun `rebuffering during a stream switch is ignored`() {
        startedAndSettled()
        assertEquals(Verdict.IGNORED, rebuffer(switching = true))
    }

    @Test
    fun `end-of-item buffer drain is ignored`() {
        startedAndSettled()
        assertEquals(
            Verdict.IGNORED,
            rebuffer(positionMs = 7_200_000L - 10_000L, durationMs = 7_200_000L),
        )
    }

    @Test
    fun `a single long stall rescues immediately`() {
        startedAndSettled()
        assertEquals(Verdict.RESCUE, tracker.onStallExceeded(600_000L, 7_200_000L, false))
    }

    @Test
    fun `a long stall inside a guard window is still ignored`() {
        startedAndSettled()
        tracker.onSeek()
        assertEquals(Verdict.IGNORED, tracker.onStallExceeded(600_000L, 7_200_000L, false))
    }

    @Test
    fun `reset clears history so the next item starts clean`() {
        startedAndSettled()
        assertEquals(Verdict.COUNTED, rebuffer())
        tracker.reset()
        assertEquals(0, tracker.totalRebuffers)
        clock += 1_000L
        // Not started yet after reset → initial buffering again
        assertEquals(Verdict.IGNORED, rebuffer())
    }

    @Test
    fun `guards reset the moment playback starts counts from first frame`() {
        clock = 50_000L
        tracker.onPlaybackStarted()
        // Still inside the post-start priming window
        clock += 2_000L
        assertEquals(Verdict.IGNORED, rebuffer())
    }
}
