package com.github.jkrishna289.orcax.ui.detail.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.engine.SourceGroups
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.ui.components.DialogItem
import com.github.jkrishna289.orcax.ui.components.DialogItemDivider
import com.github.jkrishna289.orcax.ui.components.DialogItemEntry
import com.github.jkrishna289.orcax.ui.FontAwesome
import com.github.jkrishna289.orcax.ui.components.DialogPopup

/**
 * What the source picker is currently doing. Every terminal state offers the viewer a way forward —
 * none of them is a dead end, which is the one hard rule for this flow.
 */
sealed interface SourceSearchState {
    /** Nothing requested; the picker isn't shown. */
    data object Idle : SourceSearchState

    /** A search is running. Server-side, so navigating away doesn't cancel it. */
    data object Searching : SourceSearchState

    /** Sources found and ranked. */
    data class Found(
        val groups: SourceGroups,
        val showAll: Boolean = false,
        val expandedDetails: Boolean = false,
    ) : SourceSearchState

    /** The search ran and genuinely found nothing playable. */
    data object NoSources : SourceSearchState

    /** A source was picked and a stream is being opened; can take a while on a cold torrent. */
    data class Opening(val source: TorrentSourceDto) : SourceSearchState

    /**
     * Something went wrong. [canRetry] distinguishes "this source failed, pick another" from
     * "the server isn't set up for this", which are different messages and different next steps.
     */
    data class Failed(val messageRes: Int, val canRetry: Boolean) : SourceSearchState
}

/**
 * The whole source-picking surface, driven off [state].
 *
 * Every non-idle state renders something actionable. A search that finds nothing offers a way out; a
 * source that fails to start reopens the list rather than dumping the viewer back on the details page
 * wondering what happened.
 */
@Composable
fun SourcePickerDialog(
    state: SourceSearchState,
    onPick: (TorrentSourceDto) -> Unit,
    onShowAll: () -> Unit,
    onToggleDetails: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        SourceSearchState.Idle -> Unit

        // Progress is indeterminate on purpose: how long an indexer sweep takes is genuinely unknown,
        // and a fake percentage bar would be a lie the viewer can see through.
        SourceSearchState.Searching ->
            SourceProgressDialog(
                message = stringResource(R.string.sources_searching),
                onDismiss = onDismiss,
            )

        is SourceSearchState.Opening ->
            SourceProgressDialog(
                message = stringResource(R.string.sources_opening),
                onDismiss = onDismiss,
            )

        SourceSearchState.NoSources ->
            SourceMessageDialog(
                title = stringResource(R.string.sources_none_title),
                message = stringResource(R.string.sources_none_message),
                onDismiss = onDismiss,
            )

        is SourceSearchState.Failed ->
            SourceMessageDialog(
                title = stringResource(R.string.sources_none_title),
                message = stringResource(state.messageRes),
                // Retry only where retrying can plausibly help. A missing server setup won't fix
                // itself, and offering a button that always fails is worse than not offering one.
                retryLabel = if (state.canRetry) stringResource(R.string.source_show_all) else null,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )

        is SourceSearchState.Found ->
            DialogPopup(
                showDialog = true,
                title = stringResource(R.string.available_sources),
                dialogItems =
                    sourceDialogItems(
                        state = state,
                        onPick = onPick,
                        onShowAll = onShowAll,
                        onToggleDetails = onToggleDetails,
                    ),
                onDismissRequest = onDismiss,
                // Picking a source starts a long operation and swaps in a progress dialog, so the
                // list must not dismiss itself out from under that transition.
                dismissOnClick = false,
                waitToLoad = false,
            )
    }
}

@Composable
private fun SourceProgressDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                CircularProgressIndicator()
                Text(text = message, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SourceMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    retryLabel: String? = null,
    onRetry: () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 520.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(text = message, style = MaterialTheme.typography.bodyMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (retryLabel != null) {
                        Button(onClick = onRetry) { Text(text = retryLabel) }
                    }
                    Button(onClick = onDismiss) { Text(text = stringResource(R.string.close)) }
                }
            }
        }
    }
}

/**
 * Builds the picker's rows.
 *
 * The shape matters as much as the content: a viewer sees at most five labelled choices, not a list
 * of eighty near-identical releases. Groups come first under their plain-language names, the full
 * ranked list only after "Show all sources", and technical metadata only after "Show details".
 */
@Composable
fun sourceDialogItems(
    state: SourceSearchState.Found,
    onPick: (TorrentSourceDto) -> Unit,
    onShowAll: () -> Unit,
    onToggleDetails: () -> Unit,
): List<DialogItemEntry> {
    val groups = state.groups
    val items = mutableListOf<DialogItemEntry>()

    if (state.showAll) {
        groups.all.forEach { source ->
            items += sourceRow(label = null, source = source, showDetails = state.expandedDetails, onPick = onPick)
        }
    } else {
        // Only distinct picks are worth a row — the same release is frequently the best on several
        // axes, and repeating it would make the list look padded and the choice arbitrary.
        val labelled =
            listOfNotNull(
                groups.recommended?.let { R.string.source_recommended to it },
                groups.bestQuality?.let { R.string.source_best_quality to it },
                groups.fastestStart?.let { R.string.source_fastest_start to it },
                groups.lowestBandwidth?.let { R.string.source_lowest_bandwidth to it },
                groups.fourKHdr?.let { R.string.source_4k_hdr to it },
            ).distinctBy { it.second.id }

        labelled.forEach { (labelRes, source) ->
            items += sourceRow(
                label = stringResource(labelRes),
                source = source,
                showDetails = state.expandedDetails,
                onPick = onPick,
            )
        }

        // Only offer the full list when it actually holds more than what's already shown.
        if (groups.all.size > labelled.size) {
            items += DialogItemDivider
            items += DialogItem(
                text = stringResource(R.string.source_show_all),
                iconStringRes = R.string.fa_list_ul,
                onClick = onShowAll,
            )
        }
    }

    items += DialogItemDivider
    items += DialogItem(
        text =
            stringResource(
                if (state.expandedDetails) R.string.source_hide_details else R.string.source_show_details,
            ),
        iconStringRes = R.string.fa_sliders,
        onClick = onToggleDetails,
    )

    return items
}

/**
 * One selectable source. The headline is the friendly summary the engine composed ("1080p ·
 * Excellent · EAC3 5.1 · 6.2 GB"); the release name, share count and indexer stay hidden until the
 * viewer asks for details.
 */
private fun sourceRow(
    label: String?,
    source: TorrentSourceDto,
    showDetails: Boolean,
    onPick: (TorrentSourceDto) -> Unit,
): DialogItem =
    DialogItem(
        overlineContent = label?.let { { Text(text = it, style = MaterialTheme.typography.labelSmall) } },
        headlineContent = {
            Text(text = source.summary.ifBlank { source.quality })
        },
        supportingContent =
            if (showDetails) {
                {
                    Text(
                        text =
                            stringResource(
                                R.string.source_technical,
                                source.title,
                                source.seeders,
                                source.indexer ?: "—",
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                null
            },
        leadingContent = {
            Text(
                text = stringResource(R.string.fa_circle_play),
                fontFamily = FontAwesome,
            )
        },
        onClick = { onPick(source) },
    )
