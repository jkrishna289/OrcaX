package com.github.jkrishna289.orcax.ui.engine

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.RenderBundle
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Loads the engine's personalized home bundle for the dynamic-card screen. Resolves the
 * authenticated Jellyfin user id from the session store so the engine can return a "For You"
 * row; if no user is signed in, it falls back to the engine's global (cold-start) layout.
 */
@HiltViewModel
class EngineCardPreviewViewModel
    @Inject
    constructor(
        private val client: OrcaEngineClient,
        private val preferences: DataStore<AppPreferences>,
    ) : ViewModel() {
        private val _state = MutableStateFlow<EngineCardPreviewState>(EngineCardPreviewState.Loading)
        val state: StateFlow<EngineCardPreviewState> = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                _state.value = EngineCardPreviewState.Loading

                // Resolve the active Jellyfin user id; null safely degrades to a global layout.
                val userId =
                    runCatching {
                        preferences.data.firstOrNull()?.currentUserId?.toUUIDOrNull()
                    }.getOrNull()
                if (userId == null) {
                    Timber.d("Orca Engine: no active user id; requesting non-personalized home.")
                }

                val bundle = client.getHome(userId = userId)
                _state.value =
                    if (bundle != null) {
                        EngineCardPreviewState.Success(bundle)
                    } else {
                        EngineCardPreviewState.Error
                    }
            }
        }
    }

/** UI state for [EngineCardPreviewScreen]. */
sealed interface EngineCardPreviewState {
    /** Request in flight. */
    data object Loading : EngineCardPreviewState

    /** Engine unavailable or the request failed. */
    data object Error : EngineCardPreviewState

    /** Render bundle loaded. */
    data class Success(
        val bundle: RenderBundle,
    ) : EngineCardPreviewState
}
