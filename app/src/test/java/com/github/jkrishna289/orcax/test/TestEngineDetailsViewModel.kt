package com.github.jkrishna289.orcax.test

import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.AvailabilityStatusResponse
import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.MediaId
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.ServerPlaybackProgress
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.services.mockQueryResult
import com.github.jkrishna289.orcax.services.torrent.StreamWatchTracker
import com.github.jkrishna289.orcax.ui.detail.engine.EngineDetailsViewModel
import com.github.jkrishna289.orcax.ui.nav.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TestEngineDetailsViewModel {
    private val engine = mockk<OrcaEngineClient>()
    private val api = mockk<ApiClient>()
    private lateinit var navigation: NavigationManager
    private var viewModel: EngineDetailsViewModel? = null

    private val importedId = UUID.fromString("48e59dbe-16b9-4a12-a5ce-77d447a916f5")
    private val item =
        RenderItem(
            media = MediaId(tmdbId = 45745, mediaType = MediaType.MOVIE, availability = AvailabilityState.REQUEST),
            card = CardDescriptor(title = "Sintel"),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        navigation = NavigationManager()
        coEvery { engine.getRequestStatus(45745, MediaType.MOVIE) } returns
            AvailabilityStatusResponse(availability = AvailabilityState.WATCH_NOW)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `an imported movie resolves its new library id before opening normal resume details`() {
        val saved =
            ServerPlaybackProgress(
                positionMs = 480_000,
                durationMs = 1_200_000,
                jellyfinItemId = importedId.toString(),
            )
        coEvery { engine.getPlaybackProgress(any()) } returns saved
        val details = createViewModel()
        awaitTrue("the newly imported id was never resolved") { details.libraryItemId.value == importedId }

        assertNull("the test card must predate import", item.media.jellyfinId)
        assertEquals(saved, details.playbackProgress.value)
        assertEquals(AvailabilityState.WATCH_NOW, details.availability.value)
        details.navigateToLibraryItem()
        awaitTrue("the resolved library movie did not open") { navigation.backStack.size > 1 }

        assertEquals(importedId, (navigation.backStack.last() as Destination.MediaItem).itemId)
        // Navigation refreshes the server record first so Jellyfin's details reads the reconciled
        // position for the local runtime, instead of blindly using the old stream's milliseconds.
        coVerify(exactly = 2) { engine.getPlaybackProgress(match { it.tmdbId == 45745 && it.mediaType == "movie" }) }
    }

    @Test
    fun `a transient progress failure does not forget an already resolved library copy`() {
        coEvery { engine.getPlaybackProgress(any()) } returns
            ServerPlaybackProgress(jellyfinItemId = importedId.toString())
        val details = createViewModel()
        awaitTrue("the imported id was never resolved") { details.libraryItemId.value == importedId }
        coEvery { engine.getPlaybackProgress(any()) } returns null

        details.navigateToLibraryItem()
        awaitTrue("a temporary server failure lost the known library destination") { navigation.backStack.size > 1 }
        assertEquals(importedId, (navigation.backStack.last() as Destination.MediaItem).itemId)
    }

    @Test
    fun `an in-library series with no engine id resolves its library copy by tmdb id`() {
        val seriesId = UUID.fromString("10df5605-83ad-a9d6-cf9e-b2b2d90abccf")
        val series =
            RenderItem(
                media = MediaId(tmdbId = 99652, mediaType = MediaType.SERIES, availability = AvailabilityState.RECENTLY_ADDED),
                card = CardDescriptor(title = "Kevin Can F**K Himself"),
            )
        coEvery { engine.getRequestStatus(99652, MediaType.SERIES) } returns
            AvailabilityStatusResponse(availability = AvailabilityState.WATCH_NOW)
        // Same title, wrong TMDB id first: only the provider-id match may be picked.
        val itemsApi = mockk<ItemsApi>()
        every { api.itemsApi } returns itemsApi
        coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns
            mockQueryResult(
                listOf(
                    BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES, providerIds = mapOf("Tmdb" to "1")),
                    BaseItemDto(id = seriesId, type = BaseItemKind.SERIES, providerIds = mapOf("Tmdb" to "99652")),
                ),
            )

        val details = createViewModel(series)
        awaitTrue("the library series was never resolved") { details.libraryItemId.value == seriesId }

        details.navigateToLibraryItem()
        awaitTrue("the resolved library series did not open") { navigation.backStack.size > 1 }
        assertEquals(seriesId, (navigation.backStack.last() as Destination.SeriesOverview).itemId)
    }

    private fun createViewModel(forItem: RenderItem = item): EngineDetailsViewModel =
        EngineDetailsViewModel(
            engineClient = engine,
            api = api,
            navigationManager = navigation,
            preferences = mockk(),
            streamWatchTracker = StreamWatchTracker(),
            sources = mockk(relaxed = true),
            item = forItem,
        ).also { viewModel = it }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
}
