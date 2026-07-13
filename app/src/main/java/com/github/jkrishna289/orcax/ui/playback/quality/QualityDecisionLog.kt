package com.github.jkrishna289.orcax.ui.playback.quality

import timber.log.Timber
import java.util.ArrayDeque

/**
 * Structured log of every quality decision, plus a ring buffer of the last
 * [CAPACITY] decisions for the debug overlay.
 *
 * Tag: [AUTO-QUALITY] — filter in Logcat with: adb logcat | grep AUTO-QUALITY
 *
 * Format:
 *   [AUTO-QUALITY] EVENT | from=X to=Y | reason=Z | bandwidth=B | media=M | subtitle_risk=S | session=ID
 */
object QualityDecisionLog {
    data class Entry(
        val timestampMs: Long,
        val event: String,
        val from: String?,
        val to: String,
        val reason: String,
        val bandwidth: String,
        val media: String,
        val subtitleRisk: Boolean,
        val sessionId: String,
    ) {
        fun format(): String {
            val fromPart = if (from != null) "from=$from to=$to" else "tier=$to"
            return "[AUTO-QUALITY] $event | $fromPart | reason=$reason" +
                " | bandwidth=$bandwidth | media=$media | subtitle_risk=$subtitleRisk | session=$sessionId"
        }
    }

    private const val CAPACITY = 20
    private val buffer = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun log(
        event: String,
        to: String,
        reason: String,
        from: String? = null,
        bandwidthBps: Long = 0L,
        mediaBps: Int = 0,
        subtitleRisk: Boolean = false,
        sessionId: String = "",
    ) {
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            event = event,
            from = from,
            to = to,
            reason = reason,
            bandwidth = if (bandwidthBps > 0) "%.1f Mbps".format(bandwidthBps / 1_000_000.0) else "unknown",
            media = if (mediaBps > 0) "%.1f Mbps".format(mediaBps / 1_000_000.0) else "unknown",
            subtitleRisk = subtitleRisk,
            sessionId = sessionId,
        )
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(entry)
        Timber.i(entry.format())
    }

    @Synchronized
    fun history(): List<Entry> = buffer.toList()
}
