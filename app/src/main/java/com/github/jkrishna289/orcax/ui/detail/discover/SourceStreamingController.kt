package com.github.jkrishna289.orcax.ui.detail.discover

import android.os.SystemClock
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.StreamWatchTracker
import com.github.jkrishna289.orcax.services.torrent.TorrentPlaybackArgs
import com.github.jkrishna289.orcax.ui.launchIO
import com.github.jkrishna289.orcax.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * The whole find-a-stream-and-play-it flow: search, pick, connect, escalate, hand to the player.
 *
 * Deliberately knows nothing about Jellyseerr. The flow has no request-service dependency of its own —
 * it only ever appeared to, because the one screen that could host its entry point happened to be
 * Seerr-backed. Keeping it separate means the state machine can be driven from anywhere that has a
 * title and a year, which is what makes it testable at all.
 *
 * [scope] is the owner's lifecycle: everything started here dies with it.
 */
class SourceStreamingController
    @Inject
    constructor(
        private val orcaEngineClient: OrcaEngineClient,
        private val navigationManager: NavigationManager,
        private val streamWatchTracker: StreamWatchTracker,
    ) {
        private lateinit var scope: CoroutineScope

        val state = MutableStateFlow<SourceSearchState>(SourceSearchState.Idle)

        /**
         * Whether the server offers source streaming at all. Off unless the operator enabled it, and
         * when off the entry point is not rendered — the feature stays invisible rather than showing a
         * button that explains it's unavailable.
         */
        val enabled = MutableStateFlow(false)

        /** What is being searched for. Set by [start]; used for the film's name on every screen. */
        var title: String = ""
            private set

        private var year: Int? = null

        /**
         * What kind of thing is being searched for. Sent to the engine, which searches `tv` and
         * `movie` differently — a series searched as a movie reliably returns nothing at all.
         */
        private var mediaType: MediaType = MediaType.MOVIE

        /**
         * Every source the last search returned, highest resolution first — the picker's display
         * order, and the order [nextStream] walks when a stream has to be replaced.
         */
        private var candidates: List<TorrentSourceDto> = emptyList()

        /** Sources already attempted this session; never offered again as "the next stream". */
        private val triedSourceIds = mutableSetOf<String>()

        /**
         * The one thing this controller has in flight: a search, or a stream being opened.
         *
         * Owning it is what makes leaving the flow mean anything. Opening a stream is now a poll loop
         * with no deadline, so an unowned one waits out a swarm the viewer walked away from and then
         * launches the player over whatever they went to instead — the connect screen is modal, and
         * BACK has to end the attempt rather than just hide it. Every exit cancels this; only one
         * runs at a time, so starting either kind cancels whatever was already there.
         */
        private var job: Job? = null

        /**
         * Binds the controller to its owner's scope and subject. The flag lookup is the only automatic
         * call this class ever makes, and it asks the server what it offers — it never touches an
         * indexer. A search happens if and only if the viewer presses the button.
         */
        fun attach(
            scope: CoroutineScope,
            title: String,
            year: Int?,
            mediaType: MediaType = MediaType.MOVIE,
        ) {
            this.scope = scope
            this.title = title
            this.year = year
            this.mediaType = mediaType
            scope.launchIO {
                // Series stay hidden. Nothing on a details page has picked an episode, and the
                // engine's file selector needs one — handed a season pack without it, it plays
                // whichever file sorts first. An entry point that plays the wrong episode is worse
                // than no entry point; lift this when there is a season and episode to pass through.
                enabled.update {
                    mediaType == MediaType.MOVIE && orcaEngineClient.getFeatures()?.sourceStreaming == true
                }
            }
        }

        /** Searches for streamable sources. Explicit user action only. */
        fun findSources() {
            if (title.isBlank()) return
            triedSourceIds.clear()
            state.update { SourceSearchState.Searching(SystemClock.elapsedRealtime()) }
            job?.cancel()
            job = scope.launchIO {
                // The engine swallows transport failures into a null, which reads the same as "not set
                // up for this". A deadline here is what separates the two: past it the server stopped
                // answering, and that is worth different words and a retry button.
                val completed =
                    withTimeoutOrNull(SEARCH_DEADLINE_MS) {
                        val groups =
                            orcaEngineClient.getSources(
                                title = title,
                                year = year,
                                mediaType = mediaType,
                            )
                        candidates = groups?.all.orEmpty().sortedWith(pickerOrder)
                        ensureActive()

                        state.update {
                            when {
                                groups == null -> SourceSearchState.Stopped(StopKind.SERVER_NOT_READY)

                                candidates.isEmpty() -> SourceSearchState.Stopped(StopKind.NO_SOURCES)

                                else ->
                                    SourceSearchState.Found(
                                        sources = candidates,
                                        // Where the D-pad starts, not a verdict: the engine's own top
                                        // pick when it has one, otherwise the head of the list.
                                        focusId = groups.recommended?.id ?: candidates.first().id,
                                    )
                            }
                        }
                    }
                if (completed == null) {
                    state.update { SourceSearchState.Stopped(StopKind.TIMED_OUT) }
                }
            }
        }

        /**
         * Opens a stream for the chosen source and hands it to the normal player.
         *
         * Slow by nature — torrent metadata has to arrive and the head pieces have to buffer before
         * playback can start — hence the explicit [SourceSearchState.Opening] state rather than a
         * silent wait, and the escalation ladder that runs alongside it.
         */
        fun playSource(
            source: TorrentSourceDto,
            autoSwitched: Boolean = false,
        ) {
            job?.cancel()
            triedSourceIds += source.id
            state.update {
                SourceSearchState.Opening(
                    source = source,
                    startedAtMs = SystemClock.elapsedRealtime(),
                    autoSwitched = autoSwitched,
                )
            }
            job = scope.launchIO {
                val stream = orcaEngineClient.openStreamSession(sourceId = source.id)
                ensureActive()
                if (stream == null) {
                    // Creating the session is now fast — it no longer waits on the swarm — so a null
                    // here means the source itself was unusable, not that it was slow.
                    failStream(source)
                    return@launchIO
                }

                // Wait for the swarm for as long as the viewer is willing to. There is deliberately
                // no deadline: a popular torrent can take minutes to find its first reachable peer,
                // and the old fixed budget reported healthy sources as dead. The viewer cancels with
                // BACK, or switches streams — either way the decision is theirs, informed by the
                // numbers this loop puts on screen.
                var ready = stream
                var misses = 0
                while (true) {
                    val status = orcaEngineClient.getStreamStatus(stream.token)
                    // The network call isn't cancellable mid-flight, so this is where leaving the
                    // flow actually takes effect — without it the loop finishes its round and writes
                    // state for a stream nobody is waiting on any more.
                    ensureActive()
                    if (status == null) {
                        // Null is *any* transport failure, not just a session that's gone. At one
                        // poll a second over a multi-minute wait, treating the first one as fatal
                        // hands a healthy swarm a death sentence for a dropped packet.
                        if (++misses >= MAX_STATUS_MISSES) {
                            failStream(source)
                            return@launchIO
                        }
                        delay(STATUS_POLL_INTERVAL_MS)
                        continue
                    }
                    misses = 0
                    if (status.isFailed) {
                        failStream(source)
                        return@launchIO
                    }

                    state.update { current ->
                        if (current is SourceSearchState.Opening) current.copy(status = status) else current
                    }

                    if (status.isReady) {
                        // The file is only chosen once metadata lands, so the real name and size
                        // arrive with the ready report rather than with the session.
                        ready =
                            stream.copy(
                                fileName = status.fileName.ifBlank { stream.fileName },
                                sizeBytes = status.length.takeIf { it > 0 } ?: stream.sizeBytes,
                                mediaInfo = status.mediaInfo ?: stream.mediaInfo,
                            )
                        break
                    }

                    delay(STATUS_POLL_INTERVAL_MS)
                }

                // Closing the dialog, arming the prompt and navigating are one indivisible step, and
                // they sit behind a cancellation check together: withContext won't enter after a
                // cancel, so a viewer who left can't have the flow dismissed, a Keep prompt armed or
                // a player opened on their behalf. Split apart, each was its own way to lose that.
                withContext(Dispatchers.Main) {
                    state.update { SourceSearchState.Idle }
                    // What the player is about to show is a temporary stream, so the Keep prompt has
                    // to know it was asked for before the film starts — the player itself only
                    // reports back where the viewer stopped.
                    streamWatchTracker.begin(title = title.ifBlank { source.title }, token = ready.token)
                    navigationManager.navigateTo(
                        Destination.Playback(
                            // No Jellyfin item exists for this; a fresh id per session also keeps it
                            // out of the resume and next-up tables, which are keyed on real ids.
                            itemId = UUID.randomUUID(),
                            positionMs = 0,
                            torrent =
                                TorrentPlaybackArgs(
                                    url = ready.url,
                                    title = title.ifBlank { source.title },
                                    fileName = ready.fileName,
                                    sizeBytes = ready.sizeBytes,
                                    mediaInfo = ready.mediaInfo,
                                    token = ready.token,
                                ),
                        ),
                    )
                }
            }
        }

        /**
         * A dead source is never a dead end: lead with the next stream, fall back to requesting.
         *
         * Only [source]'s own attempt may report it dead. A poll loop cancelled a moment ago can
         * still be between its last cancellation check and this line, and a stop screen for a stream
         * the viewer already abandoned is the one wrong write that doesn't correct itself.
         */
        private fun failStream(source: TorrentSourceDto) {
            state.update { current ->
                if (current !is SourceSearchState.Opening || current.source.id != source.id) {
                    current
                } else {
                    SourceSearchState.Stopped(
                        kind = StopKind.STREAM_FAILED,
                        remaining = candidates.count { it.id !in triedSourceIds },
                    )
                }
            }
        }

        /**
         * Moves to the next untried source. Viewer-driven only — "try the next stream" on the stop
         * screen, or "switch now" during a slow connect. OrcaX no longer does this on a timer.
         */
        fun nextStream(autoSwitched: Boolean = false) {
            job?.cancel()
            val next = candidates.firstOrNull { it.id !in triedSourceIds }
            if (next == null) {
                state.update { SourceSearchState.Stopped(StopKind.STREAM_FAILED, remaining = 0) }
                return
            }
            playSource(next, autoSwitched = autoSwitched)
        }

        /** Back to the full list, with everything already tried still listed and still pickable. */
        fun chooseAnother() {
            job?.cancel()
            if (candidates.isEmpty()) {
                findSources()
                return
            }
            state.update { SourceSearchState.Found(sources = candidates, focusId = candidates.first().id) }
        }

        /** The viewer would rather wait this one out — the only control that leaves the poll running. */
        fun keepWaiting() {
            state.update { current ->
                if (current is SourceSearchState.Opening) current.copy(waitAcknowledged = true) else current
            }
        }

        /** Runs a fresh search — the way out of every stop screen that can plausibly be retried. */
        fun retry() {
            findSources()
        }

        /**
         * BACK out of the flow. Cancels whatever was running — an abandoned session is left for the
         * engine's idle sweep to reclaim, which is why this doesn't have to wait for anything.
         *
         * ponytail: no `DELETE /Stream/{token}` on the way out. Now that leaving actually cancels,
         * abandoning sessions is routine rather than rare, and the server's cap is 8 per client —
         * enough candidates tried in one sitting and the next one is refused. Wire the DELETE up if
         * that turns out to be reachable in practice; the sweep covers it at 20 minutes either way.
         */
        fun dismiss() {
            job?.cancel()
            state.update { SourceSearchState.Idle }
        }

    }

/** How often the connecting screen refreshes its swarm numbers. Fast enough to feel live, cheap. */
private const val STATUS_POLL_INTERVAL_MS = 1_000L

/**
 * Consecutive unanswered status polls before the stream is called dead. Five seconds of silence:
 * long enough to ride out a Wi-Fi hiccup or a server GC pause, short enough that a genuinely gone
 * session doesn't leave the viewer watching a frozen peer count.
 */
private const val MAX_STATUS_MISSES = 5

/**
 * How long a search may run before the server is treated as having stopped answering. Generous —
 * an indexer sweep across a dozen places genuinely takes a while, and cutting it short would turn a
 * slow-but-working setup into an error screen.
 */
private const val SEARCH_DEADLINE_MS = 60_000L

/**
 * The picker's order: highest resolution first, then the healthiest swarm. This is presentation only —
 * the list is shown whole and unlabelled, and nothing here is stated to the viewer as a ranking.
 */
internal val pickerOrder =
    compareByDescending<TorrentSourceDto> { it.resolutionHeight }
        .thenByDescending { it.seeders }
