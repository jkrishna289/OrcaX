package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object PlaybackColors {
    val Purple             = Color(0xFF9C6BFF)
    val PurpleAccent       = Color(0xFF7B52F6)
    val Blue               = Color(0xFF3A86FF)
    val PurpleGlow         = Color(0x739C6BFF)
    val PurpleFaint        = Color(0x26156BFF)
    val White06            = Color(0x0FFFFFFF)
    val White09            = Color(0x17FFFFFF)
    val White12            = Color(0x1FFFFFFF)
    val White16            = Color(0x29FFFFFF)
    val White22            = Color(0x38FFFFFF)
    val White72            = Color(0xB8FFFFFF)
    val Scrim              = Color(0xB8000000)
    val SeekTrack          = Color(0x1AFFFFFF)
    val SeekBuffered       = Color(0x2EFFFFFF)
    val SeekProgressStart  = Color(0xFF9C6BFF)
    val SeekProgressEnd    = Color(0xFFC48FFF)
}

object PlaybackDimens {
    val ToolbarPaddingHorizontal = 34.dp
    val ToolbarPaddingBottom     = 34.dp
    val PlayBtnSize              = 52.dp
    val ChipHeight               = 48.dp
    val ChipRadius               = 999.dp
    val SeekBarHeight            = 3.dp
    val SeekThumbSize            = 13.dp
    val RouletteItemHeight       = 50.dp
    val RouletteChipGap          = 8.dp
    val RouletteMaxItemsVisible  = 2
    val ToastRadius              = 10.dp
    // Overscan-safe margins
    val SafeH                    = 58.dp
    val SafeV                    = 27.dp
}

object PlaybackMotion {
    val CinematicDecelerate = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val CinematicAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    val UiTransition   = tween<Float>(durationMillis = 440, easing = FastOutSlowInEasing)
    val ChipTransition = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
    val RouletteReveal = tween<Float>(durationMillis = 340, easing = FastOutSlowInEasing)

    const val HideDelayMs       = 5_000L
    const val AutoExpandDelayMs = 800L

    const val ToolbarInMs    = 280
    const val ToolbarOutMs   = 220
    const val Phase2InMs     = 420
    const val Phase2OutMs    = 320
    const val BitstreamInMs  = 800
    const val BitstreamOutMs = 600
    const val SubStateMs     = 500
}
