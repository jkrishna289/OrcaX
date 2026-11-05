package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.engine.EpisodeInfo
import com.github.jkrishna289.orcax.engine.EpisodeProgress
import com.github.jkrishna289.orcax.ui.detail.series.ResumeAt
import com.github.jkrishna289.orcax.ui.detail.series.fromTmdb
import com.github.jkrishna289.orcax.ui.detail.series.hasFile
import com.github.jkrishna289.orcax.ui.detail.series.notAiredYet
import com.github.jkrishna289.orcax.ui.detail.series.streamedResumeAt
import com.github.jkrishna289.orcax.ui.detail.series.toEpisodeItem
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Episodes of a season that isn't in the library, and where the series page picks a show up. Wrong
 * here opens the page on the wrong episode, or streams one that hasn't aired.
 */
class TestStreamedEpisodes {
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun `the page picks up mid-episode, else at the one after the last finished`() {
        assertNull(streamedResumeAt(emptyList()))
        assertEquals(
            ResumeAt(1, 4, after = false),
            streamedResumeAt(listOf(EpisodeProgress(1, 4, positionMs = 60_000, durationMs = 3_600_000), EpisodeProgress(1, 2, completed = true))),
        )
        assertEquals(ResumeAt(2, 17, after = true), streamedResumeAt(listOf(EpisodeProgress(2, 16, completed = true))))
    }

    @Test
    fun `a TMDB episode reads as not downloaded, with its guest stars and streamed progress`() {
        val ep =
            EpisodeInfo(1, 3, name = "Three", airDate = "2018-02-12", runtimeMinutes = 66, stillUrl = "https://img/s.jpg", guestStars = listOf("Ann"))
                .toEpisodeItem(76557, "Welcome to Waikiki", null, null, EpisodeProgress(1, 3, positionMs = 1_800_000, durationMs = 3_600_000))

        assertTrue(ep.fromTmdb)
        assertFalse(ep.hasFile)
        assertFalse(ep.notAiredYet(today))
        assertEquals(3, ep.indexNumber)
        assertEquals(1, ep.data.parentIndexNumber)
        assertEquals("https://img/s.jpg", ep.imageUrlOverride)
        assertEquals(listOf("Ann"), ep.data.people?.filter { it.type == PersonKind.GUEST_STAR }?.map { it.name })
        assertEquals(50.0, ep.data.userData?.playedPercentage ?: 0.0, 0.001)
        assertFalse(ep.data.userData?.played ?: true)
    }

    @Test
    fun `an episode without an air date, or with a future one, hasn't aired`() {
        val undated = EpisodeInfo(2, 14, airDate = null).toEpisodeItem(1, null, null, null, null)
        val future = EpisodeInfo(2, 15, airDate = "2027-01-01").toEpisodeItem(1, null, null, null, null)

        assertTrue(undated.notAiredYet(today))
        assertTrue(future.notAiredYet(today))
    }
}
