package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.engine.PlaybackProgressBody
import com.github.jkrishna289.orcax.engine.ProgressReportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamProgressReporterTest {
    @Test
    fun `forty percent survives a different release runtime`() {
        assertEquals(2_880_000L, resumePositionForDuration(2_880_000, 7_200_000, 7_200_000))
        assertEquals(2_400_000L, resumePositionForDuration(2_880_000, 7_200_000, 6_000_000))
        assertEquals(0L, resumePositionForDuration(2_880_000, 7_200_000, -1))
    }

    @Test
    fun `final stop supersedes a failed heartbeat and drains after closing`() = runTest {
        val attempts = mutableListOf<PlaybackProgressBody>()
        val saved = mutableListOf<Long>()
        val reporter = StreamProgressReporter(
            send = {
                attempts += it
                if (attempts.size == 1) ProgressReportResult.RETRY else ProgressReportResult.SAVED
            },
            onSaved = { saved += it },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.report(30_000, 100_000)
        runCurrent()
        reporter.report(40_000, 100_000, stopped = true)
        advanceUntilIdle()
        reporter.completion.join()
        assertEquals(listOf(1L, 2L), attempts.map { it.sequence })
        assertTrue(attempts.last().stopped)
        assertEquals(listOf(40_000L), saved)
    }

    @Test
    fun `retry retains sequence and a backward seek remains authoritative`() = runTest {
        val attempts = mutableListOf<PlaybackProgressBody>()
        val reporter = StreamProgressReporter(
            send = {
                attempts += it
                if (attempts.size == 1) ProgressReportResult.RETRY else ProgressReportResult.SAVED
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.report(40_000, 100_000)
        advanceUntilIdle()
        reporter.report(20_000, 100_000, stopped = true)
        advanceUntilIdle()
        assertEquals(listOf(1L, 1L, 2L), attempts.map { it.sequence })
        assertEquals(20_000L, attempts.last().positionMs)
    }

    @Test
    fun `unknown duration and an unstarted player cannot replace saved progress`() = runTest {
        val attempts = mutableListOf<PlaybackProgressBody>()
        val reporter = StreamProgressReporter(
            send = { attempts += it; ProgressReportResult.SAVED },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.report(0, -1)
        reporter.close()
        reporter.report(0, 100_000, stopped = true)
        advanceUntilIdle()
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `superseded sessions stop sending and final retries are bounded`() = runTest {
        var attempts = 0
        val reporter = StreamProgressReporter(
            send = { attempts++; ProgressReportResult.SUPERSEDED },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        reporter.report(40_000, 100_000)
        runCurrent()
        reporter.report(50_000, 100_000, stopped = true)
        advanceUntilIdle()
        assertEquals(1, attempts)

        var failures = 0
        val unavailable = StreamProgressReporter(
            send = { failures++; ProgressReportResult.RETRY },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        unavailable.report(40_000, 100_000, stopped = true)
        advanceUntilIdle()
        assertEquals(3, failures)
        assertTrue(unavailable.completion.isCompleted)
    }
}
