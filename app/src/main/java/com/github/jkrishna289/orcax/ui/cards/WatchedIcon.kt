package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.github.jkrishna289.orcax.preferences.AppThemeColors
import com.github.jkrishna289.orcax.ui.PreviewTvSpec
import com.github.jkrishna289.orcax.ui.theme.LocalTheme
import com.github.jkrishna289.orcax.ui.theme.OrcaTheme

@Composable
fun WatchedIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = WatchedIconColor(),
        modifier =
            modifier
                .background(WatchedIconBackground(), shape = CircleShape)
                .border(.5.dp, Color.Black, CircleShape)
                .padding(2.dp),
    )
}

@Composable
fun WatchedIconBackground(): Color =
    when (LocalTheme.current) {
        AppThemeColors.UNRECOGNIZED,
        AppThemeColors.PURPLE,
        AppThemeColors.BLUE,
        AppThemeColors.GREEN,
        AppThemeColors.ORANGE,
        AppThemeColors.BOLD_BLUE,
        -> MaterialTheme.colorScheme.border.copy(alpha = 1f)

        AppThemeColors.OLED_BLACK -> MaterialTheme.colorScheme.secondaryContainer
    }

@Composable
fun WatchedIconColor(): Color =
    when (LocalTheme.current) {
        AppThemeColors.UNRECOGNIZED,
        AppThemeColors.PURPLE,
        AppThemeColors.BLUE,
        AppThemeColors.GREEN,
        AppThemeColors.ORANGE,
        AppThemeColors.BOLD_BLUE,
        AppThemeColors.OLED_BLACK,
        -> Color.White // MaterialTheme.colorScheme.onSurface
    }

@PreviewTvSpec
@Composable
private fun WatchedIconPreview() {
    OrcaTheme {
        WatchedIcon(Modifier.size(64.dp))
    }
}
