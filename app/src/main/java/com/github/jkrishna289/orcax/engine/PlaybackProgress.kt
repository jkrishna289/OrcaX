package com.github.jkrishna289.orcax.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable identity shared by a streamed title and its eventual Jellyfin library copy. */
@Serializable
data class PlaybackIdentity(
    @SerialName("TmdbId") val tmdbId: Int,
    @SerialName("MediaType") val mediaType: String = "movie",
    @SerialName("Title") val title: String? = null,
    @SerialName("Year") val year: Int? = null,
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
)

@Serializable
data class ServerPlaybackProgress(
    @SerialName("SessionId") val sessionId: String = "",
    @SerialName("PositionMs") val positionMs: Long = 0,
    @SerialName("DurationMs") val durationMs: Long = 0,
    @SerialName("Completed") val completed: Boolean = false,
    @SerialName("JellyfinItemId") val jellyfinItemId: String? = null,
)

/** Sequence belongs to the user's playback session, never to the shared torrent session. */
@Serializable
data class PlaybackProgressBody(
    @SerialName("Sequence") val sequence: Long,
    @SerialName("PositionMs") val positionMs: Long,
    @SerialName("DurationMs") val durationMs: Long,
    @SerialName("Completed") val completed: Boolean = false,
    @SerialName("Stopped") val stopped: Boolean = false,
)

enum class ProgressReportResult { SAVED, RETRY, SUPERSEDED }
