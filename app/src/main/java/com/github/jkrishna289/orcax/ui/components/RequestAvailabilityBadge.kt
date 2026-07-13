package com.github.jkrishna289.orcax.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.ui.AppColors
import com.github.jkrishna289.orcax.ui.FontAwesome

/** Teal for the steady "Requested" provenance pill (Movie Details v2 prototype). */
private val RequestedTeal = Color(0xFF2DE0C0)

/** Gold for the actionable "Request" pill — deliberately the star-rating gold. */
private val RequestGold = AppColors.GoldenYellow

private const val FILL_ALPHA = 0.14f
private const val BORDER_ALPHA = 0.6f

private val PillShape = RoundedCornerShape(4.dp)
private val PillPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp)

/**
 * A small pill showing this title's Orca-Engine request status, in the same visual family as the
 * [StreamLabel] pills beside it:
 * - [AvailabilityState.REQUESTED] — filled teal, checkmark, "Requested". A provenance indicator,
 *   not a call to action, so it is deliberately not focusable.
 * - [AvailabilityState.REQUEST] — outlined gold, "+", "Request". D-pad focusable; invokes
 *   [onRequest].
 * - [requestInFlight] — gold, clock, "Requesting…". Kept an enabled (focusable) button with a
 *   no-op click so D-pad focus isn't dropped mid-request; only the click path is gated.
 *
 * Other states (WatchNow/Downloading/RecentlyAdded/Unavailable) render nothing — on the in-library
 * details screen they carry no request provenance worth badging.
 */
@Composable
fun RequestAvailabilityBadge(
    availability: AvailabilityState,
    requestInFlight: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        requestInFlight || availability == AvailabilityState.REQUEST -> {
            Button(
                onClick = { if (!requestInFlight) onRequest.invoke() },
                shape = ClickableSurfaceDefaults.shape(PillShape),
                colors =
                    ClickableSurfaceDefaults.colors(
                        containerColor = RequestGold.copy(alpha = FILL_ALPHA),
                        contentColor = RequestGold,
                        focusedContainerColor = RequestGold,
                        focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        pressedContainerColor = RequestGold,
                        pressedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        disabledContainerColor = RequestGold.copy(alpha = FILL_ALPHA / 2),
                        disabledContentColor = RequestGold.copy(alpha = 0.4f),
                    ),
                border =
                    ClickableSurfaceDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, RequestGold.copy(alpha = BORDER_ALPHA)),
                                shape = PillShape,
                            ),
                        focusedBorder = Border.None,
                        pressedBorder = Border.None,
                    ),
                contentPadding = PillPadding,
                contentHeight = 16.dp,
                modifier = modifier,
            ) {
                PillContent(
                    icon = if (requestInFlight) R.string.fa_clock else R.string.fa_plus,
                    label = if (requestInFlight) R.string.requesting else R.string.request,
                )
            }
        }

        availability == AvailabilityState.REQUESTED -> {
            Row(
                modifier =
                    modifier
                        .background(RequestedTeal.copy(alpha = FILL_ALPHA), PillShape)
                        .border(1.dp, RequestedTeal.copy(alpha = BORDER_ALPHA), PillShape)
                        .padding(PillPadding),
            ) {
                PillContent(
                    icon = R.string.fa_check,
                    label = R.string.requested,
                    color = RequestedTeal,
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun PillContent(
    @StringRes icon: Int,
    @StringRes label: Int,
    color: Color = Color.Unspecified,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(icon),
            fontFamily = FontAwesome,
            fontSize = 12.sp,
            color = color,
        )
        Text(
            text = stringResource(label),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
    }
}
