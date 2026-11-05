package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.engine.EpisodeInfo
import com.github.jkrishna289.orcax.engine.PlaybackIdentity
import com.github.jkrishna289.orcax.engine.SkipSegments
import com.github.jkrishna289.orcax.engine.SkipTimes
import com.github.jkrishna289.orcax.engine.SourceGroups
import com.github.jkrishna289.orcax.engine.StreamSessionStatus
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.NextEpisodeStream
import com.github.jkrishna289.orcax.services.torrent.TorrentStream
import com.github.jkrishna289.orcax.services.torrent.namedAfter
import com.github.jkrishna289.orcax.services.torrent.toMediaSegments
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Getting a streamed show's next episode ready before the current one ends. Wrong here plays the
 * wrong episode, or an episode that hasn't aired, as "next".
 */
class TestNextEpisodeStream {
    private val engine = mockk<OrcaEngineClient>(relaxed = true)
    private val today = LocalDate.of(2026, 11, 5)
    private val playing = PlaybackIdentity(tmdbId = 76557, mediaType = "tv", title = "Waikiki", season = 1, episode = 2)

    private fun ep(
        s: Int,
        e: Int,
        aired: String? = "2018-02-01",
    ) = EpisodeInfo(seasonNumber = s, episodeNumber = e, name = "S$s E$e", airDate = aired)

    private fun stream(token: String) = TorrentStream(url = "http://server/$token", token = token, fileName = "", sizeBytes = null)

    @Test
    fun `the next episode is the rest of the season, then the next season, and only aired ones`() =
        runBlocking {
            coEvery { engine.getEpisodes(76557, 1) } returns listOf(ep(1, 1), ep(1, 2), ep(1, 3))
            coEvery { engine.getEpisodes(76557, 2) } returns listOf(ep(2, 1), ep(2, 2, aired = "2027-01-01"))
            val next = NextEpisodeStream(engine)

            assertEquals(ep(1, 3), next.nextEpisode(playing, today))
            assertEquals(ep(2, 1), next.nextEpisode(playing.copy(episode = 3), today))
            assertNull(next.nextEpisode(playing.copy(season = 2, episode = 1), today)) // not aired yet
            assertNull(next.nextEpisode(playing.copy(season = 2, episode = 2), today)) // season 3 doesn't exist
            coEvery { engine.getEpisodes(76557, 0) } returns listOf(ep(0, 1))
            assertNull(next.nextEpisode(playing.copy(season = 0, episode = 1), today)) // specials don't run on into Season 1
        }

    @Test
    fun `the torrent already playing is asked first, with no search`() =
        runBlocking {
            coEvery { engine.getEpisodes(76557, 1) } returns listOf(ep(1, 2), ep(1, 3))
            coEvery { engine.openStreamSession("pack", null, 1, 3) } returns stream("t1")
            coEvery { engine.getStreamStatus("t1") } returns StreamSessionStatus(state = "Ready", fileName = "Waikiki.S01E03.mkv")

            val ready = NextEpisodeStream(engine).prepare(playing, sourceId = "pack", title = "Waikiki", today = today)!!

            assertEquals("pack", ready.sourceId)
            assertEquals(3, ready.identity.episode)
            assertEquals("Waikiki.S01E03.mkv", ready.stream.fileName)
            coVerify(exactly = 0) { engine.getSources(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a torrent without the next episode is let go, and the next episode is searched for`() =
        runBlocking {
            coEvery { engine.getEpisodes(76557, 1) } returns listOf(ep(1, 2), ep(1, 3))
            coEvery { engine.openStreamSession("single", null, 1, 3) } returns stream("t1")
            coEvery { engine.getStreamStatus("t1") } returns StreamSessionStatus(state = "Failed")
            coEvery { engine.getSources("Waikiki", null, any(), 1, 3, any()) } returns
                TorrentSourceDto(id = "e3", title = "Waikiki.S01E03.1080p.WEB").let { SourceGroups(recommended = it, all = listOf(it)) }
            coEvery { engine.openStreamSession("e3", null, 1, 3) } returns stream("t2")
            coEvery { engine.getStreamStatus("t2") } returns StreamSessionStatus(state = "Ready")

            val ready = NextEpisodeStream(engine).prepare(playing, sourceId = "single", title = "Waikiki", today = today)!!

            assertEquals("e3", ready.sourceId)
            assertEquals("t2", ready.stream.token)
            coVerify { engine.releaseStreamSession(match { it.token == "t1" }) }
        }

    @Test
    fun `with nobody choosing, a release from another show is never picked`() =
        runBlocking {
            coEvery { engine.getEpisodes(76557, 1) } returns listOf(ep(1, 2), ep(1, 3))
            val other = TorrentSourceDto(id = "fortnite", title = "Fortnite x The Simpsons S01E03 1080p", resolutionHeight = 2160, seeders = 99)
            val ours = TorrentSourceDto(id = "ours", title = "The.Simpsons.S01E03.720p.WEB", resolutionHeight = 720, seeders = 5)
            coEvery { engine.getSources("The Simpsons", null, any(), 1, 3, any()) } returns SourceGroups(recommended = other, all = listOf(other, ours))
            coEvery { engine.openStreamSession("ours", null, 1, 3) } returns stream("t3")
            coEvery { engine.getStreamStatus("t3") } returns StreamSessionStatus(state = "Ready")

            val ready = NextEpisodeStream(engine).prepare(playing, sourceId = null, title = "The Simpsons", today = today)!!

            assertEquals("ours", ready.sourceId)
            assert(namedAfter("Greys.Anatomy.S20E01.1080p", "Grey's Anatomy"))
            assert(namedAfter("Welcome.to.Waikiki.S01E03", "Welcome to Waikiki"))
            assert(!namedAfter("The Simpsons Movie 2007", "The Simpsons Movie 2"))
        }

    @Test
    fun `skip times become player segments, an open end running past the file`() {
        val id = UUID.randomUUID()
        val segments = SkipSegments(intro = SkipTimes(12_000, 71_500), outro = SkipTimes(2_700_000, null)).toMediaSegments(id)

        assertEquals(listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO), segments.map { it.type })
        assertEquals(120_000_000L to 715_000_000L, segments[0].startTicks to segments[0].endTicks)
        assertEquals(id, segments[1].itemId)
        assert(segments[1].endTicks > 2_700_000L * 10_000)
    }
}
