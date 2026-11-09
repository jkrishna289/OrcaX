package com.github.jkrishna289.orcax.services

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.BackdropStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores state for the backdrop of the app shown on non-full screen pages
 *
 * This is usually the backdrop of the currently focused media
 */
@Singleton
@OptIn(FlowPreview::class)
class BackdropService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val imageUrlService: ImageUrlService,
        private val trailerService: TrailerService,
        private val preferences: DataStore<AppPreferences>,
    ) {
        private val extractedColorCache = LruCache<String, ExtractedColors>(50)

        private val _backdropFlow = MutableStateFlow<BackdropResult>(BackdropResult.NONE)
        val backdropFlow = _backdropFlow

        /**
         * Update the backdrop to use the specified item.
         *
         * [hero] marks this as the movie-details hero backdrop: the backdrop renders as the framed
         * Movie Details v2 hero instead of the default top-right inset, and the item's local
         * trailer stream is resolved so it can crossfade into an ambient trailer preview. Opt-in
         * (movie details only) because [submit] is also called per-focus by grids/rows, where the
         * hero layout is wrong and an extra API call per focused card would be wasteful.
         */
        suspend fun submit(
            item: BaseItem,
            hero: Boolean = false,
            detail: Boolean = false,
        ) = withContext(Dispatchers.IO) {
            val imageUrl =
                if (item.type == BaseItemKind.GENRE) {
                    item.imageUrlOverride
                } else {
                    imageUrlService.getItemImageUrl(item, ImageType.BACKDROP)
                }
            submit(item.id.toString(), imageUrl, hero, detail)
            if (hero) resolveTrailerUrl(item)
        }

        /**
         * Resolves the local (Jellyfin-hosted) trailer stream for [item] and attaches it to the
         * current backdrop — only if the backdrop still belongs to [item] once resolved. Remote
         * (YouTube) trailers are already excluded by [TrailerService.getLocalTrailerStreamUrl].
         */
        private suspend fun resolveTrailerUrl(item: BaseItem) {
            val itemId = item.id.toString()
            val trailerUrl = runCatching { trailerService.getLocalTrailerStreamUrl(item.id) }.getOrNull()
            _backdropFlow.update {
                if (it.itemId == itemId) it.copy(trailerUrl = trailerUrl) else it
            }
        }

        /**
         * Update the backdrop to use the specified URL. [detail] marks a title page (see
         * [BackdropResult.detail]).
         */
        suspend fun submit(
            itemId: String,
            imageUrl: String?,
            hero: Boolean = false,
            detail: Boolean = false,
        ) = withContext(Dispatchers.IO) {
            val current = backdropFlow.firstOrNull()
            // Also re-submit when only the page kind changes (e.g. the same item focused on a home
            // row right after its details page), so the frame doesn't leak between screens.
            if (current?.imageUrl != imageUrl || current?.hero != hero || current?.detail != detail) {
                _backdropFlow.update {
                    it.copy(
                        itemId = itemId,
                        imageUrl = null,
                        hero = hero,
                        detail = detail,
                        // A new item never inherits the previous item's ambient trailer.
                        trailerUrl = null,
                    )
                }
                extractColors(itemId, imageUrl, hero, detail)
            }
        }

        /**
         * Update the backdrop directly from a set of pre-computed colors instead of an image.
         *
         * Used by the engine home's sample/demo bundle (and any card lacking real art): the items
         * carry only an accent hint, so there's no bitmap to run through [Palette]. This drives the
         * same focus-following ambient wash as image-derived backdrops, just from the accent.
         */
        suspend fun submitColors(
            itemId: String,
            primaryColor: Color,
            secondaryColor: Color,
            tertiaryColor: Color,
        ) = withContext(Dispatchers.IO) {
            _backdropFlow.update {
                BackdropResult(
                    itemId = itemId,
                    imageUrl = null,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    tertiaryColor = tertiaryColor,
                )
            }
        }

        /**
         * Remove the backdrop, such as when switching pages
         */
        suspend fun clearBackdrop() {
            clearedAt.incrementAndGet()
            _backdropFlow.update {
                BackdropResult.NONE
            }
        }

        /**
         * Bumped by [clearBackdrop]. A color extraction started before a clear must not land after
         * it: Home's last focused title used to reappear behind Search half a second after opening it.
         */
        private val clearedAt = java.util.concurrent.atomic.AtomicLong()

        private suspend fun extractColors(
            itemId: String,
            imageUrl: String?,
            hero: Boolean = false,
            detail: Boolean = false,
        ) {
            val clearsBefore = clearedAt.get()
            delay(500)
            val backdropStyle =
                preferences.data
                    .firstOrNull()
                    ?.interfacePreferences
                    ?.backdropStyle
            val dynamicEnabled =
                backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR ||
                    backdropStyle == BackdropStyle.UNRECOGNIZED
            // Title pages are image only, so their colours would never be painted.
            val (primaryColor, secondaryColor, tertiaryColor) =
                if (dynamicEnabled && !hero && !detail) {
                    extractColorsFromBackdrop(imageUrl)
                } else {
                    ExtractedColors.DEFAULT
                }
            _backdropFlow.update {
                if (it.itemId == itemId && clearedAt.get() == clearsBefore) {
                    BackdropResult(
                        itemId = itemId,
                        imageUrl = imageUrl,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        tertiaryColor = tertiaryColor,
                        hero = hero,
                        detail = detail,
                    )
                } else {
                    it
                }
            }
        }

        suspend fun extractColorsFromBackdrop(imageUrl: String?): ExtractedColors =
            withContext(Dispatchers.IO) {
                if (imageUrl.isNullOrBlank()) {
                    return@withContext ExtractedColors.DEFAULT
                }
                extractedColorCache.get(imageUrl)?.let {
                    return@withContext it
                }

                try {
                    val loader = context.imageLoader
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .allowHardware(false)
                            .bitmapConfig(Bitmap.Config.ARGB_8888)
                            .build()

                    val result = loader.execute(request)
                    if (result is SuccessResult) {
                        val drawable = result.image.asDrawable(context.resources)
                        val bitmap = drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
                        extractColorsFromBitmap(bitmap).also {
                            extractedColorCache.put(imageUrl, it)
                        }
                    } else {
                        ExtractedColors.DEFAULT
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error extracting colors from URL: $imageUrl")
                    ExtractedColors.DEFAULT
                }
            }

        /**
         * Extracts the art's own colour scheme (see [colorScheme]): its most present colour fills the
         * big top-left glow (secondary), the next two the right (tertiary) and bottom (primary) ones.
         * Generic vibrant/muted targets, with a preference for cool tones top-left, gave titles washes
         * that weren't in their art.
         */
        private suspend fun extractColorsFromBitmap(bitmap: Bitmap): ExtractedColors =
            try {
                val palette = Palette.from(bitmap).generate()
                val scheme = colorScheme(palette.swatches.map { it.rgb to it.population })
                if (scheme.isEmpty()) return ExtractedColors.DEFAULT
                val (main, second, third) = scheme

                val primaryColor = Color(third).copy(alpha = .42f)
                val secondaryColor = Color(main).copy(alpha = .5f)
                val tertiaryColor = Color(second).copy(alpha = .45f)

                Timber.v(
                    "Colors extracted: primary=%s, secondary=%s, tertiary=%s",
                    primaryColor,
                    secondaryColor,
                    tertiaryColor,
                )
                ExtractedColors(primaryColor, secondaryColor, tertiaryColor)
            } catch (e: Exception) {
                Timber.e(e, "Error extracting palette colors")
                ExtractedColors.DEFAULT
            }

        fun clearColorCache() {
            extractedColorCache.evictAll()
        }
    }

/**
 * The result from determining the backdrop URL and extracted colors for the dynamic backdrop
 */
data class BackdropResult(
    val itemId: String?,
    val imageUrl: String?,
    val primaryColor: Color = Color.Unspecified,
    val secondaryColor: Color = Color.Unspecified,
    val tertiaryColor: Color = Color.Unspecified,
    /**
     * Directly-playable local trailer stream for the ambient backdrop preview, or null when the
     * item has none / the submitter didn't opt in (see [BackdropService.submit]).
     */
    val trailerUrl: String? = null,
    /**
     * True only for the movie-details hero: renders the framed Movie Details v2 backdrop instead
     * of the default top-right inset (see [BackdropService.submit]).
     */
    val hero: Boolean = false,
    /**
     * A title page (series; the movie hero is one too): it shows the title's image and no colour
     * wash. Home and the other browsing screens are the reverse: the wash, no image.
     */
    val detail: Boolean = false,
) {
    /** A movie or series page: image only. */
    val titlePage: Boolean get() = hero || detail

    val hasColors: Boolean =
        primaryColor.isSpecified ||
            secondaryColor.isSpecified ||
            tertiaryColor.isSpecified

    companion object {
        val NONE =
            BackdropResult(
                null,
                null,
            )
    }
}

/**
 * The colors extracted from an image
 */
data class ExtractedColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
) {
    companion object {
        val DEFAULT = ExtractedColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
    }
}

/**
 * A title's colour scheme for the home wash: its most present colours, kept distinct from each other
 * (hue more than 24° apart, or clearly different saturation or lightness), most present first, three
 * long. Hue and saturation stay as in the art; only lightness moves into 0.30–0.60, so a near-black
 * tone shows as a glow on the dark base and a near-white one doesn't wash the screen out. A one-colour
 * poster stays one colour. Empty when the art has no usable colour.
 *
 * @param swatches each colour as (0xAARRGGBB, population)
 * @return up to three opaque 0xAARRGGBB colours
 */
internal fun colorScheme(swatches: List<Pair<Int, Int>>): List<Int> {
    val picks = mutableListOf<FloatArray>()
    swatches
        .map { (rgb, population) -> rgbToHsl(rgb) to population }
        .filter { (hsl, _) -> hsl[2] > .05f && hsl[2] < .95f }
        .sortedByDescending { it.second }
        .forEach { (hsl, _) -> if (picks.size < 3 && picks.all { distinct(it, hsl) }) picks += hsl }
    while (picks.isNotEmpty() && picks.size < 3) picks += picks.last()
    return picks.map { (h, s, l) -> hslToRgb(h, s, l.coerceIn(.3f, .6f)) }
}

private fun distinct(
    a: FloatArray,
    b: FloatArray,
): Boolean {
    val dh = abs(a[0] - b[0])
    return min(dh, 360 - dh) > 24 || abs(a[1] - b[1]) > .3f || abs(a[2] - b[2]) > .2f
}

/** 0xAARRGGBB to (hue 0–360, saturation 0–1, lightness 0–1). */
internal fun rgbToHsl(rgb: Int): FloatArray {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l = (max + min) / 2
    val h =
        when {
            d == 0f -> 0f
            max == r -> ((g - b) / d).mod(6f)
            max == g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
    return floatArrayOf(h, if (d == 0f) 0f else d / (1 - abs(2 * l - 1)), l)
}

/** (hue 0–360, saturation 0–1, lightness 0–1) to an opaque 0xAARRGGBB colour. */
internal fun hslToRgb(
    h: Float,
    s: Float,
    l: Float,
): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60).mod(2f) - 1))
    val m = l - c / 2
    val (r, g, b) =
        when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    fun channel(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
