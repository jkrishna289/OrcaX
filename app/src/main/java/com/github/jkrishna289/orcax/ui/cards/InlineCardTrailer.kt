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

/** How far into a trailer to start, skipping studio idents / slates (#11). */
private const val TRAILER_SKIP_MS = 3_000L

/**
 * A 16:9 preview that shows the backdrop and crossfades to a looping trailer once [play] + ready.
 * Extracted from the old InstantDetails pop-up so a focused card can play its own trailer in place:
 * mount it while a card is dwelled, unmount on focus loss (the [DisposableEffect] releases the
 * player). [volume] is 0f..1f — the home's 16:9 cards play quiet audio, other callers pass 0f.
 * On a cache miss / unplayable trailer it simply stays on the backdrop.
 */
@Composable
fun InlineCardTrailer(
    trailerUrl: String?,
    backdropUrl: String?,
    play: Boolean,
    modifier: Modifier = Modifier,
    volume: Float = 0f,
) {
    val context = LocalContext.current
    var ready by remember(trailerUrl) { mutableStateOf(false) }

    val exo =
        remember(trailerUrl) {
            if (trailerUrl != null) {
                ExoPlayer.Builder(context).build().apply {
                    this.volume = volume
                    repeatMode = Player.REPEAT_MODE_ALL
                }
            } else {
                null
            }
        }

    // Keep the live volume in sync if it changes without recreating the player.
    LaunchedEffect(exo, volume) { exo?.volume = volume }

    // Only prepare/play once the dwell says so (play == true); pause otherwise.
    LaunchedEffect(trailerUrl, play) {
        if (play && trailerUrl != null && exo != null) {
            exo.setMediaItem(MediaItem.fromUri(trailerUrl))
            exo.prepare()
            exo.seekTo(TRAILER_SKIP_MS)
            exo.playWhenReady = true
        } else {
            ready = false
            exo?.playWhenReady = false
        }
    }

    DisposableEffect(exo) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Cache miss / unplayable → stay on the backdrop.
                    ready = false
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
