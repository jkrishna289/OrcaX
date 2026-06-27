package com.github.jkrishna289.orcax.ui.preferences.subtitle

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.github.jkrishna289.orcax.preferences.AppPreference
import com.github.jkrishna289.orcax.preferences.SubtitlePreferences
import com.github.jkrishna289.orcax.preferences.resetSubtitles
import com.github.jkrishna289.orcax.ui.ifElse
import com.github.jkrishna289.orcax.ui.preferences.ClickPreference
import com.github.jkrishna289.orcax.ui.preferences.ComposablePreference
import com.github.jkrishna289.orcax.ui.preferences.PreferenceGroup
import com.github.jkrishna289.orcax.ui.preferences.PreferenceValidation
import com.github.jkrishna289.orcax.ui.preferences.PreferencesViewModel
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import com.github.jkrishna289.orcax.util.ExceptionHandler
import kotlinx.coroutines.launch

@Composable
fun SubtitlePreferencesContent(
    title: String,
    preferences: SubtitlePreferences,
    prefList: List<PreferenceGroup<SubtitlePreferences>>,
    onPreferenceChange: suspend (SubtitlePreferences) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PreferencesViewModel = hiltViewModel(),
    onFocus: (Int, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var focusedIndex by rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val state = rememberLazyListState()

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Forces the animated to trigger
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
        modifier = modifier,
    ) {
        LaunchedEffect(Unit) {
            focusRequester.tryRequestFocus()
        }
        LazyColumn(
            state = state,
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        ) {
            stickyHeader {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                )
            }
            prefList.forEachIndexed { groupIndex, group ->
                item {
                    Text(
                        text = stringResource(group.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                val groupPreferences =
                    group.preferences +
                        group.conditionalPreferences
                            .filter { it.condition.invoke(preferences) }
                            .map { it.preferences }
                            .flatten()
                groupPreferences.forEachIndexed { prefIndex, pref ->
                    pref as AppPreference<SubtitlePreferences, Any>
                    item {
                        val interactionSource = remember { MutableInteractionSource() }
                        val focused = interactionSource.collectIsFocusedAsState().value
                        LaunchedEffect(focused) {
                            if (focused) {
                                focusedIndex = Pair(groupIndex, prefIndex)
                                onFocus.invoke(groupIndex, prefIndex)
                            }
                        }
                        when (pref) {
                            SubtitleSettings.Reset -> {
                                ClickPreference(
                                    title = stringResource(pref.title),
                                    onClick = {
                                        scope.launch(ExceptionHandler()) {
                                            val newValue =
                                                SubtitlePreferences
                                                    .newBuilder()
                                                    .apply { resetSubtitles() }
                                                    .build()
                                            onPreferenceChange.invoke(newValue)
                                        }
                                    },
                                    interactionSource = interactionSource,
                                )
                            }

                            else -> {
                                val value = pref.getter.invoke(preferences)
                                ComposablePreference(
                                    preference = pref,
                                    value = value,
                                    onNavigate = viewModel.navigationManager::navigateTo,
                                    onValueChange = { newValue ->
                                        val validation = pref.validate(newValue)
                                        when (validation) {
                                            is PreferenceValidation.Invalid -> {
                                                // TODO?
                                                Toast
                                                    .makeText(
                                                        context,
                                                        validation.message,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                            }

                                            PreferenceValidation.Valid -> {
                                                scope.launch(ExceptionHandler()) {
                                                    onPreferenceChange.invoke(
                                                        pref.setter(
                                                            preferences,
                                                            newValue,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    interactionSource = interactionSource,
                                    modifier =
                                        Modifier
                                            .ifElse(
                                                groupIndex == focusedIndex.first && prefIndex == focusedIndex.second,
                                                Modifier.focusRequester(focusRequester),
                                            ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
