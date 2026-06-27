package com.github.jkrishna289.orcax.ui.playback

import android.content.Context
import androidx.compose.runtime.Immutable
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.util.StreamFormatting.mediaStreamDisplayTitle
import org.jellyfin.sdk.model.api.MediaStream

/**
 * A slim wrapper around Jellyfin's [MediaStream] carrying only what the UI needs.
 *
 * Key principle: prefer structured fields (language, codec, channelLayout, channels)
 * over parsing the human-readable title strings. StreamLabelEngine reads these fields
 * directly, falling back to title parsing only when they're null/blank.
 */
@Immutable
data class SimpleMediaStream(
    val index: Int,
    val streamTitle: String?,
    val displayTitle: String,
    /** ISO 639-2/B language code, e.g. "eng", "ita", "hin". Null if unknown. */
    val language: String?,
    /** Raw codec identifier from the container, e.g. "ac3", "eac3", "truehd", "aac". */
    val codec: String?,
    /** Channel layout string from the container, e.g. "5.1", "stereo", "7.1". */
    val channelLayout: String?,
    /** Channel count integer (e.g. 2, 6, 8). Null if unknown. */
    val channels: Int?,
) {
    companion object {
        fun from(
            context: Context,
            mediaStream: MediaStream,
            includeFlags: Boolean = true,
        ): SimpleMediaStream =
            SimpleMediaStream(
                index = mediaStream.index,
                streamTitle = mediaStream.title?.takeIf { it.isNotNullOrBlank() },
                displayTitle = mediaStreamDisplayTitle(context, mediaStream, includeFlags),
                language = mediaStream.language?.takeIf { it.isNotNullOrBlank() },
                codec = mediaStream.codec?.takeIf { it.isNotNullOrBlank() },
                channelLayout = mediaStream.channelLayout?.takeIf { it.isNotNullOrBlank() },
                channels = mediaStream.channels,
            )
    }
}

/**
 * Video stream metadata for the currently playing media.
 * Drives the dynamic quality tier menu — only tiers the source can actually
 * provide are shown (no "4K HDR" option for a 1080p SDR source, etc.).
 */
@Immutable
data class SimpleVideoStream(
    val index: Int,
    val hdr: Boolean,
    val is4k: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    /** Source bitrate in bps. 0 = unknown. */
    val bitrateBps: Int = 0,
    val codec: String? = null,
)