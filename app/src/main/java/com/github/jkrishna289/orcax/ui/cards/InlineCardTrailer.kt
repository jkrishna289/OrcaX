package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.github.jkrishna289.orcax.engine.TrailerStatus
import com.github.jkrishna289.orcax.ui.LocalTrailerVolume
import com.github.jkrishna289.orcax.ui.TrailerLoadingShimmer
import com.github.jkrishna289.orcax.ui.TrailerPhase
import com.github.jkrishna289.orcax.ui.rememberLeasedPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** How far into a trailer to start, skipping studio idents / slates (#11). */
private const val TRAILER_SKIP_MS = 3_000L

/** First status poll delay; grows toward [STATUS_POLL_MAX_MS] while the server is still producing. */
private const val STATUS_POLL_INITIAL_MS = 700L

/** Ceiling for the adaptive status-poll interval. */
private const val STATUS_POLL_MAX_MS = 4_000L

/** Give up (mark unavailable) after this many temporary failures / unreachable polls. */
private const val MAX_TEMP_FAILURES = 4

/**
 * A 16:9 preview that shows the backdrop and crossfades to the trailer once it is actually playing.
 * Playback is **state-driven** (the trailer redesign): rather than blindly playing and retrying on a
 * fixed timer, it polls the engine's trailer status ([statusProvider]) and only hands the URL to the
 * (pooled) [ExoPlayer] once the server reports the file is ready — starting immediately on Ready,
 * backing off exponentially on a temporary failure, and giving up on a permanent one. It reports its
 * [TrailerPhase] via [onPhaseChange] so the card can expand + shimmer immediately and crossfade to
 * video when it plays. The player is leased from [rememberLeasedPlayer] (pooled), plays once
 * (REPEAT_MODE_OFF), and is returned on unmount. [volume] is 0f..1f. When [statusProvider] is null it
 * falls back to a best-effort direct play (older engines without the status endpoint).
 */
@Composable
fun InlineCardTrailer(
    trailerUrl: String?,
    backdropUrl: String?,
    play: Boolean,
    modifier: Modifier = Modifier,
    volume: Float = 0f,
    statusProvider: (suspend () -> TrailerStatus?)? = null,
    onPhaseChange: (TrailerPhase) -> Unit = {},
) {
    val context = LocalContext.current
    val exo = rememberLeasedPlayer()

    // Stable holders (NOT keyed on trailerUrl) so the long-lived player listener below can never
    // capture a stale MutableState when the URL changes mid-lease.
    val ready = remember { mutableStateOf(false) }
    val ended = remember { mutableStateOf(false) }
    val phase = remember { mutableStateOf(TrailerPhase.IDLE) }
    val onPhase = rememberUpdatedState(onPhaseChange)

    // Report phase transitions to the caller.
    LaunchedEffect(phase.value) { onPhase.value(phase.value) }
    // Keep the live volume in sync without recreating the player.
    LaunchedEffect(exo, volume) { exo.volume = volume }
    // Reset per-media flags when the target changes.
    LaunchedEffect(trailerUrl) {
        ready.value = false
        ended.value = false
        phase.value = TrailerPhase.IDLE
    }

    // Adaptive, state-driven start. Cancelled the moment [play] flips false or the card unmounts (the
    // effect is torn down), so retries never outlive focus and can't pile up into a storm.
    LaunchedEffect(trailerUrl, play, statusProvider) {
        if (!play || trailerUrl == null || ended.value) {
            exo.playWhenReady = false
            return@LaunchedEffect
        }
        phase.value = TrailerPhase.PREPARING

        if (statusProvider == null) {
            startPlayback(exo, trailerUrl, TRAILER_SKIP_MS)
            return@LaunchedEffect
        }

        var pollMs = STATUS_POLL_INITIAL_MS
        var tempFailures = 0
        while (isActive) {
            val status = statusProvider()
            when {
                status == null -> {
                    // Engine unreachable / too old for the status endpoint: try a few times, then
                    // fall back to a best-effort direct play so trailers still work against old servers.
                    if (++tempFailures > MAX_TEMP_FAILURES) {
                        startPlayback(exo, trailerUrl, TRAILER_SKIP_MS)
                        return@LaunchedEffect
                    }
                    delay(backoffMs(tempFailures))
                }

                status.isReady -> {
                    // Smart preview start (Phase 14) when the engine provides one, else the default skip.
                    startPlayback(exo, trailerUrl, status.previewStartMs?.toLong() ?: TRAILER_SKIP_MS)
                    return@LaunchedEffect
                }

                status.isPermanentlyUnavailable -> {
                    phase.value = TrailerPhase.UNAVAILABLE
                    return@LaunchedEffect
                }

                status.isTemporaryFailure -> {
                    if (++tempFailures > MAX_TEMP_FAILURES) {
                        phase.value = TrailerPhase.UNAVAILABLE
                        return@LaunchedEffect
                    }
                    delay(backoffMs(tempFailures))
                }

                else -> {
                    // Discovering / Queued / Downloading / Transcoding / Unknown — the server is (or
                    // will be) producing it. Poll with mild backoff + jitter so many focused cards
                    // don't hammer the server in lockstep.
                    delay(pollMs + Random.nextLong(0, 250))
                    pollMs = (pollMs * 3 / 2).coerceAtMost(STATUS_POLL_MAX_MS)
                }
            }
        }
    }

    DisposableEffect(exo) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            ready.value = true
                            phase.value = TrailerPhase.PLAYING
                        }
                        Player.STATE_ENDED -> {
                            // Finished — hand back to the backdrop (the caller collapses).
                            ended.value = true
                            ready.value = false
                            phase.value = TrailerPhase.IDLE
                        }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Unexpected once status-gated; stay on the backdrop.
                    ready.value = false
                    phase.value = TrailerPhase.UNAVAILABLE
                }
            }
        exo.addListener(listener)
        onDispose {
            // The pool owns the player's lifecycle; just detach and pause (rememberLeasedPlayer resets it).
            exo.removeListener(listener)
            exo.playWhenReady = false
        }
    }

    Crossfade(
        targetState = play && ready.value,
        animationSpec = tween(durationMillis = 400),
        label = "inline-card-trailer",
        modifier = modifier.background(Color.Black),
    ) { playing ->
        if (playing) {
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
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                if (backdropUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Loading shimmer while the trailer is preparing (Phase 11).
                if (phase.value == TrailerPhase.PREPARING) {
                    TrailerLoadingShimmer()
                }
            }
        }
    }
}

/** Sets the media, seeks past the intro ([startMs]), and starts playback on the (already-leased) player. */
private fun startPlayback(exo: ExoPlayer, url: String, startMs: Long) {
    exo.setMediaItem(MediaItem.fromUri(url))
    exo.prepare()
    exo.seekTo(startMs.coerceAtLeast(0))
    exo.playWhenReady = true
}

/** Exponential backoff (1s, 2s, 4s, 8s…) capped at 8s, for temporary failures / unreachable polls. */
private fun backoffMs(attempt: Int): Long = (1_000L * (1L shl (attempt - 1))).coerceAtMost(8_000L)
