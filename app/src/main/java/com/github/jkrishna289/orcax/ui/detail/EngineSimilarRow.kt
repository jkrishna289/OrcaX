package com.github.jkrishna289.orcax.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.ui.main.SkeletonRow
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.cards.DynamicCardRow
import com.github.jkrishna289.orcax.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * A "More Like This" row powered by the Orca Engine's content-based similarity (catalog-wide,
 * people/genre-aware, and able to surface requestable titles). Self-contained: it loads on its own
 * and renders nothing when the engine is unavailable or has no results, so it's safe to drop into
 * any detail screen. A title outside the library opens its own details page, where it can be requested.
 */
@Composable
fun EngineSimilarRow(
    itemId: UUID?,
    modifier: Modifier = Modifier,
    // For a title outside the library, which has no Jellyfin id; the engine resolves either.
    tmdbId: Int? = null,
    // Hold the row's place with a focusable skeleton while it loads, so Down always leaves the
    // buttons above (an early Down otherwise did nothing, and the next OK pressed Request).
    placeholderWhileLoading: Boolean = false,
    // Where focus goes if the skeleton had it and the row turned out empty, so it isn't lost.
    fallbackFocus: FocusRequester? = null,
) {
    val viewModel: EngineSimilarViewModel = hiltViewModel()
    LaunchedEffect(itemId, tmdbId) { viewModel.load(itemId, tmdbId) }
    val row by viewModel.row.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var skeletonFocused by remember { mutableStateOf(false) }

    val loaded = row?.takeIf { it.items.isNotEmpty() }
    if (loaded != null) {
        val rowFocus = remember { FocusRequester() }
        // Read as the row first composes, before the skeleton's own focus loss is reported.
        val takeFocus = remember(loaded) { skeletonFocused }
        DynamicCardRow(
            row = loaded,
            onClickItem = viewModel::onClick,
            focusRequester = rowFocus,
            modifier = modifier,
        )
        LaunchedEffect(loaded) { if (takeFocus) rowFocus.tryRequestFocus() }
    } else if (placeholderWhileLoading && loading) {
        SkeletonRow(
            title = stringResource(R.string.more_like_this),
            modifier = modifier.onFocusChanged { skeletonFocused = it.isFocused }.focusable(),
        )
    } else if (placeholderWhileLoading) {
        val hadFocus = remember { skeletonFocused }
        LaunchedEffect(Unit) { if (hadFocus) fallbackFocus?.tryRequestFocus() }
    }
}

/** Loads engine similarity for an item; on click, opens the title's details page. */
@HiltViewModel
class EngineSimilarViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _row = MutableStateFlow<RenderRow?>(null)
        val row: StateFlow<RenderRow?> = _row.asStateFlow()

        private val _loading = MutableStateFlow(true)
        val loading: StateFlow<Boolean> = _loading.asStateFlow()

        private var loadedFor: Any? = null

        fun load(
            itemId: UUID?,
            tmdbId: Int? = null,
        ) {
            val key = itemId ?: tmdbId ?: return
            if (loadedFor == key) return
            loadedFor = key
            _loading.value = true
            viewModelScope.launch {
                try {
                    _row.value = client.getSimilar(jellyfinId = itemId, tmdbId = tmdbId.takeIf { itemId == null })?.row
                } finally {
                    _loading.value = false
                }
            }
        }

        fun onClick(item: RenderItem) {
            val id = item.media.jellyfinId?.toUUIDOrNull()
            if (id == null) {
                // Not in the library: its own not-yet-requested page, with its own "More like this".
                // Back returns here.
                if ((item.media.tmdbId ?: 0) > 0) navigationManager.navigateTo(Destination.EngineItem(item))
                return
            }

            val kind =
                when (item.media.mediaType) {
                    MediaType.SERIES -> BaseItemKind.SERIES
                    MediaType.EPISODE -> BaseItemKind.EPISODE
                    MediaType.SEASON -> BaseItemKind.SEASON
                    MediaType.PERSON -> BaseItemKind.PERSON
                    MediaType.COLLECTION -> BaseItemKind.BOX_SET
                    else -> BaseItemKind.MOVIE
                }
            val destination =
                if (kind == BaseItemKind.SERIES) {
                    Destination.SeriesOverview(itemId = id, type = kind)
                } else {
                    Destination.MediaItem(itemId = id, type = kind)
                }
            navigationManager.navigateTo(destination)
        }
    }
