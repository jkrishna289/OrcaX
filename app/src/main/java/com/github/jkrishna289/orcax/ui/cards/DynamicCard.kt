package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.CardAction
import com.github.jkrishna289.orcax.engine.CardAspectRatio
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.engine.CardSize
import com.github.jkrishna289.orcax.engine.CardImageType
import com.github.jkrishna289.orcax.engine.CardType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.RowStyle
import com.github.jkrishna289.orcax.engine.TrailerStatus
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.FontAwesome
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.LocalTrailerVolume
import com.github.jkrishna289.orcax.ui.TrailerPhase
import com.github.jkrishna289.orcax.ui.enableMarquee
import com.github.jkrishna289.orcax.ui.main.EngineHomeArt
import com.github.jkrishna289.orcax.ui.main.engineCardArt
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * Renders a single engine-provided [RenderItem] by dispatching on its [CardType].
 *
 * Person/Genre/Studio reuse the app's existing composables (which already encode the right
 * shape and focus behavior); everything else renders through a shared, descriptor-driven body
 * ([EngineCardBody]) that adapts aspect/shape/overlays from the engine's CardDescriptor.
 * Hero is intentionally not handled here — it feeds the home Billboard, not a row.
 */
@Composable
fun DynamicCard(
    item: RenderItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Resolvers for a 16:9 card's inline trailer + backdrop (engine-cached URLs). Default to no
    // trailer so non-home callers (debug preview, detail "similar" row) render unchanged.
    trailerUrlFor: (RenderItem) -> String? = { null },
    backdropUrlFor: (RenderItem) -> String? = { null },
    // Queries the engine trailer state machine for adaptive, state-driven playback. Null = best-effort.
    trailerStatusProvider: (suspend (RenderItem) -> TrailerStatus?)? = null,
) {
    val card = item.card
    val resolvedImageUrl = rememberEngineImageUrl(item)
    val width = Cards.height2x3 * aspectRatioValue(card.aspectRatio)

    when (card.type) {
        CardType.PERSON_CIRCLE ->
            PersonCard(
                name = card.title,
                role = card.subtitle,
                imageUrl = resolvedImageUrl,
                favorite = false,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.width(Cards.height2x3),
            )

        CardType.GENRE ->
            GenreCard(
                genreId = null,
                name = card.title,
                imageUrl = resolvedImageUrl,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.width(width),
            )

        CardType.STUDIO ->
            // Square studio cards are the "Browse by Network" wordmark tiles; StudioCard hard-codes
            // a 16:9 box, so squares get their own tile treatment.
            if (card.aspectRatio == CardAspectRatio.SQUARE) {
                NetworkTileCard(
                    name = card.title,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier,
                )
            } else {
                StudioCard(
                    studioId = null,
                    name = card.title,
                    imageUrl = resolvedImageUrl,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier.width(width),
                )
            }

        CardType.TOP_RANKED ->
            TopRankedCard(
                item = item,
                resolvedImageUrl = resolvedImageUrl,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
            )

        else ->
            EngineCardBody(
                item = item,
                resolvedImageUrl = resolvedImageUrl,
                onClick = onClick,
                onLongClick = onLongClick,
                trailerUrlFor = trailerUrlFor,
                backdropUrlFor = backdropUrlFor,
                trailerStatusProvider = trailerStatusProvider,
                modifier = modifier,
            )
    }
}

/**
 * The shared descriptor-driven card body. Wide (16:9) cards enlarge on focus and, after a short
 * dwell, play their trailer in place ([InlineCardTrailer]) — with accent progress + title beneath
 * for Continue Watching. Everything else is a poster with the title overlaid on a bottom scrim (and
 * a Top-10 rank numeral when ranked). Items without resolvable art paint a procedural accent
 * gradient instead of a blank box.
 */
@Composable
private fun EngineCardBody(
    item: RenderItem,
    resolvedImageUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailerUrlFor: (RenderItem) -> String? = { null },
    backdropUrlFor: (RenderItem) -> String? = { null },
    trailerStatusProvider: (suspend (RenderItem) -> TrailerStatus?)? = null,
) {
    val card = item.card
    val accent = EngineHomeArt.parseAccent(card.accentColorHint)
    val isWide = card.aspectRatio == CardAspectRatio.WIDE
    // Continue Watching (wide) cards render ~25% smaller than the standard poster row (problem #3);
    // engine-tagged "Large" cards (the featured For You row) render bigger for visual variety.
    val baseScale = if (isWide) WIDE_CARD_SCALE else 1f
    val sizeScale = if (card.size == CardSize.LARGE) LARGE_CARD_SCALE else 1f
    val height = Cards.height2x3 * baseScale * sizeScale
    val width = height * aspectRatioValue(card.aspectRatio)
    val interactionSource = remember { MutableInteractionSource() }
    val focusedAfterDelay by rememberFocusedAfterDelay(interactionSource)
    val cardShape = RoundedCornerShape(8.dp)

    // 16:9 trailer flow (redesigned for perceived performance). Hold focus for the dwell (3.5s) → the
    // card expands IMMEDIATELY and shows a loading shimmer while the trailer prepares (no waiting on
    // the player to reach READY) → it crossfades to video once actually playing → it collapses back
    // when the trailer ends or turns out unavailable. Expansion is horizontal layout width (the LazyRow
    // slides neighbours aside) PLUS a subtle centred +15% height. Cards without a trailer URL never
    // enlarge; poster cards are untouched.
    val focused by interactionSource.collectIsFocusedAsState()
    var playTrailer by remember { mutableStateOf(false) }
    var trailerPhase by remember { mutableStateOf(TrailerPhase.IDLE) }
    val trailerUrl = remember(item) { if (isWide) trailerUrlFor(item) else null }
    val backdropUrl = remember(item) { if (isWide) backdropUrlFor(item) else null }
    // Per-item status query for the adaptive, state-driven start (bound to this item).
    val statusProvider: (suspend () -> TrailerStatus?)? =
        remember(item) { trailerStatusProvider?.let { provider -> { provider(item) } } }
    LaunchedEffect(focused) {
        if (focused && isWide && trailerUrl != null) {
            delay(TRAILER_DWELL_MS)
            playTrailer = true
        } else {
            playTrailer = false
            trailerPhase = TrailerPhase.IDLE
        }
    }
    // Expand only once the trailer is actually PLAYING: the card stays visually untouched while the
    // trailer prepares (no early expand, no loading animation) and pops into the live preview the
    // moment playback starts. A card whose trailer never resolves simply never changes.
    val expanded = isWide && trailerPhase == TrailerPhase.PLAYING
    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) width * WIDE_FOCUS_EXPAND else width,
        animationSpec = tween(durationMillis = 260),
        label = "wide-card-expand-width",
    )
    // Subtle +15% height, grown from the centre as a transform (not a layout change) so it holds 60fps,
    // doesn't reflow the row, and doesn't clip (Phase 12).
    val heightScale by animateFloatAsState(
        targetValue = if (expanded) WIDE_FOCUS_HEIGHT_SCALE else 1f,
        animationSpec = tween(durationMillis = 260),
        label = "wide-card-expand-height",
    )

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.width(animatedWidth),
    ) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(cardShape),
            colors = CardDefaults.colors(containerColor = Color.Transparent),
            border =
                CardDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape),
                ),
            // Wide cards keep the TV focus scale OFF: horizontal growth is the animated layout width
            // above (neighbours slide aside, no overdraw), and the vertical growth is the centred
            // graphicsLayer scaleY below — a transform, so it grows into the row's spacing without
            // reflowing the row or clipping. Poster cards keep the default focus scale.
            scale = if (isWide) CardDefaults.scale(focusedScale = 1f) else CardDefaults.scale(),
            modifier =
                Modifier
                    .size(animatedWidth, height)
                    .graphicsLayer {
                        scaleY = heightScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EngineCardArt(
                    imageUrl = resolvedImageUrl,
                    name = card.title,
                    accent = accent,
                    modifier = Modifier.fillMaxSize(),
                )

                // While a wide card's inline trailer is actually playing it becomes a mini live
                // preview: the resume affordances and generic promo badges give way to trailer chrome.
                val trailerShowing = isWide && playTrailer && trailerPhase == TrailerPhase.PLAYING

                if (isWide) {
                    // Mounted after the dwell; shows the backdrop + shimmer while preparing, crossfades
                    // to video once actually playing, plays once at the configured volume, and reports
                    // its phase so the card expands immediately and collapses on end/unavailable.
                    if (playTrailer && trailerUrl != null) {
                        InlineCardTrailer(
                            trailerUrl = trailerUrl,
                            backdropUrl = backdropUrl,
                            play = true,
                            // Centralised inline-trailer volume (Phase 13); applied live.
                            volume = LocalTrailerVolume.current,
                            statusProvider = statusProvider,
                            onPhaseChange = { trailerPhase = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Dim base so the progress track / title read over the art.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.55f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.55f),
                                    ),
                                ),
                    )
                    val progress = card.progress
                    if (card.showProgress && progress != null) {
                        val fillFraction = progress.coerceIn(0.0, 1.0).toFloat()
                        // Soft glow halo behind the accent fill — static normally, pulsing only while
                        // focused (one infinite animation per row at most, not one per card).
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(fillFraction)
                                    .height(9.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = pulsingGlowAlpha(active = focused)),
                                    ),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .background(Color.White.copy(alpha = 0.25f)),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(fillFraction)
                                    .height(5.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                        )

                        // Resume affordances hide while an inline trailer is actually showing.
                        if (!trailerShowing) {
                            // "24 min left" chip (TIMELEFT badge), just above the progress bar.
                            card.badges
                                .firstOrNull { it.kind.equals("TIMELEFT", ignoreCase = true) }
                                ?.text
                                ?.takeIf { it.isNotBlank() }
                                ?.let { left ->
                                    Box(
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(start = 10.dp, bottom = 14.dp)
                                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(5.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = left,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            if (CardAction.RESUME in card.actions) {
                                ResumePill(
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 10.dp, bottom = 14.dp),
                                )
                            }
                        }
                    }

                    // Live-preview chrome while the trailer is actually playing: a "TRAILER" chip
                    // with animated equalizer bars top-left, the volume state top-right.
                    if (trailerShowing) {
                        TrailerPlayingChip(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                        TrailerVolumeChip(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                } else {
                    // Poster: bottom scrim so the overlaid title always reads over the art.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.48f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.82f),
                                    ),
                                ),
                    )

                    // Overlaid title block, bottom-aligned: an optional centered PREMIERE chip, the
                    // title, the tag, and an optional italic personalization footnote (CONTEXT badge,
                    // e.g. "Based on Nightfall Protocol").
                    if (card.showTitle && !card.title.isNullOrBlank()) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            card.badges.firstOrNull { it.kind.equals("PREMIERE", ignoreCase = true) }?.let { premiere ->
                                Box(
                                    modifier =
                                        Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .padding(bottom = 8.dp),
                                ) {
                                    BadgePill(text = premiere.text?.takeIf { it.isNotBlank() } ?: premiere.kind)
                                }
                            }
                            Text(
                                text = card.title!!.uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                letterSpacing = 0.4.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!card.subtitle.isNullOrBlank()) {
                                Text(
                                    text = card.subtitle!!,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            card.badges
                                .firstOrNull { it.kind.equals("CONTEXT", ignoreCase = true) }
                                ?.text
                                ?.takeIf { it.isNotBlank() }
                                ?.let { context ->
                                    Text(
                                        text = context,
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontWeight = FontWeight.Medium,
                                        fontStyle = FontStyle.Italic,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                        }
                    }
                }

                // The corner badges step aside for the trailer chrome while a preview is playing.
                if (!trailerShowing) {
                    // Availability / promo badges top-right. Kinds with a dedicated placement are
                    // excluded: RANK feeds TopRankedCard, STUDIO/DAY/TODAY sit top-left, PREMIERE and
                    // CONTEXT live in the title block, TIMELEFT is the wide card's bottom-left chip.
                    val overlayBadges =
                        card.badges.filterNot { badge ->
                            PLACED_BADGE_KINDS.any { badge.kind.equals(it, ignoreCase = true) }
                        }
                    if (overlayBadges.isNotEmpty()) {
                        CardBadges(badges = overlayBadges, modifier = Modifier.align(Alignment.TopEnd))
                    }

                    // Top-left stack: studio / provider tag, then the premiere-day chip (TODAY glows gold).
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        card.badges.firstOrNull { it.kind.equals("STUDIO", ignoreCase = true) }?.let { studio ->
                            StudioBadge(badge = studio)
                        }
                        card.badges.firstOrNull { it.kind.equals("TODAY", ignoreCase = true) }?.let { today ->
                            TodayChip(text = today.text?.takeIf { it.isNotBlank() } ?: today.kind)
                        }
                        card.badges.firstOrNull { it.kind.equals("DAY", ignoreCase = true) }?.let { day ->
                            DayChip(text = day.text?.takeIf { it.isNotBlank() } ?: day.kind)
                        }
                    }
                }
            }
        }

        // Wide cards keep the title + subtitle beneath the thumbnail (poster titles are overlaid).
        if (isWide && card.showTitle && !card.title.isNullOrBlank()) {
            Text(
                text = card.title!!,
                maxLines = 1,
                fontWeight = FontWeight.Bold,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().enableMarquee(focusedAfterDelay),
            )
            if (!card.subtitle.isNullOrBlank()) {
                Text(
                    text = card.subtitle!!,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Card art: real image when available, else a procedural accent gradient (sample/unresolved art). */
@Composable
private fun EngineCardArt(
    imageUrl: String?,
    name: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // The accent gradient is painted immediately as a placeholder so cards never flash blank
        // while their poster downloads (problem #1); the real art then crossfades in over it, and
        // the gradient also remains visible if the image is missing or fails.
        Box(modifier = Modifier.fillMaxSize().engineCardArt(accent))
        ItemCardImage(
            imageUrl = imageUrl,
            name = name,
            showOverlay = false,
            favorite = false,
            watched = false,
            unwatchedCount = 0,
            watchedPercent = null,
            numberOfVersions = -1,
            useFallbackText = false,
            contentScale = ContentScale.Crop,
            crossfade = true,
            fallback = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Resolves the image URL: engine-provided, else built from the Jellyfin id (poster/thumb/etc.). */
@Composable
private fun rememberEngineImageUrl(item: RenderItem): String? {
    val card = item.card
    val media = item.media
    val imageUrlService = LocalImageUrlService.current
    // Continue Watching (showProgress) items are episodes: their THUMB usually doesn't exist, which
    // left the cards blank. PRIMARY is the episode still and is almost always present (#3). Other wide
    // (16:9) cards use the BACKDROP — the reliable landscape art (THUMB is often missing for movies).
    // The engine still wins if it sends an explicit imageUrl.
    val sdkImageType =
        when {
            card.showProgress -> ImageType.PRIMARY
            card.aspectRatio == CardAspectRatio.WIDE -> ImageType.BACKDROP
            else -> card.imageType.toSdkImageType()
        }
    return card.imageUrl
        ?: media.jellyfinId?.toUUIDOrNull()?.let { id ->
            imageUrlService.getItemImageUrl(
                itemId = id,
                imageType = sdkImageType,
                fillHeight = IMAGE_FILL_HEIGHT,
            )
        }
}

private fun aspectRatioValue(aspect: CardAspectRatio): Float =
    when (aspect) {
        CardAspectRatio.WIDE -> AspectRatios.WIDE
        CardAspectRatio.SQUARE -> 1f
        CardAspectRatio.FOUR_THREE -> 4f / 3f
        CardAspectRatio.TALL -> AspectRatios.TALL
    }

/**
 * Top-right badge chips, dispatched by kind: TOP_PICK is an accent-filled pill, LIVE is a dark chip
 * with a pulsing dot (live viewer counts), and everything else (NEW, TRENDING, REQUESTED,
 * DOWNLOADING, …) renders as a glassy outlined pill per the design language.
 */
@Composable
private fun CardBadges(
    badges: List<CardBadge>,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(8.dp),
    ) {
        badges.forEach { badge ->
            val label = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind
            when {
                badge.kind.equals("TOP_PICK", ignoreCase = true) -> AccentBadgePill(text = label)
                badge.kind.equals("LIVE", ignoreCase = true) -> LiveViewersBadge(text = label)
                else -> GlassBadgePill(text = label)
            }
        }
    }
}

/**
 * The badge label text (bold, small, white) shared by [BadgePill], the studio-tag fallback, and the
 * generic [EngineBadge] strip. `internal` so BadgeStrip.kt can reuse the exact same treatment.
 */
@Composable
internal fun BadgeText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A dark rounded pill wrapping [BadgeText] — the shared badge chip. */
@Composable
internal fun BadgePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        BadgeText(text)
    }
}

/**
 * The studio / streaming-provider tag (top-left corner). Renders the engine's cached provider logo on a
 * dark chip for contrast (most brand logos are light-on-transparent); falls back to the styled text pill
 * when there's no logo URL or the image fails to load. The chip background is on the container so the
 * loading/error text sits on it without a doubled background. Outer padding is the caller's (it sits in
 * the top-left overlay stack alongside the premiere-day chips).
 */
@Composable
private fun StudioBadge(
    badge: CardBadge,
    modifier: Modifier = Modifier,
) {
    val label = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind
    val logoUrl = LocalImageUrlService.current.engineImageUrl(badge.iconUrl)
    Box(modifier = modifier) {
        if (logoUrl == null) {
            BadgePill(text = label)
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                loading = { BadgeText(label) },
                error = { BadgeText(label) },
                modifier =
                    Modifier
                        .height(22.dp)
                        .widthIn(max = 76.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * A Top-10 ranked card: the oversized gradient rank numeral sits BESIDE the poster, overlapping its
 * left edge (per the design mockup — Netflix-style), with the title beneath the poster instead of
 * overlaid. The numeral is a brushed white→grey gradient over a dark offset copy (the mockup's
 * stacked drop shadow, collapsed to one draw). Badges other than RANK (e.g. LIVE viewer counts,
 * REQUESTED) still render top-right; focus keeps the standard white border + scale.
 */
@Composable
private fun TopRankedCard(
    item: RenderItem,
    resolvedImageUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = item.card
    val accent = EngineHomeArt.parseAccent(card.accentColorHint)
    val rank = card.badges.firstOrNull { it.kind.equals("RANK", ignoreCase = true) }?.text.orEmpty()
    val height = Cards.height2x3
    val width = height * AspectRatios.TALL
    val cardShape = RoundedCornerShape(8.dp)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.width(RANK_NUMERAL_INSET + width),
    ) {
        Box {
            Card(
                onClick = onClick,
                onLongClick = onLongClick,
                shape = CardDefaults.shape(cardShape),
                colors = CardDefaults.colors(containerColor = Color.Transparent),
                border =
                    CardDefaults.border(
                        focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape),
                    ),
                modifier = Modifier.padding(start = RANK_NUMERAL_INSET).size(width, height),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EngineCardArt(
                        imageUrl = resolvedImageUrl,
                        name = card.title,
                        accent = accent,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val overlayBadges = card.badges.filterNot { it.kind.equals("RANK", ignoreCase = true) }
                    if (overlayBadges.isNotEmpty()) {
                        CardBadges(badges = overlayBadges, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
            // Drawn after the Card so it overlaps the poster's left edge.
            RankNumeral(
                rank = rank,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .width(RANK_NUMERAL_INSET + RANK_NUMERAL_OVERLAP),
            )
        }
        if (card.showTitle && !card.title.isNullOrBlank()) {
            Text(
                text = card.title!!,
                maxLines = 1,
                fontWeight = FontWeight.Bold,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = RANK_NUMERAL_INSET).fillMaxWidth(),
            )
        }
    }
}

/**
 * The oversized rank numeral: end-aligned in its slot so a wider glyph run ("10") spills LEFT into
 * the row gap (like the mockup) rather than over the poster. Overflow stays visible on purpose.
 */
@Composable
private fun RankNumeral(
    rank: String,
    modifier: Modifier = Modifier,
) {
    val numeralSize = with(LocalDensity.current) { RANK_NUMERAL_HEIGHT.toSp() }
    Box(modifier = modifier) {
        // Dark offset copy behind the gradient — the mockup's stacked drop shadow in a single draw.
        Text(
            text = rank,
            color = RankShadowColor,
            fontWeight = FontWeight.Black,
            fontSize = numeralSize,
            lineHeight = numeralSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().offset(x = 3.dp, y = 6.dp),
        )
        Text(
            text = rank,
            fontWeight = FontWeight.Black,
            fontSize = numeralSize,
            lineHeight = numeralSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = TextAlign.End,
            style =
                TextStyle(
                    brush = Brush.linearGradient(RankNumeralGradient),
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(9f, 17f), blurRadius = 20f),
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A "Browse by Network" tile: a flat square with a centered wordmark (no artwork by design). */
@Composable
private fun NetworkTileCard(
    name: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = NetworkTileBackground),
        border =
            CardDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
                focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = shape),
            ),
        modifier = modifier.size(NETWORK_TILE_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                text = name.orEmpty().uppercase(),
                color = Color(0xFFF1ECF8),
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A glassy outlined pill (promo badges: NEW, TRENDING, REQUESTED, DOWNLOADING, "New Episode"…). */
@Composable
private fun GlassBadgePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier =
            modifier
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.16f),
                        0.5f to Color.White.copy(alpha = 0.03f),
                        1f to Color.White.copy(alpha = 0.10f),
                    ),
                    shape,
                )
                .border(1.25.dp, Color.White.copy(alpha = 0.55f), shape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An accent-filled pill with dark text — the "★ Top Pick" treatment. */
@Composable
private fun AccentBadgePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = Color(0xFF1D1625),
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A dark chip with a pulsing red dot — live viewer counts on the Trending row ("2.4k ▲"). */
@Composable
private fun LiveViewersBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        PulsingDot(color = LiveDotRed)
        BadgeText(text)
    }
}

/** The gold, pulsing "TODAY" premiere chip (top-left). */
@Composable
private fun TodayChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), shape)
                .border(1.5.dp, PremiereGold.copy(alpha = 0.8f), shape)
                .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        PulsingDot(color = PremiereGold)
        Text(
            text = text.uppercase(),
            color = PremiereGold,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A plain day-of-week premiere chip (top-left), e.g. "TUE". */
@Composable
private fun DayChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The "TRAILER" chip with animated equalizer bars — shown only while an inline trailer plays. */
@Composable
private fun TrailerPlayingChip(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(R.string.trailer).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
        )
        TrailerEqualizerBars()
    }
}

/** Four staggered audio-wave bars (accent-colored). Composed only while a trailer plays. */
@Composable
private fun TrailerEqualizerBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "trailer-eq")
    val fractions =
        EQ_BAR_STAGGER_MS.map { stagger ->
            transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 450),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(stagger),
                    ),
                label = "trailer-eq-$stagger",
            )
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.height(12.dp),
    ) {
        fractions.forEach { fraction ->
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight(fraction.value)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}

/** The trailer's volume state (top-right circle) — high/low speaker per the shared preview volume. */
@Composable
private fun TrailerVolumeChip(modifier: Modifier = Modifier) {
    val quiet = LocalTrailerVolume.current <= 0f
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
    ) {
        Text(
            text = stringResource(if (quiet) R.string.fa_volume_low else R.string.fa_volume_high),
            fontFamily = FontAwesome,
            color = Color.White,
            fontSize = 11.sp,
        )
    }
}

/** The "▶ Resume" pill on a Continue Watching card (accent-tinted, bottom-right). */
@Composable
private fun ResumePill(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .background(accent.copy(alpha = 0.18f), shape)
                .border(1.dp, accent.copy(alpha = 0.55f), shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = "▶", color = accent, fontSize = 9.sp, maxLines = 1, softWrap = false)
        Text(
            text = stringResource(R.string.resume).uppercase(),
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The progress-bar glow alpha: a gentle pulse while [active] (the card is focused), a fixed soft
 * halo otherwise — so a full Continue Watching row doesn't run a dozen infinite animations at once.
 */
@Composable
private fun pulsingGlowAlpha(active: Boolean): Float =
    if (active) {
        val transition = rememberInfiniteTransition(label = "progress-glow")
        transition
            .animateFloat(
                initialValue = PROGRESS_GLOW_IDLE_ALPHA,
                targetValue = 0.75f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 1250), RepeatMode.Reverse),
                label = "progress-glow-alpha",
            ).value
    } else {
        PROGRESS_GLOW_IDLE_ALPHA
    }

/** A small dot that pulses its alpha — live/today indicators. Shared with the home Billboard. */
@Composable
internal fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "pulsing-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "pulsing-dot-alpha",
    )
    Box(
        modifier =
            modifier
                .size(size)
                .graphicsLayer { this.alpha = alpha }
                .background(color, CircleShape),
    )
}

/**
 * Renders an engine [RenderRow] as a horizontal carousel via the app's existing [ItemRow] seam, so
 * dynamic cards inherit the same focus/scroll behavior as every other row. [onFocusItem] fires when
 * a card gains focus, so the caller can drive the focus-following ambient backdrop.
 *
 * Row-style contract: the **engine is authoritative for each item's [com.github.jkrishna289.orcax.engine.CardType]**.
 * A Top-10 row arrives as `TopRanked` items (rank numerals + RANK badges), a person row as
 * `PersonCircle`, and the legacy hero row as `Hero` (consumed by the Billboard, not here).
 * [RenderRow.rowStyle] is otherwise an advisory hint only — this row renders purely from the
 * per-item card descriptors and deliberately does not re-derive carousel layout from `rowStyle`.
 *
 * The **one** exception is [RowStyle.SPOTLIGHT], which is genuine routing rather than styling: it
 * renders its single item as a full-bleed [SpotlightCard] instead of a carousel. It still behaves
 * as an ordinary focusable row, so the caller's focus-first recede/restore wrapper applies unchanged.
 */
@Composable
fun DynamicCardRow(
    row: RenderRow,
    onClickItem: (RenderItem) -> Unit,
    modifier: Modifier = Modifier,
    onLongClickItem: (RenderItem) -> Unit = {},
    onFocusItem: (RenderItem) -> Unit = {},
    onWatchlistItem: (RenderItem) -> Unit = {},
    focusRequester: FocusRequester? = null,
    // Resolve a 16:9 card's inline trailer + backdrop URLs (engine-cached). Default = no trailer.
    trailerUrlFor: (RenderItem) -> String? = { null },
    backdropUrlFor: (RenderItem) -> String? = { null },
    // Queries the engine trailer state machine for adaptive, state-driven playback. Null = best-effort.
    trailerStatusProvider: (suspend (RenderItem) -> TrailerStatus?)? = null,
) {
    if (row.rowStyle == RowStyle.SPOTLIGHT) {
        // Exactly one item by contract; an empty row is a server bug, so render nothing rather
        // than crashing on first().
        val feature = row.items.firstOrNull()
        if (feature != null) {
            SpotlightCard(
                item = feature,
                onClick = { onClickItem(feature) },
                onWatchlist = { onWatchlistItem(feature) },
                trailerUrlFor = trailerUrlFor,
                trailerStatusProvider = trailerStatusProvider,
                focusRequester = focusRequester,
                modifier =
                    modifier.onFocusChanged {
                        if (it.hasFocus) onFocusItem(feature)
                    },
            )
        }
        return
    }

    ItemRow(
        title = row.title,
        items = row.items,
        focusRequester = focusRequester,
        // A Top-10 row carries a "TOP 10" chip beside its title (the ranked layout itself comes from
        // each item's TOP_RANKED card type — rowStyle stays an advisory hint).
        titleExtra = if (row.rowStyle == RowStyle.TOP10) ({ Top10Chip() }) else null,
        onClickItem = { _, item -> onClickItem(item) },
        onLongClickItem = { _, item -> onLongClickItem(item) },
        cardContent = { _, item, cardModifier, onClick, onLongClick ->
            if (item != null) {
                DynamicCard(
                    item = item,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    trailerUrlFor = trailerUrlFor,
                    backdropUrlFor = backdropUrlFor,
                    trailerStatusProvider = trailerStatusProvider,
                    modifier =
                        cardModifier.onFocusChanged {
                            if (it.isFocused) onFocusItem(item)
                        },
                )
            }
        },
        modifier = modifier,
    )
}

/** The "TOP 10" chip beside a Top-10 row's title (light fill, heavy dark text). */
@Composable
private fun Top10Chip(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .background(Color(0xFFEFE7FA), RoundedCornerShape(5.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(R.string.top_10),
            color = Color(0xFF1D1625),
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val IMAGE_FILL_HEIGHT = 480

/** Continue Watching / wide cards render at this fraction of the standard poster height (#3). */
private const val WIDE_CARD_SCALE = 0.75f

/** Engine-tagged "Large" cards (the featured For You row) render this much bigger than standard. */
private const val LARGE_CARD_SCALE = 1.35f

/**
 * How much wider a focused 16:9 card grows (horizontal, in layout — neighbors slide aside).
 * The trailer fills the wider box via center-crop for a cinematic look. Raised from 1.45 (with the
 * height scale from 1.15) so the expanded preview reads ~35% larger overall — the old expansion was
 * too small to comfortably watch on a TV.
 */
private const val WIDE_FOCUS_EXPAND = 1.95f

/**
 * How much taller a focused 16:9 card grows, applied as a centred transform (not a layout change)
 * so it grows into the row's spacing without reflowing the row (Phase 12). Kept below the width
 * factor: as a transform it overdraws neighbouring items, so it only slightly exceeds the row gap.
 */
private const val WIDE_FOCUS_HEIGHT_SCALE = 1.35f

/** Sustained focus on a 16:9 card before its trailer starts loading (avoids firing while scrolling). */
private const val TRAILER_DWELL_MS = 3_500L

/** Resting alpha of the Continue Watching progress glow (it pulses above this while focused). */
private const val PROGRESS_GLOW_IDLE_ALPHA = 0.3f

/** Start offsets of the four trailer equalizer bars, so they wave instead of pumping in unison. */
private val EQ_BAR_STAGGER_MS = listOf(0, 150, 300, 450)

/** Badge kinds with a dedicated placement — excluded from the generic top-right chip row. */
private val PLACED_BADGE_KINDS = listOf("RANK", "STUDIO", "DAY", "TODAY", "PREMIERE", "CONTEXT", "TIMELEFT")

/** Horizontal room reserved beside a ranked poster for its rank numeral (mockup: ~26% of card width). */
private val RANK_NUMERAL_INSET = 36.dp

/** How far the rank numeral overlaps the poster's left edge (mockup: ~38% of the card width). */
private val RANK_NUMERAL_OVERLAP = 44.dp

/** Glyph height of the beside-the-card rank numeral (mockup: ~77% of the poster height). */
private val RANK_NUMERAL_HEIGHT = 128.dp

/** White→grey vertical sheen of the rank numeral (mockup's 160° gradient). */
private val RankNumeralGradient = listOf(Color.White, Color(0xFFE1DBEA), Color(0xFFABA1BB))

/** The near-black backing color of the rank numeral's offset copy. */
private val RankShadowColor = Color(0xFF0A0810)

/** Gold used by the TODAY premiere chip. */
private val PremiereGold = Color(0xFFFFD250)

/** Red used by live/pulsing dots (live viewer counts, NOW PREVIEWING). */
internal val LiveDotRed = Color(0xFFFF3B30)

/** Side length of a "Browse by Network" square wordmark tile. */
private val NETWORK_TILE_SIZE = 128.dp

/** Background of a network wordmark tile (flat, no artwork by design). */
private val NetworkTileBackground = Color(0xFF1E1A28)

private fun CardImageType.toSdkImageType(): ImageType =
    when (this) {
        CardImageType.BACKDROP -> ImageType.BACKDROP
        CardImageType.THUMB -> ImageType.THUMB
        CardImageType.LOGO -> ImageType.LOGO
        CardImageType.PRIMARY -> ImageType.PRIMARY
    }
