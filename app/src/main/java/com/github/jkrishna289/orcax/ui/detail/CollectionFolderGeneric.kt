package com.github.jkrishna289.orcax.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.jkrishna289.orcax.data.filter.DefaultFilterOptions
import com.github.jkrishna289.orcax.data.filter.ItemFilterBy
import com.github.jkrishna289.orcax.data.model.CollectionFolderFilter
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.components.CollectionFolderGrid
import com.github.jkrishna289.orcax.ui.components.ViewOptionsPoster
import com.github.jkrishna289.orcax.ui.components.ViewOptionsWide
import com.github.jkrishna289.orcax.ui.data.VideoSortOptions
import com.github.jkrishna289.orcax.ui.preferences.PreferencesViewModel
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

@Composable
fun CollectionFolderGeneric(
    preferences: UserPreferences,
    itemId: UUID,
    usePosters: Boolean,
    recursive: Boolean,
    playEnabled: Boolean,
    modifier: Modifier = Modifier,
    filter: CollectionFolderFilter = CollectionFolderFilter(),
    filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    sortOptions: List<ItemSortBy> = VideoSortOptions,
    preferencesViewModel: PreferencesViewModel = hiltViewModel(),
) {
    var showHeader by remember { mutableStateOf(true) }
    val viewOptions =
        remember(usePosters) {
            if (usePosters) {
                ViewOptionsPoster
            } else {
                ViewOptionsWide
            }
        }
    CollectionFolderGrid(
        preferences = preferences,
        onClickItem = { index, item ->
            preferencesViewModel.navigationManager.navigateTo(item.destination(index))
        },
        itemId = itemId,
        initialFilter = filter,
        showTitle = showHeader,
        recursive = recursive,
        sortOptions = sortOptions,
        modifier = modifier,
        positionCallback = { columns, position ->
            showHeader = position < columns
        },
        defaultViewOptions = viewOptions,
        playEnabled = playEnabled,
        filterOptions = filterOptions,
    )
}
