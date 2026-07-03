package com.github.jkrishna289.orcax.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.ui.components.FocusTrapOverlay

/**
 * A small focusable overlay shown when a home-row card is long-pressed: explicit thumbs up/down that
 * feed the engine's personalization (`Behavior/Feedback`). Reuses [BillboardButton] for a consistent
 * focus treatment; Back (or Close) dismisses. [onFeedback] receives `true` for thumbs-up. Focus is
 * trapped inside the menu while open and handed back to [restoreFocusTo] on dismissal.
 */
@Composable
fun CardFeedbackMenu(
    item: RenderItem,
    onFeedback: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: FocusRequester? = null,
) {
    val likeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { likeFocus.requestFocus() } }

    FocusTrapOverlay(
        onDismiss = onDismiss,
        restoreFocusTo = restoreFocusTo,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier =
                Modifier
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF15121C))
                    .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            item.card.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.feedback_tune_recommendations),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BillboardButton(
                    label = stringResource(R.string.feedback_more_like_this),
                    primary = true,
                    onClick = { onFeedback(true) },
                    modifier = Modifier.focusRequester(likeFocus),
                )
                BillboardButton(
                    label = stringResource(R.string.feedback_not_interested),
                    primary = false,
                    onClick = { onFeedback(false) },
                )
                BillboardButton(label = stringResource(R.string.close), primary = false, onClick = onDismiss)
            }
        }
    }
}
