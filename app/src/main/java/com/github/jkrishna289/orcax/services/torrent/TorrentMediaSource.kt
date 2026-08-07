package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.engine.StreamMediaInfo
import com.github.jkrishna289.orcax.engine.StreamTrack
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.UUID

/**
 * Turns an Orca Engine torrent stream URL into the shapes the existing playback pipeline already
 * knows how to handle: a [MediaSourceInfo] and a [PlaybackInfoResponse].
 *
 * This is the whole integration seam. The player, track selection, seeking and the playback UI are
 * untouched — a torrent stream arrives as `isRemote = true` with a `path`, which is exactly how
 * Jellyfin represents a source it doesn't manage itself (the same shape `.strm` entries use), and
 * [com.github.jkrishna289.orcax.ui.playback.PlaybackViewModel] already has a branch that plays that
 * path directly.
 *
 * Deliberately the ONLY place a synthetic Jellyfin model is built. If a third source type ever shows
 * up (direct HTTP links, debrid), this is the one file to replace with a neutral PlaybackSource
 * abstraction — until then a sealed hierarchy over one adapter would buy nothing.
 */
object TorrentMediaSource {
    /**
     * Builds the synthetic source for a torrent stream.
     *
     * When [mediaInfo] is null the stream list stays empty and Media3 discovers the tracks itself
     * while parsing the container — `TrackSelectionUtils` reads `player.currentTracks`, so the track
     * menus still populate. A probed [mediaInfo] is strictly better: the tracks are known before the
     * first frame, with languages and titles the container carries but the player doesn't surface.
     */
    fun mediaSource(
        streamUrl: String,
        fileName: String,
        sizeBytes: Long?,
        sourceId: UUID,
        mediaInfo: StreamMediaInfo? = null,
    ): MediaSourceInfo =
        MediaSourceInfo(
            id = sourceId.toString(),
            runTimeTicks = mediaInfo?.runTimeTicks,
            bitrate = mediaInfo?.bitrate,
            // The URL the player fetches. Paired with isRemote below, this is what the existing
            // direct-play branch hands straight to Media3.
            path = streamUrl,
            name = fileName,
            size = sizeBytes,
            container = fileName.substringAfterLast('.', "mkv"),
            protocol = MediaProtocol.HTTP,
            type = MediaSourceType.DEFAULT,
            isRemote = true,
            supportsDirectPlay = true,
            // The engine serves the file bytes as-is. It cannot remux or transcode, so claiming
            // either would let the pipeline build a URL that answers 404.
            supportsDirectStream = false,
            supportsTranscoding = false,
            supportsProbing = false,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            mediaStreams = mediaInfo?.streams?.mapNotNull(::toMediaStream).orEmpty(),
            mediaAttachments = emptyList(),
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            // Finite and seekable: the engine reports a real Content-Length and honours Range, even
            // though the bytes are still arriving. Marking it infinite would disable the seek bar.
            isInfiniteStream = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            hasSegments = false,
        )

    /**
     * Maps one probed track onto Jellyfin's [MediaStream].
     *
     * All tracks are `isExternal = false`: they live inside the container. That matters more than it
     * looks — Jellyfin orders EXTERNAL subtitles first in its stream indices, and the app's index
     * mapping depends on that convention, so mislabelling an embedded track would shift every
     * subtitle selection by one.
     *
     * Returns null for track kinds the player has no use for (attachments, data).
     */
    private fun toMediaStream(track: StreamTrack): MediaStream? {
        val type =
            when (track.type) {
                "Video" -> MediaStreamType.VIDEO
                "Audio" -> MediaStreamType.AUDIO
                "Subtitle" -> MediaStreamType.SUBTITLE
                else -> return null
            }

        // HDR is identified by the transfer function, not by bit depth: "Main 10" is just 10-bit and
        // plenty of SDR releases use it, so anything else here would misreport SDR content as HDR.
        val (videoRange, videoRangeType) =
            when (track.colorTransfer?.lowercase()) {
                "smpte2084" -> VideoRange.HDR to VideoRangeType.HDR10
                "arib-std-b67" -> VideoRange.HDR to VideoRangeType.HLG
                null -> VideoRange.UNKNOWN to VideoRangeType.UNKNOWN
                else -> VideoRange.SDR to VideoRangeType.SDR
            }

        return MediaStream(
            index = track.index,
            type = type,
            codec = track.codec,
            language = track.language,
            title = track.title,
            isDefault = track.isDefault,
            isForced = track.isForced,
            isExternal = false,
            width = track.width,
            height = track.height,
            channels = track.channels,
            channelLayout = track.channelLayout,
            bitRate = track.bitRate,
            profile = track.profile,
            videoRange = if (type == MediaStreamType.VIDEO) videoRange else VideoRange.UNKNOWN,
            videoRangeType = if (type == MediaStreamType.VIDEO) videoRangeType else VideoRangeType.UNKNOWN,
            // Text subtitles can be rendered by the player; image formats (PGS/VOBSUB) cannot be
            // turned into text, and the subtitle UI keys off this to decide what it can offer.
            isTextSubtitleStream = type == MediaStreamType.SUBTITLE && track.codec in TEXT_SUBTITLE_CODECS,
            supportsExternalStream = false,
            isInterlaced = false,
            isHearingImpaired = false,
        )
    }

    private val TEXT_SUBTITLE_CODECS =
        setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text")

    /**
     * A stand-in for Jellyfin's `getPostedPlaybackInfo` response. The real call would 404 — the
     * server has no item for this content — so the pipeline is handed the answer directly instead.
     */
    fun playbackInfo(
        streamUrl: String,
        fileName: String,
        sizeBytes: Long?,
        sourceId: UUID,
        mediaInfo: StreamMediaInfo? = null,
    ): PlaybackInfoResponse =
        PlaybackInfoResponse(
            mediaSources = listOf(mediaSource(streamUrl, fileName, sizeBytes, sourceId, mediaInfo)),
            // No Jellyfin play session exists, so there is nothing to report progress against.
            // Callers must treat a null session id as "don't report" — see PlaybackViewModel.
            playSessionId = null,
            errorCode = null,
        )

    /**
     * A minimal [BaseItem] for a torrent stream, so the pipeline has something to carry a title and
     * the source through. Not a library item and never persisted: [itemId] is generated per session,
     * which is also what keeps it out of the resume/next-up tables that are keyed on real ids.
     */
    fun baseItem(
        title: String,
        streamUrl: String,
        fileName: String,
        sizeBytes: Long?,
        itemId: UUID = UUID.randomUUID(),
        mediaInfo: StreamMediaInfo? = null,
    ): BaseItem =
        BaseItem(
            data =
                BaseItemDto(
                    id = itemId,
                    type = BaseItemKind.MOVIE,
                    mediaType = MediaType.VIDEO,
                    name = title,
                    runTimeTicks = mediaInfo?.runTimeTicks,
                    mediaSources = listOf(mediaSource(streamUrl, fileName, sizeBytes, itemId, mediaInfo)),
                ),
        )
}
