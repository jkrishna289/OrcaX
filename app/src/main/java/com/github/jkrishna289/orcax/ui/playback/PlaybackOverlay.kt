package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Playlist
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.components.TimeDisplay
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.seekBack
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration

/**
 * Full-screen overlay composable for the playback screen.
 *
 * Visibility model:
 *   controlsVisible  — standard OSD: scrim, logo, clock, debug overlay
 *   showCapsule      — paused Phase 1: toolbar stays up after OSD auto-hides
 *
 * Bottom bar (seekbar + Play/Pause + MasterCapsule pill) is visible whenever
 * controlsVisible OR showCapsule is true.  When playing (controlsVisible only)
 * the roulette segments are view-only; when paused (showCapsule) they are
 * fully interactive so the user can switch audio/subtitle tracks.
 */
@Composable
fun PlaybackOverlay(
    item: BaseItem?,
    playerControls: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    showClock: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    seekEnabled: Boolean,
    seekBack: Duration,
    skipBackOnResume: Duration?,
    seekForward: Duration,
    onPlaybackActionClick: (PlaybackAction) -> Unit,
    onClickPlaybackDialogType: (PlaybackDialogType) -> Unit,
    onSeekBarChange: (Long) -> Unit,
    showDebugInfo: Boolean,
    currentPlayback: CurrentPlayback?,
    currentSegment: MediaSegmentDto?,
    modifier: Modifier = Modifier,
    trickplayInfo: TrickplayInfo? = null,
    trickplayUrlFor: (Int) -> String? = { null },
    playlist: Playlist = Playlist(listOf(), 0),
    onClickPlaylist: (BaseItem) -> Unit = {},
    seekBarInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    qualityFocusRequester: FocusRequester = remember { FocusRequester() },
    audioSubFocusRequester: FocusRequester = remember { FocusRequester() },
    qualityLabel: String = "AUTO",
    audioFlag: String = "—",
    subtitleLabel: String = "OFF",
    audioFullLabel: String = "",
    subtitleFullLabel: String = "",
    audioStreams: List<SimpleMediaStream> = emptyList(),
    subtitleStreams: List<SimpleMediaStream> = emptyList(),
    selectedAudioIndex: Int? = null,
    selectedSubtitleIndex: Int? = null,
    onSelectAudio: (Int) -> Unit = {},
    onSelectSubtitle: (Int) -> Unit = {},
    isSwitchingStream: Boolean = false,
    showCapsule: Boolean = true,
    endsAtString: String = "",
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        // ── Persistent cinematic bottom vignette — always behind controls ────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.52f),
                        )
                    )
                )
        )

        // ── Scrim: only while standard OSD is up ─────────────────────────────
        AnimatedVisibility(
            visible = controllerViewState.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.80f),
                            ),
                        ),
                    ),
            )
        }

        // ── Bottom bar: seekbar + [PP | Audio|Sub pill | Quality] ───────────────
        // PlaybackControls handles its own visibility internally — always in the
        // tree so animations can run, but only renders content when visible/capsule.
        PlaybackControls(
            playerControls = playerControls,
            controllerViewState = controllerViewState,
            onClickPlaybackDialogType = onClickPlaybackDialogType,
            onSeekProgress = onSeekBarChange,
            showPlay = showPlay,
            seekEnabled = seekEnabled,
            seekBack = seekBack,
            seekForward = seekForward,
            skipBackOnResume = skipBackOnResume,
            qualityLabel = qualityLabel,
            audioStreams = audioStreams,
            subtitleStreams = subtitleStreams,
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
            onSelectAudio = onSelectAudio,
            onSelectSubtitle = onSelectSubtitle,
            isSwitchingStream = isSwitchingStream,
            endsAtString = endsAtString,
            showCapsule = showCapsule,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Logo — top-left ───────────────────────────────────────────────────
        val logoImageUrl = LocalImageUrlService.current.rememberImageUrl(item, ImageType.LOGO)
        if (!showDebugInfo && logoImageUrl.isNotNullOrBlank() && controllerViewState.controlsVisible) {
            Box(modifier = Modifier.align(Alignment.TopStart)) {
                AsyncImage(
                    model = logoImageUrl,
                    contentDescription = "Logo",
                    alignment = Alignment.TopStart,
                    modifier = Modifier
                        .size(width = 240.dp, height = 120.dp)
                        .padding(16.dp),
                )
            }
        }

        // ── Clock — top-right ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = !showDebugInfo && showClock && controllerViewState.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            TimeDisplay()
        }

        // ── Debug overlay — top-left ──────────────────────────────────────────
        AnimatedVisibility(
            visible = showDebugInfo && controllerViewState.controlsVisible,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            PlaybackDebugOverlay(
                currentPlayback = currentPlayback,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(AppColors.TransparentBlack50),
            )
        }
    }
}
