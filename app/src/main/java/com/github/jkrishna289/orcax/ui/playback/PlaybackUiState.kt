package com.github.jkrishna289.orcax.ui.playback

import androidx.compose.runtime.Immutable

// ── Phase enum ───────────────────────────────────────────────────────────────

enum class PlaybackPhase {
    ACTIVE,          // Phase 1: content playing, toolbar may be visible
    PAUSED_OVERLAY   // Phase 2: paused cinematic overlay
}

enum class ToolbarVisibility { HIDDEN, VISIBLE }

// Phase 2 sub-state. FULL = the cinematic browse overlay; COMPACT = the minimal
// scrubber reached by pressing BACK (structural — seek bar becomes the sole focusable).
// Idle dimming is tracked separately (isIdleDimmed) and stays within FULL's focus model.
enum class Phase2SubState { FULL, COMPACT }

// ── Track selection ───────────────────────────────────────────────────────────

enum class RouletteType { AUDIO, SUBTITLE }

@Immutable
data class RouletteItem(val title: String, val meta: String)

// Phase 1 dropdown state (replaces roulette for Phase 1)
@Immutable
data class DropdownState(
    val type: RouletteType,
    val focusedIndex: Int
)

// ── Bitstream toast ───────────────────────────────────────────────────────────

sealed class BitstreamToastState {
    object Hidden : BitstreamToastState()
    data class Visible(val audioFormat: String, val videoFormat: String) : BitstreamToastState()
}

// ── Up Next ───────────────────────────────────────────────────────────────────

@Immutable
data class UpNextItem(
    // Playlist identity so OK on a card can actually play it; null = not playable.
    val itemId: java.util.UUID? = null,
    val episodeNumber: Int,
    val title: String,
    val durationLabel: String,
    val thumbnailUrl: String,
    val isCurrentlyWatching: Boolean = false,
    // 0f..1f watched fraction for the currently-watching card; null = no bar.
    val progressFraction: Float? = null
)

// ── Phase 2 COMPACT ambient view ────────────────────────────────────────────────

@Immutable
data class AmbientPerson(
    val name: String,
    val role: String,
    val imageUrl: String?
)

// All the "ambient" metadata shown in the COMPACT slideshow. Built directly from the
// playback item (which is loaded full-detail), so no extra fetch is required.
@Immutable
data class AmbientInfo(
    val backdropUrl: String = "",
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val cast: List<AmbientPerson> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val communityRating: Float? = null,
    val tagline: String = ""
)

// ── Root UI state ─────────────────────────────────────────────────────────────

@Immutable
data class PlaybackUiState(
    // Phase
    val phase: PlaybackPhase = PlaybackPhase.ACTIVE,
    val toolbarVisibility: ToolbarVisibility = ToolbarVisibility.HIDDEN,
    val phase2SubState: Phase2SubState = Phase2SubState.FULL,

    // Playback
    val isPlaying: Boolean = false,
    val seekPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,

    // Content metadata
    val showTitle: String = "",
    val episodeName: String = "",
    val season: Int = 1,
    val episode: Int = 1,
    val synopsis: String = "",
    val artworkUrl: String = "",
    val logoUrl: String = "",
    val ambient: AmbientInfo = AmbientInfo(),
    val year: String = "",
    val rating: String = "",

    // Phase 1 dropdown
    val dropdown: DropdownState? = null,

    // Track data
    val audioItems: List<RouletteItem> = emptyList(),
    val subtitleItems: List<RouletteItem> = emptyList(),
    val audioConfirmedIndex: Int = 0,
    val subtitleConfirmedIndex: Int = 0,

    // Bitstream toast
    val bitstreamToast: BitstreamToastState = BitstreamToastState.Hidden,

    // Phase 2 up-next
    val upNextItems: List<UpNextItem> = emptyList(),

    // Phase 2 roulette picker
    val p2PickerType: RouletteType? = null,
    val p2PickerFocusIndex: Int = 0
) {
    // Single definition of "an overlay owns focus / chrome is showing". Every
    // consumer (focus hand-back, key routing, chrome gating) must read this
    // instead of re-deriving it, so a new phase or overlay state can't drift
    // the sites apart.
    val overlayActive: Boolean
        get() = phase == PlaybackPhase.PAUSED_OVERLAY || toolbarVisibility == ToolbarVisibility.VISIBLE
}
