package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                modifier = modifier,
            )
    }
}

/**
 * The shared descriptor-driven card body. Wide cards (Continue Watching) show a center play
 * affordance + accent progress with the title beneath; everything else is a poster with the title
 * overlaid on a bottom scrim (and a Top-10 rank numeral when ranked), matching the source design.
 * Items without resolvable art paint a procedural accent gradient instead of a blank box.
 */
@Composable
private fun EngineCardBody(
    item: RenderItem,
    resolvedImageUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
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

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.width(width),
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
            modifier = Modifier.size(width, height),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EngineCardArt(
                    imageUrl = resolvedImageUrl,
                    name = card.title,
                    accent = accent,
                    modifier = Modifier.fillMaxSize(),
                )

                if (isWide) {
                    // Continue Watching: dim, centered play affordance, accent progress track.
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                    ) {
                        Text(text = "▶", color = Color.White, fontSize = 20.sp)
                    }
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

                // Availability / NEW badges (skip RANK, drawn above).
                val overlayBadges = card.badges.filterNot { it.kind.equals("RANK", ignoreCase = true) }
                if (overlayBadges.isNotEmpty()) {
                    CardBadges(badges = overlayBadges, modifier = Modifier.align(Alignment.TopEnd))
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
        fallback = { Box(modifier = Modifier.fillMaxSize().engineCardArt(accent)) },
        modifier = modifier,
    )
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
            val label = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind
            Box(
                modifier =
                    Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
) {
    ItemRow(
        title = row.title,
        items = row.items,
        onClickItem = { _, item -> onClickItem(item) },
        onLongClickItem = { _, item -> onLongClickItem(item) },
        cardContent = { _, item, cardModifier, onClick, onLongClick ->
            if (item != null) {
                DynamicCard(
                    item = item,
                    onClick = onClick,
                    onLongClick = onLongClick,
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

/** Font size of the oversized Top-10 rank numeral drawn over the poster. */
private val RANK_NUMERAL_SP = 88.sp

private fun CardImageType.toSdkImageType(): ImageType =
    when (this) {
        CardImageType.BACKDROP -> ImageType.BACKDROP
        CardImageType.THUMB -> ImageType.THUMB
        CardImageType.LOGO -> ImageType.LOGO
        CardImageType.PRIMARY -> ImageType.PRIMARY
    }
