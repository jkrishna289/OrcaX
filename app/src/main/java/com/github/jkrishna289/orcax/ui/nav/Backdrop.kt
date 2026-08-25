@file:OptIn(ExperimentalCoilApi::class)

package com.github.jkrishna289.orcax.ui.nav

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    if (backdrop.hasColors &&
        (backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR || backdropStyle == BackdropStyle.UNRECOGNIZED)
    ) {
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
                        drawRect(color = baseBackgroundColor)
                        // Top Left (Vibrant/Muted)
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors = listOf(animSecondary, Color.Transparent),
                                    center = Offset(0f, 0f),
                                    radius = size.width * 0.8f,
                                ),
                        )
                        // Bottom Right (DarkVibrant/DarkMuted)
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors = listOf(animPrimary, Color.Transparent),
                                    center = Offset(size.width, size.height),
                                    radius = size.width * 0.8f,
                                ),
                        )
                        // Bottom Left (Dark / Bridge)
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(
                                            baseBackgroundColor,
                                            Color.Transparent,
                                        ),
                                    center = Offset(0f, size.height),
                                    radius = size.width * 0.8f,
                                ),
                        )
                        // Top Right (Under Image - Vibrant/Bright)
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors = listOf(animTertiary, Color.Transparent),
                                    center = Offset(size.width, 0f),
                                    radius = size.width * 0.8f,
                                ),
                        )
                    },
        )
    }
    // Dynamic-color mode renders the extracted color wash ONLY (above). The sharp, top-right
    // backdrop image is intentionally NOT drawn here, so a focused card tints the home/detail
    // surfaces with just its colors instead of bleeding a hard thumbnail behind the content
    // (problem #7 — "use the colours only"). The literal image is reserved for IMAGE_ONLY style.
    if (backdropStyle == BackdropStyle.BACKDROP_IMAGE_ONLY && backdrop.hero) {
        // Movie Details v2 hero (movie details only, via BackdropResult.hero): backdrop framed
        // like the home billboard — rounded top corners, hairline border, slim side/top insets,
        // open at the bottom — with a left/bottom scrim stack so the text block always sits on a
        // darkened surface. Scrims and the ambient trailer render inside the clip, and blend into
        // the theme background so the hero melts into the page rather than ending at a hard edge.
        val heroShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, start = 14.dp, end = 14.dp)
                    .clip(heroShape)
                    .border(1.dp, Color.White.copy(alpha = .06f), heroShape)
                    .drawWithContent {
                        drawContent()
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
    if (backdropStyle == BackdropStyle.BACKDROP_IMAGE_ONLY && !backdrop.hero) {
        // Every non-hero surface (home, categories, series, grids): the original image-only look —
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
