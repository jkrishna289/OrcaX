package com.github.jkrishna289.orcax.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.OneTimeLaunchedEffect
import com.github.jkrishna289.orcax.ui.components.ErrorMessage
import com.github.jkrishna289.orcax.ui.components.TabRow
import com.github.jkrishna289.orcax.ui.logTab
import com.github.jkrishna289.orcax.ui.nav.NavDrawerItem
import com.github.jkrishna289.orcax.ui.preferences.PreferencesViewModel

@Composable
fun DiscoverPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    preferencesViewModel: PreferencesViewModel = hiltViewModel(),
) {
    val rememberedTabIndex =
        remember { preferencesViewModel.getRememberedTab(preferences, NavDrawerItem.Discover.id, 0) }

    val tabs =
        listOf(
            stringResource(R.string.discover),
            stringResource(R.string.request),
        )
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(rememberedTabIndex) }
    val tabFocusRequesters = remember(tabs) { List(tabs.size) { FocusRequester() } }

    LaunchedEffect(selectedTabIndex) {
        logTab("discover", selectedTabIndex)
        preferencesViewModel.saveRememberedTab(preferences, NavDrawerItem.Discover.id, selectedTabIndex)
    }
    OneTimeLaunchedEffect { preferencesViewModel.backdropService.clearBackdrop() }

    var showHeader by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier,
    ) {
        AnimatedVisibility(
            showHeader,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier =
                    Modifier
                        .padding(start = 32.dp, top = 16.dp, bottom = 16.dp),
                tabs = tabs,
                onClick = { selectedTabIndex = it },
                focusRequesters = tabFocusRequesters,
            )
        }
        when (selectedTabIndex) {
            // Discover
            0 -> {
                SeerrDiscoverPage(
                    preferences = preferences,
                    modifier =
                        Modifier
                            .fillMaxSize(),
                )
            }

            // Requests
            1 -> {
                SeerrRequestsPage(
                    focusRequesterOnEmpty = tabFocusRequesters.getOrNull(selectedTabIndex),
                    modifier =
                        Modifier
                            .fillMaxSize(),
                )
            }

            else -> {
                ErrorMessage("Invalid tab index $selectedTabIndex", null)
            }
        }
    }
}
