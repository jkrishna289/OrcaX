package com.github.jkrishna289.orcax.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.jkrishna289.orcax.services.trailer.TrailerPlayerPool

/**
 * The lifecycle phase of an inline trailer preview, surfaced to the card so it can drive perceived
 * performance (Phase 11): expand + shimmer immediately, then crossfade to video once it plays, and
 * collapse if it turns out to be unavailable.
 */
enum class TrailerPhase {
    /** Not started (no dwell / no trailer). */
    IDLE,

    /** Dwell fired; resolving/downloading/buffering — show the shimmer over the backdrop. */
    PREPARING,

    /** Actually playing — crossfade to the video. */
    PLAYING,

    /** No trailer will play (permanent failure / unreachable) — stay on / collapse to the backdrop. */
    UNAVAILABLE,
}

/**
 * The shared [TrailerPlayerPool] for inline trailer players, provided once near the home root.
 * Null (the default) means "no pool" — leaf players then build/release their own instance, so
 * previews outside the home still work.
 */
val LocalTrailerPlayerPool = staticCompositionLocalOf<TrailerPlayerPool?> { null }

/**
 * Leases an [ExoPlayer] for the lifetime of the calling composable — a reused pooled instance when a
 * pool is provided, otherwise a locally-built one — and returns it to the pool (or releases it) on
 * dispose. Held across recompositions and media changes so navigation doesn't churn decoders.
 */
@Composable
fun rememberLeasedPlayer(): ExoPlayer {
    val context = LocalContext.current
    val pool = LocalTrailerPlayerPool.current
    val trailerLanguage = LocalTrailerLanguage.current
    val player =
        remember {
            pool?.acquire()
                ?: ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
        }
    // Prefer the user's trailer language for audio-track selection (auto = English), so streams
    // that carry several audio tracks pick the wanted one instead of the container default.
    DisposableEffect(player, trailerLanguage) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage(TrailerLanguages.effective(trailerLanguage))
                .build()
        onDispose { }
    }
    DisposableEffect(player) {
        onDispose {
            if (pool != null) pool.release(player) else player.release()
        }
    }
    return player
}
