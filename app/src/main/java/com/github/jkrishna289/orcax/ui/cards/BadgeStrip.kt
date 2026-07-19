package com.github.jkrishna289.orcax.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.github.jkrishna289.orcax.engine.CardBadge
import com.github.jkrishna289.orcax.ui.LocalImageUrlService
import com.github.jkrishna289.orcax.ui.PreviewTvSpec
import com.github.jkrishna289.orcax.ui.theme.OrcaTheme

/**
 * One engine badge, dispatched purely on whether it carries artwork — **not** on its [CardBadge.kind]:
 *  - [CardBadge.iconUrl] present → an **image badge**: the engine's cached transparent logo (awards,
 *    Dolby Vision, IMAX, 4K HDR…) drawn straight onto the art with no chip behind it, so the
 *    transparency reads as intended. Falls back to the text pill while loading or if it fails.
 *  - otherwise → a **text badge**: the shared [BadgePill] ("95% Match", "Trending", "Top 10",
 *    "New Season", "Because You Watched"…).
 *
 * Generalizes the provider-specific [StudioBadge] pattern so any badge the engine invents renders
 * without a client change. Awards are just one producer: the engine sends
 * `CardBadge(kind = "AWARD", text = "Academy Award Winner", iconUrl = "/OrcaEngine/Images/Award/…")`
 * and this composable never needs to know that "AWARD" exists.
 */
@Composable
internal fun EngineBadge(
    badge: CardBadge,
    modifier: Modifier = Modifier,
    // Image badges render at the strip token by default; larger surfaces (the Spotlight's corner
    // provider slot) pass their own size tier.
    logoHeight: Dp = BADGE_LOGO_HEIGHT,
) {
    val label = badge.text?.takeIf { it.isNotBlank() } ?: badge.kind
    // LocalImageUrlService is only provided inside the real app graph (it needs a Jellyfin
    // ApiClient), and it throws when unset — so previews resolve to null and show the text pill,
    // which is the same fallback a failed logo load takes.
    val logoUrl =
        if (LocalInspectionMode.current) {
            null
        } else {
            LocalImageUrlService.current.engineImageUrl(badge.iconUrl)
        }

    Box(modifier = modifier) {
        if (logoUrl == null) {
            BadgePill(text = label)
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                loading = { BadgePill(text = label) },
                error = { BadgePill(text = label) },
                modifier = Modifier.height(logoHeight).widthIn(max = BADGE_LOGO_MAX_WIDTH),
            )
        }
    }
}

/**
 * A horizontal strip of [EngineBadge]s — the accolade/format/match line under a Spotlight item's
 * metadata. Wraps to further lines when the badges outrun the available width (a title can carry an
 * award logo, a format logo and several text badges at once), so nothing is silently clipped.
 * Renders nothing when [badges] is empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeStrip(
    badges: List<CardBadge>,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(BADGE_SPACING),
        verticalArrangement = Arrangement.spacedBy(BADGE_SPACING),
        modifier = modifier,
    ) {
        badges.forEach { badge ->
            EngineBadge(badge = badge, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

/** Image badges render at a fixed height so mismatched logo aspect ratios still align on the strip. */
private val BADGE_LOGO_HEIGHT = 28.dp
private val BADGE_LOGO_MAX_WIDTH = 120.dp

/** Gap between badges, both along a line and between wrapped lines. */
private val BADGE_SPACING = 10.dp

@PreviewTvSpec
@Composable
private fun BadgeStripPreview() {
    OrcaTheme {
        BadgeStrip(
            badges =
                listOf(
                    // Image badges: outside the app graph these fall back to their text pill, which is
                    // exactly the load/error path the strip is expected to survive.
                    CardBadge(kind = "AWARD", text = "Academy Award Winner", iconUrl = "/OrcaEngine/Images/Award/oscar"),
                    CardBadge(kind = "FORMAT", text = "Dolby Vision", iconUrl = "/OrcaEngine/Images/Format/dolby-vision"),
                    // Text badges: no iconUrl, so they render as pills regardless of kind.
                    CardBadge(kind = "MATCH", text = "95% Match"),
                    CardBadge(kind = "TRENDING", text = "Trending"),
                    CardBadge(kind = "FORMAT", text = "4K HDR"),
                    CardBadge(kind = "NEW_SEASON", text = "New Season"),
                    // Text missing entirely → the kind is the label, so a badge is never blank.
                    CardBadge(kind = "TOP 10"),
                ),
        )
    }
}
