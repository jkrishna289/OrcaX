package com.github.jkrishna289.orcax.ui.detail.music

import androidx.compose.runtime.Stable
import com.github.jkrishna289.orcax.services.BackdropResult
import com.github.jkrishna289.orcax.services.MusicServiceState
import org.jellyfin.sdk.model.api.LyricDto

@Stable
data class NowPlayingState(
    val musicServiceState: MusicServiceState,
    val lyrics: LyricDto? = null,
    val currentLyricIndex: Int? = null,
    val visualizerPermissions: Boolean = false,
    val backdropResult: BackdropResult = BackdropResult.NONE,
) {
    val hasLyrics: Boolean get() = lyrics != null && lyrics.lyrics.isNotEmpty()
}
