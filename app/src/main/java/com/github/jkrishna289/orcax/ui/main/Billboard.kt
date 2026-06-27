package com.github.jkrishna289.orcax.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * The cinematic spotlight at the top of the engine home — a **floating, rounded key-art card**
 * (inset from the screen edges, with a drop shadow and hairline border) carrying a backdrop +
 * logo (or styled title) + metadata + tagline + Play / Trailer / Watchlist / Info, a giant ghosted
 * title behind the art, a content-rating chip in the corner, and pagination dots. When [trailerUrl]
 * is non-null it crossfades to a muted, looping inline trailer after the engine's auto-play delay.
 * When an item has no real backdrop (the sample/demo bundle) the art is a procedural gradient built
 * from the item's accent hint.
 *
 * When [heroCount] > 1 the home rotates through several spotlight items; [activeIndex] drives the
 * pagination dots. Rigid, linear motion only (per the design language): a single [tween] crossfade.
 */
@Composable
fun Billboard(
    item: RenderItem,
    trailerUrl: String?,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    onRequest: () -> Unit = onInfo,
    onTrailer: () -> Unit = onInfo,
    playFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    heroCount: Int = 1,
    activeIndex: Int = 0,
) {
    val card = item.card
    val media = item.media
    val imageUrlService = LocalImageUrlService.current
    val jellyfinId = media.jellyfinId?.toUUIDOrNull()
    val accent = EngineHomeArt.parseAccent(card.accentColorHint)

    val backdropUrl =
        card.backdropImageUrl
            ?: jellyfinId?.let { imageUrlService.getItemImageUrl(itemId = it, imageType = ImageType.BACKDROP, fillHeight = 720) }
    val logoUrl =
        card.logoImageUrl
            ?: jellyfinId?.let { imageUrlService.getItemImageUrl(itemId = it, imageType = ImageType.LOGO, fillHeight = 240) }

    val context = LocalContext.current
    var showTrailer by remember(trailerUrl) { mutableStateOf(false) }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }

    val exoPlayer =
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

    LaunchedEffect(trailerUrl) {
        showTrailer = false
        if (trailerUrl != null && exoPlayer != null) {
            delay(card.autoPlayDelayMs.coerceAtLeast(0).toLong())
            exoPlayer.setMediaItem(MediaItem.fromUri(trailerUrl))
            exoPlayer.prepare()
            if (card.trailerStartOffsetMs > 0) {
                exoPlayer.seekTo(card.trailerStartOffsetMs.toLong())
            }
            exoPlayer.playWhenReady = true
            showTrailer = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    // The floating card: inset (via the caller's padding), rounded, shadowed, hairline-bordered.
    val cardShape = RoundedCornerShape(20.dp)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(elevation = 32.dp, shape = cardShape, clip = false)
                .clip(cardShape)
                .border(1.dp, Color.White.copy(alpha = 0.06f), cardShape),
    ) {
        Crossfade(
            targetState = showTrailer && exoPlayer != null,
            animationSpec = tween(durationMillis = 400),
            label = "billboard-media",
        ) { playing ->
            if (playing && exoPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (backdropUrl != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(backdropUrl)
                            .crossfade(true)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Sample/demo item: procedural key-art from the accent hint.
                Box(modifier = Modifier.fillMaxSize().engineHeroArt(accent))
            }
        }

        // (The oversized "ghost" title that used to sit behind the art was removed: a single 160sp
        // glyph run re-rasterized on every spotlight rotation, starving the action buttons of frame
        // time so their text/sizes settled late. The logo / HeroTitle already carries the title.)

        // Layered legibility scrims: a strong top band (so the nav icons always read), a left wash
        // for the text column, a bottom riser for the content, and a faint global veil.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.85f),
                            0.34f to Color.Black.copy(alpha = 0.28f),
                            0.62f to Color.Transparent,
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.93f),
                            0.4f to Color.Black.copy(alpha = 0.45f),
                            0.82f to Color.Transparent,
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))

        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    // Wide enough for the four action buttons to sit on one line (otherwise the last
                    // one — "Info" — gets squeezed to a sliver and its label wraps a letter per line).
                    .fillMaxWidth(0.72f)
                    .padding(start = 44.dp, bottom = 44.dp, end = 16.dp),
        ) {
            // Type label with an accent chip (e.g. "■ SERIES"), echoing the source design.
            SeriesBadge(media.mediaType)

            // Real Jellyfin logo art wins; when absent, fall back to the styled title.
            if (logoUrl != null && !logoFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(logoUrl).crossfade(true).build(),
                    contentDescription = card.title,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.heightIn(max = 104.dp).widthIn(max = 380.dp),
                )
            } else {
                HeroTitle(card.title.orEmpty())
            }

            BillboardMetadata(item)

            card.subtitle?.takeIf { it.isNotBlank() }?.let { tagline ->
                Text(
                    text = tagline,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            BillboardActions(
                actions = card.actions,
                isFavorite = isFavorite,
                onPlay = onPlay,
                onRequest = onRequest,
                onTrailer = onTrailer,
                onWatchlist = onWatchlist,
                onInfo = onInfo,
                playFocusRequester = playFocusRequester,
                navFocusRequester = navFocusRequester,
            )

            GenreBreadcrumb(item)
        }

        // Content-rating chip in the top-right corner (driven by an AGE badge, e.g. "16+").
        item.card.badges.firstOrNull { it.kind.equals("AGE", ignoreCase = true) }?.text?.let { age ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 18.dp, end = 18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(text = age, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // Pagination dots for the rotating spotlight, anchored bottom-right of the card.
        if (heroCount > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 32.dp, bottom = 28.dp),
            ) {
                repeat(heroCount) { i ->
                    val active = i == activeIndex
                    Box(
                        modifier =
                            Modifier
                                .height(5.dp)
                                .width(if (active) 28.dp else 8.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f),
                                ),
                    )
                }
            }
        }
    }
}

/** Type label with a small accent square, e.g. "■ SERIES". */
@Composable
private fun SeriesBadge(type: MediaType) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier =
                Modifier
                    .size(15.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = type.heroLabel(),
            color = Color.White.copy(alpha = 0.78f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 5.sp,
        )
    }
}

private fun MediaType.heroLabel(): String =
    when (this) {
        MediaType.SERIES -> "SERIES"
        MediaType.MOVIE -> "FILM"
        MediaType.EPISODE -> "EPISODE"
        MediaType.SEASON -> "SEASON"
        MediaType.COLLECTION -> "COLLECTION"
        else -> name.uppercase()
    }

/**
 * The styled hero title fallback used when an item has no logo art: a small, letter-spaced lead-in
 * (everything but the last word) over a large final word, mirroring the "PROJECT / LOKI" treatment.
 */
@Composable
private fun HeroTitle(title: String) {
    val trimmed = title.trim()
    val split = trimmed.lastIndexOf(' ')
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (split > 0) {
            Text(
                text = trimmed.substring(0, split).uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                letterSpacing = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = trimmed.substring(split + 1),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 60.sp,
            lineHeight = 60.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The metadata row: a boxed certificate chip, a gold ★ score, year, and episode/runtime, separated
 * by dots. Reads structured engine badges by kind (CERT/RATING/YEAR/EPISODES); if an item carries
 * none of those, falls back to listing whatever badges it does have (so real server data still shows).
 */
@Composable
private fun BillboardMetadata(item: RenderItem) {
    val badges = item.card.badges
    fun badge(vararg kinds: String) =
        badges.firstOrNull { b -> kinds.any { b.kind.equals(it, ignoreCase = true) } }?.text?.takeIf { it.isNotBlank() }

    val cert = badge("CERT", "RATED", "CERTIFICATE")
    val rating = badge("RATING", "SCORE")
    val year = badge("YEAR", "DATE")
    val episodes = badge("EPISODES", "RUNTIME", "DURATION")

    val hasStructured = listOf(cert, rating, year, episodes).any { it != null }
    if (!hasStructured) {
        GenericMetadata(item)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        cert?.let {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(it, color = Color.White.copy(alpha = 0.88f), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            }
        }
        rating?.let {
            Text("★ $it", color = Color(0xFFDAB440), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        val tail = listOfNotNull(year, episodes)
        tail.forEach {
            Text("·", color = Color.White.copy(alpha = 0.35f), fontSize = 18.sp)
            Text(it, color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp)
        }
    }
}

/** Fallback metadata for real engine items that don't use the structured badge kinds. */
@Composable
private fun GenericMetadata(item: RenderItem) {
    val chips =
        buildList {
            add(item.media.mediaType.name.lowercase().replaceFirstChar { it.uppercase() })
            item.card.badges.forEach { badge ->
                val text = badge.text?.takeIf { it.isNotBlank() }
                add(
                    when {
                        badge.kind.equals("RATING", ignoreCase = true) && text != null -> "★ $text"
                        text != null -> text
                        else -> badge.kind
                    },
                )
            }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        chips.forEachIndexed { index, label ->
            if (index > 0) Text("·", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
            val isRating = label.startsWith("★")
            Text(
                text = label,
                color = if (isRating) Color(0xFFDAB440) else Color.White.copy(alpha = 0.85f),
                fontWeight = if (isRating) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp,
            )
        }
    }
}

/** A subtle genre breadcrumb (e.g. "Crime · Mystery · Thriller") under the actions. */
@Composable
private fun GenreBreadcrumb(item: RenderItem) {
    val genres = item.card.badges.filter { it.kind.equals("GENRE", ignoreCase = true) }.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
    if (genres.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        genres.forEachIndexed { index, g ->
            if (index > 0) Text("·", color = Color.White.copy(alpha = 0.28f), fontSize = 16.sp)
            Text(g, color = Color.White.copy(alpha = 0.48f), fontSize = 15.sp)
        }
    }
}

@Composable
private fun BillboardActions(
    actions: List<CardAction>,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onRequest: () -> Unit,
    onTrailer: () -> Unit,
    onWatchlist: () -> Unit,
    onInfo: () -> Unit,
    playFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
) {
    val playFocus = playFocusRequester ?: remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // Primary CTA driven by the hero's actions: Resume > Request (Discover) > Play.
    val (primaryLabel, primaryAction) =
        when {
            CardAction.RESUME in actions -> "Resume" to onPlay
            CardAction.REQUEST in actions && CardAction.PLAY !in actions -> "Request" to onRequest
            else -> "Play" to onPlay
        }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BillboardButton(
            label = primaryLabel,
            leading = "▶",
            primary = true,
            onClick = primaryAction,
            // Up from the primary action returns to the top nav, so the bar is always reachable (#1).
            modifier =
                Modifier
                    .focusRequester(playFocus)
                    .then(
                        if (navFocusRequester != null) {
                            Modifier.focusProperties { up = navFocusRequester }
                        } else {
                            Modifier
                        },
                    ),
        )
        BillboardButton(label = "Trailer", primary = false, onClick = onTrailer)
        BillboardButton(
            label = if (isFavorite) "Watchlisted" else "+ Watchlist",
            primary = false,
            onClick = onWatchlist,
        )
        BillboardButton(label = "Info", primary = false, onClick = onInfo)
    }
}

/**
 * A pill-shaped billboard action. Primary uses the theme accent; the rest are translucent outlines.
 * All brighten to white on focus (with a subtle scale), per the source design's focus treatment.
 * `internal` so the request-landing surface can reuse the exact same control.
 */
@Composable
internal fun BillboardButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
) {
    // The text color is computed and applied EXPLICITLY (not inherited from the Surface's content
    // color) — relying on the TV Surface's content color left the labels painted with an
    // unspecified/invisible color, so the buttons rendered as empty pills (problem #2).
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Explicit, theme-independent label colors so the text is always legible (the theme's onPrimary
    // could be a light color → invisible on the white focused/lavender fills, leaving "empty pills").
    val textColor =
        when {
            focused -> Color(0xFF15121C) // dark on the white focused fill
            primary -> Color(0xFF15121C) // dark on the light lavender primary fill
            else -> Color.White // white on the translucent outline
        }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color.White,
                pressedContainerColor = Color.White,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    if (primary) {
                        Border.None
                    } else {
                        Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.5f)), shape = CircleShape)
                    },
                focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CircleShape),
            ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
        ) {
            leading?.let { Text(it, color = textColor, fontSize = 15.sp, maxLines = 1, softWrap = false) }
            Text(label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
    }
}
