@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.github.jkrishna289.orcax.ui.playback

import android.view.Gravity
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.preferences.AppThemeColors
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.FontAwesome
import com.github.jkrishna289.orcax.ui.components.Button
import com.github.jkrishna289.orcax.ui.components.SelectedLeadingContent
import com.github.jkrishna289.orcax.ui.indexOfFirstOrNull
import com.github.jkrishna289.orcax.ui.seekBack
import com.github.jkrishna289.orcax.ui.seekForward
import com.github.jkrishna289.orcax.ui.theme.LocalTheme
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Surface
import com.github.jkrishna289.orcax.data.model.TrackIndex
import kotlin.math.abs
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackAction
// ─────────────────────────────────────────────────────────────────────────────

sealed interface PlaybackAction {
    data object ShowDebug : PlaybackAction
    data object ShowPlaylist : PlaybackAction
    data object ShowVideoFilterDialog : PlaybackAction
    data object SearchCaptions : PlaybackAction
    data class ToggleCaptions(val index: Int) : PlaybackAction
    data class ToggleAudio(val index: Int) : PlaybackAction
    data class PlaybackSpeed(val value: Float) : PlaybackAction
    data class Scale(val scale: ContentScale) : PlaybackAction
    data object Previous : PlaybackAction
    data object Next : PlaybackAction
    data class ChangeQuality(val tier: QualityTier) : PlaybackAction
}

val buttonSpacing = 12.dp

// ─── Toolbar button design tokens ─────────────────────────────────────────────
// Shared glass base (both play/pause and pills at rest)
private val TV_BG         = Color.White.copy(alpha = 0.10f)
private val TV_BORDER_CLR = Color.White.copy(alpha = 0.10f)

// Play/pause circle — scale + ring + glow on focus, no bg inversion
private val PP_SHAPE = androidx.compose.foundation.shape.CircleShape
private val PP_BORDER = androidx.tv.material3.Border(
    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
    shape = PP_SHAPE,
)
private val PP_BORDER_FOC = androidx.tv.material3.Border(
    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.68f)),
    shape = PP_SHAPE,
)

// Pill buttons (Quality, Audio/Sub) — white bg inversion + heavy glow on focus
private val PILL_SHAPE = androidx.compose.foundation.shape.CircleShape  // stadium/pill on non-square
private val PILL_BORDER = androidx.tv.material3.Border(
    border = androidx.compose.foundation.BorderStroke(1.dp, TV_BORDER_CLR),
    shape = PILL_SHAPE,
)
private val PILL_BORDER_FOC = androidx.tv.material3.Border(
    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White),
    shape = PILL_SHAPE,
)

// ─────────────────────────────────────────────────────────────────────────────
// Format milliseconds as M:SS or H:MM:SS
// ─────────────────────────────────────────────────────────────────────────────
fun Long.toTimeString(): String {
    if (this <= 0L) return "0:00"
    val totalSec = this / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackControls — Phase 1 floating layout
//
//  [40 dp from bottom]  Seekbar with 80 dp horizontal margins  +  "Ends at" above right
//  [60 dp from bottom]  ◉ Play/Pause   │  [ AUDIO | SUBTITLES | QUALITY ]
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun PlaybackControls(
    playerControls: Player,
    controllerViewState: ControllerViewState,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekProgress: (Long) -> Unit,
    showPlay: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    qualityLabel: String,
    audioStreams: List<SimpleMediaStream> = emptyList(),
    subtitleStreams: List<SimpleMediaStream> = emptyList(),
    selectedAudioIndex: Int? = null,
    selectedSubtitleIndex: Int? = null,
    onSelectAudio: (Int) -> Unit = {},
    onSelectSubtitle: (Int) -> Unit = {},
    isSwitchingStream: Boolean = false,
    endsAtString: String = "",
    showCapsule: Boolean = false,
    modifier: Modifier = Modifier,
    seekBarInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    var positionMs by remember(playerControls) { mutableLongStateOf(playerControls.currentPosition) }
    var durationMs by remember(playerControls) { mutableLongStateOf(playerControls.duration.coerceAtLeast(0L)) }
    val switching by rememberUpdatedState(isSwitchingStream)
    LaunchedEffect(playerControls) {
        while (isActive) {
            if (!switching) {
                positionMs = playerControls.currentPosition
                durationMs = playerControls.duration.coerceAtLeast(0L)
            }
            delay(500L)
        }
    }

    // Internal focus chain: PP → Audio → Sub → Quality
    val ppFr    = remember { FocusRequester() }
    val audioFr = remember { FocusRequester() }
    val subFr   = remember { FocusRequester() }
    val qualFr  = remember { FocusRequester() }

    LaunchedEffect(showCapsule) {
        if (showCapsule) ppFr.tryRequestFocus()
    }

    val controlsVisible = controllerViewState.controlsVisible
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Box(modifier = modifier.heightIn(min = 120.dp)) {

        // ── Unified bottom block — seekbar row + capsule row ─────────────────
        // Only shown during pause (showCapsule). Active-playback OSD is handled by PlaybackToolbar.
        AnimatedVisibility(
            visible = showCapsule,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 80.dp)
                    .padding(bottom = 28.dp),
            ) {
                // "Ends at" — right-aligned above seekbar
                if (endsAtString.isNotEmpty()) {
                    Text(
                        text = endsAtString,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(bottom = 6.dp),
                    )
                }

                // Row 1: ◉ Play/Pause (54dp, paused only) + seekbar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AnimatedVisibility(visible = showCapsule) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                onClick = {
                                    if (showPlay) {
                                        playerControls.play()
                                        skipBackOnResume?.let { playerControls.seekBack(it) }
                                    } else {
                                        playerControls.pause()
                                    }
                                },
                                shape = ClickableSurfaceDefaults.shape(CircleShape),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color(0xFF161616),
                                    focusedContainerColor = Color(0xFF1F1F1F),
                                ),
                                glow = ClickableSurfaceDefaults.glow(
                                    focusedGlow = Glow(
                                        elevationColor = Color.White.copy(alpha = 0.50f),
                                        elevation = 22.dp,
                                    ),
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                                border = ClickableSurfaceDefaults.border(
                                    border = PP_BORDER,
                                    focusedBorder = PP_BORDER_FOC,
                                ),
                                modifier = Modifier
                                    .size(52.dp)
                                    .focusRequester(ppFr)
                                    .onKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                                            audioFr.tryRequestFocus(); true
                                        } else false
                                    },
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (showPlay) R.drawable.baseline_play_arrow_24
                                        else R.drawable.baseline_pause_24,
                                    ),
                                    contentDescription = if (showPlay) "Play" else "Pause",
                                    tint = Color.White,
                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                    }

                    Box(Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .align(Alignment.CenterStart)
                                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        0f   to Color.White.copy(alpha = 0.00f),
                                        0.6f to Color.White.copy(alpha = 0.10f),
                                        1f   to Color.White.copy(alpha = 0.22f),
                                    ),
                                ),
                        )
                        IntervalSeekBarImpl(
                            progress = progress,
                            bufferedProgress = if (durationMs > 0)
                                playerControls.bufferedPosition.toFloat() / durationMs else 0f,
                            onSeek = onSeekProgress,
                            controllerViewState = controllerViewState,
                            modifier = Modifier.fillMaxWidth(),
                            interactionSource = seekBarInteractionSource,
                            enabled = seekEnabled,
                            durationMs = durationMs,
                            seekBack = seekBack,
                            seekForward = seekForward,
                        )
                    }
                }

                // Row 2: Capsule centred on spine + Quality anchored far right — paused only
                AnimatedVisibility(
                    visible = showCapsule,
                    enter = fadeIn(tween(300)),
                    exit  = fadeOut(tween(200)),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    ) {
                        MasterCapsule(
                            audioStreams = audioStreams,
                            selectedAudioIndex = selectedAudioIndex,
                            onSelectAudio = onSelectAudio,
                            subtitleStreams = subtitleStreams,
                            selectedSubtitleIndex = selectedSubtitleIndex,
                            onSelectSubtitle = onSelectSubtitle,
                            qualityFocusRequester = qualFr,
                            audioFocusRequester = audioFr,
                            subFocusRequester = subFr,
                            onAudioNavigateLeft = { ppFr.tryRequestFocus() },
                            interactive = showCapsule,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        QualityPillButton(
                            label = qualityLabel,
                            focusRequester = qualFr,
                            onClick = { onClickPlaybackDialogType(PlaybackDialogType.QUALITY) },
                            onLeft = {
                                if (subtitleStreams.isNotEmpty()) subFr.tryRequestFocus()
                                else if (audioStreams.isNotEmpty()) audioFr.tryRequestFocus()
                                else ppFr.tryRequestFocus()
                            },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ToolbarLabelButton   e.g. "⚡ AUTO"
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ToolbarLabelButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    onFocusChanged: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentColor = if (isFocused) Color.Black else Color.White

    Button(
        enabled = enabled,
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(PILL_SHAPE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TV_BG,
            focusedContainerColor = Color.White,
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.40f),
                elevation = 16.dp,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(
            border = PILL_BORDER,
            focusedBorder = PILL_BORDER_FOC,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        modifier = modifier.wrapContentHeight().onFocusChanged {
            isFocused = it.isFocused
            onFocusChanged()
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                modifier = Modifier.size(11.dp),
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.sp,
                color = contentColor,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AudioSubButton   e.g. "🌐 US | 💬 OFF"
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioSubButton(
    audioFlag: String,
    subtitleLabel: String,
    audioFullLabel: String = "",
    subtitleFullLabel: String = "",
    onClick: () -> Unit,
    onFocusChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Parse codec badge from audioFullLabel (last token if it is a known codec name)
    val codecBadge = audioFullLabel.split(" ").lastOrNull()
        ?.takeIf { it.length >= 3 && it.any(Char::isLetter) && it != audioFlag }
        ?: ""
    val audioLang = audioFlag  // e.g. "English"
    val subtitleOff = subtitleFullLabel.equals("Off", ignoreCase = true) ||
            subtitleFullLabel.isBlank() ||
            subtitleLabel.equals("OFF", ignoreCase = true)

    var isFocused by remember { mutableStateOf(false) }
    val onColor = if (isFocused) Color.Black else Color.White

    Button(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(PILL_SHAPE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TV_BG,
            focusedContainerColor = Color.White,
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.40f),
                elevation = 16.dp,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = ClickableSurfaceDefaults.border(
            border = PILL_BORDER,
            focusedBorder = PILL_BORDER_FOC,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        modifier = modifier.wrapContentHeight().onFocusChanged {
            isFocused = it.isFocused
            onFocusChanged()
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── AUDIO PLAYING section ──────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "AUDIO PLAYING",
                    fontSize = 6.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.12.sp,
                    color = onColor.copy(alpha = 0.35f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        audioLang,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = onColor,
                        maxLines = 1,
                    )
                    if (codecBadge.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(onColor.copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp),
                        ) {
                            Text(
                                codecBadge.uppercase(),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.06.sp,
                                color = onColor.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            // ── Divider ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(onColor.copy(alpha = 0.20f)),
            )

            // ── SUBTITLES section ─────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "SUBTITLES",
                    fontSize = 6.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.12.sp,
                    color = onColor.copy(alpha = 0.35f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (!subtitleOff) {
                        // Dot adapts: teal when unfocused, black when focused (white bg)
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    if (isFocused) Color.Black else Color(0xFF00C8C8),
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                    }
                    Text(
                        if (subtitleOff) "Off"
                        else subtitleFullLabel.ifBlank { subtitleLabel },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isFocused -> Color.Black
                            subtitleOff -> Color.White.copy(alpha = 0.55f)
                            else -> Color(0xFF00E5E5)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackButton — square icon button (kept for NowPlayingButtons compat)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PlaybackButton(
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    onControllerInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    Button(
        enabled = enabled, onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.TransparentBlack25, focusedContainerColor = MaterialTheme.colorScheme.border),
        contentPadding = PaddingValues(4.dp), interactionSource = interactionSource,
        modifier = modifier.size(36.dp).onFocusChanged { onControllerInteraction() },
    ) {
        Icon(modifier = Modifier.fillMaxSize(), painter = painterResource(iconRes), contentDescription = "",
            tint = if (LocalTheme.current == AppThemeColors.OLED_BLACK) LocalContentColor.current else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PlaybackFaButton(
    @StringRes iconRes: Int, onClick: () -> Unit, onControllerInteraction: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null, textColor: Color = Color.Unspecified,
) {
    Button(
        enabled = enabled, onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.TransparentBlack25, focusedContainerColor = MaterialTheme.colorScheme.border),
        contentPadding = PaddingValues(4.dp), interactionSource = interactionSource,
        modifier = modifier.size(36.dp).onFocusChanged { onControllerInteraction() },
    ) {
        Text(text = stringResource(iconRes), fontSize = 18.sp, fontFamily = FontAwesome, textAlign = TextAlign.Center,
            color = if (textColor.isSpecified) textColor else if (LocalTheme.current == AppThemeColors.OLED_BLACK) LocalContentColor.current else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().align(Alignment.CenterVertically))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackButtons — standalone for NowPlayingButtons.kt compatibility
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(UnstableApi::class)
@Composable
fun PlaybackButtons(
    player: Player, initialFocusRequester: FocusRequester, onControllerInteraction: () -> Unit,
    onPlaybackActionClick: (PlaybackAction) -> Unit, showPlay: Boolean,
    previousEnabled: Boolean, nextEnabled: Boolean,
    seekBack: Duration, skipBackOnResume: Duration?, seekForward: Duration,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(buttonSpacing), verticalAlignment = Alignment.CenterVertically) {
        PlaybackButton(R.drawable.baseline_skip_previous_24, onClick = { onControllerInteraction(); onPlaybackActionClick(PlaybackAction.Previous) }, enabled = previousEnabled, onControllerInteraction = onControllerInteraction)
        PlaybackButton(R.drawable.baseline_fast_rewind_24, onClick = { onControllerInteraction(); player.seekBack(seekBack) }, onControllerInteraction = onControllerInteraction)
        PlaybackButton(if (showPlay) R.drawable.baseline_play_circle_24 else R.drawable.baseline_pause_circle_24,
            onClick = { onControllerInteraction(); if (showPlay) { player.play(); skipBackOnResume?.let { player.seekBack(it) } } else player.pause() },
            onControllerInteraction = onControllerInteraction, modifier = Modifier.focusRequester(initialFocusRequester).size(44.dp))
        PlaybackButton(R.drawable.baseline_fast_forward_24, onClick = { onControllerInteraction(); player.seekForward(seekForward) }, onControllerInteraction = onControllerInteraction)
        PlaybackButton(R.drawable.baseline_skip_next_24, onClick = { onControllerInteraction(); onPlaybackActionClick(PlaybackAction.Next) }, enabled = nextEnabled, onControllerInteraction = onControllerInteraction)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SeekBar — standalone composable kept for NowPlayingOverlay.kt compatibility
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SeekBar(
    player: Player,
    isEnabled: Boolean,
    intervals: Int,
    controllerViewState: ControllerViewState,
    onSeekProgress: (Long) -> Unit,
    seekBack: Duration,
    seekForward: Duration,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    var bufferedProgress by remember(player) { mutableFloatStateOf(player.bufferedPosition.toFloat() / player.duration.coerceAtLeast(1L)) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var progress by remember(player) { mutableFloatStateOf(player.currentPosition.toFloat() / player.duration.coerceAtLeast(1L)) }
    LaunchedEffect(player) {
        while (isActive) {
            bufferedProgress = player.bufferedPosition.toFloat() / player.duration.coerceAtLeast(1L)
            position = player.currentPosition
            progress = player.currentPosition.toFloat() / player.duration.coerceAtLeast(1L)
            delay(250L)
        }
    }
    IntervalSeekBarImpl(
        progress = progress,
        bufferedProgress = bufferedProgress,
        onSeek = { onSeekProgress(it) },
        controllerViewState = controllerViewState,
        modifier = modifier,
        interactionSource = interactionSource,
        enabled = isEnabled,
        durationMs = player.duration.coerceAtLeast(0L),
        seekBack = seekBack,
        seekForward = seekForward,
    )
}


// ─────────────────────────────────────────────────────────────────────────────
// Master Capsule composables (Phase 1 glassmorphic pill control)
// ─────────────────────────────────────────────────────────────────────────────

internal val CAPSULE_BG     = Color.Black.copy(alpha = 0.72f)
private val CAPSULE_BORDER  = Color.White.copy(alpha = 0.22f)
private val CAPSULE_DIV     = Color.White.copy(alpha = 0.18f)
private val CAPSULE_ITEM_H  = 44.dp
// Material decelerate: accelerates briefly then snaps to rest — crisp, premium
private val CAPSULE_EASING  = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
private val CAPSULE_SEGMENT_W  = 148.dp  // fixed width per segment for pill alignment
private val CAPSULE_H_PAD      = 8.dp    // horizontal padding inside the outer capsule
private val CAPSULE_DIV_TOTAL  = 17.dp   // 8dp gap + 1dp line + 8dp gap
private val PILL_V_PAD         = 3.dp    // vertical inset of the sliding pill

@Composable
private fun CapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(CAPSULE_DIV),
    )
}

@Composable
private fun CapsuleSeekButton(
    @DrawableRes iconRes: Int,
    label: String,
    focusRequester: FocusRequester,
    onKeyDown: () -> Unit,
    onKeyUp: () -> Unit,
) {
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .size(44.dp)
            .focusRequester(focusRequester)
            .onKeyEvent { ev ->
                when {
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionCenter -> {
                        onKeyDown(); true
                    }
                    ev.type == KeyEventType.KeyUp && ev.key == Key.DirectionCenter -> {
                        onKeyUp(); true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 7.5.sp, color = Color.White.copy(alpha = 0.65f), fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Single-slot roulette segment that fits within the 54dp pill height.
 *
 * Layout inside the 54dp Box:
 *   HEADER label (8sp)
 *   2dp spacer
 *   22dp clipped scroll window — only the selected item is ever visible;
 *     adjacent items render at 38% alpha when focused but are masked by the clip.
 *
 * D-pad contract:
 *   UP / DOWN    → scroll one item (coerced to list bounds); gated by [interactive]
 *   CENTER/ENTER → [onCenterPress]; gated by [interactive]
 *   LEFT / RIGHT → [onLeft] / [onRight] (always active for column navigation)
 */
@Composable
private fun RouletteSegment(
    header: String,
    items: List<Pair<Int, String>>,
    scrollIndex: Int,
    onScrollIndex: (Int) -> Unit,
    focusRequester: FocusRequester,
    onCenterPress: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    interactive: Boolean = true,
    isActive: Boolean = false,
    hasActiveSegment: Boolean = false,
    onActivate: () -> Unit = {},
    onDeactivate: () -> Unit = {},
    width: Dp? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val valueSlotH = 22.dp
    val valueSlotPx = with(density) { valueSlotH.toPx() }

    val clampedIdx = scrollIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    val animatedY by animateFloatAsState(
        targetValue = -(clampedIdx * valueSlotPx),
        animationSpec = tween(durationMillis = 280, easing = CAPSULE_EASING),
        label = "rouletteY",
    )

    var isFocused by remember { mutableStateOf(false) }
    val textDimAlpha by animateFloatAsState(
        targetValue = if (hasActiveSegment && !isActive) 0.3f else 1f,
        animationSpec = tween(200),
        label = "textDimAlpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = (if (width != null) Modifier.width(width) else Modifier.wrapContentWidth().widthIn(max = 200.dp))
            .height(CAPSULE_ITEM_H)
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusable()
            .onKeyEvent { ev ->
                when (ev.key) {
                    Key.DirectionUp -> {
                        if (ev.type == KeyEventType.KeyDown && interactive && isActive) {
                            val n = (clampedIdx - 1).coerceAtLeast(0)
                            if (n != clampedIdx) onScrollIndex(n)
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (ev.type == KeyEventType.KeyDown && interactive && isActive) {
                            val n = (clampedIdx + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
                            if (n != clampedIdx) onScrollIndex(n)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (ev.type == KeyEventType.KeyDown && interactive) {
                            if (isActive) { onCenterPress(); onDeactivate() } else onActivate()
                        }
                        true
                    }
                    Key.DirectionLeft  -> { if (ev.type == KeyEventType.KeyDown) onLeft();  true }
                    Key.DirectionRight -> { if (ev.type == KeyEventType.KeyDown) onRight(); true }
                    else -> false
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = header,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.10.sp,
                color = Color.White.copy(alpha = 0.38f * textDimAlpha),
            )
            Spacer(Modifier.height(2.dp))
            // Clipped 22dp window — scrolling column translates so selected item fills it
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(valueSlotH)
                    .wrapContentWidth()
                    .clip(RectangleShape),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .wrapContentWidth()
                        .graphicsLayer { translationY = animatedY },
                ) {
                    items.forEachIndexed { i, (_, label) ->
                        val distance = abs(i - clampedIdx)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(valueSlotH)
                                .wrapContentWidth(),
                        ) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Normal,
                                color = Color.White.copy(
                                    alpha = when {
                                        distance == 0                -> 1.00f * textDimAlpha
                                        isFocused && distance == 1  -> 0.38f * textDimAlpha
                                        else                         -> 0.00f
                                    },
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QualitySegment(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLeft: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(PILL_SHAPE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .height(CAPSULE_ITEM_H)
            .widthIn(min = 80.dp)
            .focusRequester(focusRequester)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onLeft(); true }
                else false
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text("QUALITY", fontSize = 7.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.10.sp, color = Color.White.copy(alpha = 0.38f))
            Text(label.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/**
 * Standalone Quality pill button — placed to the right of MasterCapsule in Phase 1 toolbar.
 * Layout: [badge square] [QUALITY header / label value]
 */
@Composable
internal fun QualityPillButton(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentColor = if (isFocused) Color.Black else Color.White

    // Short badge derived from quality tier name
    val badgeLabel = when {
        label.contains("direct", ignoreCase = true) || label.contains("cinema", ignoreCase = true) -> "4K"
        label.contains("balanced", ignoreCase = true) -> "HD"
        label.contains("saver", ignoreCase = true) || label.contains("data", ignoreCase = true) -> "SD"
        label.equals("auto", ignoreCase = true) -> "A"
        else -> label.take(4).uppercase()
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CAPSULE_BG,
            focusedContainerColor = Color.White,
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.40f), elevation = 16.dp),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, CAPSULE_BORDER),
                shape = RoundedCornerShape(50.dp),
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                shape = RoundedCornerShape(50.dp),
            ),
        ),
        modifier = modifier
            .height(CAPSULE_ITEM_H)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { ev ->
                when {
                    ev.type == KeyEventType.KeyUp &&
                        (ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> { onClick(); true }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft -> { onLeft(); true }
                    else -> false
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 16.dp),
        ) {
            // Rounded-square badge: tier abbreviation
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(contentColor.copy(alpha = 0.10f)),
            ) {
                Text(
                    text = badgeLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.04.sp,
                    color = contentColor,
                )
            }
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * Glassmorphic pill containing the Phase 1 stream-control segments (Audio + Subtitles).
 * Quality is a separate standalone [QualityPillButton] placed to the right.
 *
 * Focus chain: Play/Pause ← Audio ↔ Subtitles → Quality
 * [audioFocusRequester]  is the entry point from the Play/Pause button (RIGHT key).
 * [onAudioNavigateLeft]  sends focus back to Play/Pause (LEFT key on Audio segment).
 */
@Composable
internal fun MasterCapsule(
    audioStreams: List<SimpleMediaStream>,
    selectedAudioIndex: Int?,
    onSelectAudio: (Int) -> Unit,
    subtitleStreams: List<SimpleMediaStream>,
    selectedSubtitleIndex: Int?,
    onSelectSubtitle: (Int) -> Unit,
    qualityFocusRequester: FocusRequester,
    audioFocusRequester: FocusRequester,
    subFocusRequester: FocusRequester = remember { FocusRequester() },
    onAudioNavigateLeft: () -> Unit,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val audioItems = remember(audioStreams) {
        audioStreams.map { Pair(it.index, StreamLabelEngine.audioLabel(it)) }
    }
    val subItems = remember(subtitleStreams) {
        buildList {
            add(Pair(TrackIndex.DISABLED, "Off"))
            subtitleStreams
                .filter { "forced" !in (it.streamTitle ?: it.displayTitle).lowercase() }
                .forEach { stream ->
                    val label = StreamLabelEngine.subtitleLabel(stream).let { l ->
                        when {
                            l.isNotBlank() && l != "Unknown" -> l
                            else -> stream.streamTitle?.takeIf { it.isNotBlank() }
                                ?: stream.displayTitle.takeIf { it.isNotBlank() }
                                ?: "Track ${stream.index + 1}"
                        }
                    }
                    add(Pair(stream.index, label))
                }
        }
    }

    val initialAudioIdx = audioItems.indexOfFirst { it.first == selectedAudioIndex }.coerceAtLeast(0)
    val initialSubIdx = when {
        selectedSubtitleIndex == null ||
            selectedSubtitleIndex == TrackIndex.DISABLED ||
            selectedSubtitleIndex == TrackIndex.UNSPECIFIED -> 0
        else -> subItems.indexOfFirst { it.first == selectedSubtitleIndex }.coerceAtLeast(0)
    }
    var audioScrollIdx by remember(initialAudioIdx) { mutableIntStateOf(initialAudioIdx) }
    var subScrollIdx   by remember(initialSubIdx)   { mutableIntStateOf(initialSubIdx) }

    // Modal roulette state — which segment's wheel is currently active
    var activeSegmentId by remember { mutableStateOf<String?>(null) }
    val anyActive = activeSegmentId != null

    // Sliding pill — tracks which segment has D-pad focus
    var focusedSegment by remember { mutableStateOf<String?>(null) }
    val hasAudio = audioItems.isNotEmpty()
    val hasSub   = subItems.size > 1

    val pillTargetX = if (focusedSegment == "sub" && hasAudio)
        CAPSULE_H_PAD + CAPSULE_SEGMENT_W + CAPSULE_DIV_TOTAL
    else
        CAPSULE_H_PAD
    val pillX by animateDpAsState(
        targetValue = pillTargetX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pillX",
    )
    val pillBgAlpha by animateFloatAsState(
        targetValue = when {
            focusedSegment == null  -> 0f
            activeSegmentId != null -> 0.50f
            else                    -> 0.28f
        },
        animationSpec = tween(260, easing = CAPSULE_EASING),
        label = "pillBgAlpha",
    )
    val pillScale by animateFloatAsState(
        targetValue = if (anyActive) 1.016f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pillScale",
    )
    val breathingTransition = rememberInfiniteTransition(label = "pillBreath")
    val breathOffset by breathingTransition.animateFloat(
        initialValue = -0.03f,
        targetValue = 0.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathOffset",
    )
    val effectivePillAlpha = (pillBgAlpha + if (anyActive) breathOffset else 0f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .wrapContentWidth()
            .height(CAPSULE_ITEM_H)
            .clip(RoundedCornerShape(50.dp))
            .background(CAPSULE_BG)
            .border(1.dp, CAPSULE_BORDER, RoundedCornerShape(50.dp)),
    ) {
        // Inner soft illumination — capsule feels lit from within
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        0.0f to Color.White.copy(alpha = 0.055f),
                        0.65f to Color.White.copy(alpha = 0.018f),
                        1.0f to Color.Transparent,
                    )
                )
        )
        // Top-edge inner sheen — subtle glass reflection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.13f), Color.Transparent)
                    )
                )
        )
        // Sliding active pill — drawn behind segments
        Box(
            modifier = Modifier
                .offset(x = pillX, y = PILL_V_PAD)
                .width(CAPSULE_SEGMENT_W)
                .height(CAPSULE_ITEM_H - PILL_V_PAD * 2)
                .graphicsLayer { scaleX = pillScale; scaleY = pillScale }
                .clip(RoundedCornerShape(50.dp))
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = minOf(effectivePillAlpha * 2.4f, 0.92f)),
                        0.10f to Color.White.copy(alpha = effectivePillAlpha * 1.5f),
                        1.00f to Color.White.copy(alpha = effectivePillAlpha),
                    ),
                ),
        )

        // Segments row — drawn over the pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .height(CAPSULE_ITEM_H)
                .padding(horizontal = CAPSULE_H_PAD),
        ) {
            if (hasAudio) {
                RouletteSegment(
                    header = "AUDIO",
                    items = audioItems,
                    scrollIndex = audioScrollIdx,
                    onScrollIndex = { audioScrollIdx = it },
                    focusRequester = audioFocusRequester,
                    onCenterPress = {
                        audioItems.getOrNull(audioScrollIdx)?.let { onSelectAudio(it.first) }
                    },
                    onActivate   = { activeSegmentId = "audio" },
                    onDeactivate = { activeSegmentId = null },
                    onLeft  = onAudioNavigateLeft,
                    onRight = { subFocusRequester.tryRequestFocus() },
                    interactive = interactive,
                    isActive = activeSegmentId == "audio",
                    hasActiveSegment = anyActive,
                    width = CAPSULE_SEGMENT_W,
                    onFocusChanged = { focused ->
                        if (focused) focusedSegment = "audio"
                        else if (focusedSegment == "audio") focusedSegment = null
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(CAPSULE_DIV),
                )
                Spacer(Modifier.width(8.dp))
            }

            if (hasSub) {
                RouletteSegment(
                    header = "SUBTITLES",
                    items = subItems,
                    scrollIndex = subScrollIdx,
                    onScrollIndex = { subScrollIdx = it },
                    focusRequester = subFocusRequester,
                    onCenterPress = {
                        subItems.getOrNull(subScrollIdx)?.let { onSelectSubtitle(it.first) }
                    },
                    onActivate   = { activeSegmentId = "sub" },
                    onDeactivate = { activeSegmentId = null },
                    onLeft  = { audioFocusRequester.tryRequestFocus() },
                    onRight = { qualityFocusRequester.tryRequestFocus() },
                    interactive = interactive,
                    isActive = activeSegmentId == "sub",
                    hasActiveSegment = anyActive,
                    width = CAPSULE_SEGMENT_W,
                    onFocusChanged = { focused ->
                        if (focused) focusedSegment = "sub"
                        else if (focusedSegment == "sub") focusedSegment = null
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BottomDialogItem + BottomDialog — kept for PlaybackDialog/NowPlayingPage compat
// ─────────────────────────────────────────────────────────────────────────────

data class BottomDialogItem<T>(val data: T, val headline: String, val supporting: String?, val enabled: Boolean = true)

@Composable
fun <T> BottomDialog(
    choices: List<BottomDialogItem<T>>,
    onDismissRequest: () -> Unit,
    onSelectChoice: (Int, BottomDialogItem<T>) -> Unit,
    gravity: Int,
    currentChoice: BottomDialogItem<T>? = null,
) {
    val focusRequesters = remember(choices.size) { List(choices.size) { FocusRequester() } }
    if (currentChoice != null) {
        LaunchedEffect(Unit) {
            choices.indexOfFirstOrNull { it == currentChoice }?.let { focusRequesters.getOrNull(it)?.tryRequestFocus() }
        }
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        dialogWindowProvider?.window?.let { window ->
            window.setGravity(Gravity.BOTTOM or gravity)
            window.setDimAmount(0f)
        }
        Box(modifier = Modifier.wrapContentSize().padding(8.dp).background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp), shape = RoundedCornerShape(8.dp))) {
            LazyColumn(modifier = Modifier.fillMaxWidth().wrapContentWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(choices) { index, choice ->
                    val interactionSource = remember { MutableInteractionSource() }
                    ListItem(
                        selected = choice == currentChoice, enabled = choice.enabled,
                        onClick = { onDismissRequest(); onSelectChoice(index, choice) },
                        leadingContent = { SelectedLeadingContent(choice == currentChoice) },
                        headlineContent = { Text(text = choice.headline) },
                        supportingContent = { choice.supporting?.let { Text(text = it) } },
                        interactionSource = interactionSource,
                        modifier = Modifier.focusRequester(focusRequesters[index]),
                    )
                }
            }
        }
    }
}