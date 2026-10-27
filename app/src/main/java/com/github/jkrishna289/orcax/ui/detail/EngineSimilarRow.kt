package com.github.jkrishna289.orcax.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
) {
    val viewModel: EngineSimilarViewModel = hiltViewModel()
    LaunchedEffect(itemId, tmdbId) { viewModel.load(itemId, tmdbId) }
    val row by viewModel.row.collectAsStateWithLifecycle()

    row?.takeIf { it.items.isNotEmpty() }?.let { r ->
        DynamicCardRow(
            row = r,
            onClickItem = viewModel::onClick,
            modifier = modifier,
        )
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

        private var loadedFor: Any? = null

        fun load(
            itemId: UUID?,
            tmdbId: Int? = null,
        ) {
            val key = itemId ?: tmdbId ?: return
            if (loadedFor == key) return
            loadedFor = key
            viewModelScope.launch {
                _row.value = client.getSimilar(jellyfinId = itemId, tmdbId = tmdbId.takeIf { itemId == null })?.row
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
