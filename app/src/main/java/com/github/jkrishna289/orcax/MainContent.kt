package com.github.jkrishna289.orcax

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.ScreensaverService
import com.github.jkrishna289.orcax.services.SetupDestination
import com.github.jkrishna289.orcax.ui.components.AppScreensaver
import com.github.jkrishna289.orcax.ui.nav.ApplicationContent
import com.github.jkrishna289.orcax.ui.setup.SwitchServerContent
import com.github.jkrishna289.orcax.ui.setup.SwitchUserContent

@Composable
fun MainContent(
    backStack: MutableList<SetupDestination>,
    navigationManager: NavigationManager,
    appPreferences: AppPreferences,
    backdropService: BackdropService,
    screensaverService: ScreensaverService,
    modifier: Modifier = Modifier,
) {
    val preferences by rememberUpdatedState(UserPreferences(appPreferences))
    Surface(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
        shape = RectangleShape,
    ) {
//                            val backStack = rememberNavBackStack(SetupDestination.Loading)
//                            setupNavigationManager.backStack = backStack
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { key ->
                key as SetupDestination
                NavEntry(key) {
                    when (key) {
                        SetupDestination.Loading -> {
                            Box(
                                modifier = Modifier.size(200.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.border,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }

                        SetupDestination.ServerList -> {
                            SwitchServerContent(Modifier.fillMaxSize())
                        }

                        is SetupDestination.UserList -> {
                            SwitchUserContent(
                                currentServer = key.server,
                                Modifier.fillMaxSize(),
                            )
                        }

                        is SetupDestination.AppContent -> {
                            LaunchedEffect(Unit) {
                                backdropService.clearBackdrop()
                            }
                            val current = key.current
                            var showContent by remember {
                                mutableStateOf(true)
                            }
                            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                                if (!appPreferences.signInAutomatically) {
                                    showContent = false
                                }
                            }

                            if (showContent) {
                                ApplicationContent(
                                    user = current.user,
                                    server = current.server,
                                    navigationManager = navigationManager,
                                    preferences = preferences,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(200.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.border,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
        val screenSaverState by screensaverService.state.collectAsState()
        if (screenSaverState.enabled || screenSaverState.enabledTemp) {
            AnimatedVisibility(
                screenSaverState.show,
                Modifier.fillMaxSize(),
            ) {
                AppScreensaver(appPreferences, Modifier.fillMaxSize())
            }
        }
    }
}
