package com.github.jkrishna289.orcax.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CollectionEntry
import com.github.jkrishna289.orcax.engine.CollectionShelf
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.cards.ItemRow
import com.github.jkrishna289.orcax.ui.cards.WatchedIcon
import com.github.jkrishna289.orcax.ui.cards.availabilityBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * The collections a title belongs to, one framed shelf each: first its TMDB collection (Homecoming,
 * Far From Home, No Way Home), numbered in release order with the next one to watch flagged and the
 * films you don't have greyed out to request; then each server collection holding it ("Based on
 * comic"), which has no watch order, so no numbers. The current title is marked "You're here".
 * Self-contained and fail-soft like [EngineSimilarRow]: renders nothing until the engine answers,
 * and nothing when the title is in no collection.
 */
@Composable
fun CollectionShelves(
    itemId: UUID,
    // OK on the current title: the page puts focus back on its own buttons.
    onCurrentClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Lets the page put focus back here (on the card last used) when returning from a title.
    focusRequester: FocusRequester? = null,
    // Any title opened from a shelf, so the page knows to restore focus here.
    onOpen: () -> Unit = {},
) {
    val viewModel: CollectionShelvesViewModel = hiltViewModel()
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    if (shelves.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .focusGroup()
                .focusRestorer()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        shelves.forEach { shelf ->
            CollectionShelfCard(
                shelf = shelf,
                currentId = itemId.toString(),
                onClick = { entry, current ->
                    if (current) {
                        onCurrentClick()
                    } else {
                        onOpen()
                        viewModel.open(entry)
                    }
                },
            )
        }
    }
}

@Composable
private fun CollectionShelfCard(
    shelf: CollectionShelf,
    currentId: String,
    onClick: (CollectionEntry, Boolean) -> Unit,
) {
    val imageUrlService = LocalImageUrlService.current
    val entries = shelf.items
    val isCurrent = { e: CollectionEntry -> e.item.media.jellyfinId?.let { sameId(it, currentId) } == true }
    val seen = entries.count { it.played }
    val missing = entries.count { it.item.media.jellyfinId == null }
    // The first one you have and haven't finished: only a franchise has an order to be next in.
    val next = if (shelf.franchise) entries.firstOrNull { it.item.media.jellyfinId != null && !it.played } else null
    var focused by remember { mutableIntStateOf(-1) }
    var hasFocus by remember { mutableStateOf(false) }
    val captionAlpha by animateFloatAsState(if (hasFocus) 1f else 0f, label = "caption")
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ShelfBackground)
                .border(1.dp, Color.White.copy(alpha = .08f), shape)
                .onFocusChanged { hasFocus = it.hasFocus },
    ) {
        // The collection's backdrop, blurred and dimmed so the posters stand out (no blur before Android 12).
        val backdrop = remember(shelf.backdropImageUrl) { imageUrlService.engineImageUrl(shelf.backdropImageUrl) }
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = BackdropDim,
                modifier = Modifier.matchParentSize().scale(1.2f).blur(18.dp),
            )
        }
        Box(
            modifier =
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0f to Scrim.copy(alpha = .9f), .75f to Scrim.copy(alpha = .35f)),
                ),
        )

        Column {
            ShelfHeader(shelf, seen, missing)
            ItemRow(
                title = null,
                items = entries,
                horizontalPadding = 20.dp,
                itemSpacing = 16.dp,
                initialPosition = entries.indexOfFirst(isCurrent).coerceAtLeast(0),
                onClickItem = { _, entry -> onClick(entry, isCurrent(entry)) },
                onLongClickItem = { _, _ -> },
                cardContent = { index, entry, cardModifier, onCardClick, _ ->
                    if (entry != null) {
                        CollectionPoster(
                            entry = entry,
                            number = if (shelf.franchise) index + 1 else null,
                            current = isCurrent(entry),
                            next = entry === next,
                            started = seen > 0,
                            onClick = onCardClick,
                            modifier = cardModifier.onFocusChanged { if (it.isFocused) focused = index },
                        )
                    }
                },
            )
            Caption(entries.getOrNull(focused), entries.getOrNull(focused)?.let(isCurrent) == true, Modifier.alpha(captionAlpha))
        }
    }
}

@Composable
private fun ShelfHeader(
    shelf: CollectionShelf,
    seen: Int,
    missing: Int,
) {
    val total = shelf.items.size
    val years = shelf.items.mapNotNull { it.year }
    val span =
        when {
            years.isEmpty() -> null
            years.first() == years.last() -> years.first().toString()
            else -> "${years.first()} – ${years.last()}"
        }
    val allFilms = shelf.items.all { it.item.media.mediaType == MediaType.MOVIE }
    val meta =
        listOfNotNull(
            pluralStringResource(if (allFilms) R.plurals.collection_films else R.plurals.collection_titles, total, total),
            span,
            if (missing > 0) stringResource(R.string.collection_missing, missing) else null,
        ).joinToString("  ·  ")
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.collection).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = TextColor.copy(alpha = .6f),
            )
            Text(
                text = if (shelf.franchise) shelfName(shelf.name) else shelf.name,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = TitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = meta, fontSize = 13.sp, color = TextColor.copy(alpha = .72f), maxLines = 1)
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Text(
                text =
                    when (seen) {
                        total -> stringResource(R.string.collection_all_watched)
                        0 -> stringResource(R.string.collection_not_started)
                        else -> stringResource(R.string.collection_progress, seen, total)
                    },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextColor.copy(alpha = .8f),
            )
            Box(
                modifier =
                    Modifier
                        .width(140.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = .14f)),
            ) {
                Box(Modifier.fillMaxWidth(seen.toFloat() / total).height(4.dp).background(Teal))
            }
        }
    }
}

@Composable
private fun CollectionPoster(
    entry: CollectionEntry,
    number: Int?,
    current: Boolean,
    next: Boolean,
    started: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageUrlService = LocalImageUrlService.current
    val poster = remember(entry.posterImageUrl) { imageUrlService.engineImageUrl(entry.posterImageUrl) }
    val owned = entry.item.media.jellyfinId != null
    var focused by remember { mutableStateOf(false) }
    val tag: Pair<String, Color>? =
        when {
            current -> stringResource(R.string.collection_here) to Lilac
            next -> stringResource(if (started) R.string.up_next else R.string.collection_start_here) to Teal
            !owned ->
                (availabilityBadge(entry.item.media.availability) ?: (R.string.request to AppColors.GoldenYellow))
                    .let { (label, color) -> stringResource(label) to color }
            else -> null
        }

    Card(
        onClick = onClick,
        border = if (current) CardDefaults.border(border = Border(androidx.compose.foundation.BorderStroke(2.dp, Lilac))) else CardDefaults.border(),
        modifier = modifier.size(PosterWidth, PosterHeight).onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.fillMaxSize().background(PosterBackground)) {
            Text(
                text = entry.item.card.title.orEmpty(),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextColor.copy(alpha = .8f),
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
            )
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = entry.item.card.title,
                    contentScale = ContentScale.Crop,
                    // Not yours: greyed until focused, so the row reads at a glance.
                    colorFilter = if (!owned && !focused) MissingFilter else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (number != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                            .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = number.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (owned) TextColor else TextColor.copy(alpha = .6f),
                    )
                }
            }
            if (entry.played) {
                WatchedIcon(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
            }
            if (tag != null) {
                Text(
                    text = tag.first.uppercase(),
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = tag.second,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f))))
                            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 6.dp),
                )
            }
            val pct = entry.playedPercentage
            if (pct != null && pct > 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .height(Cards.playedPercentHeight)
                            .fillMaxWidth((pct / 100).toFloat())
                            .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

/** Names the focused title under the shelf: "Spider-Man: Far From Home · 2019 · 60% watched". */
@Composable
private fun Caption(
    entry: CollectionEntry?,
    current: Boolean,
    modifier: Modifier = Modifier,
) {
    val state =
        when {
            entry == null || current -> null
            entry.played -> stringResource(R.string.collection_watched)
            (entry.playedPercentage ?: 0.0) > 0 -> stringResource(R.string.collection_percent_watched, entry.playedPercentage!!.toInt())
            entry.item.media.jellyfinId == null ->
                when (entry.item.media.availability) {
                    AvailabilityState.REQUESTED -> stringResource(R.string.requested)
                    AvailabilityState.DOWNLOADING -> stringResource(R.string.availability_adding)
                    else -> stringResource(R.string.collection_request_hint)
                }
            else -> null
        }
    Text(
        text =
            buildAnnotatedString {
                if (entry != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TitleColor)) { append(entry.item.card.title.orEmpty()) }
                    listOfNotNull(entry.year?.toString(), state).forEach { append("  ·  $it") }
                }
            },
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextColor.copy(alpha = .7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().height(20.dp + 14.dp).padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
    )
}

/** "Spider-Man (Avengers) Collection" → "Spider-Man": the shelf's label already says Collection. */
internal fun shelfName(name: String): String =
    name
        .replace(Regex("""\s+collection$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\([^)]*\)"""), "")
        .trim()
        .ifEmpty { name }

// Jellyfin ids arrive with or without dashes depending on who formatted them.
private fun sameId(
    a: String,
    b: String,
) = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

private val PosterWidth = 112.dp
private val PosterHeight = 168.dp
private val ShelfBackground = Color(0xFF1C1826)
private val PosterBackground = Color(0xFF241F30)
private val Scrim = Color(0xFF15121C)
private val TextColor = Color(0xFFE8DFF0)
private val TitleColor = Color(0xFFF5F1FA)
private val Teal = Color(0xFF2DE0C0)
private val Lilac = Color(0xFFD2BCFF)
private val BackdropDim = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(.42f, .42f, .42f, 1f) })
private val MissingFilter =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(.1f)
            timesAssign(ColorMatrix().apply { setToScale(.55f, .55f, .55f, 1f) })
        },
    )

/** Loads a title's collection shelves; opens a title like any engine card. */
@HiltViewModel
class CollectionShelvesViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _shelves = MutableStateFlow<List<CollectionShelf>>(emptyList())
        val shelves: StateFlow<List<CollectionShelf>> = _shelves.asStateFlow()

        private var loadedFor: UUID? = null

        fun load(itemId: UUID) {
            if (loadedFor == itemId) return
            loadedFor = itemId
            viewModelScope.launch {
                _shelves.value = client.getCollections(itemId)?.shelves.orEmpty()
            }
        }

        fun open(entry: CollectionEntry) = navigationManager.openRenderItem(entry.item)
    }
