package com.github.jkrishna289.orcax.ui.playback

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// "PURE FROST" TOOLBAR BUTTONS
//
// Design system: unfocused buttons are transparent "ghosts" (50% white content,
// no background). On D-pad focus they materialise into frosted glass (12% white
// fill), brighten to 100% white, go bold and scale to 1.05. When a sibling's
// dropdown is open every other button dims to 20% (spotlight on the active one).
// Every channel animates with tween(250, FastOutSlowIn) — never snaps. A tight
// drop-shadow keeps text legible over bright video even in the ghost state.
// ─────────────────────────────────────────────────────────────────────────────

private val FrostTween = tween<Float>(durationMillis = 250, easing = FastOutSlowInEasing)

private val FrostTextShadow =
    Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 2f), blurRadius = 4f)

private const val GHOST_ALPHA = 0.5f
private const val DIMMED_ALPHA = 0.2f
private const val GLASS_ALPHA = 0.12f

/**
 * Media transport button (seek / play-pause). Icon stacked over a short caption.
 *
 * @param mirrorIcon horizontally flips the icon (used to turn a fast-forward glyph
 *                   into a rewind glyph).
 * @param isDimmed   true while a sibling's dropdown is open → content drops to 20%.
 */
@Composable
fun FrostPlaybackButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDimmed: Boolean = false,
    mirrorIcon: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bgAlpha by animateFloatAsState(
        targetValue = if (focused) GLASS_ALPHA else 0f,
        animationSpec = FrostTween,
        label = "fpb_bg",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = when {
            isDimmed -> DIMMED_ALPHA
            focused -> 1f
            else -> GHOST_ALPHA
        },
        animationSpec = FrostTween,
        label = "fpb_content",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = FrostTween,
        label = "fpb_scale",
    )

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = bgAlpha))
            .defaultMinSize(minWidth = 102.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = Color.White.copy(alpha = contentAlpha),
            modifier = Modifier
                .size(20.dp)
                .then(if (mirrorIcon) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            color = Color.White.copy(alpha = contentAlpha),
            style = TextStyle(shadow = FrostTextShadow),
            maxLines = 1,
        )
    }
}

/**
 * Settings selector (Audio / Subtitles). Small header stacked over the current value.
 *
 * Opens its dropdown on Enter/OK when closed; while open ([isActive]) Enter is NOT
 * consumed so it bubbles up to PlaybackScreen.handlePhase1Key to confirm the choice.
 *
 * @param isActive true when this selector's dropdown is open → stays "lit".
 * @param isDimmed true when a *different* sibling's dropdown is open → content 20%.
 */
@Composable
fun FrostSettingButton(
    header: String,
    primary: String,
    isActive: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isDimmed: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lit = focused || isActive

    val bgAlpha by animateFloatAsState(
        targetValue = if (lit) GLASS_ALPHA else 0f,
        animationSpec = FrostTween,
        label = "fsb_bg",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = when {
            isDimmed -> DIMMED_ALPHA
            lit -> 1f
            else -> GHOST_ALPHA
        },
        animationSpec = FrostTween,
        label = "fsb_content",
    )
    val scale by animateFloatAsState(
        targetValue = if (lit) 1.05f else 1f,
        animationSpec = FrostTween,
        label = "fsb_scale",
    )

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = bgAlpha))
            .defaultMinSize(minWidth = 140.dp)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                        if (!isActive) {
                            onOpen()
                            true
                        } else {
                            false
                        }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = header,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = contentAlpha * 0.8f),
            style = TextStyle(shadow = FrostTextShadow),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = primary,
            fontSize = 13.sp,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
            color = Color.White.copy(alpha = contentAlpha),
            style = TextStyle(shadow = FrostTextShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}
