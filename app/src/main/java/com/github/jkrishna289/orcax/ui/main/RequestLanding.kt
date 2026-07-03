package com.github.jkrishna289.orcax.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.ui.components.RestoreFocusOnDispose
import com.github.jkrishna289.orcax.ui.components.focusTrap

/**
 * A full-screen landing surface for a **not-yet-available** title (a Discover/request item), shown
 * instead of opening a details page that doesn't exist yet. It presents the item's art, title,
 * genres and synopsis with a single primary **Request** action (plus Close), then dismisses back to
 * the home. Deliberately separate from the available-media detail page, which is left untouched.
 *
 * Art comes from the engine's backdrop URL when present, else the same procedural accent key-art the
 * rest of the engine home uses, so request cards never render blank (problems #4/#5).
 */
@Composable
fun RequestLanding(
    item: RenderItem,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusTo: FocusRequester? = null,
) {
    BackHandler(onBack = onDismiss)
    RestoreFocusOnDispose(restoreFocusTo)
    val card = item.card
    val accent = EngineHomeArt.parseAccent(card.accentColorHint)
    val context = LocalContext.current
    val requestFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requestFocus.requestFocus() } }

    val genres =
        card.badges
            .filter { it.kind.equals("GENRE", ignoreCase = true) }
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }

    Box(modifier = modifier.fillMaxSize().focusTrap()) {
        // Art (real backdrop or procedural gradient).
        val backdropUrl = card.backdropImageUrl
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().engineHeroArt(accent))
        }

        // Legibility scrims.
        Box(
            modifier =
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.7f to Color.Black.copy(alpha = 0.3f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.52f)
                    .padding(start = 64.dp, end = 24.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = stringResource(R.string.request_not_yet_available),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 3.sp,
                )
            }

            Text(
                text = card.title.orEmpty(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 46.sp,
                lineHeight = 50.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    genres.forEachIndexed { index, g ->
                        if (index > 0) Text("·", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
                        Text(g, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    }
                }
            }

            card.subtitle?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Text(
                    text = synopsis,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BillboardButton(
                    label = stringResource(R.string.request),
                    leading = "+",
                    primary = true,
                    onClick = onRequest,
                    modifier = Modifier.focusRequester(requestFocus),
                )
                BillboardButton(label = stringResource(R.string.close), primary = false, onClick = onDismiss)
            }
        }
    }
}
