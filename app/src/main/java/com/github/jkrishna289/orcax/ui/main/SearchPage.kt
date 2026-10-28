package com.github.jkrishna289.orcax.ui.main

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.preferences.UserPreferences
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.cards.DynamicCardRow
import com.github.jkrishna289.orcax.ui.AspectRatios
import com.github.jkrishna289.orcax.ui.Cards
import com.github.jkrishna289.orcax.ui.SlimItemFields
import com.github.jkrishna289.orcax.ui.cards.EpisodeCard
import com.github.jkrishna289.orcax.ui.cards.ItemRow
import com.github.jkrishna289.orcax.ui.cards.SeasonCard
import com.github.jkrishna289.orcax.ui.components.SearchEditTextBox
import com.github.jkrishna289.orcax.ui.components.VoiceInputManager
import com.github.jkrishna289.orcax.ui.components.VoiceSearchButton
import com.github.jkrishna289.orcax.ui.data.RowColumn
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.onMain
import com.github.jkrishna289.orcax.ui.rememberPosition
import com.github.jkrishna289.orcax.ui.setValueOnMain
import com.github.jkrishna289.orcax.ui.tryRequestFocus
import com.github.jkrishna289.orcax.util.ApiRequestPager
import com.github.jkrishna289.orcax.util.ExceptionHandler
import com.github.jkrishna289.orcax.util.GetItemsRequestHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        val api: ApiClient,
        val navigationManager: NavigationManager,
        val voiceInputManager: VoiceInputManager,
        private val engine: OrcaEngineClient,
    ) : ViewModel() {
        val voiceState = voiceInputManager.state
        val soundLevel = voiceInputManager.soundLevel
        val partialResult = voiceInputManager.partialResult

        val movies = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val series = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val episodes = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val collections = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val albums = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val artists = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val songs = MutableLiveData<SearchResult>(SearchResult.NoQuery)

        /** Titles the server doesn't have yet, from the engine, so they can be found and requested. */
        val notInLibrary = MutableLiveData<SearchResult>(SearchResult.NoQuery)

        private var currentQuery: String? = null
        private var notInLibraryJob: Job? = null

        private val semaphore = Semaphore(4)

        fun search(query: String?) {
            if (currentQuery == query) {
                return
            }
            currentQuery = query
            if (query.isNotNullOrBlank()) {
                movies.value = SearchResult.Searching
                series.value = SearchResult.Searching
                episodes.value = SearchResult.Searching
                collections.value = SearchResult.Searching
                searchInternal(query, BaseItemKind.MOVIE, movies)
                searchInternal(query, BaseItemKind.SERIES, series)
                searchInternal(query, BaseItemKind.EPISODE, episodes)
                searchInternal(query, BaseItemKind.MUSIC_ALBUM, albums)
                searchInternal(query, BaseItemKind.MUSIC_ARTIST, artists)
                searchInternal(query, BaseItemKind.AUDIO, songs)
                searchInternal(query, BaseItemKind.BOX_SET, collections)
                searchNotInLibrary(query)
            } else {
                movies.value = SearchResult.NoQuery
                series.value = SearchResult.NoQuery
                episodes.value = SearchResult.NoQuery
                collections.value = SearchResult.NoQuery
                notInLibraryJob?.cancel()
                notInLibrary.value = SearchResult.NoQuery
            }
        }

        private fun searchNotInLibrary(query: String) {
            notInLibraryJob?.cancel()
            notInLibrary.value = SearchResult.Searching
            notInLibraryJob =
                viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
                    // No engine (or one without search) hides the row rather than showing an error.
                    val row = engine.searchTitles(query)?.row
                    withContext(Dispatchers.Main) {
                        notInLibrary.value = row?.let { SearchResult.Titles(it) } ?: SearchResult.NoQuery
                    }
                }
        }

        fun openTitle(item: RenderItem) = navigationManager.navigateTo(Destination.EngineItem(item))

        private fun searchInternal(
            query: String,
            type: BaseItemKind,
            target: MutableLiveData<SearchResult>,
        ) {
            viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
                try {
                    semaphore.withPermit {
                        val request =
                            GetItemsRequest(
                                searchTerm = query,
                                recursive = true,
                                includeItemTypes = listOf(type),
                                fields = SlimItemFields,
                                limit = 25,
                            )
                        val pager =
                            ApiRequestPager(api, request, GetItemsRequestHandler, viewModelScope)
                        pager.init()
                        withContext(Dispatchers.Main) {
                            target.value = SearchResult.Success(pager)
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception searching for $type")
                    withContext(Dispatchers.Main) {
                        target.value = SearchResult.Error(ex)
                    }
                }
            }
        }

        init {
            addCloseable(voiceInputManager)
        }

        fun getHints(query: String) {
            // TODO
//        api.searchApi.getSearchHints()
        }
    }

sealed interface SearchResult {
    data object NoQuery : SearchResult

    data object Searching : SearchResult

    data class Error(
        val ex: Exception,
    ) : SearchResult

    data class Success(
        val items: List<BaseItem?>,
    ) : SearchResult

    /** Engine titles outside the library. */
    data class Titles(
        val row: RenderRow,
    ) : SearchResult
}

/** Whether a finished search has something to focus. */
private val SearchResult.hasResults: Boolean
    get() =
        when (this) {
            is SearchResult.Success -> items.isNotEmpty()
            is SearchResult.Titles -> row.items.isNotEmpty()
            else -> false
        }

private const val SEARCH_ROW = 0
private const val MOVIE_ROW = SEARCH_ROW + 1
private const val SERIES_ROW = MOVIE_ROW + 1
private const val NOT_IN_LIBRARY_ROW = SERIES_ROW + 1
private const val EPISODE_ROW = NOT_IN_LIBRARY_ROW + 1
private const val ALBUM_ROW = EPISODE_ROW + 1
private const val ARTIST_ROW = ALBUM_ROW + 1
private const val SONG_ROW = ARTIST_ROW + 1
private const val COLLECTION_ROW = SONG_ROW + 1

/** Delay for focus to settle after voice search dialog dismisses. */
private const val VOICE_RESULT_FOCUS_DELAY_MS = 350L

@Composable
fun SearchPage(
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val movies by viewModel.movies.observeAsState(SearchResult.NoQuery)
    val collections by viewModel.collections.observeAsState(SearchResult.NoQuery)
    val series by viewModel.series.observeAsState(SearchResult.NoQuery)
    val episodes by viewModel.episodes.observeAsState(SearchResult.NoQuery)
    val albums by viewModel.albums.observeAsState(SearchResult.NoQuery)
    val artists by viewModel.artists.observeAsState(SearchResult.NoQuery)
    val songs by viewModel.songs.observeAsState(SearchResult.NoQuery)
    val notInLibrary by viewModel.notInLibrary.observeAsState(SearchResult.NoQuery)

//    val query = rememberTextFieldState()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequesters = remember { List(COLLECTION_ROW + 1) { FocusRequester() } }

    var position by rememberPosition(0, 0)
    var searchClicked by rememberSaveable { mutableStateOf(false) }
    var immediateSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            viewModel.voiceInputManager.stopListening()
        }
    }

    fun triggerImmediateSearch(searchQuery: String) {
        immediateSearchQuery = searchQuery
        searchClicked = true
        viewModel.search(searchQuery)
    }

    LaunchedEffect(query) {
        when {
            immediateSearchQuery == query -> {
                immediateSearchQuery = null
            }

            else -> {
                delay(750L)
                viewModel.search(query)
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequesters.getOrNull(position.row)?.tryRequestFocus()
    }
    val onClickItem = { index: Int, item: BaseItem ->
        viewModel.navigationManager.navigateTo(item.destination())
    }

    LaunchedEffect(searchClicked, movies, series, notInLibrary, episodes, albums, artists, songs, collections) {
        if (!searchClicked) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            // Want to focus on the first row with results after all of the ones before it are finished
            // searching (an empty row has nothing to take focus, which left a not-owned film unfocused).
            // Index i is row i + 1 (row 0 is the search bar), so this must stay in on-screen row order.
            val results = listOf(movies, series, notInLibrary, episodes, albums, artists, songs, collections)
            val firstSuccess =
                results.indexOfFirst { it.hasResults }
            if (firstSuccess >= 0) {
                val anyBeforeSearching =
                    results.subList(0, firstSuccess).any { it is SearchResult.Searching }
                if (!anyBeforeSearching) {
                    position = RowColumn(firstSuccess + 1, 0)
                    onMain { focusRequesters[firstSuccess + 1].tryRequestFocus() }
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.focusGroup(),
    ) {
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                var isSearchActive by remember { mutableStateOf(false) }
                var isTextFieldFocused by remember { mutableStateOf(false) }
                val textFieldFocusRequester = remember { FocusRequester() }

                BackHandler(isTextFieldFocused) {
                    when {
                        isSearchActive -> {
                            isSearchActive = false
                            keyboardController?.hide()
                        }

                        else -> {
                            focusManager.moveFocus(FocusDirection.Next)
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .focusGroup()
                            .focusRestorer(textFieldFocusRequester),
                ) {
                    VoiceSearchButton(
                        onSpeechResult = { spokenText ->
                            query = spokenText
                            triggerImmediateSearch(spokenText)
                        },
                        voiceInputManager = viewModel.voiceInputManager,
                    )

                    SearchEditTextBox(
                        value = query,
                        onValueChange = {
                            isSearchActive = true
                            query = it
                        },
                        onSearchClick = { triggerImmediateSearch(query) },
                        readOnly = !isSearchActive,
                        modifier =
                            Modifier
                                .focusRequester(textFieldFocusRequester)
                                // The page opens here, not on the mic beside it (a requester on the
                                // row landed on its first child, so typing went nowhere and OK
                                // started voice search).
                                .focusRequester(focusRequesters[SEARCH_ROW])
                                .onFocusChanged { state ->
                                    isTextFieldFocused = state.isFocused
                                    if (!state.isFocused) isSearchActive = false
                                }.onPreviewKeyEvent { event ->
                                    val isActivationKey =
                                        event.key in listOf(Key.DirectionCenter, Key.Enter)
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        // The text field eats Down as a cursor move, so focus could never reach the results
                                        focusManager.moveFocus(FocusDirection.Down)
                                    } else if (event.type == KeyEventType.KeyUp && isActivationKey && !isSearchActive) {
                                        isSearchActive = true
                                        keyboardController?.show()
                                        true
                                    } else {
                                        false
                                    }
                                },
                    )
                }
            }
        }
        searchResultRow(
            title = R.string.movies,
            result = movies,
            rowIndex = MOVIE_ROW,
            position = position,
            focusRequester = focusRequesters[MOVIE_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
        searchResultRow(
            title = R.string.tv_shows,
            result = series,
            rowIndex = SERIES_ROW,
            position = position,
            focusRequester = focusRequesters[SERIES_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
        notInLibraryRow(
            result = notInLibrary,
            focusRequester = focusRequesters[NOT_IN_LIBRARY_ROW],
            onClickItem = {
                position = RowColumn(NOT_IN_LIBRARY_ROW, 0)
                viewModel.openTitle(it)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        searchResultRow(
            title = R.string.episodes,
            result = episodes,
            rowIndex = EPISODE_ROW,
            position = position,
            focusRequester = focusRequesters[EPISODE_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
            cardContent = @Composable { index, item, mod, onClick, onLongClick ->
                EpisodeCard(
                    item = item,
                    onClick = {
                        position = RowColumn(EPISODE_ROW, index)
                        onClick.invoke()
                    },
                    onLongClick = onLongClick,
                    imageHeight = 140.dp,
                    showImageOverlay = true,
                    modifier = mod.padding(horizontal = 8.dp),
                )
            },
        )
        searchResultRow(
            title = R.string.albums,
            result = albums,
            rowIndex = ALBUM_ROW,
            position = position,
            focusRequester = focusRequesters[ALBUM_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
            cardContent = { index, item, mod, onClick, onLongClick ->
                SeasonCard(
                    item = item,
                    onClick = {
                        position = RowColumn(ALBUM_ROW, index)
                        onClick.invoke()
                    },
                    onLongClick = onLongClick,
                    imageHeight = Cards.heightEpisode,
                    aspectRatio = AspectRatios.SQUARE,
                    showImageOverlay = true,
                    modifier = mod,
                )
            },
        )
        searchResultRow(
            title = R.string.artists,
            result = artists,
            rowIndex = ARTIST_ROW,
            position = position,
            focusRequester = focusRequesters[ARTIST_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
            cardContent = { index, item, mod, onClick, onLongClick ->
                SeasonCard(
                    item = item,
                    onClick = {
                        position = RowColumn(ARTIST_ROW, index)
                        onClick.invoke()
                    },
                    onLongClick = onLongClick,
                    imageHeight = Cards.heightEpisode,
                    aspectRatio = AspectRatios.SQUARE,
                    showImageOverlay = true,
                    modifier = mod,
                )
            },
        )
        searchResultRow(
            title = R.string.songs,
            result = songs,
            rowIndex = SONG_ROW,
            position = position,
            focusRequester = focusRequesters[SONG_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
            cardContent = { index, item, mod, onClick, onLongClick ->
                SeasonCard(
                    item = item,
                    onClick = {
                        position = RowColumn(SONG_ROW, index)
                        onClick.invoke()
                    },
                    onLongClick = onLongClick,
                    imageHeight = Cards.heightEpisode,
                    aspectRatio = AspectRatios.SQUARE,
                    showImageOverlay = true,
                    modifier = mod,
                )
            },
        )
        searchResultRow(
            title = R.string.collections,
            result = collections,
            rowIndex = COLLECTION_ROW,
            position = position,
            focusRequester = focusRequesters[COLLECTION_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun LazyListScope.searchResultRow(
    @StringRes title: Int,
    result: SearchResult,
    rowIndex: Int,
    position: RowColumn,
    focusRequester: FocusRequester,
    onClickItem: (Int, BaseItem) -> Unit,
    onClickPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (
        index: Int,
        item: BaseItem?,
        modifier: Modifier,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit = @Composable { index, item, mod, onClick, onLongClick ->
        SeasonCard(
            item = item,
            onClick = {
                onClickPosition.invoke(RowColumn(rowIndex, index))
                onClick.invoke()
            },
            onLongClick = onLongClick,
            imageHeight = Cards.height2x3,
            showImageOverlay = true,
            modifier = mod,
        )
    },
) {
    item {
        when (val r = result) {
            is SearchResult.Error -> {
                SearchResultPlaceholder(
                    title = stringResource(title),
                    message = r.ex.localizedMessage ?: "Error occurred during search",
                    messageColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier,
                )
            }

            SearchResult.NoQuery, is SearchResult.Titles -> {
                // no-op
            }

            SearchResult.Searching -> {
                SearchResultPlaceholder(
                    title = stringResource(title),
                    message = stringResource(R.string.searching),
                    modifier = modifier,
                )
            }

            is SearchResult.Success -> {
                if (r.items.isEmpty()) {
                    SearchResultPlaceholder(
                        title = stringResource(title),
                        message = stringResource(R.string.no_results),
                        modifier = modifier,
                    )
                } else {
                    ItemRow(
                        title = stringResource(title),
                        items = r.items,
                        onClickItem = onClickItem,
                        onLongClickItem = { _, _ -> },
                        modifier = modifier.focusRequester(focusRequester),
                        cardContent = cardContent,
                    )
                }
            }
        }
    }
}

/** Titles matching the search that the server doesn't have, opening their not-yet-requested page. */
fun LazyListScope.notInLibraryRow(
    result: SearchResult,
    focusRequester: FocusRequester,
    onClickItem: (RenderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    item {
        val title = stringResource(R.string.not_in_your_library)
        when (val r = result) {
            SearchResult.Searching ->
                SearchResultPlaceholder(title = title, message = stringResource(R.string.searching), modifier = modifier)

            is SearchResult.Titles ->
                if (r.row.items.isEmpty()) {
                    SearchResultPlaceholder(title = title, message = stringResource(R.string.no_results), modifier = modifier)
                } else {
                    DynamicCardRow(
                        row = r.row.copy(title = title),
                        onClickItem = onClickItem,
                        focusRequester = focusRequester,
                        modifier = modifier,
                    )
                }

            else -> Unit
        }
    }
}

@Composable
fun SearchResultPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    messageColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(bottom = 32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = messageColor,
        )
    }
}
