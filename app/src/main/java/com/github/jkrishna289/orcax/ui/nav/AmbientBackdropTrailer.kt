package com.github.jkrishna289.orcax.ui.nav

import android.view.LayoutInflater
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.ui.FontAwesome
import com.github.jkrishna289.orcax.ui.TrailerPhase
import com.github.jkrishna289.orcax.ui.rememberLeasedPlayer
import kotlinx.coroutines.delay

/** Dwell before the ambient trailer starts — long enough that quick back-and-forth navigation never fires it. */
private const val AMBIENT_TRAILER_DWELL_MS = 2_200L

/** Peak opacity: the video stays translucent so it reads as ambience behind the text, not playback. */
private const val AMBIENT_TRAILER_MAX_ALPHA = 0.85f

/** Fade-in duration from the still backdrop into the moving video. */
private const val AMBIENT_TRAILER_FADE_MS = 1_400

/** Skip studio idents / slates, mirroring the inline card trailers. */
private const val AMBIENT_TRAILER_SKIP_MS = 3_000L

/**
 * The ambient trailer layer of the shared [Backdrop]: after a dwell on a backdrop whose item has a
 * local trailer ([trailerUrl], resolved by BackdropService), fades a muted, looping video in over
 * the still image. Composed inside the backdrop's existing top-right inset box, under the same
 * edge-fade gradients, so it can never read as a fullscreen takeover by construction.
 *
 * This is the inline-card-trailer pattern minus the parts that would fight the backdrop: no shimmer
 * (ambience shouldn't advertise loading), no black-background crossfade (the still image is the
 * permanent fallback underneath), and a TextureView surface (see ambient_trailer_player.xml) so the
 * backdrop's DstIn masking applies to the video. The player is leased via [rememberLeasedPlayer]
 * only while preparing/playing and is returned on dispose. Never focusable; any failure simply
 * leaves the still image showing.
 */
@Composable
fun AmbientBackdropTrailer(
    itemId: String?,
    trailerUrl: String?,
    modifier: Modifier = Modifier,
) {
    var phase by remember(itemId) { mutableStateOf(TrailerPhase.IDLE) }
    LaunchedEffect(itemId, trailerUrl) {
        phase = TrailerPhase.IDLE
        if (trailerUrl == null) return@LaunchedEffect
        delay(AMBIENT_TRAILER_DWELL_MS)
        phase = TrailerPhase.PREPARING
    }
    if (trailerUrl == null || phase == TrailerPhase.IDLE || phase == TrailerPhase.UNAVAILABLE) {
        return
    }

    val exo = rememberLeasedPlayer()
    LaunchedEffect(exo, trailerUrl) {
        exo.volume = 0f // muted — ambient only, never competes with UI sound
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setMediaItem(MediaItem.fromUri(trailerUrl))
        exo.prepare()
        exo.seekTo(AMBIENT_TRAILER_SKIP_MS)
        exo.playWhenReady = true
    }
    DisposableEffect(exo) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        phase = TrailerPhase.PLAYING
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Stay on the still image; the layer simply never fades in.
                    phase = TrailerPhase.UNAVAILABLE
                }
            }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.playWhenReady = false
            // Looping is ambient-only; don't leak it back into the shared pool.
            exo.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    // The view is attached (alpha 0) from PREPARING so the surface exists before the first frame,
    // then fades in once actually playing — "faded" even before the edge-gradient masking.
    val alpha by animateFloatAsState(
        targetValue = if (phase == TrailerPhase.PLAYING) AMBIENT_TRAILER_MAX_ALPHA else 0f,
        animationSpec = tween(AMBIENT_TRAILER_FADE_MS),
        label = "ambient_trailer_alpha",
    )
    Box(modifier = modifier.graphicsLayer { this.alpha = alpha }) {
        AndroidView(
            factory = { ctx ->
                LayoutInflater.from(ctx).inflate(R.layout.ambient_trailer_player, null) as PlayerView
            },
            update = { view -> view.player = exo },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Small "▶ TRAILER" chip so ambient motion is identifiable as a trailer, per the v2 spec.
        // Fades in/out with the video (shares the Box's alpha layer).
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.background.copy(alpha = .55f),
                        shape = RoundedCornerShape(10.dp),
                    ).border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = .14f),
                        shape = RoundedCornerShape(10.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.fa_play),
                fontFamily = FontAwesome,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = stringResource(R.string.play_trailer_chip).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
            )
        }
    }
}
