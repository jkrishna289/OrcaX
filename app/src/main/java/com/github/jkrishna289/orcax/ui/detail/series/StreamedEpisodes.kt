package com.github.jkrishna289.orcax.ui.detail.series

import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.engine.EpisodeInfo
import com.github.jkrishna289.orcax.engine.EpisodeProgress
import com.github.jkrishna289.orcax.engine.SeasonInfo
import com.github.jkrishna289.orcax.engine.TitleDetails
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.extensions.inWholeTicks
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Marks items made from the engine's TMDB lists: episodes of a season that isn't in the library, and
 * the seasons of a show that isn't. They look like Jellyfin's own missing episodes to the series page,
 * but there's nothing in Jellyfin to mark, favorite or delete.
 */
private const val FROM_TMDB = "orca-tmdb"

val BaseItem.fromTmdb: Boolean get() = data.serverId == FROM_TMDB

/** Nothing to play from the library: a missing episode, or one from TMDB. */
val BaseItem.hasFile: Boolean get() = data.locationType != LocationType.VIRTUAL && !data.mediaSources.isNullOrEmpty()

/** An episode that hasn't been shown yet. A TMDB episode with no air date counts as one. */
fun BaseItem.notAiredYet(today: LocalDate = LocalDate.now()): Boolean =
    data.premiereDate?.toLocalDate()?.isAfter(today) ?: fromTmdb

/** A TMDB episode, shaped like Jellyfin's missing episodes, with this user's streamed progress on it. */
fun EpisodeInfo.toEpisodeItem(
    tmdbId: Int,
    seriesName: String?,
    seriesId: UUID?,
    seasonId: UUID?,
    progress: EpisodeProgress?,
): BaseItem {
    val id = UUID.nameUUIDFromBytes("tmdb:$tmdbId:$seasonNumber:$episodeNumber".toByteArray())
    return BaseItem(
        data =
            BaseItemDto(
                id = id,
                type = BaseItemKind.EPISODE,
                serverId = FROM_TMDB,
                name = name,
                overview = overview,
                seriesName = seriesName,
                seriesId = seriesId,
                seasonId = seasonId,
                parentIndexNumber = seasonNumber,
                indexNumber = episodeNumber,
                premiereDate = airDate?.let { runCatching { LocalDate.parse(it).atStartOfDay() }.getOrNull() },
                runTimeTicks = runtimeMinutes?.minutes?.inWholeTicks,
                communityRating = communityRating?.toFloat(),
                locationType = LocationType.VIRTUAL,
                people =
                    guestStars.map {
                        BaseItemPerson(name = it, id = UUID.nameUUIDFromBytes(it.toByteArray()), type = PersonKind.GUEST_STAR)
                    },
                userData =
                    progress?.let {
                        UserItemDataDto(
                            playbackPositionTicks = it.positionMs.milliseconds.inWholeTicks,
                            playedPercentage = if (it.durationMs > 0) it.positionMs * 100.0 / it.durationMs else null,
                            playCount = if (it.completed) 1 else 0,
                            isFavorite = false,
                            played = it.completed,
                            key = id.toString(),
                            itemId = id,
                        )
                    },
            ),
        imageUrlOverride = stillUrl,
    )
}

/** A show that isn't in the library, from the engine's catalog facts, for the series page's header. */
fun tmdbSeriesItem(
    tmdbId: Int,
    title: String?,
    details: TitleDetails?,
    synopsis: String?,
): BaseItem =
    BaseItem(
        data =
            BaseItemDto(
                id = UUID.nameUUIDFromBytes("tmdb:$tmdbId".toByteArray()),
                type = BaseItemKind.SERIES,
                serverId = FROM_TMDB,
                name = title ?: details?.title,
                overview = details?.overview ?: synopsis,
                productionYear = details?.year,
                genres = details?.genres,
                officialRating = details?.officialRating,
                communityRating = details?.communityRating?.takeIf { it > 0 },
                providerIds = mapOf("Tmdb" to tmdbId.toString()),
            ),
    )

/** A TMDB season of a show that isn't in the library. */
fun SeasonInfo.toSeasonItem(tmdbId: Int): BaseItem =
    BaseItem(
        data =
            BaseItemDto(
                id = UUID.nameUUIDFromBytes("tmdb:$tmdbId:$seasonNumber".toByteArray()),
                type = BaseItemKind.SEASON,
                serverId = FROM_TMDB,
                name = name,
                indexNumber = seasonNumber,
                childCount = episodeCount,
            ),
    )

/**
 * Where to pick a show up from its streamed episodes: the last one touched if it was left partway,
 * else the one after it. [after] is set for the latter, so a finished season's last episode can roll
 * on to the next season's first.
 */
data class ResumeAt(
    val season: Int,
    val episode: Int,
    val after: Boolean,
)

fun streamedResumeAt(progress: List<EpisodeProgress>): ResumeAt? =
    progress.firstOrNull()?.let {
        if (it.completed) ResumeAt(it.season, it.episode + 1, after = true) else ResumeAt(it.season, it.episode, after = false)
    }
