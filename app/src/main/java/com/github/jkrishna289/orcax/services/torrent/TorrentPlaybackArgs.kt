package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.engine.StreamMediaInfo
import kotlinx.serialization.Serializable

/**
 * Everything the player needs to play a torrent stream, carried on the navigation destination.
 *
 * Its presence is what tells [com.github.jkrishna289.orcax.ui.playback.PlaybackViewModel] to skip
 * the Jellyfin lookups: there is no library item behind this, so `getItem` and `getPostedPlaybackInfo`
 * would both fail.
 *
 * @property url absolute stream URL, including the session's capability token.
 * @property title shown in the player UI.
 * @property fileName the video file the engine chose inside the torrent.
 * @property sizeBytes total size when known.
 * @property mediaInfo probed tracks, or null when the probe couldn't run — the player then discovers
 *   tracks itself while parsing the container.
 */
@Serializable
data class TorrentPlaybackArgs(
    val url: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long? = null,
    val mediaInfo: StreamMediaInfo? = null,
    /**
     * The engine session this stream belongs to, so the player can read live swarm health.
     *
     * Carried explicitly rather than parsed back out of [url]: the URL's shape is the engine's to
     * change, and a stream that silently stopped reporting its health because a path changed is
     * exactly the kind of quiet breakage that took a day to find last time.
     */
    val token: String = "",
)
