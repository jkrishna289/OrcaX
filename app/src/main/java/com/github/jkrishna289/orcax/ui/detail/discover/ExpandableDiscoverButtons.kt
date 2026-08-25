package com.github.jkrishna289.orcax.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.SeerrAvailability
import com.github.jkrishna289.orcax.data.model.Trailer
import com.github.jkrishna289.orcax.ui.FontAwesome
import com.github.jkrishna289.orcax.ui.components.ExpandableFaButton
import com.github.jkrishna289.orcax.ui.components.ExpandablePlayButton
import com.github.jkrishna289.orcax.ui.components.TrailerButton
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import kotlin.time.Duration

@Composable
fun ExpandableDiscoverButtons(
    canRequest: Boolean,
    canCancel: Boolean,
    availability: SeerrAvailability,
    trailers: List<Trailer>?,
    requestOnClick: () -> Unit,
    cancelOnClick: () -> Unit,
    goToOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    buttonOnFocusChanged: (FocusState) -> Unit,
    modifier: Modifier = Modifier,
    pendingOnClick: () -> Unit = {},
    canFindSources: Boolean = false,
    findSourcesOnClick: () -> Unit = {},
) {
    val firstFocus = remember { FocusRequester() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(8.dp),
        modifier =
            modifier
                .focusGroup()
                .focusRestorer(firstFocus),
    ) {
        val text =
            when (availability) {
                SeerrAvailability.UNKNOWN -> R.string.request

                SeerrAvailability.PENDING,
                SeerrAvailability.PROCESSING,
                -> R.string.pending

                SeerrAvailability.PARTIALLY_AVAILABLE,
                SeerrAvailability.AVAILABLE,
                -> R.string.go_to

                SeerrAvailability.DELETED -> R.string.delete // TODO
            }
        val icon =
            when (availability) {
                SeerrAvailability.UNKNOWN -> R.string.fa_download

                SeerrAvailability.PENDING,
                SeerrAvailability.PROCESSING,
                -> R.string.fa_clock

                SeerrAvailability.PARTIALLY_AVAILABLE,
                SeerrAvailability.AVAILABLE,
                -> R.string.fa_play

                SeerrAvailability.DELETED -> R.string.fa_video // TODO
            }
        // The fork in the road. When a title isn't in the library the viewer faces a genuine choice
        // between two unfamiliar actions, so these two stay expanded and carry a one-line explanation
        // instead of retracting to icons the way Play or Trailer can. The difference between them is
        // stated in plain words underneath each — "for good" versus "this once" — because without it
        // the viewer has to guess, and guessing is where technical vocabulary creeps back in.
        val isTheFork = availability == SeerrAvailability.UNKNOWN
        if (isTheFork) {
            // Not rendered at all when the viewer has no way to request — never rendered disabled.
            // Find Streaming Sources then takes the focused position on its own.
            if (canRequest) {
                item("decide_request") {
                    DecisionCard(
                        title = R.string.request,
                        subtitle = R.string.request_subtitle,
                        iconStringRes = R.string.fa_plus,
                        onClick = requestOnClick,
                        modifier =
                            Modifier
                                .focusRequester(firstFocus)
                                .onFocusChanged(buttonOnFocusChanged),
                    )
                }
            }
            if (canFindSources) {
                item("decide_stream") {
                    DecisionCard(
                        title = R.string.find_streaming_sources,
                        subtitle = R.string.find_streaming_sources_subtitle,
                        iconStringRes = null,
                        onClick = findSourcesOnClick,
                        modifier =
                            Modifier
                                .then(if (canRequest) Modifier else Modifier.focusRequester(firstFocus))
                                .onFocusChanged(buttonOnFocusChanged),
                    )
                }
            }
        } else {
            item("first") {
                ExpandableFaButton(
                    title = text,
                    iconStringRes = icon,
                    enabled = true,
                    onClick = {
                        when (availability) {
                            SeerrAvailability.PENDING,
                            SeerrAvailability.PROCESSING,
                            -> {
                                pendingOnClick.invoke()
                            }

                            SeerrAvailability.PARTIALLY_AVAILABLE,
                            SeerrAvailability.AVAILABLE,
                            -> {
                                goToOnClick.invoke()
                            }

                            else -> {
                                // DELETED — TODO
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .focusRequester(firstFocus)
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }
        if (availability == SeerrAvailability.PARTIALLY_AVAILABLE) {
            item("request_partial") {
                ExpandableFaButton(
                    title = R.string.request,
                    iconStringRes = R.string.fa_download,
                    onClick = {
                        requestOnClick.invoke()
                    },
                    enabled = availability == SeerrAvailability.PARTIALLY_AVAILABLE,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        // Past the fork — the title is already requested or partly there — streaming drops back to a
        // retracted pill: it's a fallback at that point, not a decision. Rendered only when the server
        // offers it AND the title isn't already in the library; when the operator hasn't enabled the
        // feature, the viewer never learns it exists.
        if (!isTheFork && canFindSources && availability != SeerrAvailability.AVAILABLE) {
            item("find_sources") {
                ExpandableFaButton(
                    title = R.string.find_streaming_sources,
                    iconStringRes = R.string.fa_film,
                    onClick = findSourcesOnClick,
                    modifier = Modifier.onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        if (canCancel) {
            item("cancel") {
                ExpandablePlayButton(
                    title = R.string.cancel,
                    icon = Icons.Default.Delete,
                    onClick = {
                        firstFocus.tryRequestFocus()
                        cancelOnClick.invoke()
                    },
                    resume = Duration.ZERO,
                    enabled = canCancel,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        if (trailers != null) {
            item("trailers") {
                TrailerButton(
                    trailers = trailers,
                    trailerOnClick = trailerOnClick,
                    modifier = Modifier.onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        // More button
        // No functionality yet
//        item("more") {
//            ExpandablePlayButton(
//                R.string.more,
//                Duration.ZERO,
//                Icons.Default.MoreVert,
//                { moreOnClick.invoke() },
//                Modifier
//                    .onFocusChanged(buttonOnFocusChanged),
//            )
//        }
    }
}

/**
 * One of the two ways out of a title you don't own.
 *
 * Everywhere else in OrcaX a button retracts to an icon circle, because the viewer already knows what
 * Play does. Here they don't — so these stay expanded, carry the one line that tells them apart, and
 * are the only things on the row that can hold focus first.
 */
@Composable
fun DecisionCard(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    @StringRes iconStringRes: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(11.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.White.copy(alpha = 0.22f),
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
                pressedContainerColor = Color.White.copy(alpha = 0.22f),
                pressedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = shape),
            ),
        // These sit side by side and are already large; the default focus scale would shove the second
        // one off the row rather than making the choice clearer.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.widthIn(min = 210.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 11.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                iconStringRes?.let {
                    Text(text = stringResource(it), fontFamily = FontAwesome, fontSize = 15.sp)
                }
                Text(
                    text = stringResource(title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(subtitle),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = if (iconStringRes != null) 22.dp else 0.dp),
            )
        }
    }
}
