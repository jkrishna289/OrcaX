package com.github.jkrishna289.orcax.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.github.jkrishna289.orcax.preferences.PlaybackPreferences
import com.github.jkrishna289.orcax.preferences.skipBackOnResume
import com.github.jkrishna289.orcax.ui.seekBack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import timber.log.Timber

@OptIn(UnstableApi::class)
class MediaSessionPlayer(
    player: Player,
    private val playbackPreferences: PlaybackPreferences,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.v("handleSetPlayWhenReady: playWhenReady=$playWhenReady")
        if (playWhenReady) {
            playbackPreferences.skipBackOnResume?.let {
                player.seekBack(it)
            }
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    // The playlist lives in the ViewModel, so the wrapped player holds one item and would report no
    // next/previous. Advertise both so session controllers (Bluetooth remotes, system media UI) send
    // them here, and route them to the ViewModel.
    override fun getState(): State {
        val state = super.getState()
        return state
            .buildUpon()
            .setAvailableCommands(
                state.availableCommands
                    .buildUpon()
                    .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build(),
            ).build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                onNext()
                Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                onPrevious()
                Futures.immediateVoidFuture()
            }

            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
}
