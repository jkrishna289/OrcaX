package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.engine.FeatureFlags
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.StreamSessionStatus
import com.github.jkrishna289.orcax.engine.TorrentSourceDto
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.torrent.StreamWatchTracker
import com.github.jkrishna289.orcax.services.torrent.TorrentStream
import com.github.jkrishna289.orcax.ui.detail.discover.SourceSearchState
import com.github.jkrishna289.orcax.ui.detail.discover.SourceStreamingController
import com.github.jkrishna289.orcax.ui.nav.Destination
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connect-and-hand-over state machine, which is the part of the source flow that runs unattended.
 *
 * Opening a stream is a poll loop with no deadline, so its coroutine can easily outlive the screen
 * that started it. That makes "what happens after the viewer leaves" the only question here worth
 * asking, and the reason these are real-time tests: the loop runs on [Dispatchers.IO] and the point
 * is precisely that it keeps running when nothing is watching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TestSourceStreamingController {
    private val engine = mockk<OrcaEngineClient>()
    private lateinit var navigation: NavigationManager
    private lateinit var watchTracker: StreamWatchTracker
    private lateinit var scope: CoroutineScope
    private lateinit var controller: SourceStreamingController

    private val source = TorrentSourceDto(title = "Sintel.2010.1080p.WEB-DL", id = "src-1")

    @Before
    fun setUp() {
        // Unconfined rather than a queued main looper: the hand-over to the player runs on Main, and
        // a Main that never drains would make these tests pass without the code being right.
        Dispatchers.setMain(Dispatchers.Unconfined)
        navigation = NavigationManager()
        watchTracker = StreamWatchTracker()
        scope = CoroutineScope(SupervisorJob())
        controller = SourceStreamingController(engine, navigation, watchTracker)
        coEvery { engine.getFeatures() } returns FeatureFlags(sourceStreaming = true)
        coEvery { engine.openStreamSession(any(), any(), any(), any()) } returns
            TorrentStream(url = "http://server/stream/tok", token = "tok", fileName = "", sizeBytes = null)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * The regression that matters: BACK during a connect has to end the attempt, not just hide it.
     * An unowned poll loop keeps going, sees the swarm come up minutes later, and drops the viewer
     * into a player they never asked for from whatever page they had moved on to.
     */
    @Test
    fun `a stream that goes ready after the viewer backs out never reaches the player`() {
        val ready = AtomicBoolean(false)
        coEvery { engine.getStreamStatus("tok") } answers {
            StreamSessionStatus(state = if (ready.get()) "Ready" else "Downloading", peers = 2)
        }

        controller.attach(scope, title = "Sintel", year = 2010)
        controller.playSource(source)
        awaitTrue("the connect screen never showed a swarm report") {
            (controller.state.value as? SourceSearchState.Opening)?.status != null
        }

        controller.dismiss()
        ready.set(true)
        Thread.sleep(2_500) // several poll intervals: an orphaned loop would have fired by now

        assertEquals(SourceSearchState.Idle, controller.state.value)
        assertEquals(1, navigation.backStack.size) // still just Home
        assertNull("the Keep prompt was armed for a film nobody watched", watchTracker.consume())
    }

    /**
     * The happy path, plus the tolerance that protects it. `getStreamStatus` returns null on any
     * transport failure, not just a session that's gone, so a couple of unanswered polls have to be
     * survivable — at one a second, a Wi-Fi hiccup would otherwise condemn a working swarm.
     */
    @Test
    fun `a couple of unanswered polls are survivable and the ready report fills in the file`() {
        var polls = 0
        coEvery { engine.getStreamStatus("tok") } answers {
            when (++polls) {
                1, 2 -> null // dropped, not dead
                else ->
                    StreamSessionStatus(
                        state = "Ready",
                        fileName = "Sintel.2010.1080p.mkv",
                        length = 1_234L,
                    )
            }
        }

        controller.attach(scope, title = "Sintel", year = 2010)
        controller.playSource(source)
        awaitTrue("the stream never reached the player") { navigation.backStack.size > 1 }

        val playback = navigation.backStack.last() as Destination.Playback
        val torrent = playback.torrent!!
        // Name and size only exist once metadata lands, so they come off the ready report rather
        // than the session that was opened before it.
        assertEquals("Sintel.2010.1080p.mkv", torrent.fileName)
        assertEquals(1_234L, torrent.sizeBytes)
        assertEquals(SourceSearchState.Idle, controller.state.value)
        assertEquals("Sintel", watchTracker.consume()?.title)
    }

    /**
     * A series has no episode to pass, and the engine picks blind from a season pack without one.
     * The entry point stays hidden rather than offering a button that plays the wrong episode.
     */
    @Test
    fun `the entry point is offered for films and withheld for series`() {
        controller.attach(scope, title = "Sintel", year = 2010, mediaType = MediaType.MOVIE)
        awaitTrue("the film entry point never appeared") { controller.enabled.value }

        val series = SourceStreamingController(engine, navigation, watchTracker)
        series.attach(scope, title = "Severance", year = 2022, mediaType = MediaType.SERIES)
        Thread.sleep(250) // long enough for the flag lookup it should never act on
        assertTrue("source streaming was offered for a series", !series.enabled.value)
    }

    /** Polls a real-time condition; the loop under test runs on its own dispatcher and clock. */
    private fun awaitTrue(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
}
