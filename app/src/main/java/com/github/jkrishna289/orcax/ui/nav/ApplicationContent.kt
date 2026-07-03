package com.github.jkrishna289.orcax.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.github.jkrishna289.orcax.data.model.JellyfinServer
import com.github.jkrishna289.orcax.data.model.JellyfinUser
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.ui.components.ExitConfirmDialog
import com.github.jkrishna289.orcax.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Top scrim configuration for text readability (clock, season tabs)
const val TOP_SCRIM_ALPHA = 0.55f
const val TOP_SCRIM_END_FRACTION = 0.25f // Fraction of backdrop image height

@HiltViewModel
class ApplicationContentViewModel
    @Inject
    constructor(
        val backdropService: BackdropService,
    ) : ViewModel() {
        fun clearBackdrop() {
            viewModelScope.launchIO { backdropService.clearBackdrop() }
        }
    }

/**
 * This is generally the root composable of the app.
 *
 * The left navigation rail has been retired in favour of the in-screen top navigation bar
 * (see [com.github.jkrishna289.orcax.ui.main.TopNavBar], hosted by the engine home). Every
 * destination now renders [DestinationContent] full-size; Home is the hub and other screens are
 * reached from the top-nav and returned-from via Back (Apple-TV push model).
 */
@Composable
fun ApplicationContent(
    server: JellyfinServer,
    user: JellyfinUser,
    navigationManager: NavigationManager,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    enableTopScrim: Boolean = true,
    viewModel: ApplicationContentViewModel = hiltViewModel(),
) {
    Box(
        modifier = modifier,
    ) {
        val backdropStyle = preferences.appPreferences.interfacePreferences.backdropStyle
        Backdrop(
            drawerIsOpen = false,
            backdropStyle = backdropStyle,
            enableTopScrim = enableTopScrim,
            viewModel = viewModel,
        )
        // Back at the root of the stack (Home, at the top) → confirm before quitting. Screens
        // deeper in the composition (e.g. the home's scrolled-rows Back intercept) register their
        // handlers later and therefore take precedence while enabled.
        val activity = LocalActivity.current
        var showExitDialog by remember { mutableStateOf(false) }
        BackHandler(enabled = navigationManager.backStack.size <= 1) { showExitDialog = true }
        NavDisplay(
            backStack = navigationManager.backStack,
            onBack = { navigationManager.goBack() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { key ->
                val destination = key as Destination
                val contentKey = "${destination}_${server.id}_${user.id}"
                NavEntry(key, contentKey = contentKey) {
                    DestinationContent(
                        destination = destination,
                        preferences = preferences,
                        onClearBackdrop = viewModel::clearBackdrop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
        if (showExitDialog) {
            ExitConfirmDialog(
                onExit = { activity?.finish() },
                onDismiss = { showExitDialog = false },
            )
        }
    }
}
