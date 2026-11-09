@file:OptIn(ExperimentalCoilApi::class)

package com.github.jkrishna289.orcax.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.useExistingImageAsPlaceholder
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import com.github.jkrishna289.orcax.preferences.BackdropStyle
import com.github.jkrishna289.orcax.services.BackdropResult
import com.github.jkrishna289.orcax.ui.CrossFadeFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows the current backdrop images provided by [com.github.jkrishna289.orcax.services.BackdropService]
 */
@Composable
fun Backdrop(
    drawerIsOpen: Boolean,
    backdropStyle: BackdropStyle,
    viewModel: ApplicationContentViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    enableTopScrim: Boolean = true,
    useExistingImageAsPlaceholder: Boolean = false,
    crossfadeDuration: Duration = 800.milliseconds,
) {
    val backdrop by viewModel.backdropService.backdropFlow.collectAsStateWithLifecycle()
    Backdrop(
        backdrop = backdrop,
        drawerIsOpen = drawerIsOpen,
        backdropStyle = backdropStyle,
        modifier = modifier,
        enableTopScrim = enableTopScrim,
        useExistingImageAsPlaceholder = useExistingImageAsPlaceholder,
        crossfadeDuration = crossfadeDuration,
    )
}

/**
 * Shows the current backdrop images provided by the [BackdropResult]
 */
@Composable
fun Backdrop(
    backdrop: BackdropResult,
    drawerIsOpen: Boolean,
    backdropStyle: BackdropStyle,
    modifier: Modifier = Modifier,
    enableTopScrim: Boolean = true,
    useExistingImageAsPlaceholder: Boolean = false,
    crossfadeDuration: Duration = 800.milliseconds,
) {
    val baseBackgroundColor = MaterialTheme.colorScheme.background
    val dynamic = backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR || backdropStyle == BackdropStyle.UNRECOGNIZED
    // Home and the other browsing screens: colour only, from the focused title's own scheme (see
    // colorScheme). Movie and series pages: image only, so no wash there.
    if (backdrop.hasColors && dynamic && !backdrop.titlePage) {
        val animPrimary by animateColorAsState(
            backdrop.primaryColor,
            animationSpec = tween(1250),
            label = "dynamic_backdrop_primary",
        )
        val animSecondary by animateColorAsState(
            backdrop.secondaryColor,
            animationSpec = tween(1250),
            label = "dynamic_backdrop_secondary",
        )
        val animTertiary by animateColorAsState(
            backdrop.tertiaryColor,
            animationSpec = tween(1250),
            label = "dynamic_backdrop_tertiary",
        )
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .drawBehind {
                        val reach = size.width * 0.8f
                        fun glow(
                            color: Color,
                            x: Float,
                            y: Float,
                            radius: Float,
                            fadeAt: Float,
                        ) = drawRect(
                            brush =
                                Brush.radialGradient(
                                    colorStops = arrayOf(0f to color, fadeAt to Color.Transparent),
                                    center = Offset(size.width * x, size.height * y),
                                    radius = radius,
                                ),
                        )
                        drawRect(color = baseBackgroundColor)
                        // Large soft glows: the third colour low across the bottom, the second on the
                        // right, the title's most present colour as the big one top-left.
                        glow(animPrimary, .62f, 1.18f, reach * 1.1f, .72f)
                        glow(animTertiary, 1.04f, .28f, reach * .9f, .70f)
                        glow(animSecondary, .08f, -.08f, reach * 1.05f, .72f)
                        // The lower half calms down so rows stay readable over the glows.
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            .45f to Color.Transparent,
                                            1f to Color(0xFF100D16).copy(alpha = .5f),
                                        ),
                                ),
                        )
                    },
        )
    }
    // The movie page's framed hero image shows in Dynamic color and Image alike; only "None" turns it off.
    if (backdrop.hero && backdropStyle != BackdropStyle.BACKDROP_NONE) {
        // Movie Details v2 hero (movie details only, via BackdropResult.hero): backdrop framed
        // like the home billboard — rounded top corners, hairline border, slim side/top insets,
        // open at the bottom — with a left/bottom scrim stack so the text block always sits on a
        // darkened surface. Scrims and the ambient trailer render inside the clip, and blend into
        // the theme background so the hero melts into the page rather than ending at a hard edge.
        val heroShape = HeroFrame.shape
        // Scrolled past the header (cast, more like this), the image softens and darkens so those
        // rows read well over it. Blur needs Android 12; older devices just get the darkening.
        val veil by animateFloatAsState(if (HeroFrame.scrolled) 1f else 0f, tween(400), label = "hero_veil")
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(top = HeroFrame.top, start = HeroFrame.side, end = HeroFrame.side)
                    .clip(heroShape)
                    .border(HeroFrame.border, Color.White.copy(alpha = .06f), heroShape)
                    .blur((veil * 10).dp)
                    .drawWithContent {
                        drawContent()
                        if (veil > 0f) drawRect(Color.Black, alpha = .35f * veil)
                        // Left scrim (text protection): background → transparent by 55%.
                        drawRect(
                            brush =
                                Brush.horizontalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to baseBackgroundColor,
                                            0.18f to baseBackgroundColor.copy(alpha = .92f),
                                            0.55f to Color.Transparent,
                                        ),
                                ),
                        )
                        // Bottom scrim: opaque background at the bottom edge (where the below-fold
                        // rows begin) fading out by 60% of the way up.
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0.40f to Color.Transparent,
                                            0.68f to baseBackgroundColor.copy(alpha = .5f),
                                            0.88f to baseBackgroundColor.copy(alpha = .96f),
                                            1f to baseBackgroundColor,
                                        ),
                                ),
                        )
                        // Subtle top scrim for system UI readability (clock, tabs)
                        if (enableTopScrim) {
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        colorStops =
                                            arrayOf(
                                                0f to Color.Black.copy(alpha = TOP_SCRIM_ALPHA),
                                                TOP_SCRIM_END_FRACTION to Color.Transparent,
                                            ),
                                    ),
                                blendMode = BlendMode.Multiply,
                            )
                        }
                        if (drawerIsOpen) {
                            drawRect(
                                brush = SolidColor(Color.Black),
                                alpha = .75f,
                            )
                        }
                    },
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(backdrop.imageUrl)
                        .useExistingImageAsPlaceholder(useExistingImageAsPlaceholder)
                        .transitionFactory(CrossFadeFactory(crossfadeDuration))
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                modifier = Modifier.fillMaxSize(),
            )
            // Ambient trailer preview: dwell → fade a muted, looping video in over the still image
            // (which stays underneath as the permanent fallback). Bounded to a top-right inset and
            // edge-faded by construction, so it can never read as a fullscreen video takeover.
            // Gated on trailerUrl so screens without one (grids, libraries with no local trailers)
            // don't pay for an empty offscreen-composited layer.
            // ponytail: linear left/bottom edge fades instead of the prototype's radial-ellipse
            // mask; swap for a scaled radial DstIn gradient if the seam ever reads as an edge.
            backdrop.trailerUrl?.let { trailerUrl ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight(.82f)
                            .fillMaxWidth(.74f)
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush =
                                        Brush.horizontalGradient(
                                            colors = listOf(Color.Transparent, Color.Black),
                                            startX = 0f,
                                            endX = size.width * 0.6f,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startY = 0f,
                                            endY = size.height,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                ) {
                    AmbientBackdropTrailer(
                        itemId = backdrop.itemId,
                        trailerUrl = trailerUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    if (!backdrop.hero && (backdropStyle == BackdropStyle.BACKDROP_IMAGE_ONLY || (dynamic && backdrop.detail))) {
        // The series page (Dynamic color or Image), and every non-hero surface in the Image style: the
        // still image inset top-right, edge-faded into the background by DstIn gradients.
        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight(.7f)
                        .fillMaxWidth(.7f)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            if (drawerIsOpen) {
                                drawRect(
                                    brush = SolidColor(Color.Black),
                                    alpha = .75f,
                                )
                            }
                            // Subtle top scrim for system UI readability (clock, tabs)
                            if (enableTopScrim) {
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            colorStops =
                                                arrayOf(
                                                    0f to Color.Black.copy(alpha = TOP_SCRIM_ALPHA),
                                                    TOP_SCRIM_END_FRACTION to Color.Transparent,
                                                ),
                                        ),
                                    blendMode = BlendMode.Multiply,
                                )
                            }
                            drawRect(
                                brush =
                                    Brush.horizontalGradient(
                                        colors = listOf(Color.Transparent, Color.Black),
                                        startX = 0f,
                                        endX = size.width * 0.6f,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startY = 0f,
                                        endY = size.height,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(backdrop.imageUrl)
                            .useExistingImageAsPlaceholder(useExistingImageAsPlaceholder)
                            .transitionFactory(CrossFadeFactory(crossfadeDuration))
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Where the movie page's framed hero backdrop sits on screen. The movie page lays its content out
 * inside this same frame (see MovieDetailsContent), so its text and rows can never run over the
 * frame's border: change one and the other follows.
 */
object HeroFrame {
    val top = 8.dp
    val side = 14.dp
    val border = 1.dp
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    /** The frame's inside: the area the movie page's content fills. */
    val contentShape = RoundedCornerShape(topStart = 20.dp - border, topEnd = 20.dp - border)

    /** Set by the movie page while it is scrolled down; the hero blurs and darkens behind it. */
    // ponytail: one global flag, fine while the movie page is the only hero page at a time.
    var scrolled by mutableStateOf(false)
}
