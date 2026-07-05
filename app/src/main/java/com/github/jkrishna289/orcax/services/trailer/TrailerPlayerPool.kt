package com.github.jkrishna289.orcax.services.trailer

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small pool of reusable [ExoPlayer] instances for inline trailer previews (Phase 10 of the trailer
 * redesign). The old code built and tore down a fresh player on every card focus and every billboard
 * rotation, churning video decoders and thrashing GC while the user scrubbed a row. Leasing from a
 * pool keeps a couple of warm players alive and simply swaps their media, so navigation stays smooth.
 *
 * Only the focused inline card and the hero billboard ever play at once, so a tiny idle cap is plenty.
 * All access must be on the main thread (ExoPlayer is single-threaded); Compose leases/returns players
 * from composition, which already runs there.
 */
@Singleton
class TrailerPlayerPool
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val idle = ArrayDeque<ExoPlayer>()

        /** Leases a player — a reset idle instance if one is free, otherwise a freshly built one. */
        fun acquire(): ExoPlayer {
            val reused = idle.removeFirstOrNull()
            if (reused != null) {
                return reused
            }
            Timber.v("TrailerPlayerPool: building a new ExoPlayer")
            return ExoPlayer
                .Builder(context)
                .build()
                .apply { repeatMode = Player.REPEAT_MODE_OFF }
        }

        /**
         * Returns a leased player to the pool. The caller MUST have removed its own listeners first;
         * the player is reset (stopped, media cleared) so it can be reused cleanly. Beyond the idle
         * cap the instance is fully released to bound memory.
         */
        fun release(player: ExoPlayer) {
            player.resetForReuse()
            if (idle.size < MAX_IDLE) {
                idle.addLast(player)
            } else {
                player.release()
            }
        }

        private fun ExoPlayer.resetForReuse() {
            playWhenReady = false
            stop()
            clearMediaItems()
        }

        private companion object {
            /** How many warm players to keep idle. Billboard + one focused card is the realistic peak. */
            const val MAX_IDLE = 3
        }
    }
