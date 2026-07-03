package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay

/** How far into a trailer to start, skipping studio idents / slates (#11). */
private const val TRAILER_SKIP_MS = 3_000L

/** How many times to re-try after a cache-miss failure while the card stays dwelled. */
private const val MAX_TRAILER_RETRIES = 3

/** Wait between retries — roughly how long the server needs to warm a missing trailer. */
private const val TRAILER_RETRY_DELAY_MS = 6_000L

/**
 * A 16:9 preview that shows the backdrop and crossfades to the trailer once [play] + ready. The
 * trailer plays ONCE (no loop) and hands back to the backdrop when it ends. Mount it while a card is
 * dwelled, unmount on focus loss (the [DisposableEffect] releases the player). [volume] is 0f..1f —
 * the home's 16:9 cards play quiet audio. On a cache miss / unplayable trailer it stays on the
 * backdrop. [onReadyChange] reports confirmed playability — true only while the trailer is actually
 * playable — so the caller can gate its expansion on "the trailer is sure to play".
 */
@Composable
fun InlineCardTrailer(
    trailerUrl: String?,
    backdropUrl: String?,
    play: Boolean,
    modifier: Modifier = Modifier,
    volume: Float = 0f,
    onReadyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var ready by remember(trailerUrl) { mutableStateOf(false) }
    // Report readiness transitions (READY → true; ended/error/unmount → false) to the caller.
    LaunchedEffect(ready) { onReadyChange(ready) }
    var ended by remember(trailerUrl) { mutableStateOf(false) }
    // The engine 404s a trailer that isn't cached yet — but that same request kicks off a background
    // warm on the server. Retrying a few times while the card stays dwelled lets the trailer start
    // as soon as the cache fills, instead of silently giving up on the first miss.
    var failedAttempts by remember(trailerUrl) { mutableStateOf(0) }
    var retryNonce by remember(trailerUrl) { mutableStateOf(0) }

    val exo =
        remember(trailerUrl) {
            if (trailerUrl != null) {
                ExoPlayer.Builder(context).build().apply {
                    this.volume = volume
                    // Play once — trailers must not loop; the backdrop returns when it ends.
                    repeatMode = Player.REPEAT_MODE_OFF
                }
            } else {
                null
            }
        }

    // Keep the live volume in sync if it changes without recreating the player.
    LaunchedEffect(exo, volume) { exo?.volume = volume }

    // Only prepare/play once the dwell says so (play == true); pause otherwise. Re-runs on each
    // retry bump after a cache-miss error. Never restarts a trailer that finished (ended).
    LaunchedEffect(trailerUrl, play, retryNonce) {
        if (play && trailerUrl != null && exo != null && !ended) {
            exo.setMediaItem(MediaItem.fromUri(trailerUrl))
            exo.prepare()
            exo.seekTo(TRAILER_SKIP_MS)
            exo.playWhenReady = true
        } else {
            ready = false
            exo?.playWhenReady = false
        }
    }

    // Schedule a bounded retry after each failure (server-side warm typically takes seconds).
    LaunchedEffect(failedAttempts, play) {
        if (play && failedAttempts in 1..MAX_TRAILER_RETRIES) {
            delay(TRAILER_RETRY_DELAY_MS)
            retryNonce++
        }
    }

    DisposableEffect(exo) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> ready = true
                        Player.STATE_ENDED -> {
                            // Finished — hand back to the backdrop (and the caller collapses).
                            ended = true
                            ready = false
                        }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Cache miss / unplayable → stay on the backdrop and schedule a retry.
                    ready = false
                    failedAttempts++
                }
            }
        exo?.addListener(listener)
        onDispose {
            exo?.removeListener(listener)
            exo?.release()
        }
    }

    Crossfade(
        targetState = play && ready && exo != null,
        animationSpec = tween(durationMillis = 400),
        label = "inline-card-trailer",
        modifier = modifier.background(Color.Black),
    ) { playing ->
        if (playing && exo != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = exo
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
