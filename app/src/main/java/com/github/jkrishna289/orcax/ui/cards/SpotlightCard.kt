package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.TrailerStatus
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.LocalTrailerVolume
import com.github.jkrishna289.orcax.ui.TrailerPhase
import com.github.jkrishna289.orcax.ui.main.BillboardButton
import com.github.jkrishna289.orcax.ui.main.EngineHomeArt
import com.github.jkrishna289.orcax.ui.main.engineHeroArt
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * The dedicated cinematic showcase — the single item of a
 * [com.github.jkrishna289.orcax.engine.RowStyle.SPOTLIGHT] row, rendered nearly full-screen with
 * **no row caption**: identity comes from the treatment, not a label (product ruling).
 *
 * It speaks the Billboard's card language — rounded 28dp corners, slight edge inset, the theme's
 * lavender accent — so the two hero surfaces rhyme. The interaction is focus-first and unhurried:
 *  1. Focus arrives → a rounded accent frame marks the card; artwork, title, metadata and synopsis
 *     are fully legible.
 *  2. After a ~1.5s dwell the trailer starts and the frame recedes; while it actually plays, the
 *     detail tier (metadata, synopsis, corner logo) fades out fully and the title holds at 85%.
 *  3. Focus leaves → the dwell is cancelled and playback stops immediately.
 *
 * There is deliberately no progress bar: the preview is ambience, not playback UI. Playback itself
 * is entirely [InlineCardTrailer]'s (status-gated start, leased pooled player, volume, crossfade).
 */
@Composable
internal fun SpotlightCard(
    item: RenderItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onWatchlist: () -> Unit = onClick,
    trailerUrlFor: (RenderItem) -> String? = { null },
    trailerStatusProvider: (suspend (RenderItem) -> TrailerStatus?)? = null,
    // Row-focus restoration lands here (EngineHomePage re-focuses the last row on return).
    focusRequester: FocusRequester? = null,
) {
    val card = item.card
    val context = LocalContext.current
    val imageUrlService = LocalImageUrlService.current
    val jellyfinId = item.media.jellyfinId?.toUUIDOrNull()
    val accent = EngineHomeArt.parseAccent(card.accentColorHint)

    // Resolved through the image service, not used raw: an engine-served path is server-relative
    // (`/OrcaEngine/Images/…`) and would silently fail to load, leaving the procedural art in place
    // even though real backdrop artwork exists. Absolute URLs pass through unchanged.
    val backdropUrl =
        imageUrlService.engineImageUrl(card.backdropImageUrl)
            ?: jellyfinId?.let {
                imageUrlService.getItemImageUrl(itemId = it, imageType = ImageType.BACKDROP, fillHeight = 1080)
            }
    // An explicit stream on the card wins (the sample/demo bundle and any engine that inlines a URL);
    // otherwise resolve through the engine's cached-trailer seam like every 16:9 row card does.
    val trailerUrl = remember(item) { card.trailerStreamUrl ?: trailerUrlFor(item) }

    // Focus of the whole card subtree (the action buttons are the real focus targets).
    var focused by remember { mutableStateOf(false) }
    var play by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(TrailerPhase.IDLE) }

    // The dwell: read-first, then play. Cancelling this effect on blur is what makes the trailer
    // stop the instant focus leaves — no timer survives the card losing focus.
    LaunchedEffect(focused) {
        if (!focused) {
            play = false
            return@LaunchedEffect
        }
        delay(SPOTLIGHT_TRAILER_DELAY_MS)
        play = true
    }

    val playing = phase == TrailerPhase.PLAYING
    // Two-tier recede while the trailer plays: the detail tier (metadata, synopsis, corner logo)
    // steps out entirely; the title only dims, so you never lose track of what you're watching.
    val detailAlpha by animateFloatAsState(
        targetValue = if (playing) 0f else 1f,
        animationSpec = tween(durationMillis = 450),
        label = "spotlight-detail-alpha",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (playing) SPOTLIGHT_TITLE_PLAYING_ALPHA else 1f,
        animationSpec = tween(durationMillis = 450),
        label = "spotlight-title-alpha",
    )
    // Focus frame: full strength while focus arrives (the read window), receding once the card is
    // engaged (dwell fired) so nothing competes with the content. Gone entirely when unfocused.
    val frameAlpha by animateFloatAsState(
        targetValue =
            when {
                !focused -> 0f
                play -> SPOTLIGHT_FRAME_ENGAGED_ALPHA
                else -> SPOTLIGHT_FRAME_FOCUS_ALPHA
            },
        animationSpec = tween(durationMillis = 400),
        label = "spotlight-frame-alpha",
    )

    // The corner slot takes the first image badge (typically the provider/studio logo); everything
    // metadata-shaped feeds the metadata line instead, and AVAILABILITY is expressed once, by the
    // primary action button — never as a raw chip.
    val cornerBadge = card.badges.firstOrNull { it.iconUrl != null }
    val stripBadges =
        card.badges.filterNot {
            it === cornerBadge || it.kind.uppercase() in SPOTLIGHT_CONSUMED_KINDS
        }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(screenHeight * SPOTLIGHT_HEIGHT_FRACTION)
                // Slim edge inset + rounded clip: the Billboard's floating-card language.
                .padding(horizontal = 14.dp)
                .clip(shape)
                .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = frameAlpha), shape)
                .onFocusChanged { focused = it.hasFocus },
    ) {
        // Artwork base. InlineCardTrailer draws nothing until the video actually plays, so this
        // stays visible underneath through the whole dwell + preparing window.
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().engineHeroArt(accent))
        }

        InlineCardTrailer(
            trailerUrl = trailerUrl,
            backdropUrl = backdropUrl,
            play = play,
            volume = LocalTrailerVolume.current,
            statusProvider = trailerStatusProvider?.let { provider -> { provider(item) } },
            onPhaseChange = { phase = it },
            modifier = Modifier.fillMaxSize(),
        )

        // Scrim system: contrast is guaranteed by the scrims, never by the artwork. A strong bottom
        // riser for the text column, a left wash, and a light top band for the corner logo.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.18f to Color.Transparent,
                            0.52f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.62f),
                            1f to Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            0.38f to Color.Black.copy(alpha = 0.30f),
                            0.66f to Color.Transparent,
                        ),
                    ),
        )

        // Provider/studio artwork, anchored to the card's top-right corner. Part of the detail
        // tier: it steps out with the metadata while the trailer plays.
        cornerBadge?.let { badge ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 28.dp)
                        .graphicsLayer { alpha = detailAlpha },
            ) {
                EngineBadge(badge = badge, logoHeight = SPOTLIGHT_CORNER_LOGO_HEIGHT)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.62f)
                    .padding(start = 48.dp, bottom = 44.dp, end = 16.dp),
        ) {
            SpotlightTitle(item = item, modifier = Modifier.graphicsLayer { alpha = titleAlpha })

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.graphicsLayer { alpha = detailAlpha },
            ) {
                SpotlightMetadataLine(item)
                // Any remaining engine badges (future award/format artwork or text) still render
                // generically; the strip collapses when empty.
                BadgeStrip(badges = stripBadges)
                card.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    Text(
                        text = synopsis,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SpotlightActions(
                item = item,
                onClick = onClick,
                onWatchlist = onWatchlist,
                focusRequester = focusRequester,
            )
        }

        // Cinematic chrome, shown only while the trailer is genuinely on screen: a single top
        // letterbox band and a compact "● NOW PREVIEWING" tag, top-left (the top-right corner
        // belongs to the clock + avatar). No progress bar: the preview is ambience, not playback.
        AnimatedVisibility(
            visible = playing,
            enter = fadeIn(tween(durationMillis = 400)),
            exit = fadeOut(tween(durationMillis = 300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(LETTERBOX_HEIGHT)
                            .background(Color.Black.copy(alpha = 0.55f)),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 44.dp, top = LETTERBOX_HEIGHT + 14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    PulsingDot(color = LiveDotRed, size = 6.dp)
                    Text(
                        text = stringResource(R.string.now_previewing).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

/**
 * Logo art when the item has it; otherwise the styled two-line treatment: a small, letter-spaced
 * lead-in over a large emphasized tail, split at the space nearest the title's middle — a phrase
 * boundary, not the naive last-word split (which would render "…GAME OF" / "ZONES").
 */
@Composable
private fun SpotlightTitle(
    item: RenderItem,
    modifier: Modifier = Modifier,
) {
    val card = item.card
    val context = LocalContext.current
    val imageUrlService = LocalImageUrlService.current
    val jellyfinId = item.media.jellyfinId?.toUUIDOrNull()
    val logoUrl =
        imageUrlService.engineImageUrl(card.logoImageUrl)
            ?: jellyfinId?.let {
                imageUrlService.getItemImageUrl(itemId = it, imageType = ImageType.LOGO, fillHeight = 240)
            }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }

    if (logoUrl != null && !logoFailed) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(logoUrl).crossfade(true).build(),
            contentDescription = card.title,
            contentScale = ContentScale.Fit,
            onError = { logoFailed = true },
            alignment = Alignment.BottomStart,
            modifier = modifier.height(116.dp).widthIn(max = 420.dp),
        )
        return
    }

    val title = card.title.orEmpty().trim()
    val splitAt = phraseSplitIndex(title)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        if (splitAt > 0) {
            Text(
                text = title.substring(0, splitAt).uppercase(),
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = (if (splitAt > 0) title.substring(splitAt + 1) else title).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 48.sp,
            lineHeight = 52.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where to break a title into lead-in + emphasized tail: the space closest to the middle. Short or
 * single/two-word titles don't split at all (returns -1) — "Malik" renders as one big word.
 */
private fun phraseSplitIndex(title: String): Int {
    if (title.length <= 16 || title.count { it == ' ' } < 2) return -1
    val mid = title.length / 2
    var best = -1
    title.forEachIndexed { i, c ->
        if (c == ' ' && (best == -1 || kotlin.math.abs(i - mid) < kotlin.math.abs(best - mid))) best = i
    }
    return best
}

/**
 * The single metadata line: cert chip · ★ score · year · runtime · genres, all read from the
 * engine's structured badges. Absent values collapse — nothing is invented or padded in.
 */
@Composable
private fun SpotlightMetadataLine(item: RenderItem) {
    val badges = item.card.badges
    fun badge(vararg kinds: String) =
        badges.firstOrNull { b -> kinds.any { b.kind.equals(it, ignoreCase = true) } }
            ?.text?.takeIf { it.isNotBlank() }

    val cert = badge("CERT", "RATED", "CERTIFICATE")
    val rating = badge("RATING", "SCORE")
    val year = badge("YEAR", "DATE")
    val runtime = badge("RUNTIME", "DURATION", "EPISODES")
    val genres =
        badges.filter { it.kind.equals("GENRE", ignoreCase = true) }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            .take(3)
    if (cert == null && rating == null && year == null && runtime == null && genres.isEmpty()) return

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        cert?.let {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(it, color = Color.White.copy(alpha = 0.88f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        rating?.let { Text("★ $it", color = SpotlightGold, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        val tail = listOfNotNull(year, runtime) + genres
        tail.forEachIndexed { index, label ->
            if (index > 0 || cert != null || rating != null) {
                Text("·", color = Color.White.copy(alpha = 0.35f), fontSize = 16.sp)
            }
            Text(label, color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp)
        }
    }
}

/** Play (or Request, when the title isn't in the library yet), Details and Watchlist. */
@Composable
private fun SpotlightActions(
    item: RenderItem,
    onClick: () -> Unit,
    onWatchlist: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val requestable = item.media.availability == AvailabilityState.REQUEST
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BillboardButton(
            label = stringResource(if (requestable) R.string.request else R.string.play),
            leading = if (requestable) null else "▶",
            primary = true,
            onClick = onClick,
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        )
        BillboardButton(label = stringResource(R.string.info), primary = false, onClick = onClick)
        BillboardButton(label = stringResource(R.string.billboard_watchlist), primary = false, onClick = onWatchlist)
    }
}

/** How much of the screen the showcase occupies — the whole card (and its chrome) fits the viewport. */
private const val SPOTLIGHT_HEIGHT_FRACTION = 0.82f

/** Read-first dwell: how long the artwork + metadata hold before the trailer starts. */
private const val SPOTLIGHT_TRAILER_DELAY_MS = 1_500L

/** The title's opacity while the trailer plays — dimmed, never hidden. */
private const val SPOTLIGHT_TITLE_PLAYING_ALPHA = 0.85f

/** Focus-frame alpha: full while focus arrives, receding once the card is engaged. */
private const val SPOTLIGHT_FRAME_FOCUS_ALPHA = 0.62f
private const val SPOTLIGHT_FRAME_ENGAGED_ALPHA = 0.18f

/** Corner provider/studio logo height — the strip token (28dp) scaled up 35% for the showcase. */
private val SPOTLIGHT_CORNER_LOGO_HEIGHT = 38.dp

/** Height of the single cinematic letterbox band shown over a previewing trailer. */
private val LETTERBOX_HEIGHT = 28.dp

/** The ★ score color, matching the Billboard's metadata treatment. */
private val SpotlightGold = Color(0xFFDAB440)

/**
 * Badge kinds the showcase consumes elsewhere: the metadata line owns score/cert/year/runtime/
 * genres, and AVAILABILITY is already expressed by the primary action button — repeating it as a
 * raw chip reads like debug output.
 */
private val SPOTLIGHT_CONSUMED_KINDS =
    setOf(
        "RATING", "SCORE", "CERT", "RATED", "CERTIFICATE", "YEAR", "DATE",
        "RUNTIME", "DURATION", "EPISODES", "GENRE", "AVAILABILITY",
    )
