package com.github.jkrishna289.orcax.ui.main

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Procedural "key-art" used by the engine home when an item has no real backdrop/poster — the
 * sample/demo bundle, or any card whose image hasn't resolved. Mirrors the source prototype, whose
 * cards are pure CSS gradients derived from a single accent: a deep duotone base plus a soft accent
 * glow. Keeping this here means the billboard, the row cards, and the ambient wash all paint from
 * the same palette math, so a focused card and the screen behind it always agree on color.
 */
object EngineHomeArt {
    /** A sensible on-brand fallback (the app's lavender accent) when an item has no hint. */
    val DefaultAccent = Color(0xFFD2BCFF)

    /** Parses a `#rrggbb` / `#aarrggbb` hint into a [Color], falling back to [DefaultAccent]. */
    fun parseAccent(hint: String?): Color {
        if (hint.isNullOrBlank()) return DefaultAccent
        return runCatching { Color(android.graphics.Color.parseColor(hint.trim())) }
            .getOrDefault(DefaultAccent)
    }

    /** Duotone endpoints for a gradient: a mid tone and a near-black, both carrying the accent hue. */
    fun duotone(accent: Color): Pair<Color, Color> {
        val mid = lerp(accent, Color.Black, 0.58f)
        val deep = lerp(accent, Color(0xFF0E0C15), 0.88f)
        return mid to deep
    }

    /**
     * Ambient wash colors for [com.github.jkrishna289.orcax.services.BackdropService.submitColors],
     * shaped like the values the image extractor produces (pre-multiplied alpha baked in).
     */
    fun ambient(accent: Color): Triple<Color, Color, Color> {
        val primary = lerp(accent, Color.Black, 0.25f).copy(alpha = 0.42f)
        val secondary = lerp(accent, Color(0xFF3A2F55), 0.45f).copy(alpha = 0.40f)
        val tertiary = accent.copy(alpha = 0.34f)
        return Triple(primary, secondary, tertiary)
    }
}

/**
 * Paints a row-card sized procedural poster: a diagonal duotone base plus a corner accent glow.
 * Apply to a clipped Box that fills the card; content (scrims, title) draws on top.
 */
fun Modifier.engineCardArt(accent: Color): Modifier {
    val (c1, c2) = EngineHomeArt.duotone(accent)
    return this
        .background(Brush.linearGradient(listOf(c1, c2)))
        .background(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                center = Offset(0f, 0f),
                radius = 520f,
            ),
        )
}

/**
 * Paints the billboard hero's procedural art: a wide duotone wash with a brighter accent bloom
 * toward the upper area, echoing the prototype's layered radial/linear hero background.
 */
fun Modifier.engineHeroArt(accent: Color): Modifier {
    val (c1, c2) = EngineHomeArt.duotone(accent)
    return this
        .background(Brush.linearGradient(listOf(c2, c1, c2)))
        .background(
            Brush.radialGradient(
                colors = listOf(c1.copy(alpha = 0.9f), Color.Transparent),
                center = Offset(2200f, 360f),
                radius = 1700f,
            ),
        )
        .background(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(2600f, 120f),
                radius = 1300f,
            ),
        )
}
