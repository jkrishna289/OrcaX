package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.useExistingImageAsPlaceholder
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.ui.CrossFadeFactory
import kotlin.time.Duration.Companion.milliseconds

// ─────────────────────────────────────────────────────────────────────────────
// ROOT ENTRY POINT
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PlaybackScreen(
    viewModel: PlaybackPhaseViewModel,
    onPlayPauseRequested: () -> Unit = {},
    onSeekRequested: (Long) -> Unit = {},
    onCCRequested: () -> Unit = {},
    onAudioDialogRequested: () -> Unit = {},
    onConfirmRoulette: (RouletteType, Int) -> Unit = { _, _ -> },
    onPlayUpNext: (UpNextItem) -> Unit = {},
    qualityLabel: String = "",
    onQualityRequested: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Ambient slideshow auto-advance hold (DOWN toggles it). Reset on leaving COMPACT.
    var slideshowHold by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase2SubState) {
        if (state.phase2SubState != Phase2SubState.COMPACT) slideshowHold = false
    }

    // Keys the router consumed on KeyDown — their paired KeyUp must also be
    // swallowed so it can't leak into PlaybackKeyHandler (which acts on KeyUp).
    val consumedDownKeys = remember { mutableSetOf<Key>() }
    // When no chrome is showing, focus sits on the video surface and no key can
    // legitimately reach this router — anything left in the set is a stale entry
    // from a press whose KeyUp escaped during a phase transition. Clear it so it
    // can't swallow an unrelated future KeyUp.
    LaunchedEffect(state.phase, state.toolbarVisibility) {
        if (state.phase == PlaybackPhase.ACTIVE && state.toolbarVisibility == ToolbarVisibility.HIDDEN) {
            consumedDownKeys.clear()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    return@onKeyEvent consumedDownKeys.remove(event.key)
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val handled = when (state.phase) {
                    PlaybackPhase.ACTIVE         -> handlePhase1Key(event, state, viewModel, onConfirmRoulette)
                    // Back during pause → handled by BackHandlers in PlaybackPage.
                    // Picker Up/Down/Enter → handled inside RoulettePicker.
                    PlaybackPhase.PAUSED_OVERLAY ->
                        if (state.p2PickerType != null) {
                            // Picker is modal: swallow horizontal nav so focus can't
                            // escape to the dimmed controls behind it.
                            when (event.key) {
                                Key.DirectionLeft, Key.DirectionRight -> true
                                else -> false
                            }
                        } else if (state.phase2SubState == Phase2SubState.COMPACT) {
                            // Ambient COMPACT: UP → FULL, OK → resume, DOWN → hold/resume the
                            // slideshow, LEFT/RIGHT swallowed (informative only — no scrub).
                            when (event.key) {
                                Key.DirectionUp -> { viewModel.expandPhase2(); true }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    onPlayPauseRequested()
                                    viewModel.onPlayPauseToggle()
                                    true
                                }
                                Key.DirectionDown -> {
                                    // Auto-repeat delivers KeyDowns with no KeyUp between —
                                    // only the initial press may toggle, or holding DOWN
                                    // oscillates the hold state.
                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                        slideshowHold = !slideshowHold
                                    }
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight -> true
                                else -> false
                            }
                        } else {
                            false
                        }
                }
                if (handled) consumedDownKeys.add(event.key)
                handled
            }
    ) {
        PhaseScrim(
            phase     = state.phase,
            isCompact = state.phase2SubState == Phase2SubState.COMPACT
        )

        Phase2OverlayLayer(
            state                  = state,
            viewModel              = viewModel,
            slideshowHold          = slideshowHold,
            onPlayPauseRequested   = onPlayPauseRequested,
            onSeekRequested        = onSeekRequested,
            onCCRequested          = onCCRequested,
            onAudioDialogRequested = onAudioDialogRequested,
            onConfirmRoulette      = onConfirmRoulette,
            onPlayUpNext           = onPlayUpNext
        )

        Phase1ToolbarLayer(
            state                = state,
            viewModel            = viewModel,
            onPlayPauseRequested = onPlayPauseRequested,
            onSeekRequested      = onSeekRequested,
            qualityLabel         = qualityLabel,
            onQualityRequested   = onQualityRequested
        )

        BitstreamToast(
            state    = state.bitstreamToast,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = PlaybackDimens.SafeV + 8.dp, end = PlaybackDimens.SafeH)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// D-PAD ROUTING HELPERS
// ─────────────────────────────────────────────────────────────────────────────

private fun handlePhase1Key(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: PlaybackUiState,
    vm: PlaybackPhaseViewModel,
    onConfirmRoulette: (RouletteType, Int) -> Unit
): Boolean {
    // BACK must not re-arm the toolbar timers it is about to dismiss.
    if (!isBackKey(event)) vm.onUserInteraction()
    return when {
        state.dropdown != null -> when (event.key) {
            Key.DirectionUp              -> { vm.navigateDropdown(-1); true }
            Key.DirectionDown            -> { vm.navigateDropdown(+1); true }
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                val confirmed = vm.confirmDropdown()
                if (confirmed != null) onConfirmRoulette(confirmed.first, confirmed.second)
                true
            }
            // Swallow horizontal nav so focus can't escape to the dimmed toolbar
            // buttons behind the open dropdown (mirrors the Phase 2 picker's modal
            // behaviour). BACK is handled by the dropdown BackHandler in PlaybackPage.
            Key.DirectionLeft, Key.DirectionRight -> true
            else     -> false
        }
        state.toolbarVisibility == ToolbarVisibility.VISIBLE -> when (event.key) {
            Key.DirectionUp -> { vm.onUpFromPhase1(); true }
            else            -> false
        }
        else -> false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LAYER 1: PHASE SCRIM
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PhaseScrim(phase: PlaybackPhase, isCompact: Boolean) {
    // COMPACT lightens the global scrim so the paused frame shows; a separate constant
    // control gradient (in Phase2OverlayLayer) keeps the scrubber legible.
    val scrimAlpha by animateFloatAsState(
        targetValue   = when {
            phase != PlaybackPhase.PAUSED_OVERLAY -> 0f
            isCompact                             -> 0.45f
            else                                  -> 1f
        },
        animationSpec = tween(
            durationMillis = if (phase == PlaybackPhase.PAUSED_OVERLAY) PlaybackMotion.Phase2InMs
                             else PlaybackMotion.Phase2OutMs,
            easing = PlaybackMotion.CinematicDecelerate
        ),
        label = "scrim_alpha"
    )

    if (scrimAlpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.40f),
                            0.40f to Color.Black.copy(alpha = 0.70f),
                            0.70f to Color.Black.copy(alpha = 0.88f),
                            1.00f to Color.Black.copy(alpha = 0.97f)
                        )
                    )
                )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LAYER 2: PHASE 2 — CINEMATIC PAUSED OVERLAY
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Phase2OverlayLayer(
    state: PlaybackUiState,
    viewModel: PlaybackPhaseViewModel,
    slideshowHold: Boolean,
    onPlayPauseRequested: () -> Unit,
    onSeekRequested: (Long) -> Unit,
    onCCRequested: () -> Unit,
    onAudioDialogRequested: () -> Unit,
    onConfirmRoulette: (RouletteType, Int) -> Unit = { _, _ -> },
    onPlayUpNext: (UpNextItem) -> Unit = {}
) {
    val phase = state.phase

    val overlayAlpha by animateFloatAsState(
        targetValue   = if (phase == PlaybackPhase.PAUSED_OVERLAY) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (phase == PlaybackPhase.PAUSED_OVERLAY)
                PlaybackMotion.Phase2InMs else PlaybackMotion.Phase2OutMs,
            easing = PlaybackMotion.CinematicDecelerate
        ),
        label = "p2_overlay_alpha"
    )

    // Dim transport controls when picker is open
    val controlsDim by animateFloatAsState(
        targetValue   = if (state.p2PickerType != null) 0.4f else 1f,
        animationSpec = tween(220),
        label         = "controls_dim"
    )

    // FULL vs COMPACT (BACK) vs idle-dim. COMPACT fades the browse chrome to nothing
    // and reveals the frame; idle only dims the narrative and stays in FULL's focus model.
    val isCompact = state.phase2SubState == Phase2SubState.COMPACT
    val narrativeAlpha by animateFloatAsState(
        targetValue   = if (isCompact) 0f else 1f,
        animationSpec = tween(300),
        label         = "p2_narrative_alpha"
    )
    val chromeAlpha by animateFloatAsState(
        targetValue   = if (isCompact) 0f else 1f,
        animationSpec = tween(300),
        label         = "p2_chrome_alpha"
    )
    val compactAlpha by animateFloatAsState(
        targetValue   = if (isCompact) 1f else 0f,
        animationSpec = tween(300),
        label         = "p2_compact_alpha"
    )

    // Up Next rail expansion state. Expands when focus enters the column and stays
    // expanded until focus leaves (collapse handled by the Box onFocusChanged below).
    var isRailExpanded by remember { mutableStateOf(false) }

    // Focus requesters
    val skipBackFR   = remember { FocusRequester() }
    val playBtnFR    = remember { FocusRequester() }
    val skipFwdFR    = remember { FocusRequester() }
    val audioChipFR  = remember { FocusRequester() }
    val ccChipFR     = remember { FocusRequester() }
    val seekBarFR    = remember { FocusRequester() }
    val cardFRs      = remember { Array(5) { FocusRequester() } }

    // Focus contract: COMPACT → seek bar is the sole focusable; FULL → play button.
    // Keyed on sub-state ONLY (never isIdleDimmed) so idle dimming can't move focus.
    LaunchedEffect(phase, state.phase2SubState) {
        if (phase == PlaybackPhase.PAUSED_OVERLAY) {
            kotlinx.coroutines.delay(80L)
            if (state.phase2SubState == Phase2SubState.COMPACT) {
                runCatching { seekBarFR.requestFocus() }
            } else {
                runCatching { playBtnFR.requestFocus() }
            }
        }
    }

    // When picker opens, request focus on the picker; when it closes (confirm or
    // back), restore focus to the chip that opened it so it behaves like a modal.
    val pickerFR = remember { FocusRequester() }
    var lastPickerType by remember { mutableStateOf<RouletteType?>(null) }
    LaunchedEffect(state.p2PickerType) {
        val type = state.p2PickerType
        if (type != null) {
            lastPickerType = type
            kotlinx.coroutines.delay(60L)
            runCatching { pickerFR.requestFocus() }
        } else {
            when (lastPickerType) {
                RouletteType.AUDIO    -> runCatching { audioChipFR.requestFocus() }
                RouletteType.SUBTITLE -> runCatching { ccChipFR.requestFocus() }
                null -> {}
            }
        }
    }

    if (overlayAlpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = overlayAlpha }
        ) {

            // ── LEFT: Title + meta line + synopsis ────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.34f)
                    .graphicsLayer { alpha = narrativeAlpha }
                    .padding(start = PlaybackDimens.SafeH, top = 96.dp)
            ) {
                LogoOrTitle(
                    logoUrl            = state.logoUrl,
                    contentDescription = state.showTitle,
                    logoHeight         = 56.dp,
                    maxLogoWidth       = 320.dp
                ) {
                    Text(
                        text          = state.showTitle.uppercase(),
                        fontSize      = 26.sp,
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = (0.06f * 26).sp,
                        lineHeight    = (26 * 1.2f).sp,
                        color         = Color.White.copy(alpha = 0.90f),
                        maxLines      = 3,
                        overflow      = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(5.dp))
                val metaLine = buildMetaLine(
                    state.year, state.rating, state.season, state.episode, state.durationMs
                )
                if (metaLine.isNotEmpty()) {
                    Text(
                        text     = metaLine,
                        fontSize = 14.sp,
                        color    = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text       = state.synopsis,
                    fontSize   = 14.sp,
                    color      = Color.White.copy(alpha = 0.80f),
                    lineHeight = (14 * 1.55f).sp,
                    maxLines   = 3,
                    overflow   = TextOverflow.Ellipsis
                )
            }

            // ── Constant bottom control gradient — legibility for the scrubber over
            //    any frame, independent of the global scrim (which lightens in COMPACT).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.60f))
                        )
                    )
            )

            // ── TRANSPORT CONTROLS — bottom-left (gone in COMPACT) ───────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start  = PlaybackDimens.SafeH,
                        bottom = PlaybackDimens.SafeV + 58.dp
                    )
                    .graphicsLayer { alpha = controlsDim * chromeAlpha }
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Phase2ControlsRow(
                        isPlaying  = state.isPlaying,
                        onSkipBack = {
                            val pos = (state.seekPositionMs - 10_000L).coerceAtLeast(0L)
                            onSeekRequested(pos)
                            viewModel.onSeekTo(pos)
                        },
                        onPlayPause = {
                            onPlayPauseRequested()
                            viewModel.onPlayPauseToggle()
                        },
                        onSkipForward = {
                            // durationMs == 0 while unknown (live / not yet reported) — clamping
                            // against it would seek to the start instead of forward.
                            val limit = if (state.durationMs > 0L) state.durationMs else Long.MAX_VALUE
                            val pos = (state.seekPositionMs + 10_000L).coerceAtMost(limit)
                            onSeekRequested(pos)
                            viewModel.onSeekTo(pos)
                        },
                        onInteraction = { viewModel.onPhase2Interaction() },
                        skipBackFR  = skipBackFR,
                        playBtnFR   = playBtnFR,
                        skipFwdFR   = skipFwdFR,
                        audioChipFR = audioChipFR,
                        ccChipFR    = ccChipFR,
                        firstCardFR = cardFRs[0],
                        canFocus    = !isCompact,
                    )

                    // AUD + CC track chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        P2TrackChip(
                            prefix        = stringResource(R.string.p1_aud),
                            label         = state.audioItems.getOrNull(state.audioConfirmedIndex)?.title ?: "",
                            onClick       = { viewModel.openP2Picker(RouletteType.AUDIO) },
                            onInteraction = { viewModel.onPhase2Interaction() },
                            focusRequester = audioChipFR,
                            focusEnabled   = !isCompact,
                            focusPropertiesBlock = {
                                up    = playBtnFR
                                right = ccChipFR
                                down  = seekBarFR
                            }
                        )
                        P2TrackChip(
                            prefix        = stringResource(R.string.p1_cc),
                            label         = state.subtitleItems.getOrNull(state.subtitleConfirmedIndex)?.title
                                ?: stringResource(R.string.track_off),
                            onClick       = { viewModel.openP2Picker(RouletteType.SUBTITLE) },
                            onInteraction = { viewModel.onPhase2Interaction() },
                            focusRequester = ccChipFR,
                            focusEnabled   = !isCompact,
                            focusPropertiesBlock = {
                                left  = audioChipFR
                                up    = skipFwdFR
                                right = cardFRs[0]
                                down  = seekBarFR
                            }
                        )
                    }
                }
            }

            // ── COMPACT ambient view: backdrop + logo + cast/info slideshow ──
            if (compactAlpha > 0f) {
                Phase2CompactAmbient(
                    state         = state,
                    slideshowHold = slideshowHold,
                    alpha         = compactAlpha
                )
            }

            // ── SEEK BAR (bottom). Informative-only in COMPACT (no scrub). ────
            val seekFraction     = if (state.durationMs > 0L)
                state.seekPositionMs.toFloat() / state.durationMs else 0f
            val bufferedFraction = if (state.durationMs > 0L)
                state.bufferedPositionMs.toFloat() / state.durationMs else 0f

            CinematicSeekBar(
                seekFraction      = seekFraction,
                bufferedFraction  = bufferedFraction,
                onSeek            = { fraction ->
                    val posMs = (fraction * state.durationMs).toLong()
                    onSeekRequested(posMs)
                    viewModel.onSeekTo(posMs)
                },
                focusRequester    = seekBarFR,
                currentPositionMs = state.seekPositionMs,
                durationMs        = state.durationMs,
                showTimestamps    = true,
                interactive       = !isCompact,
                modifier          = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start  = PlaybackDimens.SafeH,
                        end    = PlaybackDimens.SafeH,
                        bottom = PlaybackDimens.SafeV + 8.dp
                    )
                    .focusProperties { up = audioChipFR }
            )

            // ── RIGHT: UP NEXT vertical card stack ────────────────────────────
            val hiddenCount = (state.upNextItems.size - 2).coerceAtLeast(0)
            val chevronRotation by animateFloatAsState(
                targetValue   = if (isRailExpanded) 180f else 0f,
                animationSpec = tween(350, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                label         = "chevron_rotate"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.36f)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = chromeAlpha }
                    // Top inset clears the title; bottom inset reserves the seek-bar /
                    // timestamp / subtitle zone so cards never collide with "1:02:02".
                    .padding(
                        top    = 90.dp,
                        end    = PlaybackDimens.SafeH,
                        bottom = PlaybackDimens.SafeV + 96.dp
                    )
                    .onFocusChanged { focusState ->
                        if (!focusState.hasFocus && isRailExpanded) isRailExpanded = false
                    }
            ) {
                Column(
                    // Scrolls when the expanded rail outgrows the reserved height; the
                    // horizontal padding gives the focus ring/scale room inside the clip.
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text          = stringResource(R.string.p2_up_next),
                            fontSize      = 12.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = (0.14f * 12).sp,
                            color         = Color.White.copy(alpha = 0.68f)
                        )
                        Text(
                            text     = "▾",
                            fontSize = 14.sp,
                            color    = Color.White.copy(alpha = 0.68f),
                            modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                        )
                        if (hiddenCount > 0) {
                            AnimatedVisibility(
                                visible = !isRailExpanded,
                                enter   = fadeIn(tween(200)),
                                exit    = fadeOut(tween(200))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(PlaybackColors.Purple.copy(alpha = 0.22f))
                                        .border(1.dp, PlaybackColors.Purple.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 9.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text       = stringResource(R.string.p2_more_count, hiddenCount),
                                        fontSize   = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color      = PlaybackColors.Purple
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    // Card 0 — always visible (Watching). Its label/progress are derived
                    // live from the player position so it reads "Xm left" with a real bar.
                    state.upNextItems.getOrNull(0)?.let { item ->
                        val displayItem = if (item.isCurrentlyWatching && state.durationMs > 0L) {
                            val remaining = (state.durationMs - state.seekPositionMs).coerceAtLeast(0L)
                            item.copy(
                                durationLabel    = formatMinutesLeft(remaining),
                                progressFraction = (state.seekPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                            )
                        } else {
                            item
                        }
                        Phase2Card(
                            item           = displayItem,
                            onClick        = { onPlayUpNext(displayItem) },
                            focusRequester = cardFRs[0],
                            focusProperties = {
                                canFocus = !isCompact
                                up    = FocusRequester.Cancel
                                left  = skipFwdFR
                                right = FocusRequester.Cancel
                                down  = if (state.upNextItems.size > 1) cardFRs[1]
                                        else FocusRequester.Default
                            },
                            onDownPressed  = if (state.upNextItems.size <= 1) ({ viewModel.dismissPhase2() }) else null,
                            onFocused      = { isRailExpanded = true }
                        )
                    }

                    // Card 1 — always visible
                    state.upNextItems.getOrNull(1)?.let { item ->
                        val card1Down = if (isRailExpanded && state.upNextItems.size > 2) cardFRs[2]
                                        else FocusRequester.Default
                        Phase2Card(
                            item           = item,
                            onClick        = { onPlayUpNext(item) },
                            focusRequester = cardFRs[1],
                            focusProperties = {
                                canFocus = !isCompact
                                up    = cardFRs[0]
                                left  = ccChipFR
                                right = FocusRequester.Cancel
                                down  = card1Down
                            },
                            onDownPressed  = if (!isRailExpanded || state.upNextItems.size <= 2)
                                             ({ viewModel.dismissPhase2() }) else null,
                            onFocused      = { isRailExpanded = true }
                        )
                    }

                    // Cards 2–4 — expanded only, staggered reveal
                    val staggerDelays = listOf(0, 40, 100)
                    state.upNextItems.drop(2).take(3).forEachIndexed { extraIdx, item ->
                        val cardIdx = extraIdx + 2
                        val delayMs = staggerDelays.getOrElse(extraIdx) { 160 }
                        val isLast  = cardIdx == (state.upNextItems.size - 1).coerceAtMost(4)

                        AnimatedVisibility(
                            visible = isRailExpanded,
                            enter   = fadeIn(tween(450, delayMillis = delayMs)) +
                                      scaleIn(tween(450, delayMillis = delayMs), initialScale = 0.95f) +
                                      slideInVertically(tween(450, delayMillis = delayMs)) { -(it / 5) },
                            exit    = fadeOut(tween(200)) +
                                      scaleOut(tween(200), targetScale = 0.95f) +
                                      slideOutVertically(tween(200)) { -(it / 5) }
                        ) {
                            val prevFR = cardFRs[cardIdx - 1]
                            val nextFR = if (cardIdx < 4 && state.upNextItems.size > cardIdx + 1)
                                cardFRs[cardIdx + 1] else FocusRequester.Default

                            Phase2Card(
                                item           = item,
                                onClick        = { onPlayUpNext(item) },
                                focusRequester = cardFRs[cardIdx],
                                focusProperties = {
                                    canFocus = isRailExpanded && !isCompact
                                    up       = prevFR
                                    left     = ccChipFR
                                    right    = FocusRequester.Cancel
                                    down     = nextFR
                                },
                                onDownPressed  = if (isLast) ({ viewModel.dismissPhase2() }) else null,
                                onFocused      = {}
                            )
                        }
                    }
                }
            }

            // ── PHASE 2 ROULETTE PICKER overlay ──────────────────────────────
            state.p2PickerType?.let { pickerType ->
                val pickerItems = when (pickerType) {
                    RouletteType.AUDIO    -> state.audioItems
                    RouletteType.SUBTITLE -> state.subtitleItems
                }
                val confirmedIdx = when (pickerType) {
                    RouletteType.AUDIO    -> state.audioConfirmedIndex
                    RouletteType.SUBTITLE -> state.subtitleConfirmedIndex
                }
                val headerLabel = when (pickerType) {
                    RouletteType.AUDIO    -> stringResource(R.string.p2_header_audio)
                    RouletteType.SUBTITLE -> stringResource(R.string.p2_header_subtitles)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start  = PlaybackDimens.SafeH,
                            bottom = PlaybackDimens.SafeV + 150.dp
                        )
                ) {
                    // Radial scrim to lift the picker off the video
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(300.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.78f),
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Transparent
                                    ),
                                    radius = 400f
                                )
                            )
                    )

                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text          = headerLabel,
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = (0.16f * 11).sp,
                            color         = Color.White.copy(alpha = 0.50f),
                            modifier      = Modifier.padding(bottom = 8.dp)
                        )

                        RoulettePicker(
                            items                = pickerItems,
                            selectedIndex        = state.p2PickerFocusIndex,
                            onSelectedIndexChange = { viewModel.setP2PickerIndex(it) },
                            itemHeight           = 40.dp,
                            width                = 248.dp,
                            visibleCount         = 5,
                            focusRequester       = pickerFR,
                            onCenterPress        = {
                                val result = viewModel.confirmP2Picker()
                                if (result != null) onConfirmRoulette(result.first, result.second)
                            }
                        ) { item, isSelected ->
                            val isCurrent = pickerItems.indexOf(item) == confirmedIdx
                            Row(
                                modifier              = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (isCurrent) PlaybackColors.Purple else Color.Transparent,
                                            CircleShape
                                        )
                                )
                                Text(
                                    text       = item.title,
                                    fontSize   = 16.sp,
                                    fontWeight = if (isSelected || isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color      = Color.White.copy(alpha = when {
                                        isSelected -> 1f
                                        isCurrent  -> 0.92f
                                        else       -> 0.60f
                                    }),
                                    maxLines   = 1,
                                    overflow   = TextOverflow.Ellipsis,
                                    modifier   = Modifier.weight(1f)
                                )
                                if (isCurrent) {
                                    Text(
                                        text       = stringResource(R.string.p2_current_on),
                                        fontSize   = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color      = Color.White.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Phase2ControlsRow(
    isPlaying: Boolean,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onInteraction: () -> Unit,
    skipBackFR:  FocusRequester,
    playBtnFR:   FocusRequester,
    skipFwdFR:   FocusRequester,
    audioChipFR: FocusRequester,
    ccChipFR:    FocusRequester,
    firstCardFR: FocusRequester,
    canFocus:    Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        P2CircleBtn(
            label          = "−10",
            onClick        = onSkipBack,
            onInteraction  = onInteraction,
            focusRequester = skipBackFR,
            rightFR        = playBtnFR,
            downFR         = audioChipFR,
            focusEnabled   = canFocus,
        )
        P2PlayPauseBtn(
            isPlaying      = isPlaying,
            onClick        = onPlayPause,
            onInteraction  = onInteraction,
            focusRequester = playBtnFR,
            leftFR         = skipBackFR,
            rightFR        = skipFwdFR,
            downFR         = audioChipFR,
            focusEnabled   = canFocus,
        )
        P2CircleBtn(
            label          = "+10",
            onClick        = onSkipForward,
            onInteraction  = onInteraction,
            focusRequester = skipFwdFR,
            leftFR         = playBtnFR,
            rightFR        = firstCardFR,
            downFR         = ccChipFR,
            focusEnabled   = canFocus,
        )
    }
}

@Composable
private fun P2CircleBtn(
    label: String,
    onClick: () -> Unit,
    onInteraction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    leftFR:  FocusRequester? = null,
    rightFR: FocusRequester? = null,
    downFR:  FocusRequester? = null,
    focusEnabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.08f else 1f,
        spring(0.68f, 400f), label = "cbtn_scale"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.10f))
            .then(
                if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.90f), CircleShape)
                else Modifier
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                this.canFocus = focusEnabled
                up = FocusRequester.Cancel   // transport row is the top of the Phase-2 focus graph
                if (leftFR  != null) left  = leftFR
                if (rightFR != null) right = rightFR
                if (downFR  != null) down  = downFR
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteraction()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White.copy(alpha = if (focused) 1f else 0.90f),
        )
    }
}

@Composable
private fun P2PlayPauseBtn(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onInteraction: () -> Unit = {},
    leftFR:  FocusRequester? = null,
    rightFR: FocusRequester? = null,
    downFR:  FocusRequester? = null,
    focusEnabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.08f else 1f,
        spring(0.68f, 400f), label = "pp_scale"
    )
    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 1f else 0.78f))
            .then(
                if (focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.90f), CircleShape)
                else Modifier
            )
            .focusRequester(focusRequester)
            .focusProperties {
                this.canFocus = focusEnabled
                up = FocusRequester.Cancel   // transport row is the top of the Phase-2 focus graph
                if (leftFR  != null) left  = leftFR
                if (rightFR != null) right = rightFR
                if (downFR  != null) down  = downFR
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteraction()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(5.dp).height(18.dp).background(Color(0xFF0C0C0F), RoundedCornerShape(2.dp)))
                Box(Modifier.width(5.dp).height(18.dp).background(Color(0xFF0C0C0F), RoundedCornerShape(2.dp)))
            }
        } else {
            Canvas(Modifier.size(18.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.14f)
                    lineTo(size.width * 0.85f, size.height * 0.50f)
                    lineTo(size.width * 0.28f, size.height * 0.86f)
                    close()
                }
                drawPath(path, Color(0xFF0C0C0F))
            }
        }
    }
}

@Composable
private fun P2TrackChip(
    prefix: String,
    label: String,
    onClick: () -> Unit,
    onInteraction: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    focusEnabled: Boolean = true,
    focusPropertiesBlock: (androidx.compose.ui.focus.FocusProperties.() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (isFocused) 1.06f else 1f,
        spring(0.72f, 400f), label = "track_chip_scale"
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = if (isFocused) 0.22f else 0.10f))
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.80f), RoundedCornerShape(24.dp))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            )
            .focusRequester(focusRequester)
            .focusProperties {
                this.canFocus = focusEnabled
                focusPropertiesBlock?.invoke(this)
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onInteraction()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = prefix,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (0.14f * 11).sp,
                color = Color.White.copy(alpha = if (isFocused) 0.70f else 0.45f)
            )
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = if (isFocused) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}

@Composable
private fun formatMinutesLeft(ms: Long): String {
    val totalMin = (ms / 60_000L).toInt()
    return when {
        totalMin <= 0 -> stringResource(R.string.minutes_left_under_one)
        totalMin < 60 -> stringResource(R.string.minutes_left, totalMin)
        else          -> stringResource(R.string.hours_minutes_left, totalMin / 60, totalMin % 60)
    }
}

@Composable
private fun Phase2Card(
    item: UpNextItem,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    focusProperties: (androidx.compose.ui.focus.FocusProperties.() -> Unit) = {},
    onDownPressed: (() -> Unit)?,
    onFocused: () -> Unit
) {
    val cardShape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.02f else 1f,
        spring(androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
               androidx.compose.animation.core.Spring.StiffnessMedium),
        label = "card_scale"
    )
    val glowAlpha by animateFloatAsState(
        if (focused) 1f else 0f,
        tween(280, easing = FastOutSlowInEasing),
        label = "card_glow"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties(focusProperties)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // clickable subsumes focusable() and consumes BOTH key phases of OK,
            // so this cannot re-introduce an F1-style KeyUp leak.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation    = (6 * glowAlpha).dp,
                shape        = cardShape,
                spotColor    = Color.White.copy(alpha = 0.35f * glowAlpha),
                ambientColor = Color.White.copy(alpha = 0.15f * glowAlpha)
            )
            .clip(cardShape)
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.07f))
            .then(
                if (focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.90f), cardShape)
                else Modifier
            )
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown &&
                    onDownPressed != null) {
                    onDownPressed(); true
                } else false
            }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        val thumbShape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.36f)
                .aspectRatio(16f / 9f)
                .clip(thumbShape)
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            // Placeholder beneath the still — deeper episodes whose PRIMARY image 404s
            // read as an intentional "EP N" tile instead of a broken grey box.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.p2_ep_short, item.episodeNumber),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (0.10f * 12).sp,
                    color = Color.White.copy(alpha = 0.30f)
                )
            }
            AsyncImage(
                model              = item.thumbnailUrl,
                contentDescription = item.title,
                contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
            // Resume progress (currently-watching card only) — real fraction, purple.
            item.progressFraction?.let { frac ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(PlaybackColors.Purple))
                }
            }
        }
        // Identical structure on every card (title + status line) → consistent alignment.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.p2_episode_number, item.episodeNumber),
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.90f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (item.isCurrentlyWatching) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.p2_watching), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (0.06f * 12).sp, color = PlaybackColors.Purple,
                        maxLines = 1
                    )
                    if (item.durationLabel.isNotEmpty()) {
                        Text(
                            item.durationLabel,
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1
                        )
                    }
                }
            } else {
                Text(
                    item.durationLabel,
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PHASE 2 COMPACT — ambient cast/info slideshow (passive, nothing focusable)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoilApi::class)
@Composable
private fun Phase2CompactAmbient(
    state: PlaybackUiState,
    slideshowHold: Boolean,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current

    // Ken-Burns zoom on the backdrop (reused from AppScreensaver)
    val ken = rememberInfiniteTransition(label = "ken")
    val kenScale by ken.animateFloat(
        1f, 1.08f,
        infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "ken_scale"
    )
    // Burn-in mitigation: slow horizontal drift + dim after a few minutes paused.
    val driftAnim = rememberInfiniteTransition(label = "drift")
    val drift by driftAnim.animateFloat(
        -5f, 5f,
        infiniteRepeatable(tween(45_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift_x"
    )
    var longIdle by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(180_000L); longIdle = true }
    val burnInDim by animateFloatAsState(
        targetValue = if (longIdle) 0.55f else 1f,
        animationSpec = tween(3_000),
        label = "burnin_dim"
    )

    val radialScrim = remember {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val bigger = maxOf(size.height, size.width)
                return RadialGradientShader(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    center = size.center,
                    radius = bigger / 1.4f,
                    colorStops = listOf(0f, 0.9f),
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().graphicsLayer { this.alpha = alpha * burnInDim }) {
        if (state.ambient.backdropUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(state.ambient.backdropUrl)
                    .transitionFactory(CrossFadeFactory(2000.milliseconds))
                    .useExistingImageAsPlaceholder(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = kenScale; scaleY = kenScale }
            )
        }
        // Contrast scrims: radial vignette + a left wash (for the panel) + a bottom wash (for the bar)
        Canvas(Modifier.fillMaxSize()) { drawRect(brush = radialScrim, blendMode = BlendMode.Multiply) }
        Box(
            Modifier.fillMaxWidth(0.62f).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.40f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
        )

        // Logo (top-left)
        if (state.logoUrl.isNotBlank()) {
            AsyncImage(
                model = state.logoUrl,
                contentDescription = state.showTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = PlaybackDimens.SafeH, top = PlaybackDimens.SafeV + 16.dp)
                    .offset(x = drift.dp)
                    .height(64.dp)
                    .widthIn(max = 300.dp)
            )
        } else {
            Text(
                text = state.showTitle.uppercase(),
                fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = Color.White.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = PlaybackDimens.SafeH, top = PlaybackDimens.SafeV + 16.dp)
                    .offset(x = drift.dp)
                    .widthIn(max = 360.dp)
            )
        }

        // The cycling cast/info panel
        AmbientSlideshow(
            state = state,
            hold = slideshowHold,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = PlaybackDimens.SafeH, end = 24.dp)
                .offset(x = drift.dp)
                .fillMaxWidth(0.52f)
        )

        // Discoverability hint
        Text(
            text = stringResource(
                if (slideshowHold) R.string.p2_hint_compact_held else R.string.p2_hint_compact
            ),
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = PlaybackDimens.SafeH, bottom = PlaybackDimens.SafeV + 44.dp)
        )
    }
}

private sealed interface AmbientSlide {
    data class Synopsis(val text: String) : AmbientSlide
    data class Tagline(val text: String) : AmbientSlide
    data class Cast(val person: AmbientPerson) : AmbientSlide
    data class Facts(
        val genres: List<String>, val studios: List<String>, val meta: String, val rating: Float?
    ) : AmbientSlide
    data class Crew(val directors: List<String>, val writers: List<String>) : AmbientSlide
}

@Composable
private fun AmbientSlideshow(
    state: PlaybackUiState,
    hold: Boolean,
    modifier: Modifier = Modifier,
) {
    val a = state.ambient
    val slides = remember(state.synopsis, state.year, state.rating, state.durationMs, a) {
        buildList {
            if (a.tagline.isNotBlank()) add(AmbientSlide.Tagline(a.tagline))
            if (state.synopsis.isNotBlank()) add(AmbientSlide.Synopsis(state.synopsis))
            a.cast.take(12).forEach { add(AmbientSlide.Cast(it)) }
            val meta = buildList {
                if (state.year.isNotBlank()) add(state.year)
                val mins = state.durationMs / 60_000L
                if (mins > 0) add(if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m")
                if (state.rating.isNotBlank()) add(state.rating)
            }.joinToString("   ·   ")
            if (a.genres.isNotEmpty() || a.studios.isNotEmpty() || meta.isNotBlank()) {
                add(AmbientSlide.Facts(a.genres, a.studios, meta, a.communityRating))
            }
            if (a.directors.isNotEmpty() || a.writers.isNotEmpty()) {
                add(AmbientSlide.Crew(a.directors, a.writers))
            }
        }
    }
    if (slides.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides.size) { if (index >= slides.size) index = 0 }
    LaunchedEffect(index, hold, slides.size) {
        if (!hold && slides.size > 1) {
            kotlinx.coroutines.delay(8_000L)
            index = (index + 1) % slides.size
        }
    }

    Crossfade(
        targetState = slides[index.coerceIn(0, slides.lastIndex)],
        animationSpec = tween(500),
        label = "ambient_slide",
        modifier = modifier,
    ) { slide ->
        when (slide) {
            is AmbientSlide.Synopsis -> AmbientBlock(stringResource(R.string.p2_kicker_synopsis)) {
                Text(
                    slide.text, fontSize = 16.sp, color = Color.White.copy(alpha = 0.92f),
                    lineHeight = (16 * 1.5f).sp, maxLines = 5, overflow = TextOverflow.Ellipsis
                )
            }
            is AmbientSlide.Tagline -> AmbientBlock(null) {
                Text(
                    "“${slide.text}”", fontSize = 17.sp, fontWeight = FontWeight.Light,
                    color = Color.White.copy(alpha = 0.92f), lineHeight = (17 * 1.4f).sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
            is AmbientSlide.Cast -> AmbientPersonSlide(slide.person)
            is AmbientSlide.Facts -> AmbientBlock(stringResource(R.string.p2_kicker_details)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (slide.meta.isNotBlank()) {
                        Text(slide.meta, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.85f))
                    }
                    if (slide.rating != null) {
                        Text("★ ${"%.1f".format(slide.rating)}", fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFFFFC700))
                    }
                    if (slide.genres.isNotEmpty()) {
                        Text(slide.genres.take(5).joinToString("   ·   "), fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.80f))
                    }
                    if (slide.studios.isNotEmpty()) {
                        Text(slide.studios.take(3).joinToString("   ·   "), fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.60f))
                    }
                }
            }
            is AmbientSlide.Crew -> AmbientBlock(stringResource(R.string.p2_kicker_crew)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (slide.directors.isNotEmpty()) {
                        AmbientCredit(stringResource(R.string.p2_directed_by), slide.directors)
                    }
                    if (slide.writers.isNotEmpty()) {
                        AmbientCredit(stringResource(R.string.p2_written_by), slide.writers)
                    }
                }
            }
        }
    }
}

@Composable
private fun AmbientBlock(label: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (0.18f * 11).sp, color = PlaybackColors.Blue)
        }
        content()
    }
}

@Composable
private fun AmbientCredit(label: String, names: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = (0.14f * 11).sp,
            color = Color.White.copy(alpha = 0.5f))
        Text(names.take(3).joinToString(", "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AmbientPersonSlide(person: AmbientPerson) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
        ) {
            if (person.imageUrl != null) {
                AsyncImage(
                    model = person.imageUrl, contentDescription = person.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.p2_kicker_cast), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (0.18f * 11).sp, color = PlaybackColors.Blue)
            Text(person.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (person.role.isNotBlank()) {
                Text(person.role, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase 2 detail helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun buildMetaLine(
    year: String,
    rating: String,
    season: Int,
    episode: Int,
    durationMs: Long
): String {
    val parts = mutableListOf<String>()
    if (year.isNotEmpty()) parts.add(year)
    if (rating.isNotEmpty()) parts.add(rating)
    if (season > 0 && episode > 0) parts.add("S$season:E$episode")
    val durationLabel = formatPhase2Duration(durationMs)
    if (durationLabel.isNotEmpty()) parts.add(durationLabel)
    return parts.joinToString(" · ")
}

private fun formatPhase2Duration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalMin = ms / 60_000L
    val h = totalMin / 60L
    val m = totalMin % 60L
    return if (h > 0L) "${h}h %02dm".format(m) else "${m}m"
}

// ─────────────────────────────────────────────────────────────────────────────
// LAYER 3: PHASE 1 — TOOLBAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Phase1ToolbarLayer(
    state: PlaybackUiState,
    viewModel: PlaybackPhaseViewModel,
    onPlayPauseRequested: () -> Unit,
    onSeekRequested: (Long) -> Unit,
    qualityLabel: String = "",
    onQualityRequested: () -> Unit = {}
) {
    val isVisible = state.phase == PlaybackPhase.ACTIVE &&
                    state.toolbarVisibility == ToolbarVisibility.VISIBLE

    val playBtnFocusRequester = remember { FocusRequester() }

    // Measured distance from each setting chip's RIGHT edge to the chips-row end, so
    // each dropdown stays glued over its chip regardless of chip/label width.
    var audioChipEndInset by remember { mutableStateOf(164.dp) }
    var subChipEndInset by remember { mutableStateOf(0.dp) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            kotlinx.coroutines.delay(60L)
            runCatching { playBtnFocusRequester.requestFocus() }
        }
    }

    val alpha by animateFloatAsState(
        targetValue   = if (isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isVisible) PlaybackMotion.ToolbarInMs else PlaybackMotion.ToolbarOutMs,
            easing         = if (isVisible) PlaybackMotion.CinematicDecelerate
                             else PlaybackMotion.CinematicAccelerate
        ),
        label = "toolbar_alpha"
    )
    val topOffset by animateDpAsState(
        targetValue   = if (isVisible) 0.dp else (-24).dp,
        animationSpec = tween(PlaybackMotion.ToolbarInMs, easing = PlaybackMotion.CinematicDecelerate),
        label = "topbar_offset"
    )
    val bottomOffset by animateDpAsState(
        targetValue   = if (isVisible) 0.dp else 24.dp,
        animationSpec = tween(PlaybackMotion.ToolbarInMs, easing = PlaybackMotion.CinematicDecelerate),
        label = "btmbar_offset"
    )

    if (alpha > 0f) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            // Top gradient legibility scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.24f)
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
            )

            // Bottom gradient legibility scrim — grounds the control bar so the
            // ghost-state buttons stay readable over bright video.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.30f)
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.80f))
                        )
                    )
            )

            P1TopBar(
                showTitle = state.showTitle,
                logoUrl   = state.logoUrl,
                season    = state.season,
                episode   = state.episode,
                modifier  = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .offset(y = topOffset)
                    .padding(
                        horizontal = PlaybackDimens.SafeH,
                        vertical   = PlaybackDimens.SafeV
                    )
            )

            P1BottomBar(
                state                = state,
                viewModel            = viewModel,
                onPlayPauseRequested = onPlayPauseRequested,
                onSeekRequested      = onSeekRequested,
                playBtnFocusRequester = playBtnFocusRequester,
                qualityLabel         = qualityLabel,
                onQualityRequested   = onQualityRequested,
                onChipEndInsetChanged = { type, inset ->
                    when (type) {
                        RouletteType.AUDIO    -> audioChipEndInset = inset
                        RouletteType.SUBTITLE -> subChipEndInset = inset
                    }
                },
                modifier             = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .offset(y = bottomOffset)
                    .padding(
                        start  = PlaybackDimens.SafeH,
                        end    = PlaybackDimens.SafeH,
                        bottom = PlaybackDimens.SafeV + 8.dp
                    )
            )

            // Phase 1 dropdown — renders above the chip that opened it, right edges
            // aligned via the measured chip insets.
            state.dropdown?.let { dd ->
                val endPadding = when (dd.type) {
                    RouletteType.SUBTITLE -> PlaybackDimens.SafeH + subChipEndInset
                    RouletteType.AUDIO    -> PlaybackDimens.SafeH + audioChipEndInset
                }
                val dropdownItems = when (dd.type) {
                    RouletteType.AUDIO    -> state.audioItems
                    RouletteType.SUBTITLE -> state.subtitleItems
                }
                val confirmedIdx = when (dd.type) {
                    RouletteType.AUDIO    -> state.audioConfirmedIndex
                    RouletteType.SUBTITLE -> state.subtitleConfirmedIndex
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = endPadding, bottom = PlaybackDimens.SafeV + 8.dp + 88.dp)
                ) {
                    P1DropdownMenu(
                        type         = dd.type,
                        items        = dropdownItems,
                        confirmedIdx = confirmedIdx,
                        focusedIdx   = dd.focusedIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun P1DropdownMenu(
    type: RouletteType,
    items: List<RouletteItem>,
    confirmedIdx: Int,
    focusedIdx: Int
) {
    val header = when (type) {
        RouletteType.AUDIO    -> stringResource(R.string.p1_dropdown_audio)
        RouletteType.SUBTITLE -> stringResource(R.string.p1_dropdown_subtitle)
    }
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E0F14).copy(alpha = 0.96f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
    ) {
        // Header
        Text(
            text          = header,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = (0.16f * 11).sp,
            color         = Color.White.copy(alpha = 0.40f),
            modifier      = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp, bottom = 6.dp)
        )

        items.forEachIndexed { index, item ->
            val isCurrent = index == confirmedIdx
            val isFocused = index == focusedIdx

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFocused) Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(2.dp, Color.White.copy(alpha = 0.80f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                        else Modifier
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isCurrent) PlaybackColors.Purple else Color.Transparent,
                            CircleShape
                        )
                        .then(
                            if (!isCurrent) Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            else Modifier
                        )
                )
                Text(
                    text       = item.title,
                    fontSize   = 15.sp,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                    color      = Color.White.copy(alpha = when {
                        isFocused -> 1f
                        isCurrent -> 0.92f
                        else      -> 0.55f
                    }),
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f)
                )
                if (isCurrent) {
                    Text(
                        text       = stringResource(R.string.p2_current_on),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (0.08f * 11).sp,
                        color      = Color.White.copy(alpha = 0.38f)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

// Renders the Jellyfin logo (stylized wordmark) when one is available, otherwise
// falls back to the provided title text — keeping each call site's own text style.
@Composable
private fun LogoOrTitle(
    logoUrl: String,
    contentDescription: String,
    logoHeight: Dp,
    maxLogoWidth: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    if (logoUrl.isNotEmpty()) {
        AsyncImage(
            model              = logoUrl,
            contentDescription = contentDescription,
            contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
            alignment          = Alignment.CenterStart,
            modifier           = modifier
                .height(logoHeight)
                .widthIn(max = maxLogoWidth)
        )
    } else {
        fallback()
    }
}

@Composable
private fun P1TopBar(
    showTitle: String,
    logoUrl: String,
    season: Int,
    episode: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LogoOrTitle(
                logoUrl            = logoUrl,
                contentDescription = showTitle,
                logoHeight         = 34.dp,
                maxLogoWidth       = 300.dp
            ) {
                Text(
                    text          = showTitle,
                    fontSize      = 20.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = (-0.04f * 20).sp,
                    lineHeight    = (20 * 1.1f).sp,
                    color         = Color.White,
                    maxLines      = 1,
                    overflow      = TextOverflow.Ellipsis
                )
            }
            Text(
                text          = stringResource(R.string.p1_season_episode, season, episode),
                fontSize      = 13.sp,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = (0.12f * 13).sp,
                color         = Color.White.copy(alpha = 0.72f)
            )
        }
        P1LiveClock()
    }
}

@Composable
private fun P1LiveClock() {
    var timeText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val cal = java.util.Calendar.getInstance()
            val h   = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
            val m   = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
            val ap  = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
            timeText = "$h:$m $ap"
            kotlinx.coroutines.delay(10_000L)
        }
    }
    Text(
        text          = timeText,
        color         = Color.White.copy(alpha = 0.85f),
        fontSize      = 15.sp,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = (0.02f * 15).sp
    )
}

@Composable
private fun P1BottomBar(
    state: PlaybackUiState,
    viewModel: PlaybackPhaseViewModel,
    onPlayPauseRequested: () -> Unit,
    onSeekRequested: (Long) -> Unit,
    playBtnFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    qualityLabel: String = "",
    onQualityRequested: () -> Unit = {},
    onChipEndInsetChanged: (RouletteType, Dp) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    // Reports the distance from a chip's right edge to the chips-row end, which the
    // dropdown layer uses to right-align each dropdown over its chip.
    fun chipInsetModifier(type: RouletteType) = Modifier.onGloballyPositioned { coords ->
        val row = coords.parentLayoutCoordinates ?: return@onGloballyPositioned
        val insetPx = row.size.width - coords.positionInParent().x - coords.size.width
        onChipEndInsetChanged(type, with(density) { insetPx.toDp() })
    }
    val seekFraction    = if (state.durationMs > 0L) state.seekPositionMs.toFloat() / state.durationMs else 0f
    val bufferedFraction = if (state.durationMs > 0L) state.bufferedPositionMs.toFloat() / state.durationMs else 0f

    val seekBarAlpha by animateFloatAsState(
        targetValue   = if (state.dropdown != null) 0.25f else 1f,
        animationSpec = tween(220),
        label         = "seekBarDim"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CinematicSeekBar(
            seekFraction     = seekFraction,
            bufferedFraction = bufferedFraction,
            onSeek           = { fraction ->
                val posMs = (fraction * state.durationMs).toLong()
                onSeekRequested(posMs)
                viewModel.onSeekTo(posMs)
            },
            // Display-only in Phase 1 — LEFT/RIGHT are the skip buttons' job; a stray
            // focusable here strands D-pad focus above the transport row.
            focusable = false,
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = seekBarAlpha }
        )
        val anyDropdownOpen = state.dropdown != null
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            FrostPlaybackButton(
                iconRes            = null,
                text               = "«30",
                contentDescription = stringResource(R.string.p1_skip_back_30),
                isDimmed           = anyDropdownOpen,
                onClick            = {
                    val pos = (state.seekPositionMs - 30_000L).coerceAtLeast(0L)
                    onSeekRequested(pos)
                    viewModel.onSeekTo(pos)
                },
            )
            FrostPlaybackButton(
                iconRes            = if (state.isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
                text               = null,
                contentDescription = stringResource(if (state.isPlaying) R.string.p1_pause else R.string.p1_play),
                isPrimary          = true,
                isDimmed           = anyDropdownOpen,
                focusRequester     = playBtnFocusRequester,
                onClick            = {
                    onPlayPauseRequested()
                    viewModel.onPlayPauseToggle()
                },
            )
            FrostPlaybackButton(
                iconRes            = null,
                text               = "15»",
                contentDescription = stringResource(R.string.p1_skip_forward_15),
                isDimmed           = anyDropdownOpen,
                onClick            = {
                    // durationMs == 0 while unknown (live / not yet reported) — clamping
                    // against it would seek to the start instead of forward.
                    val limit = if (state.durationMs > 0L) state.durationMs else Long.MAX_VALUE
                    val pos = (state.seekPositionMs + 15_000L).coerceAtMost(limit)
                    onSeekRequested(pos)
                    viewModel.onSeekTo(pos)
                },
            )
            Spacer(Modifier.weight(1f))
            FrostSettingButton(
                header   = stringResource(R.string.p2_header_audio),
                primary  = state.audioItems.getOrNull(state.audioConfirmedIndex)?.title ?: "",
                isActive = state.dropdown?.type == RouletteType.AUDIO,
                isDimmed = anyDropdownOpen && state.dropdown?.type != RouletteType.AUDIO,
                onOpen   = { viewModel.openDropdown(RouletteType.AUDIO) },
                modifier = chipInsetModifier(RouletteType.AUDIO),
            )
            FrostSettingButton(
                header   = stringResource(R.string.p1_sub),
                primary  = state.subtitleItems.getOrNull(state.subtitleConfirmedIndex)?.title
                    ?: stringResource(R.string.track_off),
                isActive = state.dropdown?.type == RouletteType.SUBTITLE,
                isDimmed = anyDropdownOpen && state.dropdown?.type != RouletteType.SUBTITLE,
                onOpen   = { viewModel.openDropdown(RouletteType.SUBTITLE) },
                modifier = chipInsetModifier(RouletteType.SUBTITLE),
            )
            FrostSettingButton(
                header   = stringResource(R.string.p1_quality),
                primary  = qualityLabel,
                isActive = false,
                isDimmed = anyDropdownOpen,
                onOpen   = onQualityRequested,
            )
        }
    }
}
