package com.github.jkrishna289.orcax.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.ui.FontAwesome

/**
 * One of the two ways out of a title you don't own.
 *
 * Everywhere else in OrcaX a button retracts to an icon circle, because the viewer already knows what
 * Play does. Here they don't — so these stay expanded, carry the one line that tells them apart, and
 * are the only things on the row that can hold focus first.
 */
@Composable
fun DecisionCard(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    @StringRes iconStringRes: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(11.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.White.copy(alpha = 0.22f),
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
                pressedContainerColor = Color.White.copy(alpha = 0.22f),
                pressedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
            ),
        // These sit side by side and are already large; the default focus scale would shove the second
        // one off the row rather than making the choice clearer.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.widthIn(min = 210.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 11.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                iconStringRes?.let {
                    Text(text = stringResource(it), fontFamily = FontAwesome, fontSize = 15.sp)
                }
                Text(
                    text = stringResource(title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(subtitle),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = if (iconStringRes != null) 22.dp else 0.dp),
            )
        }
    }
}
