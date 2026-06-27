package com.github.jkrishna289.orcax.ui.playback

import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.preferences.PlayerBackend
import com.github.jkrishna289.orcax.util.TrackSupport
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodingInfo
import kotlin.time.Duration

/**
 * Information about how the current media is being played such transcoding and decoder info
 *
 * @see CurrentMediaInfo
 */
data class CurrentPlayback(
    val item: BaseItem,
    val tracks: List<TrackSupport>,
    val backend: PlayerBackend,
    val playMethod: PlayMethod,
    val playSessionId: String?,
    val liveStreamId: String?,
    val mediaSourceInfo: MediaSourceInfo,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val transcodeInfo: TranscodingInfo? = null,
    val subtitleDelay: Duration = Duration.ZERO,
)
