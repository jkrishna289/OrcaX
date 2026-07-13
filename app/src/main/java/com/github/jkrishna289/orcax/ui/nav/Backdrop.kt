@file:OptIn(ExperimentalCoilApi::class)

package com.github.jkrishna289.orcax.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
    if (backdropStyle == BackdropStyle.BACKDROP_IMAGE_ONLY) {
        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            // The still image and the ambient trailer share this one inset box, so the trailer is
            // bounded and edge-faded by the exact same gradients as the image — it can never become
            // a fullscreen video takeover.
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
                // Ambient trailer preview: dwell → fade a muted, looping video in over the still
                // image (which stays underneath as the permanent fallback).
                AmbientBackdropTrailer(
                    itemId = backdrop.itemId,
                    trailerUrl = backdrop.trailerUrl,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
