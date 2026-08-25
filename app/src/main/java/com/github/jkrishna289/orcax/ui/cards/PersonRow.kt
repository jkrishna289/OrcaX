package com.github.jkrishna289.orcax.ui.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.DiscoverItem
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.ui.ifElse
import com.github.jkrishna289.orcax.ui.rememberInt

@Composable
fun PersonRow(
    people: List<Person>,
    onClick: (Person) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int = R.string.people,
    onLongClick: ((Int, Person) -> Unit)? = null,
    compact: Boolean = false,
) {
    val firstFocus = remember { FocusRequester() }
    var position by rememberInt()
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        modifier = modifier,
    ) {
        Text(
            text = if (compact) stringResource(title).uppercase() else stringResource(title),
            style =
                if (compact) {
                    MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp)
                } else {
                    MaterialTheme.typography.titleLarge
                },
            color =
                MaterialTheme.colorScheme.onBackground.let {
                    if (compact) it.copy(alpha = .55f) else it
                },
            modifier = Modifier.padding(start = 8.dp),
        )
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ifElse(compact, Modifier.fadeRightEdge())
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(people) { index, person ->
                PersonCard(
                    person = person,
                    onClick = {
                        position = index
                        onClick.invoke(person)
                    },
                    onLongClick = {
                        position = index
                        onLongClick?.invoke(index, person)
                    },
                    modifier =
                        Modifier
                            .width(if (compact) compactPersonCardWidth else personRowCardWidth)
                            .ifElse(index == position, Modifier.focusRequester(firstFocus))
                            .animateItem(),
                )
            }
        }
    }
}

/**
 * Fades the last ~13% of the row's right edge to transparent, so the hero's cast peek window reads
 * as "more cards this way" instead of ending at a hard clip (Movie Details v2 spec).
 */
private fun Modifier.fadeRightEdge(): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0.87f to Color.Black,
                                1f to Color.Transparent,
                            ),
                    ),
                blendMode = BlendMode.DstIn,
            )
        }

@Composable
fun DiscoverPersonRow(
    people: List<DiscoverItem>,
    onClick: (DiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int = R.string.people,
    onLongClick: ((Int, DiscoverItem) -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier =
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(people) { index, person ->
                PersonCard(
                    name = person.title,
                    role = person.subtitle,
                    imageUrl = person.posterUrl,
                    favorite = false,
                    onClick = { onClick.invoke(person) },
                    onLongClick = { onLongClick?.invoke(index, person) },
                    modifier =
                        Modifier
                            .width(personRowCardWidth)
                            .ifElse(index == 0, Modifier.focusRequester(firstFocus))
                            .animateItem(),
                )
            }
        }
    }
}

val personRowCardWidth = 108.dp

/** Cast peek row inside the movie-details hero: ~130px avatars at 1080p (Movie Details v2 spec). */
private val compactPersonCardWidth = 65.dp
