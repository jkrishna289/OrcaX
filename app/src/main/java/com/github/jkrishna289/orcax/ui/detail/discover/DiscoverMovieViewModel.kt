package com.github.jkrishna289.orcax.ui.detail.discover

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.api.seerr.model.MediaRequest
import com.github.jkrishna289.orcax.api.seerr.model.MovieDetails
import com.github.jkrishna289.orcax.api.seerr.model.RelatedVideo
import com.github.jkrishna289.orcax.api.seerr.model.RequestPostRequest
import com.github.jkrishna289.orcax.R
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.DiscoverItem
import com.github.jkrishna289.orcax.data.model.DiscoverRating
import com.github.jkrishna289.orcax.data.model.RemoteTrailer
import com.github.jkrishna289.orcax.data.model.SeerrPermission
import com.github.jkrishna289.orcax.data.model.Trailer
import com.github.jkrishna289.orcax.data.model.hasPermission
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.services.BackdropService
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.TorrentPlaybackArgs
import com.github.jkrishna289.orcax.services.SeerrServerRepository
import com.github.jkrishna289.orcax.services.SeerrService
import com.github.jkrishna289.orcax.services.SeerrUserConfig
import com.github.jkrishna289.orcax.ui.isNotNullOrBlank
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import com.github.jkrishna289.orcax.ui.setValueOnMain
import com.github.jkrishna289.orcax.util.LoadingExceptionHandler
import com.github.jkrishna289.orcax.util.LoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber

@HiltViewModel(assistedFactory = DiscoverMovieViewModel.Factory::class)
class DiscoverMovieViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        @param:ApplicationContext private val context: Context,
        private val navigationManager: NavigationManager,
        private val backdropService: BackdropService,
        val serverRepository: ServerRepository,
        val seerrService: SeerrService,
        private val seerrServerRepository: SeerrServerRepository,
        private val orcaEngineClient: OrcaEngineClient,
        @Assisted val item: DiscoverItem,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(item: DiscoverItem): DiscoverMovieViewModel
        }

        val loading = MutableLiveData<LoadingState>(LoadingState.Pending)
        val movie = MutableLiveData<MovieDetails?>(null)
        val rating = MutableLiveData<DiscoverRating?>(null)

        val trailers = MutableLiveData<List<Trailer>>(listOf())
        val people = MutableLiveData<List<DiscoverItem>>(listOf())
        val similar = MutableLiveData<List<DiscoverItem>>()
        val recommended = MutableLiveData<List<DiscoverItem>>()
        val canCancelRequest = MutableStateFlow(false)

        /**
         * Whether the server offers source streaming at all. Off unless the operator enabled it, and
         * when off the entry point is not rendered — the feature stays invisible rather than showing a
         * button that explains it's unavailable.
         */
        val sourceStreamingEnabled = MutableStateFlow(false)

        val sourceSearch = MutableStateFlow<SourceSearchState>(SourceSearchState.Idle)

        val userConfig = seerrServerRepository.current.map { it?.config }
        val request4kEnabled =
            seerrServerRepository.current.map { it?.request4kMovieEnabled ?: false }

        init {
            init()
            loadSourceStreamingFlag()
        }

        private fun fetchAndSetItem(): Deferred<MovieDetails> =
            viewModelScope.async(
                Dispatchers.IO +
                    LoadingExceptionHandler(
                        loading,
                        "Error fetching movie",
                    ),
            ) {
                val movie = seerrService.api.moviesApi.movieMovieIdGet(movieId = item.id)
                this@DiscoverMovieViewModel.movie.setValueOnMain(movie)
                movie
            }

        fun init(): Job =
            viewModelScope.launch(
                Dispatchers.IO +
                    LoadingExceptionHandler(
                        loading,
                        "Error fetching movie",
                    ),
            ) {
                Timber.v("Init for movie %s", item.id)
                val movie = fetchAndSetItem().await()
                val discoveredItem = seerrService.createDiscoverItem(movie)
                backdropService.submit(discoveredItem)

                updateCanCancel()

                withContext(Dispatchers.Main) {
                    loading.value = LoadingState.Success
                }
                viewModelScope.launchIO {
                    val result = seerrService.api.moviesApi.movieMovieIdRatingsGet(movieId = item.id)
                    rating.setValueOnMain(DiscoverRating(result))
                }
                if (!similar.isInitialized) {
                    viewModelScope.launchIO {
                        val result =
                            seerrService.api.moviesApi
                                .movieMovieIdSimilarGet(movieId = item.id, page = 1)
                                .results
                                ?.map { seerrService.createDiscoverItem(it) }
                                .orEmpty()
                        similar.setValueOnMain(result)
                    }
                    viewModelScope.launchIO {
                        val result =
                            seerrService.api.moviesApi
                                .movieMovieIdRecommendationsGet(movieId = item.id, page = 1)
                                .results
                                ?.map { seerrService.createDiscoverItem(it) }
                                .orEmpty()
                        recommended.setValueOnMain(result)
                    }
                }
                val people =
                    movie.credits
                        ?.cast
                        ?.map { seerrService.createDiscoverItem(it) }
                        .orEmpty() +
                        movie.credits
                            ?.crew
                            ?.map { seerrService.createDiscoverItem(it) }
                            .orEmpty()
                this@DiscoverMovieViewModel.people.setValueOnMain(people)
                val trailers =
                    movie.relatedVideos
                        ?.filter { it.type == RelatedVideo.Type.TRAILER }
                        ?.filter { it.name.isNotNullOrBlank() && it.url.isNotNullOrBlank() }
                        ?.map {
                            RemoteTrailer(it.name!!, it.url!!, it.site)
                        }.orEmpty()
                this@DiscoverMovieViewModel.trailers.setValueOnMain(trailers)
            }

        private suspend fun updateCanCancel() {
            val user = userConfig.firstOrNull()
            val canCancel = canUserCancelRequest(user, movie.value?.mediaInfo?.requests)
            canCancelRequest.update { canCancel }
        }

        fun navigateTo(destination: Destination) {
            navigationManager.navigateTo(destination)
        }

        fun request(
            id: Int,
            is4k: Boolean,
        ) {
            viewModelScope.launchIO {
                val request =
                    seerrService.api.requestApi.requestPost(
                        RequestPostRequest(
                            is4k = is4k,
                            mediaId = id,
                            mediaType = RequestPostRequest.MediaType.MOVIE,
                        ),
                    )
                fetchAndSetItem().await()
                updateCanCancel()
            }
        }

        // ── Source streaming ────────────────────────────────────────────────
        // Nothing here runs on its own. The flag lookup is the only automatic call, and it asks the
        // server what it offers — it never touches an indexer. A search happens if and only if the
        // viewer presses the button.

        private fun loadSourceStreamingFlag() {
            viewModelScope.launchIO {
                val enabled = orcaEngineClient.getFeatures()?.sourceStreaming ?: false
                sourceStreamingEnabled.update { enabled }
            }
        }

        /** Searches for streamable sources. Explicit user action only. */
        fun findSources() {
            val details = movie.value ?: return
            val title = details.title ?: return

            sourceSearch.update { SourceSearchState.Searching }
            viewModelScope.launchIO {
                val year =
                    details.releaseDate
                        ?.takeIf { it.length >= 4 }
                        ?.substring(0, 4)
                        ?.toIntOrNull()

                val groups = orcaEngineClient.getSources(title = title, year = year)

                sourceSearch.update {
                    when {
                        // Null means the engine, the feature or the indexer config is missing — a
                        // server-setup problem, which reads differently from "nothing to play".
                        groups == null ->
                            SourceSearchState.Failed(
                                R.string.sources_unavailable_message,
                                canRetry = false,
                            )

                        groups.all.isEmpty() -> SourceSearchState.NoSources

                        else -> SourceSearchState.Found(groups)
                    }
                }
            }
        }

        /**
         * Opens a stream for the chosen source and hands it to the normal player.
         *
         * Slow by nature — torrent metadata has to arrive and the head pieces have to buffer before
         * playback can start — hence the explicit [SourceSearchState.Opening] state rather than a
         * silent wait.
         */
        fun playSource(source: TorrentSourceDto) {
            sourceSearch.update { SourceSearchState.Opening(source) }
            viewModelScope.launchIO {
                val stream = orcaEngineClient.openStreamSession(sourceId = source.id)
                if (stream == null) {
                    // Keep the picker open so the viewer can simply choose another source, which is
                    // the useful next step when one swarm turns out to be dead.
                    sourceSearch.update { SourceSearchState.Failed(R.string.sources_failed_message, canRetry = true) }
                    return@launchIO
                }

                sourceSearch.update { SourceSearchState.Idle }
                withContext(Dispatchers.Main) {
                    navigationManager.navigateTo(
                        Destination.Playback(
                            // No Jellyfin item exists for this; a fresh id per session also keeps it
                            // out of the resume and next-up tables, which are keyed on real ids.
                            itemId = UUID.randomUUID(),
                            positionMs = 0,
                            torrent =
                                TorrentPlaybackArgs(
                                    url = stream.url,
                                    title = movie.value?.title ?: source.title,
                                    fileName = stream.fileName,
                                    sizeBytes = stream.sizeBytes,
                                    mediaInfo = stream.mediaInfo,
                                ),
                        ),
                    )
                }
            }
        }

        /** Reopens the ranked list after a failed pick, so a dead source isn't a dead end. */
        fun retrySourceSelection() {
            findSources()
        }

        fun dismissSources() {
            sourceSearch.update { SourceSearchState.Idle }
        }

        fun showAllSources() {
            sourceSearch.update { current ->
                if (current is SourceSearchState.Found) current.copy(showAll = true) else current
            }
        }

        fun toggleSourceDetails() {
            sourceSearch.update { current ->
                if (current is SourceSearchState.Found) {
                    current.copy(expandedDetails = !current.expandedDetails)
                } else {
                    current
                }
            }
        }

        fun cancelRequest(id: Int) {
            viewModelScope.launchIO {
                movie.value?.mediaInfo?.requests?.firstOrNull()?.let {
                    // TODO handle multiple requests? Or just delete self's request?
                    seerrService.api.requestApi.requestRequestIdDelete(it.id.toString())
                    fetchAndSetItem().await()
                    updateCanCancel()
                }
            }
        }
    }

fun canUserCancelRequest(
    user: SeerrUserConfig?,
    requests: List<MediaRequest>?,
) = (user.hasPermission(SeerrPermission.MANAGE_REQUESTS) && requests?.isNotEmpty() == true) ||
    (
        // User requested this
        user.hasPermission(SeerrPermission.REQUEST) &&
            requests?.any { it.requestedBy?.id == user?.id } == true
    )
