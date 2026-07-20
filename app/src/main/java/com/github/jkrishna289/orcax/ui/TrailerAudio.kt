package com.github.jkrishna289.orcax.ui

import androidx.compose.runtime.compositionLocalOf
import com.github.jkrishna289.orcax.preferences.TrailerPreviewVolume

/**
 * The single source of truth for inline-trailer preview audio (Phase 13 of the trailer redesign).
 *
 * Every inline trailer player — the home [com.github.jkrishna289.orcax.ui.main.Billboard] and the
 * 16:9 [com.github.jkrishna289.orcax.ui.cards.InlineCardTrailer] cards — reads its volume from the
 * [LocalTrailerVolume] composition local, which is fed from the user's [TrailerPreviewVolume]
 * preference. Centralising it here means one setting drives all current (and future) trailer
 * players, applied live without recreating the player or restarting the app.
 */

/**
 * The historical inline-trailer volume (quiet, not muted — per the user's long-standing preference).
 * Used both as the composition-local fallback and as the resolved value for the unset/unknown enum,
 * so installs that never touch the new setting behave exactly as before.
 */
const val DEFAULT_TRAILER_PREVIEW_VOLUME = 0.20f

/** Maps the persisted [TrailerPreviewVolume] preference to a 0f..1f ExoPlayer volume. */
fun TrailerPreviewVolume.toTrailerVolume(): Float =
    when (this) {
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_MUTE -> 0f
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_VERY_LOW -> 0.10f
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_LOW -> 0.20f
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_MEDIUM -> 0.35f
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_HIGH -> 0.50f
        TrailerPreviewVolume.TRAILER_PREVIEW_VOLUME_MAX -> 1.0f
        // UNSPECIFIED (never set) / UNRECOGNIZED (forward-compat) -> the quiet default.
        else -> DEFAULT_TRAILER_PREVIEW_VOLUME
    }

/**
 * Current inline-trailer preview volume (0f..1f). Provided once near the home root from the user's
 * preference; trailer players consume it via `LocalTrailerVolume.current`. Defaults to the quiet
 * historical level so previews outside the home (e.g. the detail "More Like This" row) still read a
 * sensible value.
 */
val LocalTrailerVolume = compositionLocalOf { DEFAULT_TRAILER_PREVIEW_VOLUME }

/**
 * The user's preferred trailer audio language. The persisted preference is an ISO 639-1 code;
 * empty = auto, which resolves to English-preferred everywhere ("use English audio whenever it's
 * available"): the leased players set it as their preferred audio track language, and the engine
 * client forwards it so the server picks a matching-language trailer when TMDB offers one.
 */
object TrailerLanguages {
    /** Selectable codes, index-aligned with the `trailer_language_options` string-array ("" = auto). */
    val CODES = listOf("", "en", "hi", "ta", "te", "ml", "kn", "es", "fr", "ja", "ko")

    /** Resolves the persisted code to the effective preferred audio language. */
    fun effective(code: String?): String = code?.takeIf { it.isNotBlank() } ?: "en"
}

/** The persisted trailer-language code ("" = auto/English-preferred), provided near the home root. */
val LocalTrailerLanguage = compositionLocalOf { "" }
