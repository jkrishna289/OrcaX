package com.github.jkrishna289.orcax.services.torrent

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.github.jkrishna289.orcax.engine.PlaybackProgressBody
import com.github.jkrishna289.orcax.engine.ProgressReportResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamProgressPlaybackListenerTest {
    @Test
    fun `resume waits for runtime then saves actual position and final stop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var duration = -1L
            var position = 0L
            val player = mockk<Player>(relaxed = true)
            every { player.duration } answers { duration }
            every { player.currentPosition } answers { position }
            every { player.isPlaying } returns false
            every { player.seekTo(any<Long>()) } answers { position = firstArg() }
            val reports = mutableListOf<PlaybackProgressBody>()
            val reporter = StreamProgressReporter(
                send = { reports += it; ProgressReportResult.SAVED },
                dispatcher = dispatcher,
            )
            val listener = StreamProgressPlaybackListener(player, reporter, 2_880_000, 7_200_000) {}
            listener.onIsPlayingChanged(true)
            runCurrent()
            assertTrue(reports.isEmpty())

            duration = 6_000_000
            listener.onTimelineChanged(mockk<Timeline>(), Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            verify(exactly = 1) { player.seekTo(2_400_000L) }
            listener.onIsPlayingChanged(false)
            runCurrent()
            assertEquals(2_400_000L, reports.last().positionMs)

            position = 2_500_000
            listener.release()
            listener.release() // lifecycle callbacks can tear down twice
            advanceUntilIdle()
            assertTrue(reports.last().stopped)
            assertEquals(2_500_000L, reports.last().positionMs)
            assertEquals(1, reports.count { it.stopped })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `backing out during preparation preserves the old server position`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val player = mockk<Player>(relaxed = true)
            every { player.duration } returns 100_000
            every { player.isPlaying } returns false
            val reports = mutableListOf<PlaybackProgressBody>()
            val reporter = StreamProgressReporter(send = { reports += it; ProgressReportResult.SAVED }, dispatcher = dispatcher)
            val listener = StreamProgressPlaybackListener(player, reporter, 40_000, 100_000) {}
            listener.release()
            advanceUntilIdle()
            assertTrue(reports.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
