package com.github.jkrishna289.orcax.ui.main

import androidx.activity.compose.BackHandler
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
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
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
import com.github.jkrishna289.orcax.ui.Cards
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
            // card was collapsing to its content height regardless of the fraction. 80% of the screen
            // leaves a ~20% peek of the first row beneath it.
            val billboardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
            val billboardPlayFocus = remember { FocusRequester() }
            // Shared so the nav Search icon and the billboard Play button can point at each other
            // (Down into content, Up back to the bar) — making the top nav reliably reachable (#1).
            val navFocus = remember { FocusRequester() }
            val activeTrailerUrl by viewModel.activeTrailerUrl.collectAsStateWithLifecycle()

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

            // Rotating spotlight: the active hero advances on a timer while the billboard is on
            // screen. The VM resolves each active item's trailer, watchlist state, and ambient color.
            val heroes = s.heroes
            var activeHero by remember(s) { mutableStateOf(0) }
            // Drive trailer/favorite/ambient for the active item — and restore it when the user
            // scrolls back up to the billboard after a row card had taken over the ambient color.
            LaunchedEffect(s, activeHero, isAtTop) {
                if (isAtTop && heroes.isNotEmpty()) viewModel.onHeroActive(activeHero)
            }
            // Auto-advance every 8s (only with >1 item, and only while the billboard is visible).
            // Keying on activeHero restarts the delay after each advance.
            LaunchedEffect(s, activeHero, heroes.size, isAtTop) {
                if (heroes.size > 1 && isAtTop) {
                    delay(SPOTLIGHT_ROTATE_MS)
                    activeHero = (activeHero + 1) % heroes.size
                }
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
                        item(key = "billboard") {
                            // Full-bleed billboard — keep the default spec so it isn't pushed down.
                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                                Billboard(
                                    item = hero,
                                    trailerUrl = activeTrailerUrl,
                                    isFavorite = heroFavorite,
                                    heroCount = heroes.size,
                                    activeIndex = activeHero,
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
                                                    scope.launch { listState.animateScrollToItem(0) }
                                                }
                                            },
                                )
                            }
                        }
                    }

                    items(s.rows, key = { it.id }) { row ->
                        // Restore the default spec so the row's own horizontal scroll isn't offset.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            DynamicCardRow(
                                row = row,
                                focusRequester = rowFocus(row.id),
                                onClickItem = { item ->
                                    // Not-yet-available items have no details page — open the request
                                    // landing instead of routing to a dead end (#5).
                                    if (item.media.availability == AvailabilityState.REQUEST) {
                                        requestLandingItem = item
                                    } else {
                                        viewModel.onItemClick(item)
                                    }
                                },
                                onFocusItem = {
                                    lastFocusedRowId = row.id
                                    viewModel.onCardFocused(it)
                                },
                                // Long-press a card to open the thumbs up/down feedback menu (F7 signal).
                                onLongClickItem = { feedbackItem = it },
                                // 16:9 cards enlarge + play their trailer in place (replaces the pop-up).
                                trailerUrlFor = { viewModel.trailerUrlFor(it) },
                                backdropUrlFor = { viewModel.backdropUrlFor(it) },
                            )
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
                            .onSizeChanged { topNavOffsetPx = it.height.toFloat() },
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

/** Let the restored scroll position + composition settle before restoring row focus (#1). */
private const val FOCUS_RESTORE_SETTLE_MS = 48L

/** Retry restoring row focus this many times (the row may not be attached for a few frames). */
private const val FOCUS_RESTORE_ATTEMPTS = 15

/** Delay between focus-restore attempts (~15 × 60ms ≈ 0.9s budget before the billboard fallback). */
private const val FOCUS_RESTORE_RETRY_MS = 60L
