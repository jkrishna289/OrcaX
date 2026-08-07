package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.engine.StreamMediaInfo

/**
 * An open torrent stream on the server, ready to play.
 *
 * @property url absolute URL the player fetches; already carries the session's capability token.
 * @property token session identifier, used to close the session server-side.
 * @property fileName the video file the engine picked out of the torrent.
 * @property sizeBytes total size, or null when the engine didn't report one.
 * @property mediaInfo probed tracks, or null when ffprobe couldn't read the partial file — in which
 *   case the player discovers tracks itself and the track menus still populate.
 */
data class TorrentStream(
    val url: String,
    val token: String,
    val fileName: String,
    val sizeBytes: Long?,
    val mediaInfo: StreamMediaInfo? = null,
)
