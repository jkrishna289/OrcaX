package com.github.jkrishna289.orcax.services.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a streamed film was left, once the player has torn down.
 *
 * [token] identifies the engine session the film came from. Keeping is an instruction to *that*
 * session — it already holds the torrent, so finishing the download is the work it is halfway
 * through anyway — which is why the token has to survive the trip through the player rather than
 * being looked up again afterwards.
 */
data class StreamWatch(
    val title: String,
    val token: String,
    val positionMs: Long,
)

/**
 * Carries one fact across the player boundary: how far a *streamed* title got before the viewer
 * stopped.
 *
 * A library title reports its progress to Jellyfin, which the details screen can simply read back.
 * A torrent stream has no Jellyfin item to report against, so nothing survives the trip home — and
 * without it the "Keep it?" prompt cannot tell a viewer who watched the whole film from one who
 * bounced off the first minute. This is the smallest thing that closes that gap: the details screen
 * arms it before navigating, the player fills in the position on teardown, and the details screen
 * consumes it once on the way back.
 *
 * [answered] is remembered for the process lifetime so the prompt is asked once per title and never
 * again, whichever way it was answered. It is deliberately not persisted: an answer that outlives the
 * app would need invalidating every time the library changes, and asking again after a restart is the
 * cheaper mistake.
 */
@Singleton
class StreamWatchTracker
    @Inject
    constructor() {
        private val pending = MutableStateFlow<StreamWatch?>(null)
        private val answered = mutableSetOf<String>()

        /** Armed when a streamed playback is launched. Position is filled in when the player stops. */
        fun begin(
            title: String,
            token: String,
        ) {
            pending.update { StreamWatch(title = title, token = token, positionMs = 0) }
        }

        /** Called by the player as it tears down a torrent stream. No-op if nothing was armed. */
        fun report(positionMs: Long) {
            pending.update { it?.copy(positionMs = positionMs) }
        }

        /**
         * Takes the pending watch, if any, and clears it — so a prompt survives exactly one return
         * from the player and never reappears on a later visit to the same page.
         */
        fun consume(): StreamWatch? {
            val watch = pending.value ?: return null
            pending.update { null }
            return watch.takeIf { it.title !in answered }
        }

        /** Records that this title has been asked about, either way. Silence counts as an answer. */
        fun markAnswered(title: String) {
            answered += title
        }
    }
