package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.engine.PlaybackProgressBody
import com.github.jkrishna289.orcax.engine.ProgressReportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serializes progress writes and retries brief outages. Only the newest unsent position matters.
 * Its bounded final flush outlives the player ViewModel, without retaining the player itself.
 */
class StreamProgressReporter(
    private val send: suspend (PlaybackProgressBody) -> ProgressReportResult,
    private val onSaved: (Long) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val updates = Channel<PlaybackProgressBody>(Channel.CONFLATED)
    private var sequence = 0L
    private var finished = false

    internal val completion = scope.launch {
        try {
            for (queued in updates) {
                var pending = queued
                var failures = 0
                while (true) {
                    val result = try {
                        send(pending)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        ProgressReportResult.RETRY
                    }
                    when (result) {
                        ProgressReportResult.SAVED -> {
                            onSaved(pending.positionMs)
                            break
                        }
                        ProgressReportResult.SUPERSEDED -> return@launch
                        ProgressReportResult.RETRY -> {
                            if (++failures >= 3) break
                            delay(failures * 1_000L)
                            // A pause/seek/stop supersedes an older failed heartbeat, preserving its
                            // own sequence. Retries of the same sample retain the same sequence.
                            updates.tryReceive().getOrNull()?.let { pending = it }
                        }
                    }
                }
            }
        } finally {
            updates.close()
            scope.cancel()
        }
    }

    @Synchronized
    fun report(positionMs: Long, durationMs: Long, completed: Boolean = false, stopped: Boolean = false) {
        if (finished) return
        if (durationMs > 0 && positionMs >= 0) {
            updates.trySend(
                PlaybackProgressBody(
                    sequence = ++sequence,
                    positionMs = positionMs.coerceAtMost(durationMs),
                    durationMs = durationMs,
                    completed = completed,
                    stopped = stopped,
                ),
            )
        }
        if (stopped) {
            finished = true
            updates.close()
        }
    }

    /** Nothing actually played, so close without replacing previously saved progress with zero. */
    @Synchronized
    fun close() {
        finished = true
        updates.close()
    }
}

/** Keep the watched fraction when switching releases with different runtimes. */
internal fun resumePositionForDuration(positionMs: Long, savedDurationMs: Long, durationMs: Long): Long {
    if (positionMs <= 0 || durationMs <= 0) return 0
    if (savedDurationMs <= 0) return positionMs.coerceAtMost(durationMs)
    return ((positionMs.toDouble() / savedDurationMs).coerceIn(0.0, 1.0) * durationMs)
        .toLong().coerceIn(0, durationMs)
}
