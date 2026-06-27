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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.github.jkrishna289.orcax.ui.cards.InstantDetails
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

            // Instant details (#10) + two-stage trailer (#11): a staged dwell timer, reset on every
            // focus change so nothing fires while scrolling. ~0.9s → metadata overlay (no trailer);
            // 5s → small trailer plays; 10s → it enlarges into the pop-up.
            var focusedRowItem by remember { mutableStateOf<RenderItem?>(null) }
            var instantDetailsItem by remember { mutableStateOf<RenderItem?>(null) }
            var playTrailer by remember { mutableStateOf(false) }
            var trailerEnlarged by remember { mutableStateOf(false) }
            LaunchedEffect(focusedRowItem) {
                instantDetailsItem = null
                playTrailer = false
                trailerEnlarged = false
                focusedRowItem?.let {
                    delay(INFO_DELAY_MS)
                    instantDetailsItem = it
                    delay(TRAILER_DELAY_MS - INFO_DELAY_MS)
                    playTrailer = true
                    delay(TRAILER_ENLARGE_DELAY_MS - TRAILER_DELAY_MS)
                    trailerEnlarged = true
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
            val topNavOffsetPx = with(LocalDensity.current) { TopNavBarHeight.toPx() }

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
                                            .padding(top = 8.dp, start = 14.dp, end = 14.dp),
                                )
                            }
                        }
                    }

                    items(s.rows, key = { it.id }) { row ->
                        // Restore the default spec so the row's own horizontal scroll isn't offset.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            DynamicCardRow(
                                row = row,
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
                                    focusedRowItem = it
                                    viewModel.onCardFocused(it)
                                },
                            )
                        }
                    }
                    }
                }

                // Instant details + trailer preview — only while scrolled into the rows (the billboard
                // shows its own info). Trailer/backdrop URLs are resolved from the focused card.
                val detailsShown = if (isScrolledDown) instantDetailsItem else null
                val detailsTrailerUrl = remember(detailsShown) { detailsShown?.let { viewModel.trailerUrlFor(it) } }
                val detailsBackdropUrl = remember(detailsShown) { detailsShown?.let { viewModel.backdropUrlFor(it) } }
                InstantDetails(
                    item = detailsShown,
                    trailerUrl = detailsTrailerUrl,
                    backdropUrl = detailsBackdropUrl,
                    play = playTrailer,
                    enlarged = trailerEnlarged,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 48.dp, bottom = 24.dp, end = 48.dp),
                )

                TopNavBar(
                    isAtTop = isAtTop,
                    contentFocusRequester = billboardPlayFocus,
                    navFocusRequester = navFocus,
                    modifier = Modifier.align(Alignment.TopStart),
                )

                requestLandingItem?.let { landing ->
                    RequestLanding(
                        item = landing,
                        onRequest = {
                            viewModel.onRequest(landing)
                            requestLandingItem = null
                        },
                        onDismiss = { requestLandingItem = null },
                    )
                }
            }
        }
    }
}

/** How long each spotlight item stays on screen before the billboard rotates to the next. */
private const val SPOTLIGHT_ROTATE_MS = 8_000L

/** How long a row card must hold focus before the instant-details overlay appears (#10). */
private const val INFO_DELAY_MS = 900L

/** Sustained focus before the (small) trailer starts playing (#11). */
private const val TRAILER_DELAY_MS = 5_000L

/** Sustained focus before the small trailer enlarges into the pop-up (#11). */
private const val TRAILER_ENLARGE_DELAY_MS = 10_000L
