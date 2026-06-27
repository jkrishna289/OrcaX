package com.github.jkrishna289.orcax.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaybackPhaseViewModel : ViewModel() {

    companion object {
        const val TOOLBAR_HIDE_MS    = 5_000L
        const val PAUSE_TO_P2_MS     = 5_000L
        const val PHASE2_IDLE_MS     = 12_000L
        const val MENU_TO_COMPACT_MS = 59_000L
        const val BITSTREAM_DELAY_MS = 300L
        const val BITSTREAM_HOLD_MS  = 2_800L
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var toolbarHideJob: Job? = null
    private var pauseIdleJob: Job? = null
    private var idleCompactJob: Job? = null
    private var menuIdleJob: Job? = null
    private var bitstreamJob: Job? = null
    private var bitstreamShownForCurrentContent = false

    // ── State updaters called by PlaybackScreen / PlaybackPage ────────────────

    fun updateProgress(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        _uiState.update {
            it.copy(
                seekPositionMs     = positionMs,
                durationMs         = durationMs,
                bufferedPositionMs = bufferedMs
            )
        }
    }

    fun updatePlayingState(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
    }

    fun updateMetadata(
        showTitle: String,
        episodeName: String = "",
        season: Int,
        episode: Int,
        synopsis: String = "",
        artworkUrl: String = "",
        logoUrl: String = "",
        year: String = "",
        rating: String = ""
    ) {
        _uiState.update {
            it.copy(
                showTitle   = showTitle,
                episodeName = episodeName,
                season      = season,
                episode     = episode,
                synopsis    = synopsis,
                artworkUrl  = artworkUrl,
                logoUrl     = logoUrl,
                year        = year,
                rating      = rating
            )
        }
    }

    fun updateTracks(
        audioItems: List<RouletteItem>,
        subtitleItems: List<RouletteItem>,
        audioConfirmedIndex: Int = _uiState.value.audioConfirmedIndex,
        subtitleConfirmedIndex: Int = _uiState.value.subtitleConfirmedIndex
    ) {
        _uiState.update {
            it.copy(
                audioItems             = audioItems,
                subtitleItems          = subtitleItems,
                audioConfirmedIndex    = audioConfirmedIndex,
                subtitleConfirmedIndex = subtitleConfirmedIndex
            )
        }
    }

    fun updateUpNext(items: List<UpNextItem>) {
        _uiState.update { it.copy(upNextItems = items) }
    }

    // ── Phase 1: toolbar ──────────────────────────────────────────────────────

    fun onUserInteraction() {
        when (_uiState.value.phase) {
            PlaybackPhase.ACTIVE         -> {
                showToolbar()
                rescheduleToolbarHide()
                // While paused, active toolbar use keeps deferring the Phase 2 overlay
                // (Phase 2 only starts once the user has finished with the buttons).
                if (!_uiState.value.isPlaying) reschedulePauseIdle()
            }
            PlaybackPhase.PAUSED_OVERLAY -> onPhase2Interaction()
        }
    }

    fun showToolbar() {
        _uiState.update { it.copy(toolbarVisibility = ToolbarVisibility.VISIBLE) }
    }

    fun hideToolbar() {
        _uiState.update { it.copy(toolbarVisibility = ToolbarVisibility.HIDDEN) }
        if (_uiState.value.dropdown != null) closeDropdown()
    }

    fun rescheduleToolbarHide() {
        toolbarHideJob?.cancel()
        if (_uiState.value.dropdown == null) {
            toolbarHideJob = viewModelScope.launch {
                delay(TOOLBAR_HIDE_MS)
                hideToolbar()
            }
        }
    }

    // ── Phase transitions ─────────────────────────────────────────────────────

    fun onVideoPlayingChanged(isPlaying: Boolean) {
        val wasPlaying = _uiState.value.isPlaying
        _uiState.update { it.copy(isPlaying = isPlaying) }

        if (!isPlaying && wasPlaying) {
            reschedulePauseIdle()
        } else if (isPlaying && !wasPlaying) {
            pauseIdleJob?.cancel()
            menuIdleJob?.cancel()
            if (_uiState.value.phase != PlaybackPhase.ACTIVE) {
                transitionToPhase1Active()
            }
        }
    }

    /**
     * (Re)start the paused → Phase 2 countdown. Called on pause and on every Phase 1
     * toolbar interaction while paused, so the overlay only appears once the user is
     * genuinely idle. No-op unless we are paused in [PlaybackPhase.ACTIVE] with no
     * dropdown open (an open menu holds Phase 2 back via [menuIdleJob] instead).
     */
    private fun reschedulePauseIdle() {
        pauseIdleJob?.cancel()
        val s = _uiState.value
        if (s.isPlaying || s.phase != PlaybackPhase.ACTIVE || s.dropdown != null) return
        pauseIdleJob = viewModelScope.launch {
            delay(PAUSE_TO_P2_MS)
            val cur = _uiState.value
            if (!cur.isPlaying && cur.phase == PlaybackPhase.ACTIVE && cur.dropdown == null) {
                transitionToPhase2()
            }
        }
    }

    fun onPlayPauseToggle() {
        if (!_uiState.value.isPlaying && _uiState.value.phase == PlaybackPhase.PAUSED_OVERLAY) {
            dismissPhase2()
        }
        if (_uiState.value.isPlaying) {
            pauseIdleJob?.cancel()
        }
    }

    fun onUpFromPhase1() {
        if (_uiState.value.phase == PlaybackPhase.ACTIVE) transitionToPhase2()
    }

    fun dismissPhase2() {
        pauseIdleJob?.cancel()
        idleCompactJob?.cancel()
        // Leave Phase 2 to the bare paused/playing frame (no toolbar). One more BACK exits.
        _uiState.update {
            it.copy(
                phase             = PlaybackPhase.ACTIVE,
                toolbarVisibility = ToolbarVisibility.HIDDEN,
                phase2SubState    = Phase2SubState.FULL
            )
        }
    }

    private fun transitionToPhase2() {
        toolbarHideJob?.cancel()
        pauseIdleJob?.cancel()
        _uiState.update {
            it.copy(
                phase             = PlaybackPhase.PAUSED_OVERLAY,
                toolbarVisibility = ToolbarVisibility.HIDDEN,
                phase2SubState    = Phase2SubState.FULL
            )
        }
        rescheduleIdleCompact()
    }

    // ── Phase 2 FULL ↔ COMPACT (ambient). Reached by BACK or by idle. ─────────

    /** Idle timer in FULL → drop into the ambient COMPACT view (same as BACK). */
    private fun rescheduleIdleCompact() {
        idleCompactJob?.cancel()
        idleCompactJob = viewModelScope.launch {
            delay(PHASE2_IDLE_MS)
            compactPhase2()
        }
    }

    /** BACK (or idle) from FULL → ambient COMPACT (seek bar is an inert focus anchor). */
    fun compactPhase2() {
        if (_uiState.value.phase == PlaybackPhase.PAUSED_OVERLAY &&
            _uiState.value.phase2SubState == Phase2SubState.FULL
        ) {
            idleCompactJob?.cancel()
            _uiState.update { it.copy(phase2SubState = Phase2SubState.COMPACT) }
        }
    }

    /** UP from COMPACT → back to FULL. */
    fun expandPhase2() {
        if (_uiState.value.phase == PlaybackPhase.PAUSED_OVERLAY &&
            _uiState.value.phase2SubState == Phase2SubState.COMPACT
        ) {
            _uiState.update { it.copy(phase2SubState = Phase2SubState.FULL) }
            rescheduleIdleCompact()
        }
    }

    private fun transitionToPhase1Active() {
        toolbarHideJob?.cancel()
        _uiState.update {
            it.copy(
                phase             = PlaybackPhase.ACTIVE,
                toolbarVisibility = ToolbarVisibility.HIDDEN
            )
        }
    }

    fun onPhase2Interaction() {
        // Any interaction while FULL restarts the idle→compact timer.
        // While COMPACT, do nothing (only UP leaves COMPACT).
        if (_uiState.value.phase2SubState == Phase2SubState.FULL) {
            rescheduleIdleCompact()
        }
    }

    fun updateAmbient(info: AmbientInfo) {
        _uiState.update { it.copy(ambient = info) }
    }

    // ── Bitstream toast ───────────────────────────────────────────────────────

    fun onPlaybackStarted(audioFormat: String, videoFormat: String) {
        if (bitstreamShownForCurrentContent) return
        bitstreamShownForCurrentContent = true
        bitstreamJob?.cancel()
        bitstreamJob = viewModelScope.launch {
            delay(BITSTREAM_DELAY_MS)
            _uiState.update {
                it.copy(bitstreamToast = BitstreamToastState.Visible(audioFormat, videoFormat))
            }
            delay(BITSTREAM_HOLD_MS)
            _uiState.update { it.copy(bitstreamToast = BitstreamToastState.Hidden) }
        }
    }

    fun resetBitstreamFlag() {
        bitstreamShownForCurrentContent = false
    }

    // ── Seek ──────────────────────────────────────────────────────────────────

    fun onSeekTo(positionMs: Long) {
        _uiState.update { it.copy(seekPositionMs = positionMs) }
        onUserInteraction()
    }

    // ── Phase 1 dropdown ──────────────────────────────────────────────────────

    fun openDropdown(type: RouletteType) {
        val confirmedIdx = when (type) {
            RouletteType.AUDIO    -> _uiState.value.audioConfirmedIndex
            RouletteType.SUBTITLE -> _uiState.value.subtitleConfirmedIndex
        }
        _uiState.update { it.copy(dropdown = DropdownState(type, confirmedIdx)) }
        toolbarHideJob?.cancel()
        // An open audio/subtitle menu holds Phase 2 back entirely while paused; if it
        // lingers past MENU_TO_COMPACT_MS, drop straight into the COMPACT ambient view.
        pauseIdleJob?.cancel()
        if (!_uiState.value.isPlaying) {
            menuIdleJob?.cancel()
            menuIdleJob = viewModelScope.launch {
                delay(MENU_TO_COMPACT_MS)
                val cur = _uiState.value
                if (!cur.isPlaying && cur.dropdown != null) {
                    _uiState.update { it.copy(dropdown = null) }
                    transitionToPhase2()  // PAUSED_OVERLAY + FULL
                    compactPhase2()       // → COMPACT
                }
            }
        }
    }

    fun closeDropdown() {
        menuIdleJob?.cancel()
        _uiState.update { it.copy(dropdown = null) }
        rescheduleToolbarHide()
        // Menu dismissed while still paused → resume the paused → Phase 2 countdown.
        if (!_uiState.value.isPlaying) reschedulePauseIdle()
    }

    fun navigateDropdown(delta: Int) {
        val state = _uiState.value
        val dd = state.dropdown ?: return
        val items = when (dd.type) {
            RouletteType.AUDIO    -> state.audioItems
            RouletteType.SUBTITLE -> state.subtitleItems
        }
        val next = (dd.focusedIndex + delta).coerceIn(0, items.lastIndex)
        _uiState.update { it.copy(dropdown = dd.copy(focusedIndex = next)) }
    }

    fun confirmDropdown(): Pair<RouletteType, Int>? {
        val state = _uiState.value
        val dd = state.dropdown ?: return null
        _uiState.update {
            when (dd.type) {
                RouletteType.AUDIO    -> it.copy(audioConfirmedIndex = dd.focusedIndex, dropdown = null)
                RouletteType.SUBTITLE -> it.copy(subtitleConfirmedIndex = dd.focusedIndex, dropdown = null)
            }
        }
        menuIdleJob?.cancel()
        rescheduleToolbarHide()
        if (!_uiState.value.isPlaying) reschedulePauseIdle()
        return Pair(dd.type, dd.focusedIndex)
    }

    // ── Phase 2 roulette picker ───────────────────────────────────────────────

    fun openP2Picker(type: RouletteType) {
        val confirmedIdx = when (type) {
            RouletteType.AUDIO    -> _uiState.value.audioConfirmedIndex
            RouletteType.SUBTITLE -> _uiState.value.subtitleConfirmedIndex
        }
        _uiState.update { it.copy(p2PickerType = type, p2PickerFocusIndex = confirmedIdx) }
    }

    fun closeP2Picker() {
        _uiState.update { it.copy(p2PickerType = null) }
    }

    fun setP2PickerIndex(index: Int) {
        _uiState.update { it.copy(p2PickerFocusIndex = index) }
    }

    fun confirmP2Picker(): Pair<RouletteType, Int>? {
        val state = _uiState.value
        val type = state.p2PickerType ?: return null
        val index = state.p2PickerFocusIndex
        _uiState.update {
            when (type) {
                RouletteType.AUDIO    -> it.copy(audioConfirmedIndex = index, p2PickerType = null)
                RouletteType.SUBTITLE -> it.copy(subtitleConfirmedIndex = index, p2PickerType = null)
            }
        }
        return Pair(type, index)
    }

    override fun onCleared() {
        super.onCleared()
        listOf(toolbarHideJob, pauseIdleJob, idleCompactJob, menuIdleJob, bitstreamJob).forEach { it?.cancel() }
    }
}
