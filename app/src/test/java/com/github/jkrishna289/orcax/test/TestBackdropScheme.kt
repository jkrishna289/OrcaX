package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.services.colorScheme
import com.github.jkrishna289.orcax.services.hslToRgb
import com.github.jkrishna289.orcax.services.rgbToHsl
import com.github.jkrishna289.orcax.ui.cards.EpisodeLabel
import com.github.jkrishna289.orcax.ui.cards.episodeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TestBackdropScheme {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `the most present colour leads and near-duplicates are skipped`() {
        val sky = rgb(3, 183, 203)
        val skyAgain = rgb(10, 178, 200) // same hue as the sky, one bucket over
        val road = rgb(87, 74, 66)
        val scheme = colorScheme(listOf(skyAgain to 900, sky to 1000, road to 400))

        assertEquals(3, scheme.size)
        assertEquals(rgbToHsl(sky)[0], rgbToHsl(scheme[0])[0], 1f) // the sky first, hue kept
        assertTrue(abs(rgbToHsl(scheme[1])[0] - rgbToHsl(road)[0]) < 2f) // the road next, not the sky again
    }

    @Test
    fun `hue and saturation stay, only lightness moves into range`() {
        val nearBlackRed = rgb(40, 8, 8)
        val (h, s, l) = rgbToHsl(colorScheme(listOf(nearBlackRed to 1)).first())
        val (h0, s0, _) = rgbToHsl(nearBlackRed)

        assertEquals(h0, h, 1f)
        assertEquals(s0, s, .03f)
        assertEquals(.3f, l, .01f)
    }

    @Test
    fun `a grey poster gives a grey wash, not an invented colour`() {
        val scheme = colorScheme(listOf(rgb(100, 100, 100) to 5, rgb(60, 60, 60) to 3))
        scheme.forEach { assertEquals(0f, rgbToHsl(it)[1], .01f) }
    }

    @Test
    fun `a one-colour poster stays one colour and black or white art gives nothing`() {
        val one = colorScheme(listOf(rgb(200, 40, 40) to 10))
        assertEquals(3, one.size)
        assertEquals(1, one.toSet().size)
        assertTrue(colorScheme(listOf(rgb(0, 0, 0) to 10, rgb(255, 255, 255) to 10)).isEmpty())
    }

    @Test
    fun `hsl round-trips`() {
        val c = rgb(46, 102, 158)
        val (h, s, l) = rgbToHsl(c)
        assertEquals(c, hslToRgb(h, s, l))
    }

    @Test
    fun `an episode subtitle splits into season, episode and a real name`() {
        assertEquals(EpisodeLabel(1, 3, null), episodeLabel("S1:E3 · Episode 3"))
        assertEquals(EpisodeLabel(2, 10, "Journey Into Mystery"), episodeLabel("S2:E10 · Journey Into Mystery"))
        assertEquals(EpisodeLabel(1, 4, null), episodeLabel("S1:E4"))
        assertNull(episodeLabel("Because you like Ethan Hawke"))
        assertNull(episodeLabel(null))
    }
}
