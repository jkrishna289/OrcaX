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
    data class ChangeQuality(val selection: com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection) : PlaybackAction
}

val buttonSpacing = 12.dp

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