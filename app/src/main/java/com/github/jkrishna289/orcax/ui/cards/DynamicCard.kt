package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import com.github.jkrishna289.orcax.engine.CardAspectRatio
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.engine.CardSize
import com.github.jkrishna289.orcax.engine.CardImageType
import com.github.jkrishna289.orcax.engine.CardType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
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
            StudioCard(
                studioId = null,
                name = card.title,
                imageUrl = resolvedImageUrl,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.width(width),
            )

        else ->
            EngineCardBody(
                item = item,
                resolvedImageUrl = resolvedImageUrl,
                onClick = onClick,
                onLongClick = onLongClick,
                trailerUrlFor = trailerUrlFor,
                backdropUrlFor = backdropUrlFor,
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
    val rank = card.badges.firstOrNull { it.kind.equals("RANK", ignoreCase = true) }?.text
    val interactionSource = remember { MutableInteractionSource() }
    val focusedAfterDelay by rememberFocusedAfterDelay(interactionSource)
    val cardShape = RoundedCornerShape(8.dp)

    // 16:9 cards expand HORIZONTALLY on focus (a real layout width change, so the LazyRow slides the
    // neighboring cards aside instead of overdrawing them) and, after a short dwell, crossfade their
    // art to a quiet inline trailer (replacing the old pop-up). Only these landscape cards play
    // trailers; poster cards are untouched. One card is focused at a time → at most one player.
    val focused by interactionSource.collectIsFocusedAsState()
    var playTrailer by remember { mutableStateOf(false) }
    val trailerUrl = remember(item) { if (isWide) trailerUrlFor(item) else null }
    val backdropUrl = remember(item) { if (isWide) backdropUrlFor(item) else null }
    LaunchedEffect(focused) {
        if (focused && isWide && trailerUrl != null) {
            delay(TRAILER_DWELL_MS)
            playTrailer = true
        } else {
            playTrailer = false
        }
    }
    val animatedWidth by animateDpAsState(
        targetValue = if (isWide && focused) width * WIDE_FOCUS_EXPAND else width,
        animationSpec = tween(durationMillis = 260),
        label = "wide-card-expand",
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
            // Wide cards must NOT scale-transform (that draws over neighbors) — their focus growth is
            // the animated layout width above, which pushes the row's other cards aside instead.
            scale = if (isWide) CardDefaults.scale(focusedScale = 1f) else CardDefaults.scale(),
            modifier = Modifier.size(animatedWidth, height),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EngineCardArt(
                    imageUrl = resolvedImageUrl,
                    name = card.title,
                    accent = accent,
                    modifier = Modifier.fillMaxSize(),
                )

                if (isWide) {
                    // While the card is dwelled, its trailer plays in place over the art (quiet audio).
                    // No encircled play-icon overlay — the enlarge + trailer is the affordance now.
                    if (playTrailer && trailerUrl != null) {
                        InlineCardTrailer(
                            trailerUrl = trailerUrl,
                            backdropUrl = backdropUrl,
                            play = true,
                            volume = TRAILER_VOLUME,
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
                                    .fillMaxWidth(progress.coerceIn(0.0, 1.0).toFloat())
                                    .height(5.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
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

                    // Top-10 rank numeral (engine tags ranked items with a RANK badge).
                    if (rank != null) {
                        Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp)) {
                            Text(
                                text = rank,
                                fontWeight = FontWeight.Black,
                                fontSize = RANK_NUMERAL_SP,
                                lineHeight = RANK_NUMERAL_SP,
                                style = TextStyle(drawStyle = Stroke(width = 6f), color = Color.Black.copy(alpha = 0.55f)),
                            )
                            Text(
                                text = rank,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = RANK_NUMERAL_SP,
                                lineHeight = RANK_NUMERAL_SP,
                            )
                        }
                    }

                    // Overlaid title (and tag, when not a ranked card), bottom-aligned.
                    if (card.showTitle && !card.title.isNullOrBlank()) {
                        Column(
                            horizontalAlignment = if (rank != null) Alignment.End else Alignment.Start,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = card.title!!.uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                lineHeight = 15.sp,
                                letterSpacing = 0.4.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = if (rank != null) TextAlign.End else TextAlign.Start,
                            )
                            if (rank == null && !card.subtitle.isNullOrBlank()) {
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
                        }
                    }
                }

                // Availability / NEW badges top-right (RANK is drawn above; STUDIO is drawn top-left).
                val overlayBadges =
                    card.badges.filterNot {
                        it.kind.equals("RANK", ignoreCase = true) || it.kind.equals("STUDIO", ignoreCase = true)
                    }
                if (overlayBadges.isNotEmpty()) {
                    CardBadges(badges = overlayBadges, modifier = Modifier.align(Alignment.TopEnd))
                }

                // Studio / streaming-provider tag, top-left (provider logo when cached, else a text pill).
                card.badges.firstOrNull { it.kind.equals("STUDIO", ignoreCase = true) }?.let { studio ->
                    StudioBadge(badge = studio, modifier = Modifier.align(Alignment.TopStart))
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
            BadgePill(text = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind)
        }
    }
}

/** The badge label text (bold, small, white) shared by [BadgePill] and the studio-tag fallback. */
@Composable
private fun BadgeText(text: String) {
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
private fun BadgePill(
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
 * loading/error text sits on it without a doubled background.
 */
@Composable
private fun StudioBadge(
    badge: CardBadge,
    modifier: Modifier = Modifier,
) {
    val label = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind
    val logoUrl = LocalImageUrlService.current.engineImageUrl(badge.iconUrl)
    Box(modifier = modifier.padding(8.dp)) {
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
 * Renders an engine [RenderRow] as a horizontal carousel via the app's existing [ItemRow] seam, so
 * dynamic cards inherit the same focus/scroll behavior as every other row. [onFocusItem] fires when
 * a card gains focus, so the caller can drive the focus-following ambient backdrop.
 *
 * Row-style contract: the **engine is authoritative for each item's [com.github.jkrishna289.orcax.engine.CardType]**.
 * A Top-10 row arrives as `TopRanked` items (rank numerals + RANK badges), a person row as
 * `PersonCircle`, and the spotlight as `Hero` (consumed by the Billboard, not here).
 * [RenderRow.rowStyle] is an advisory hint only — this row renders purely from the per-item card
 * descriptors and deliberately does not re-derive layout from `rowStyle`.
 */
@Composable
fun DynamicCardRow(
    row: RenderRow,
    onClickItem: (RenderItem) -> Unit,
    modifier: Modifier = Modifier,
    onLongClickItem: (RenderItem) -> Unit = {},
    onFocusItem: (RenderItem) -> Unit = {},
    focusRequester: FocusRequester? = null,
    // Resolve a 16:9 card's inline trailer + backdrop URLs (engine-cached). Default = no trailer.
    trailerUrlFor: (RenderItem) -> String? = { null },
    backdropUrlFor: (RenderItem) -> String? = { null },
) {
    ItemRow(
        title = row.title,
        items = row.items,
        focusRequester = focusRequester,
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

private const val IMAGE_FILL_HEIGHT = 480

/** Continue Watching / wide cards render at this fraction of the standard poster height (#3). */
private const val WIDE_CARD_SCALE = 0.75f

/** Engine-tagged "Large" cards (the featured For You row) render this much bigger than standard. */
private const val LARGE_CARD_SCALE = 1.35f

/**
 * How much wider a focused 16:9 card grows (horizontal-only, in layout — neighbors slide aside).
 * The trailer fills the wider box via center-crop for a cinematic look.
 */
private const val WIDE_FOCUS_EXPAND = 1.45f

/** Sustained focus on a 16:9 card before its inline trailer starts (avoids firing while scrolling). */
private const val TRAILER_DWELL_MS = 1_200L

/** Inline-card trailer audio: quiet, not muted (per the user) and not intrusive on a 10-ft UI. */
private const val TRAILER_VOLUME = 0.2f

/** Font size of the oversized Top-10 rank numeral drawn over the poster. */
private val RANK_NUMERAL_SP = 88.sp

private fun CardImageType.toSdkImageType(): ImageType =
    when (this) {
        CardImageType.BACKDROP -> ImageType.BACKDROP
        CardImageType.THUMB -> ImageType.THUMB
        CardImageType.LOGO -> ImageType.LOGO
        CardImageType.PRIMARY -> ImageType.PRIMARY
    }
