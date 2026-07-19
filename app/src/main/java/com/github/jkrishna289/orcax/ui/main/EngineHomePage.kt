package com.github.jkrishna289.orcax.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.jkrishna289.orcax.ui.util.TopInsetBringIntoViewSpec
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RowStyle
import com.github.jkrishna289.orcax.engine.TrailerPrefetchPriority
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.LocalTrailerLanguage
import com.github.jkrishna289.orcax.ui.LocalTrailerPlayerPool
import com.github.jkrishna289.orcax.ui.LocalTrailerVolume
import com.github.jkrishna289.orcax.ui.cards.DynamicCardRow
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main home screen, driven by the Orca Engine: an inset, card-style spotlight [Billboard]
 * followed by the engine's rows as horizontal carousels. A fixed [TopNavBar] overlays the top —
 * transparent over the billboard, frosting once scrolled. The focused card drives the ambient
 * [com.github.jkrishna289.orcax.ui.nav.Backdrop] color so the app "illuminates" with its art.
 * When the engine can't serve a bundle it renders [onUnavailable] (the on-device home).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EngineHomePage(
    modifier: Modifier = Modifier,
    onUnavailable: @Composable () -> Unit,
) {
    val viewModel: EngineHomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val heroFavorite by viewModel.heroFavorite.collectAsStateWithLifecycle()

    // Surface one-shot engine messages (e.g. request results) as toasts.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.message.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    when (val s = state) {
        is EngineHomeState.Loading -> {
            // Skeleton while the bundle loads; avoids flashing an empty screen or the vanilla home.
            EngineHomeSkeleton(modifier = modifier)
        }

        is EngineHomeState.Unavailable -> onUnavailable()

        is EngineHomeState.Success -> {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            // Size the billboard off the REAL screen height: fillParentMaxHeight(fraction) is silently
            // ignored in this nav/LazyColumn setup (the item gets an unbounded parent height), so the
            // card was collapsing to its content height regardless of the fraction. It rests at ~80% of
            // the screen (a ~20% peek of the first row) and grows when the billboard is focused (below).
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            // Focus-first adaptive layout: which zone currently holds focus. When the billboard is
            // focused it reclaims vertical space to breathe; when a row is focused the others recede.
            var billboardFocused by remember { mutableStateOf(false) }
            var focusedRowId by remember { mutableStateOf<String?>(null) }
            val billboardPlayFocus = remember { FocusRequester() }
            // Shared so the nav Search icon and the billboard Play button can point at each other
            // (Down into content, Up back to the bar) — making the top nav reliably reachable (#1).
            val navFocus = remember { FocusRequester() }
            // Single source of truth for inline-trailer volume (Phase 13), provided to every trailer
            // player below via LocalTrailerVolume.
            val trailerVolume by viewModel.trailerVolume.collectAsStateWithLifecycle()
            // Preferred trailer audio language ("" = auto/English-preferred), for the leased players.
            val trailerLanguage by viewModel.trailerLanguage.collectAsStateWithLifecycle()

            // A not-yet-available (Discover/request) card opens a lightweight request landing
            // instead of a details page; null while the landing is closed (#5).
            var requestLandingItem by remember { mutableStateOf<RenderItem?>(null) }

            // Long-press feedback menu (thumbs up/down → engine personalization); null when closed.
            var feedbackItem by remember { mutableStateOf<RenderItem?>(null) }

            // Per-row focus handles + the last row the user focused (saved across navigation).
            // Entry recreation on pop drops the focus system's own memory, so returning from a
            // details page pushes focus back into this row; its saved position then restores the
            // exact card (#F2). Also the hand-back target when a popup overlay dismisses (#F3).
            val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
            fun rowFocus(id: String): FocusRequester = rowFocusRequesters.getOrPut(id) { FocusRequester() }
            var lastFocusedRowId by rememberSaveable { mutableStateOf<String?>(null) }

            // Single owner of initial focus. On return from a details page the nav entry is recreated,
            // so the focus system's own memory is gone AND the saved row often isn't composed/attached
            // for a few frames after the restored scroll settles — a one-shot requestFocus() then
            // silently no-ops (swallowed) and Compose's default focus falls to the top nav (#1). Retry
            // until the saved row's FocusRequester attaches; ItemRow's saved position then restores the
            // exact card the user left. Fall back to the billboard if it never attaches.
            LaunchedEffect(Unit) {
                delay(FOCUS_RESTORE_SETTLE_MS) // let the restored scroll position / composition settle
                val rowId = lastFocusedRowId
                if (rowId != null) {
                    repeat(FOCUS_RESTORE_ATTEMPTS) {
                        if (rowFocus(rowId).tryRequestFocus()) return@LaunchedEffect
                        delay(FOCUS_RESTORE_RETRY_MS)
                    }
                    // Never attached within budget → don't strand focus on the top nav.
                    runCatching { billboardPlayFocus.requestFocus() }
                } else {
                    runCatching { billboardPlayFocus.requestFocus() }
                }
            }

            // Bar background follows scroll: transparent over the billboard (index 0), frosted below.
            val isAtTop by remember {
                derivedStateOf { listState.firstVisibleItemIndex == 0 }
            }
            val isScrolledDown by remember {
                derivedStateOf { listState.firstVisibleItemIndex > 0 }
            }

            // The billboard grows toward the top of the screen when it's focused and at rest at the
            // top — reclaiming the first row's peek so the hero art + metadata breathe. Animated so
            // the change eases rather than jumping.
            val billboardHeightFraction by animateFloatAsState(
                targetValue = if (billboardFocused && isAtTop) BILLBOARD_FOCUSED_FRACTION else BILLBOARD_RESTING_FRACTION,
                animationSpec = tween(durationMillis = 320),
                label = "billboard-height-fraction",
            )
            val billboardHeight = screenHeight * billboardHeightFraction

            // Rotating billboard: the active hero advances on a fixed artwork-only timer while the
            // billboard is on screen. The VM resolves each active item's watchlist state and ambient
            // color. The billboard no longer plays trailers, so nothing can hold the rotation.
            val heroes = s.heroes
            var activeHero by remember(s) { mutableStateOf(0) }
            // Drive favorite/ambient for the active item — but only while the billboard is the
            // focused zone. A focused row card owns the ambient wash (via onCardFocused); without the
            // `focusedRowId == null` guard the billboard's rotation keeps overwriting the focused card's
            // colors with the hero's, because focusing the first row leaves isAtTop == true (the
            // billboard still peeks). When focus returns to the billboard (focusedRowId cleared) this
            // re-fires and restores the hero's colors.
            LaunchedEffect(s, activeHero, isAtTop, focusedRowId) {
                if (isAtTop && focusedRowId == null && heroes.isNotEmpty()) {
                    viewModel.onHeroActive(activeHero)
                }
            }
            // Plain artwork rotation: only with >1 hero and while the billboard is visible.
            LaunchedEffect(s, activeHero, heroes.size, isAtTop) {
                if (heroes.size <= 1 || !isAtTop) return@LaunchedEffect
                delay(SPOTLIGHT_ROTATE_MS)
                activeHero = (activeHero + 1) % heroes.size
            }

            // Smart back-intercept: when scrolled into the rows, Back returns to the top and
            // refocuses the billboard's Play button instead of exiting the app.
            BackHandler(enabled = isScrolledDown) {
                scope.launch { listState.animateScrollToItem(0) }
                runCatching { billboardPlayFocus.requestFocus() }
            }

            // Focused cards in scrolled rows must land BELOW the fixed top nav, not under it (#8).
            // The default spec is restored inside the billboard + each row so their own (horizontal /
            // full-bleed) scrolling is unaffected.
            val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
            // Measured (not assumed) bar height: TopNavBarHeight is only the first-frame estimate;
            // the real height follows font scale/density, keeping focused cards below the bar (#8).
            val estimatedTopNavPx = with(LocalDensity.current) { TopNavBarHeight.toPx() }
            var topNavOffsetPx by remember { mutableFloatStateOf(estimatedTopNavPx) }

            Box(modifier = modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides TopInsetBringIntoViewSpec(topNavOffsetPx),
                    // One trailer-volume source for the billboard + every 16:9 card below (Phase 13).
                    LocalTrailerVolume provides trailerVolume,
                    // Preferred trailer audio language for every leased player's track selection.
                    LocalTrailerLanguage provides trailerLanguage,
                    // Shared, reusable inline-trailer players so navigation doesn't churn decoders (Phase 10).
                    LocalTrailerPlayerPool provides viewModel.trailerPlayerPool,
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(bottom = Cards.height2x3),
                        // Focus memory (#9): restore focus to the last-focused row when Home regains
                        // focus; scroll position is restored by rememberLazyListState's own saver.
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                    ) {
                    heroes.getOrNull(activeHero)?.let { hero ->
                        // The upcoming spotlight items (wrap-around) for the billboard's UP NEXT strip.
                        val upNext =
                            if (heroes.size > 1) {
                                (1..UP_NEXT_COUNT).map { heroes[(activeHero + it) % heroes.size] }
                            } else {
                                emptyList()
                            }
                        item(key = "billboard") {
                            // Full-bleed billboard — keep the default spec so it isn't pushed down.
                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                                Billboard(
                                    item = hero,
                                    isFavorite = heroFavorite,
                                    heroCount = heroes.size,
                                    activeIndex = activeHero,
                                    upNext = upNext,
                                    onPlay = { viewModel.onPlay(hero) },
                                    onInfo = { viewModel.onInfo(hero) },
                                    onWatchlist = { viewModel.onWatchlist(hero) },
                                    onRequest = { viewModel.onRequest(hero) },
                                    onTrailer = { viewModel.onInfo(hero) },
                                    playFocusRequester = billboardPlayFocus,
                                    navFocusRequester = navFocus,
                                    // Big, immersive spotlight card: 80% of the screen (explicit height,
                                    // since fillParentMaxHeight is ignored here) so the whole billboard
                                    // plus a ~20% peek of the first row show together — and stay so when
                                    // its buttons are focused (the bring-into-view spec returns 0 while
                                    // it's fully visible). Slim edge insets keep it wide/cinematic.
                                    modifier =
                                        Modifier
                                            .height(billboardHeight)
                                            .padding(top = 8.dp, start = 14.dp, end = 14.dp)
                                            // When focus returns to the billboard from a row below,
                                            // the default bring-into-view only reveals the focused
                                            // button (near the card's bottom), leaving the top of the
                                            // billboard scrolled off so it appears "half" (#2). Snap
                                            // the list fully back to the top so the whole spotlight
                                            // shows again — the 80%-tall card keeps the focused button
                                            // on screen, so this doesn't fight the focus.
                                            .onFocusChanged {
                                                if (it.hasFocus) {
                                                    billboardFocused = true
                                                    focusedRowId = null
                                                    scope.launch { listState.animateScrollToItem(0) }
                                                } else {
                                                    billboardFocused = false
                                                }
                                            },
                                )
                            }
                        }
                    }

                    items(s.rows, key = { it.id }) { row ->
                        // Focus-first emphasis: while a row is focused, the others recede (dim +
                        // slight shrink) so the focused content stands out; nothing focused → all
                        // rows rest at full size. Pure graphicsLayer transforms — no reflow, no
                        // effect on focus order or D-pad movement — so the layout never jumps.
                        val isFocusedRow = row.id == focusedRowId
                        val anyRowFocused = focusedRowId != null
                        // While a cinematic Spotlight holds focus its neighbors also soften with a
                        // blur, so the showcase is the only sharp surface on screen. (RenderEffect
                        // blur needs API 31; on older devices it's a graceful no-op.)
                        val spotlightFocused =
                            remember(focusedRowId, s) {
                                s.rows.firstOrNull { it.id == focusedRowId }?.rowStyle == RowStyle.SPOTLIGHT
                            }
                        val rowAlpha by animateFloatAsState(
                            targetValue = if (!anyRowFocused || isFocusedRow) 1f else CONTEXT_ROW_ALPHA,
                            animationSpec = tween(durationMillis = 260),
                            label = "row-alpha",
                        )
                        val rowScale by animateFloatAsState(
                            targetValue = if (anyRowFocused && !isFocusedRow) CONTEXT_ROW_SCALE else 1f,
                            animationSpec = tween(durationMillis = 260),
                            label = "row-scale",
                        )
                        val rowBlur by animateDpAsState(
                            targetValue = if (spotlightFocused && !isFocusedRow) SPOTLIGHT_NEIGHBOR_BLUR else 0.dp,
                            animationSpec = tween(durationMillis = 260),
                            label = "row-blur",
                        )
                        // The focus system's automatic bring-into-view only requests the focused
                        // CARD's own bounds, so a wide card's title/subtitle — laid out BELOW the
                        // card inside the row — was left clipped off the bottom of the screen when
                        // focus entered a row at the viewport's bottom edge. Request the WHOLE row
                        // instead (title, cards, and the under-card info text); the top-inset spec
                        // still returns 0 when the row is already fully visible, so rows that fit
                        // don't move.
                        val rowBringIntoView = remember { BringIntoViewRequester() }
                        // Restore the default spec so the row's own horizontal scroll isn't offset.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            Box(
                                modifier =
                                    Modifier
                                        .bringIntoViewRequester(rowBringIntoView)
                                        .onFocusChanged {
                                            if (it.hasFocus) {
                                                focusedRowId = row.id
                                                billboardFocused = false
                                                scope.launch { rowBringIntoView.bringIntoView() }
                                            }
                                        }
                                        .graphicsLayer {
                                            alpha = rowAlpha
                                            scaleX = rowScale
                                            scaleY = rowScale
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                        }
                                        .then(if (rowBlur > 0.dp) Modifier.blur(rowBlur) else Modifier),
                            ) {
                            DynamicCardRow(
                                row = row,
                                focusRequester = rowFocus(row.id),
                                onClickItem = { item ->
                                    // Not-yet-available items have no details page — open the request
                                    // landing instead of routing to a dead end (#5).
                                    if (item.media.availability == AvailabilityState.REQUEST) {
                                        requestLandingItem = item
                                    } else {
                                        // Opening a detail page is high trailer intent (Phase 15).
                                        viewModel.prefetchTrailers(listOf(item), TrailerPrefetchPriority.DETAIL_PAGE)
                                        viewModel.onItemClick(item)
                                    }
                                },
                                onFocusItem = { focusedItem ->
                                    lastFocusedRowId = row.id
                                    viewModel.onCardFocused(focusedItem)
                                    // Predictively warm the cards the user is most likely to move to next.
                                    val idx = row.items.indexOf(focusedItem)
                                    if (idx >= 0) {
                                        viewModel.prefetchTrailers(
                                            row.items.subList(
                                                (idx - 1).coerceAtLeast(0),
                                                (idx + PREFETCH_AHEAD + 1).coerceAtMost(row.items.size),
                                            ),
                                            TrailerPrefetchPriority.LIKELY_NEXT,
                                        )
                                    }
                                },
                                // Long-press a card to open the thumbs up/down feedback menu (F7 signal).
                                onLongClickItem = { feedbackItem = it },
                                // Only the Spotlight showcase surfaces a Watchlist button inline.
                                onWatchlistItem = { viewModel.onWatchlist(it) },
                                // 16:9 cards enlarge + play their trailer in place (replaces the pop-up).
                                trailerUrlFor = { viewModel.trailerUrlFor(it) },
                                backdropUrlFor = { viewModel.backdropUrlFor(it) },
                                // Adaptive, state-driven start via the engine trailer status machine (M3).
                                trailerStatusProvider = { viewModel.trailerStatusFor(it) },
                            )
                            }
                        }
                    }
                    }
                }

                TopNavBar(
                    isAtTop = isAtTop,
                    contentFocusRequester = billboardPlayFocus,
                    navFocusRequester = navFocus,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .onSizeChanged { topNavOffsetPx = it.height.toFloat() }
                            // Snap the list fully back to the top whenever the nav bar takes focus, so
                            // the whole billboard shows under the (transparent-at-top) bar instead of
                            // being left half-scrolled beneath it. Mirrors the billboard/Back snap.
                            .onFocusChanged {
                                if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) }
                            },
                )

                requestLandingItem?.let { landing ->
                    RequestLanding(
                        item = landing,
                        onRequest = {
                            viewModel.onRequest(landing)
                            requestLandingItem = null
                        },
                        onDismiss = { requestLandingItem = null },
                        restoreFocusTo = lastFocusedRowId?.let { rowFocus(it) },
                    )
                }

                feedbackItem?.let { fb ->
                    CardFeedbackMenu(
                        item = fb,
                        onFeedback = { thumbsUp ->
                            viewModel.recordFeedback(fb, thumbsUp)
                            feedbackItem = null
                        },
                        onDismiss = { feedbackItem = null },
                        restoreFocusTo = lastFocusedRowId?.let { rowFocus(it) },
                    )
                }
            }
        }
    }
}

/** How long each spotlight item stays on screen before the billboard rotates to the next. */
private const val SPOTLIGHT_ROTATE_MS = 8_000L

/** How many upcoming heroes the billboard shows in its "UP NEXT" strip. */
private const val UP_NEXT_COUNT = 2

/** Billboard height as a fraction of the screen — at rest, and grown when the billboard is focused. */
private const val BILLBOARD_RESTING_FRACTION = 0.8f
private const val BILLBOARD_FOCUSED_FRACTION = 0.88f

/** Non-focused rows recede to this alpha / scale while another row holds focus (focus-first layout). */
private const val CONTEXT_ROW_ALPHA = 0.55f
private const val CONTEXT_ROW_SCALE = 0.94f

/** Neighbor rows soften by this much while a cinematic Spotlight holds focus. */
private val SPOTLIGHT_NEIGHBOR_BLUR = 6.dp

/** How many cards ahead of the focused one to predictively prefetch trailers for (Phase 3). */
private const val PREFETCH_AHEAD = 3

/** Let the restored scroll position + composition settle before restoring row focus (#1). */
private const val FOCUS_RESTORE_SETTLE_MS = 48L

/** Retry restoring row focus this many times (the row may not be attached for a few frames). */
private const val FOCUS_RESTORE_ATTEMPTS = 15

/** Delay between focus-restore attempts (~15 × 60ms ≈ 0.9s budget before the billboard fallback). */
private const val FOCUS_RESTORE_RETRY_MS = 60L
