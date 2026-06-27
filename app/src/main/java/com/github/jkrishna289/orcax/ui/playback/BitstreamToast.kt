package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BitstreamToast(state: BitstreamToastState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible  = state is BitstreamToastState.Visible,
        enter    = fadeIn(tween(PlaybackMotion.BitstreamInMs, easing = PlaybackMotion.CinematicDecelerate)) +
                   slideInVertically(tween(PlaybackMotion.BitstreamInMs, easing = PlaybackMotion.CinematicDecelerate)) { -it / 3 },
        exit     = fadeOut(tween(PlaybackMotion.BitstreamOutMs, easing = PlaybackMotion.CinematicAccelerate)) +
                   slideOutVertically(tween(PlaybackMotion.BitstreamOutMs, easing = PlaybackMotion.CinematicAccelerate)) { -it / 4 },
        modifier = modifier
    ) {
        if (state is BitstreamToastState.Visible) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(PlaybackDimens.ToastRadius))
                    .background(Color(0xFF08080E).copy(alpha = 0.78f))
                    .drawBehind {
                        drawRect(
                            color   = PlaybackColors.Purple.copy(alpha = 0.55f),
                            topLeft = Offset.Zero,
                            size    = Size(4f, this.size.height)
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(PlaybackDimens.ToastRadius))
                    .padding(start = 18.dp, end = 22.dp, top = 14.dp, bottom = 14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.audioFormat.isNotEmpty()) BitstreamRow(state.audioFormat)
                    if (state.videoFormat.isNotEmpty()) BitstreamRow(state.videoFormat)
                }
            }
        }
    }
}

@Composable
private fun BitstreamRow(label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(5.dp).background(PlaybackColors.Purple.copy(alpha = 0.75f), CircleShape))
        androidx.compose.material3.Text(
            text          = label.uppercase(),
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = (0.13f * 11).sp,
            color         = Color.White.copy(alpha = 0.82f),
            maxLines      = 1
        )
    }
}
