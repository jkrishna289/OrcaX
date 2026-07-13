package com.github.jkrishna289.orcax.ui.playback.quality

import android.content.Context
import android.view.Display
import androidx.compose.runtime.Immutable
import com.github.jkrishna289.orcax.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Display capabilities of the attached panel. */
@Immutable
data class TvCapabilities(val maxWidth: Int, val maxHeight: Int, val supportsHdr: Boolean)

/** Best supported display mode + HDR support. Falls back to 1080p SDR. */
fun probeTvCapabilities(context: Context): TvCapabilities = try {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
    val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
    val modes = display?.supportedModes ?: emptyArray()
    val maxMode = modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
    val hdrCaps = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
    TvCapabilities(
        maxWidth = maxMode?.physicalWidth ?: 1920,
        maxHeight = maxMode?.physicalHeight ?: 1080,
        supportsHdr = hdrCaps.isNotEmpty(),
    )
} catch (t: Throwable) {
    TvCapabilities(1920, 1080, false)
}

/**
 * Device capability source for the quality engine: display caps (moved here
 * from QualitySelectionDialog) + audio codec knowledge derived from the user's
 * AppPreference audio overrides.
 *
 * Audio knowledge is explicit both ways (see DeviceQualityCaps): codecs in
 * neither set are UNKNOWN — they don't block Original direct play, but they do
 * block stream-copy while transcoding.
 */
@Singleton
class DeviceAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val tvCapabilities: TvCapabilities by lazy { probeTvCapabilities(context) }

    fun audioCaps(prefs: AppPreferences): QualityResolver.DeviceQualityCaps {
        val ac3Supported = prefs.playbackPreferences.overrides.ac3Supported
        val supported = buildSet {
            addAll(SOFTWARE_DECODABLE)
            if (ac3Supported) {
                add("ac3")
                add("eac3")
            }
        }
        val unsupported = if (!ac3Supported) setOf("ac3", "eac3") else emptySet()
        return QualityResolver.DeviceQualityCaps(
            knownSupportedAudioCodecs = supported,
            knownUnsupportedAudioCodecs = unsupported,
        )
    }

    companion object {
        /** Codecs media3 decodes in software on any device. */
        private val SOFTWARE_DECODABLE = setOf("aac", "mp3", "mp2", "flac", "vorbis", "opus")
    }
}
