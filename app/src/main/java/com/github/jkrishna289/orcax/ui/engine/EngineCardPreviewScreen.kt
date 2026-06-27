package com.github.jkrishna289.orcax.ui.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.ui.cards.DynamicCardRow

/**
 * Renders an engine-provided [com.github.jkrishna289.orcax.engine.RenderBundle] entirely from
 * the server's card descriptors — proof that the app can accommodate the dynamic card layout.
 *
 * Drop this into a navigation destination to display it. Currently backed by the engine's
 * `/Cards/Preview` endpoint; swap the ViewModel's source to `/home` once that lands.
 */
@Composable
fun EngineCardPreviewScreen(
    modifier: Modifier = Modifier,
    viewModel: EngineCardPreviewViewModel = hiltViewModel(),
    onClickItem: (RenderItem) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is EngineCardPreviewState.Loading ->
            CenterMessage(text = "Loading engine layout…", modifier = modifier)

        is EngineCardPreviewState.Error ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.fillMaxSize(),
            ) {
                Button(onClick = viewModel::load) {
                    Text("Engine unavailable — retry")
                }
            }

        is EngineCardPreviewState.Success ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 24.dp),
                modifier = modifier.fillMaxSize(),
            ) {
                items(items = current.bundle.rows, key = { it.id }) { row ->
                    DynamicCardRow(
                        row = row,
                        onClickItem = onClickItem,
                    )
                }
            }
    }
}

@Composable
private fun CenterMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize().padding(24.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
