package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import com.github.jkrishna289.orcax.ui.seekBack
import com.github.jkrishna289.orcax.ui.seekForward
import kotlin.time.Duration

/**
 * Handles [KeyEvent]s during playback on [PlaybackPage]
 */
class PlaybackKeyHandler(
    private val player: Player,
    private val controlsEnabled: Boolean,
    private val skipWithLeftRight: Boolean,
    private val seekBack: Duration,
    private val seekForward: Duration,
    private val getDurationMs: () -> Long,
    private val controllerViewState: ControllerViewState,
    private val updateSkipIndicator: (Long) -> Unit,
    private val skipBackOnResume: Duration?,
    private val oneClickPause: Boolean,
    private val onInteraction: () -> Unit,
    private val onStop: () -> Unit,
    private val onPlaybackDialogTypeClick: (PlaybackDialogType) -> Unit,
    private val isOverlayActive: () -> Boolean = { false },
) {
    private var leftHandledByRepeat = false
    private var rightHandledByRepeat = false
    private val seenDownKeys = mutableSetOf<Key>()

    fun onKeyEvent(it: KeyEvent): Boolean {
        // This handler acts on KeyUp. Only act on a KeyUp whose KeyDown it also saw —
        // an orphan KeyUp means the press was consumed by an overlay that closed
        // mid-press (focus moved here before release); acting on it double-applies
        // the key (e.g. OK that resumed from Phase 2 immediately re-pausing).
        if (it.type == KeyEventType.KeyDown) {
            seenDownKeys.add(it.key)
        } else if (it.type == KeyEventType.KeyUp && !seenDownKeys.remove(it.key)) {
            return false
        }

        val isBareState = !controllerViewState.controlsVisible && !isOverlayActive()
        if (it.type == KeyEventType.KeyUp) {
            val isPauseFromBareState = isEnterKey(it) &&
                isBareState &&
                player.isPlaying
            // Bare-state skips get the skip indicator as feedback — they must not
            // summon the toolbar (same exemption as BACK).
            val isBareSkip = skipWithLeftRight &&
                (isSkipBack(it) || isSkipForward(it)) &&
                isBareState
            // BACK must not count as a generic interaction — otherwise it re-shows the
            // toolbar on every press and the dismiss → exit chain can never terminate.
            if (!isPauseFromBareState && !isBackKey(it) && !isBareSkip) {
                onInteraction.invoke()
            }
        }

        if (!controlsEnabled) {
            return false
        } else if (handleHoldSkip(it)) {
            return true
        } else if (it.type != KeyEventType.KeyUp) {
            return false
        }

        var result = true
        if (isDirectionalDpad(it) || isEnterKey(it) || isControllerMedia(it)) {
            if (isBareState) {
                if (skipWithLeftRight && isSkipBack(it)) {
                    updateSkipIndicator(-seekBack.inWholeMilliseconds)
                    player.seekBack(seekBack)
                } else if (skipWithLeftRight && isSkipForward(it)) {
                    player.seekForward(seekForward)
                    updateSkipIndicator(seekForward.inWholeMilliseconds)
                } else if (isEnterKey(it)) {
                    val wasPlaying = player.isPlaying
                    Util.handlePlayPauseButtonAction(player)
                    if (!wasPlaying) {
                        skipBackOnResume?.let {
                            player.seekBack(it)
                        }
                    }
                } else {
                    // no-op: the phase toolbar was already summoned via onInteraction.
                    // The legacy OSD flag must stay untouched during video playback —
                    // the phase system is the single OSD source of truth.
                }
            } else {
                // OSD is visible — D-pad drives UI focus navigation, not transport
                result = false
            }
        } else if (isMedia(it)) {
            when (it.key) {
                Key.MediaPlay, Key.MediaPause, Key.MediaPlayPause -> {
                    // no-op, MediaSession will handle
                }

                Key.MediaFastForward, Key.MediaSkipForward -> {
                    player.seekForward(seekForward)
                    updateSkipIndicator(seekForward.inWholeMilliseconds)
                }

                Key.MediaRewind, Key.MediaSkipBackward -> {
                    player.seekBack(seekBack)
                    updateSkipIndicator(-seekBack.inWholeMilliseconds)
                }

                Key.MediaNext -> {
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) player.seekToNext()
                }

                Key.MediaPrevious -> {
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) player.seekToPrevious()
                }

                Key.Captions -> {
                    onPlaybackDialogTypeClick.invoke(PlaybackDialogType.CAPTIONS)
                }

                Key.MediaAudioTrack -> {
                    onPlaybackDialogTypeClick.invoke(PlaybackDialogType.AUDIO)
                }

                Key.MediaStop -> {
                    onStop.invoke()
                }

                else -> {
                    result = false
                }
            }
        } else if (isBackKey(it)) {
            // BACK is owned by the phase-aware BackHandlers in PlaybackPage (dismiss
            // chrome first, then exit). Keep the legacy OSD in sync but never consume
            // the event, so it always reaches those handlers.
            if (controllerViewState.controlsVisible) controllerViewState.hideControls()
            result = false
        } else {
            controllerViewState.pulseControls()
            result = false
        }
        return result
    }

    private fun handleHoldSkip(event: KeyEvent): Boolean {
        if (
            controllerViewState.controlsVisible ||
            isOverlayActive() ||
            !skipWithLeftRight ||
            (!isSkipBack(event) && !isSkipForward(event))
        ) {
            return false
        }

        val isBack = isSkipBack(event)
        return when (event.type) {
            KeyEventType.KeyDown -> {
                val repeatCount = event.nativeKeyEvent.repeatCount
                if (repeatCount > 0) {
                    if (repeatCount < HOLD_TO_SEEK_REPEAT_START_COUNT) {
                        setHandledByRepeat(isBack = isBack, handled = false)
                        return true
                    }
                    val multiplier =
                        calculateSeekAccelerationMultiplier(
                            repeatCount = repeatCount - HOLD_TO_SEEK_REPEAT_START_COUNT,
                            durationMs = normalizedDurationMs(),
                        )
                    setHandledByRepeat(isBack = isBack, handled = true)
                    seekWithMultiplier(isBack = isBack, multiplier = multiplier)
                } else {
                    setHandledByRepeat(isBack = isBack, handled = false)
                }
                true
            }

            KeyEventType.KeyUp -> {
                if (!handledByRepeat(isBack = isBack)) {
                    seekWithMultiplier(isBack = isBack, multiplier = 1)
                }
                setHandledByRepeat(isBack = isBack, handled = false)
                true
            }

            else -> {
                false
            }
        }
    }

    private fun seekWithMultiplier(
        isBack: Boolean,
        multiplier: Int,
    ) {
        if (isBack) {
            val skipDuration = seekBack * multiplier
            player.seekBack(skipDuration)
            updateSkipIndicator(-skipDuration.inWholeMilliseconds)
        } else {
            val skipDuration = seekForward * multiplier
            player.seekForward(skipDuration)
            updateSkipIndicator(skipDuration.inWholeMilliseconds)
        }
    }

    private fun setHandledByRepeat(
        isBack: Boolean,
        handled: Boolean,
    ) {
        if (isBack) {
            leftHandledByRepeat = handled
        } else {
            rightHandledByRepeat = handled
        }
    }

    private fun handledByRepeat(isBack: Boolean): Boolean =
        if (isBack) {
            leftHandledByRepeat
        } else {
            rightHandledByRepeat
        }

    private fun normalizedDurationMs(): Long = getDurationMs().coerceAtLeast(0L)
}
