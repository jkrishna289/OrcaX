package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.jkrishna289.orcax.engine.RenderItem

/** How far into a trailer to start, skipping studio idents / slates (#11). */
private const val TRAILER_SKIP_MS = 3_000L

private val PANEL_WIDTH_SMALL = 340.dp
private val PANEL_WIDTH_LARGE = 640.dp

/**
 * Instant details + two-stage smart trailer preview (#10 / #11). After a card holds focus the overlay
 * appears with metadata (no network). When [play] turns true (5s dwell) a muted, low-bitrate trailer
 * starts at the **small** size, skipping the first 3 seconds; when [enlarged] turns true (10s) the panel
 * grows into the pop-up — the same player keeps running. On a cache miss it just shows the backdrop.
 */
@Composable
fun InstantDetails(
    item: RenderItem?,
    modifier: Modifier = Modifier,
    trailerUrl: String? = null,
    backdropUrl: String? = null,
    play: Boolean = false,
    enlarged: Boolean = false,
) {
    AnimatedVisibility(
        visible = item != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        val card = item?.card
        val panelWidth by animateDpAsState(
            targetValue = if (enlarged) PANEL_WIDTH_LARGE else PANEL_WIDTH_SMALL,
            animationSpec = tween(durationMillis = 320),
            label = "instant-panel-width",
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .width(panelWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(16.dp),
        ) {
            if (trailerUrl != null || backdropUrl != null) {
                TrailerPreview(
                    trailerUrl = trailerUrl,
                    backdropUrl = backdropUrl,
                    play = play,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp)),
                )
            }

            card?.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val chips = card?.let { metaChips(it) }.orEmpty()
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chips.forEach { chip ->
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            val synopsis = card?.synopsis ?: card?.subtitle
            if (!synopsis.isNullOrBlank()) {
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = if (enlarged) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A 16:9 preview that shows the backdrop and crossfades to a muted trailer once [play] + ready. */
@Composable
private fun TrailerPreview(
    trailerUrl: String?,
    backdropUrl: String?,
    play: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var ready by remember(trailerUrl) { mutableStateOf(false) }

    val exo =
        remember(trailerUrl) {
            if (trailerUrl != null) {
                ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ALL
                }
            } else {
                null
            }
        }

    // Only prepare/play once the dwell timer says so (play == true); pause otherwise.
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
        label = "instant-trailer",
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

/** Builds the chip line (year · ★TMDB · ★W Orca · genres) from the card's badges. */
private fun metaChips(card: com.github.jkrishna289.orcax.engine.CardDescriptor): List<String> {
    val chips = mutableListOf<String>()
    card.badges.firstOrNull { it.kind == "YEAR" }?.text?.let { chips.add(it) }
    card.badges.firstOrNull { it.kind == "RATING" }?.text?.let { chips.add("★ $it") }
    card.badges.firstOrNull { it.kind == "WRATING" }?.text?.let { chips.add("★W $it") }
    val genres = card.badges.filter { it.kind == "GENRE" }.mapNotNull { it.text }.take(3)
    if (genres.isNotEmpty()) chips.add(genres.joinToString(" · "))
    return chips
}
