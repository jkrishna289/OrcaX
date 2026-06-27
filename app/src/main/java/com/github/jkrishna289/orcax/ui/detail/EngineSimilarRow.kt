package com.github.jkrishna289.orcax.ui.detail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.cards.DynamicCardRow
import com.github.jkrishna289.orcax.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * A "More Like This" row powered by the Orca Engine's content-based similarity (catalog-wide,
 * people/genre-aware, and able to surface requestable titles). Self-contained: it loads on its own
 * and renders nothing when the engine is unavailable or has no results, so it's safe to drop into
 * any detail screen. Requestable similar titles submit an engine-proxied request on click.
 */
@Composable
fun EngineSimilarRow(
    itemId: UUID,
    modifier: Modifier = Modifier,
) {
    val viewModel: EngineSimilarViewModel = hiltViewModel()
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val row by viewModel.row.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.message.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    row?.takeIf { it.items.isNotEmpty() }?.let { r ->
        DynamicCardRow(
            row = r,
            onClickItem = viewModel::onClick,
            modifier = modifier,
        )
    }
}

/**
 * Loads engine similarity for an item; on click, requests requestable titles (Jellyseerr) or
 * navigates to a watchable title's detail page.
 */
@HiltViewModel
class EngineSimilarViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val navigationManager: NavigationManager,
        private val preferences: DataStore<AppPreferences>,
    ) : ViewModel() {
        private val _row = MutableStateFlow<RenderRow?>(null)
        val row: StateFlow<RenderRow?> = _row.asStateFlow()

        private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val message: SharedFlow<String> = _message.asSharedFlow()

        private var loadedFor: UUID? = null
        private var currentUserId: UUID? = null

        fun load(itemId: UUID) {
            if (loadedFor == itemId) return
            loadedFor = itemId
            viewModelScope.launch {
                currentUserId =
                    runCatching {
                        preferences.data.firstOrNull()?.currentUserId?.toUUIDOrNull()
                    }.getOrNull()
                _row.value = client.getSimilar(jellyfinId = itemId)?.row
            }
        }

        fun onClick(item: RenderItem) {
            // Requestable (not-in-library) similar titles submit an engine-proxied request.
            if (item.media.availability == AvailabilityState.REQUEST && item.media.jellyfinId == null) {
                request(item)
                return
            }

            val id = item.media.jellyfinId?.toUUIDOrNull() ?: return
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

        private fun request(item: RenderItem) {
            val tmdbId = item.media.tmdbId
            val mediaType =
                when (item.media.mediaType) {
                    MediaType.MOVIE -> "movie"
                    MediaType.SERIES -> "tv"
                    else -> null
                }
            val uid = currentUserId
            if (tmdbId == null || mediaType == null || uid == null) return

            val title = item.card.title ?: "this title"
            viewModelScope.launch {
                val result = runCatching { client.requestMedia(uid, tmdbId, mediaType, item.card.title) }.getOrNull()
                val msg =
                    when {
                        result?.success == true -> "Requested $title"
                        result != null && result.message.isNotBlank() -> result.message
                        else -> "Couldn't request $title"
                    }
                _message.emit(msg)
            }
        }
    }
