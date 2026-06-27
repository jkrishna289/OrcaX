package com.github.jkrishna289.orcax.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.services.NavDrawerService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.nav.NavDrawerItem
import com.github.jkrishna289.orcax.ui.nav.ServerNavDrawerItem
import com.github.jkrishna289.orcax.ui.theme.OrcaTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel
    @Inject
    constructor(
        navDrawerService: NavDrawerService,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        val state = navDrawerService.state

        fun onClick(item: NavDrawerItem) {
            when (item) {
                NavDrawerItem.Favorites -> navigationManager.navigateTo(Destination.Favorites)
                is ServerNavDrawerItem -> navigationManager.navigateTo(item.destination)
                else -> Unit
            }
        }
    }

/**
 * The screen behind the top-nav "Categories" (grid) icon: lists the user's libraries plus
 * Favorites (the content the old left rail used to hold), sourced from [NavDrawerService]. The
 * separate "Discover" entry is intentionally dropped — discover/requestable items are blended into
 * the engine home and searchable from the Search page.
 */
@Composable
fun CategoriesPage(
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val categories =
        remember(state) {
            (state.items + state.moreItems)
                .filter { it is ServerNavDrawerItem || it is NavDrawerItem.Favorites }
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 48.dp, end = 48.dp),
    ) {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(categories.size) { index ->
                val item = categories[index]
                CategoryTile(
                    name = item.name(context),
                    onClick = { viewModel.onClick(item) },
                )
            }
        }
    }
}

@Composable
private fun CategoryTile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = Color.White,
            ),
        modifier = modifier.fillMaxWidth().height(96.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun CategoryTilePreview() {
    OrcaTheme {
        CategoryTile(name = "Movies", onClick = {})
    }
}
