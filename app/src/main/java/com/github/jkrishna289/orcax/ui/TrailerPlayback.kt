package com.github.jkrishna289.orcax.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val player =
        remember {
            pool?.acquire()
                ?: ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
        }
    DisposableEffect(player) {
        onDispose {
            if (pool != null) pool.release(player) else player.release()
        }
    }
    return player
}

/**
 * A subtle left-to-right shimmer sweep, shown over the backdrop while a trailer is preparing so the
 * card reads as "loading" the instant it expands — instead of sitting on a static frame until the
 * player is READY (Phase 11).
 */
@Composable
fun TrailerLoadingShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "trailer-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "trailer-shimmer-progress",
    )
    // A narrow bright band swept across a translucent dark base; deliberately low-contrast for a 10ft UI.
    val shift = progress * 2f - 0.5f
    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.0f),
            Color.White.copy(alpha = 0.12f),
            Color.White.copy(alpha = 0.0f),
        ),
        start = Offset(shift * 1000f, 0f),
        end = Offset((shift + 0.4f) * 1000f, 0f),
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize().background(brush),
    )
}
