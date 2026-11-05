package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.engine.EpisodeInfo
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.PlaybackIdentity
import com.github.jkrishna289.orcax.engine.SkipSegments
import com.github.jkrishna289.orcax.engine.SkipTimes
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.detail.discover.pickerOrder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.extensions.inWholeTicks
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Gets a streamed show's next episode ready while the current one is still playing, so the player's
 * Next-episode card can start it at once rather than sending the viewer through a search.
 *
 * The torrent already playing is asked first: a season pack holds the next episode, and the same
 * torrent needs no search and is already connected to its peers. The engine refuses when that torrent
 * doesn't hold the episode, and only then is the next episode searched for, taking the engine's own
 * recommended source.
 */
class NextEpisodeStream(
    private val engine: OrcaEngineClient,
) {
    /** The next episode, with a stream that is ready to play. */
    data class Ready(
        val episode: EpisodeInfo,
        val identity: PlaybackIdentity,
        val sourceId: String,
        val stream: TorrentStream,
    )

    /** Null when there's no next episode yet, nothing to stream it from, or the engine can't say. */
    suspend fun prepare(
        playing: PlaybackIdentity,
        sourceId: String?,
        title: String,
        today: LocalDate = LocalDate.now(),
    ): Ready? {
        val next = nextEpisode(playing, today) ?: return null
        val identity = playing.copy(season = next.seasonNumber, episode = next.episodeNumber)
        if (!sourceId.isNullOrBlank()) {
            openReady(sourceId, next)?.let { return Ready(next, identity, sourceId, it) }
        }
        val found = engine.getSources(title = title, mediaType = MediaType.SERIES, season = next.seasonNumber, episode = next.episodeNumber)
        // A search for "Show S01E03" also finds shows with this one's name inside theirs ("Fortnite x
        // The Simpsons"). With nobody looking at the list, only a release named after this show will do.
        val pick =
            found?.recommended?.takeIf { namedAfter(it.title, title) }
                ?: found?.all.orEmpty().filter { namedAfter(it.title, title) }.sortedWith(pickerOrder).firstOrNull()
                ?: return null
        return openReady(pick.id, next)?.let { Ready(next, identity, pick.id, it) }
    }

    /** The episode after [playing]: the rest of its season, then the next season's first. Only aired ones. */
    internal suspend fun nextEpisode(
        playing: PlaybackIdentity,
        today: LocalDate,
    ): EpisodeInfo? {
        val season = playing.season ?: return null
        val episode = playing.episode ?: return null
        if (playing.mediaType != "tv") return null
        val next =
            engine.getEpisodes(playing.tmdbId, season).orEmpty().firstOrNull { it.episodeNumber > episode }
                // Specials don't run on into a season
                ?: if (season > 0) engine.getEpisodes(playing.tmdbId, season + 1).orEmpty().firstOrNull() else null
        val aired = next?.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return next?.takeIf { aired != null && !aired.isAfter(today) }
    }

    /**
     * Opens [sourceId] for [next] and waits for it to be playable. Nobody is watching this wait, so it
     * is bounded; a stream it gives up on, or that is cancelled with the player, is released.
     */
    private suspend fun openReady(
        sourceId: String,
        next: EpisodeInfo,
    ): TorrentStream? {
        val stream = engine.openStreamSession(sourceId = sourceId, season = next.seasonNumber, episode = next.episodeNumber) ?: return null
        var ready: TorrentStream? = null
        try {
            repeat(MAX_POLLS) {
                val status = engine.getStreamStatus(stream.token)
                if (status?.isFailed == true) return null
                if (status?.isReady == true) {
                    ready =
                        stream.copy(
                            fileName = status.fileName.ifBlank { stream.fileName },
                            sizeBytes = status.length.takeIf { it > 0 } ?: stream.sizeBytes,
                            mediaInfo = status.mediaInfo ?: stream.mediaInfo,
                        )
                    return ready
                }
                delay(POLL_MS)
            }
            return null
        } finally {
            if (ready == null) withContext(NonCancellable) { engine.releaseStreamSession(stream) }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L

        /** Three minutes: started this far from the end, a swarm slower than that won't make it anyway. */
        const val MAX_POLLS = 90
    }
}

/**
 * Whether a release name starts with the show's: "Welcome.to.Waikiki.S01E03" is Welcome to Waikiki,
 * "Greys.Anatomy" is Grey's Anatomy, "Fortnite x The Simpsons S01E01" isn't The Simpsons.
 */
internal fun namedAfter(
    release: String,
    show: String,
): Boolean {
    fun words(s: String) = s.lowercase().replace(Regex("['’]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val name = words(show)
    return name.isNotEmpty() && (words(release) + " ").startsWith("$name ")
}

/** How long before the end of a streamed episode the next one is opened. */
internal const val PREPARE_NEXT_STREAM_MS = 4 * 60_000L

/** The prepared episode as the player's Next card shows it, with TMDB's still as its picture. */
internal fun nextStreamItem(
    ready: NextEpisodeStream.Ready,
    seriesTitle: String,
): BaseItem =
    BaseItem(
        data =
            BaseItemDto(
                id = UUID.nameUUIDFromBytes(ready.identity.toString().toByteArray()),
                type = BaseItemKind.EPISODE,
                name = ready.episode.name,
                overview = ready.episode.overview,
                seriesName = seriesTitle,
                parentIndexNumber = ready.episode.seasonNumber,
                indexNumber = ready.episode.episodeNumber,
                runTimeTicks = ready.episode.runtimeMinutes?.minutes?.inWholeTicks,
            ),
        imageUrlOverride = ready.episode.stillUrl,
    )

/**
 * The engine's skip times as the player's segments, so a stream gets the same Skip button and
 * credits-time Next card as a library episode. [itemId] is the player's own id for the stream.
 */
internal fun SkipSegments.toMediaSegments(itemId: UUID): List<MediaSegmentDto> =
    listOfNotNull(
        recap?.segment(itemId, MediaSegmentType.RECAP),
        intro?.segment(itemId, MediaSegmentType.INTRO),
        outro?.segment(itemId, MediaSegmentType.OUTRO),
    )

private fun SkipTimes.segment(
    itemId: UUID,
    type: MediaSegmentType,
) = MediaSegmentDto(
    id = UUID.nameUUIDFromBytes("$itemId:$type".toByteArray()),
    itemId = itemId,
    type = type,
    startTicks = startMs.milliseconds.inWholeTicks,
    // ponytail: "to the end of the file" as a day past the start; the player clamps a seek past the end
    endTicks = (endMs?.milliseconds ?: (startMs.milliseconds + 24.hours)).inWholeTicks,
)
