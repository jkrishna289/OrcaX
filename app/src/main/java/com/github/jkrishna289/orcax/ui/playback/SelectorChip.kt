package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Audio / Subtitle selector chip for the Phase 1 toolbar.
 *
 * Opens a dropdown on Enter/OK or click. While the dropdown is open (isActive),
 * Enter is NOT consumed so it bubbles up to PlaybackScreen.handlePhase1Key for confirm.
 */
@Composable
fun SelectorChip(
    prefix: String,
    label: String,
    isActive: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    val bgAlpha by animateFloatAsState(
        targetValue   = when {
            isActive -> 0.22f
            focused  -> 0.15f
            else     -> 0.08f
        },
        animationSpec = tween(180),
        label = "chip_bg"
    )
    val borderAlpha by animateFloatAsState(
        targetValue   = if (focused || isActive) 0.22f else 0.06f,
        animationSpec = tween(180),
        label = "chip_border"
    )
    val labelScale by animateFloatAsState(
        targetValue   = if (focused || isActive) 1.04f else 1f,
        animationSpec = spring(0.72f, 500f),
        label = "chip_scale"
    )

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = PlaybackDimens.ChipHeight)
                .graphicsLayer { scaleX = labelScale; scaleY = labelScale }
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = bgAlpha))
                .border(1.dp, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(8.dp))
                .onFocusChanged { state -> focused = state.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (!isActive) {
                                // Closed → open the dropdown (consume the key)
                                onOpen()
                                true
                            } else {
                                // Open → let it bubble to handlePhase1Key for confirm
                                false
                            }
                        }
                        else -> false
                    }
                }
                .focusable()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text          = prefix,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = (0.12f * 10).sp,
                color         = Color.White.copy(alpha = if (focused || isActive) 0.65f else 0.45f),
                maxLines      = 1,
                softWrap      = false,
                overflow      = TextOverflow.Ellipsis
            )
            Text(
                text          = label,
                fontSize      = 13.sp,
                fontWeight    = FontWeight.SemiBold,
                color         = Color.White,
                maxLines      = 1,
                softWrap      = false,
                overflow      = TextOverflow.Ellipsis,
                modifier      = Modifier.widthIn(max = 110.dp)
            )
        }
    }
}
