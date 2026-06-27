package com.github.jkrishna289.orcax.ui.detail.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.HomeRowViewOptions
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.ui.AspectRatio
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.data.RowColumn
import com.github.jkrishna289.orcax.ui.main.HomePageContent
import com.github.jkrishna289.orcax.ui.rememberPosition
import com.github.jkrishna289.orcax.util.HomeRowLoadingState
import org.jellyfin.sdk.model.api.BaseItemKind

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionRows(
    preferences: UserPreferences,
    state: CollectionState,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (RowColumn, BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    onFocusPosition: (RowColumn) -> Unit = {},
) {
    var position by rememberPosition(0, 0)

    Box(modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
        ) {
            val cardViewOptions = state.viewOptions.cardViewOptions
            val homeRows =
                remember(state.separateItems, cardViewOptions) {
                    state.separateItems.map { (type, row) ->
                        if (row is HomeRowLoadingState.Success) {
                            // TODO not great to do this in the UI
                            val viewOptions =
                                if (type == BaseItemKind.EPISODE) {
                                    HomeRowViewOptions(
                                        heightDp = Cards.HEIGHT_EPISODE,
                                        episodeAspectRatio = AspectRatio.WIDE,
                                        showTitles = cardViewOptions.showTitles,
                                        useSeries = false,
                                    )
                                } else {
                                    HomeRowViewOptions(
                                        showTitles = cardViewOptions.showTitles,
                                    )
                                }
                            row.copy(viewOptions = viewOptions)
                        } else {
                            row
                        }
                    }
                }
            HomePageContent(
                homeRows = homeRows,
                position = position,
                onFocusPosition = { newPosition ->
                    position = newPosition
                    onFocusPosition.invoke(newPosition)
                },
                onClickItem = onClickItem,
                onLongClickItem = onLongClickItem,
                onClickPlay = onClickPlay,
                showClock = false,
                onUpdateBackdrop = {},
                headerComposable = {},
                takeFocus = false,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                modifier = Modifier,
            )
        }
    }
}
