package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.ContentWarning
import com.github.jkrishna289.orcax.engine.ContentWarningsResponse

/**
 * A passive, non-interactive content advisory shown on the player surface for a few seconds at the start
 * of playback. It never enters the focus tree ([clearAndSetSemantics]) and has no controls — the engine
 * (Groq) supplies the ordered, spoiler-free advisories, and [PlaybackViewModel] drives visibility. The
 * caller supplies alignment/z-order via [modifier].
 */
@Composable
fun ContentWarningOverlay(
    warnings: ContentWarningsResponse?,
    modifier: Modifier = Modifier,
) {
    val visible = warnings != null && warnings.hasWarnings && warnings.warnings.isNotEmpty()

    // Retain the last shown payload so it stays rendered during the exit fade (warnings goes null on clear).
    var shown by remember { mutableStateOf<ContentWarningsResponse?>(null) }
    if (visible) {
        shown = warnings
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(500)),
        modifier = modifier,
    ) {
        shown?.let { ContentWarningCard(it, modifier = Modifier.clearAndSetSemantics {}) }
    }
}

@Composable
private fun ContentWarningCard(
    data: ContentWarningsResponse,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .padding(start = 28.dp, bottom = 40.dp)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xD9141414)),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.content_advisory),
                color = Color(0xFFF2F2F0),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (data.summary.isNotBlank()) {
                Text(
                    text = data.summary,
                    color = Color(0xFF9A9A96),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(
                modifier = Modifier.padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                data.warnings.take(6).forEach { ContentWarningRow(it) }
            }
        }

        // A thin bar that empties over the visible window — signals the card will disappear on its
        // own. Scaled in the draw phase (graphicsLayer) so the animation never recomposes/relayouts
        // — this runs concurrently with playback start on low-end devices.
        val progress = remember { Animatable(1f) }
        LaunchedEffect(Unit) { progress.animateTo(0f, tween(8000, easing = LinearEasing)) }
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer {
                        scaleX = progress.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }.background(Color(0xFFEF9F27)),
        )
    }
}

@Composable
private fun ContentWarningRow(warning: ContentWarning) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = warning.category,
            color = Color(0xFFF2F2F0),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 210.dp),
        )
        SeverityChip(warning.severity)
        if (warning.note.isNotBlank()) {
            Text(
                text = warning.note,
                color = Color(0xFFB9B9B4),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun SeverityChip(severity: String) {
    val (bg, fg) =
        when (severity.lowercase()) {
            "severe" -> Color(0x38E24B4A) to Color(0xFFF09595)
            "mild" -> Color(0x24FFFFFF) to Color(0xFFCFCFCA)
            else -> Color(0x38EF9F27) to Color(0xFFFAC775)
        }
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(text = severity.lowercase(), color = fg, fontSize = 12.sp)
    }
}
