package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.engine.ContentWarningsResponse
import kotlinx.coroutines.delay

// ── Spring specs — top-level so they are never re-allocated on recomposition ──

/** Snappy entrance — approximates cubic-bezier(0.22, 1, 0.36, 1) from the prototype. */
private val popSpec =
    spring<Float>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMedium,
    )

/** Critically-damped exit — clean, no bounce. */
private val collapseSpec =
    spring<Float>(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

// ── Rating helpers ─────────────────────────────────────────────────────────────

fun normalizeToAgeRating(raw: String): String =
    when (raw.uppercase().trim()) {
        "TV-Y", "G", "U", "0+", "ALL" -> "All"
        "TV-Y7", "TV-Y7-FV", "PG", "GB-PG" -> "7+"
        "TV-PG", "TV-14", "PG-13", "12", "12A", "GB-12", "GB-12A" -> "13+"
        "15", "GB-15" -> "16+"
        "TV-MA", "NC-17", "R", "18", "GB-18" -> "18+"
        else -> raw
    }

/** Thin left-border accent color, keyed to the normalized rating. */
private fun ratingAccentColor(normalized: String): Color =
    when (normalized) {
        "All" -> Color(0xFF3D6B7A)
        "7+" -> Color(0xFF4A6B55)
        "13+" -> Color(0xFF8A6B35)
        "16+" -> Color(0xFF8A5238)
        "18+" -> Color(0xFF7A2020)
        else -> Color(0xFF555760)
    }

/** Badge fill color. */
private fun ratingBadgeColor(normalized: String): Color =
    when (normalized) {
        "All" -> Color(0xFF5A8B9E)
        "7+" -> Color(0xFF6B9477)
        "13+" -> Color(0xFFC29B62)
        "16+" -> Color(0xFFC27A59)
        "18+" -> Color(0xFF923030)
        else -> Color(0xFF7A7D84)
    }

/**
 * A passive, non-interactive content advisory shown on the player surface for a few seconds at the start
 * of playback. It never enters the focus tree ([clearAndSetSemantics]) and has no controls — the engine
 * (Groq) supplies the ordered, spoiler-free advisories and [PlaybackViewModel] owns the visible window
 * (it publishes the payload, then clears it). The caller supplies alignment/z-order via [modifier].
 *
 * The reveal is staged in three beats — accent line, then age badge, then the descriptor list — and
 * collapses in reverse once the payload clears.
 */
@Composable
fun ContentWarningOverlay(
    warnings: ContentWarningsResponse?,
    rating: String,
    modifier: Modifier = Modifier,
) {
    val visible = warnings != null && warnings.hasWarnings && warnings.warnings.isNotEmpty()

    // Retain the last payload so it stays rendered during the exit beats (warnings goes null on clear).
    var shown by remember { mutableStateOf<ContentWarningsResponse?>(null) }
    if (visible) {
        shown = warnings
    }

    // One state per animation beat.
    var wrapVisible by remember { mutableStateOf(false) }
    var badgeVisible by remember { mutableStateOf(false) }
    var descVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            wrapVisible = true
            delay(150L)
            badgeVisible = true
            delay(350L)
            descVisible = true
        } else {
            descVisible = false
            delay(300L)
            badgeVisible = false
            delay(400L)
            wrapVisible = false
        }
    }

    val data = shown ?: return
    val displayRating = remember(rating) { normalizeToAgeRating(rating) }
    val warningText = remember(data) { data.warnings.take(4).joinToString(" · ") { it.category } }

    // ── Beat 1: the left accent line + container ──────────────────────────────
    AnimatedVisibility(
        visible = wrapVisible,
        enter = fadeIn(tween(500)) + slideInHorizontally(tween(500)) { -10 },
        exit = fadeOut(tween(500)) + slideOutHorizontally(tween(500)) { -10 },
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clearAndSetSemantics {}
                    .padding(start = 28.dp, bottom = 40.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(2.5.dp)
                        .height(34.dp)
                        .background(ratingAccentColor(displayRating)),
            )

            Spacer(modifier = Modifier.width(10.dp))

            // ── Beat 2: badge pops ────────────────────────────────────────────
            AnimatedVisibility(
                visible = badgeVisible,
                enter = scaleIn(popSpec, initialScale = 0.75f) + fadeIn(tween(600)),
                exit = scaleOut(collapseSpec, targetScale = 0.75f) + fadeOut(tween(300)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .height(26.dp)
                            .defaultMinSize(minWidth = 44.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ratingBadgeColor(displayRating))
                            .padding(horizontal = 11.dp),
                ) {
                    Text(
                        text = displayRating,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        softWrap = false, // prevents reflow → no layout pass during scale
                    )
                }
            }

            // ── Beat 3: descriptors pop ───────────────────────────────────────
            AnimatedVisibility(
                visible = descVisible,
                enter =
                    scaleIn(
                        animationSpec = popSpec,
                        initialScale = 0.82f,
                        transformOrigin = TransformOrigin(0f, 0.5f), // pivot: left-center
                    ) + slideInHorizontally(tween(350)) { -6 } + fadeIn(tween(350)),
                exit =
                    scaleOut(
                        animationSpec = collapseSpec,
                        targetScale = 0.82f,
                        transformOrigin = TransformOrigin(0f, 0.5f),
                    ) + slideOutHorizontally(tween(250)) { -6 } + fadeOut(tween(250)),
            ) {
                Text(
                    text = warningText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.6f),
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .padding(start = 10.dp)
                            .widthIn(max = 520.dp),
                )
            }
        }
    }
}
