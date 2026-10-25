package com.github.jkrishna289.orcax.test

import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.MediaId
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.RequestResult
import com.github.jkrishna289.orcax.engine.SimilarResponse
import com.github.jkrishna289.orcax.preferences.AppPreferences
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TestEngineSimilarViewModel {
    private val engine = mockk<OrcaEngineClient>()
    private val userId = UUID.fromString("48e59dbe-16b9-4a12-a5ce-77d447a916f5")
    private val itemId = UUID.fromString("9d1c6a0e-2f4b-4c1e-8a7d-3b5e6f708192")
    private lateinit var viewModel: EngineSimilarViewModel

    private val requestable =
        RenderItem(
            media = MediaId(tmdbId = 99652, mediaType = MediaType.SERIES, availability = AvailabilityState.REQUEST),
            card = CardDescriptor(title = "Kevin Can F**k Himself"),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        coEvery { engine.getSimilar(jellyfinId = itemId) } returns
            SimilarResponse(row = RenderRow(items = listOf(requestable)))
        coEvery { engine.requestMedia(userId, 99652, "tv", any()) } returns RequestResult(success = true)
        val prefs = mockk<DataStore<AppPreferences>>()
        every { prefs.data } returns flowOf(AppPreferences.newBuilder().setCurrentUserId(userId.toString()).build())
        viewModel = EngineSimilarViewModel(engine, NavigationManager(), prefs)
        viewModel.load(itemId)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `clicking a requestable card asks first and cancel sends nothing`() {
        viewModel.onClick(requestable)
        assertEquals(requestable, viewModel.pendingRequest.value)

        viewModel.cancelRequest()
        assertNull(viewModel.pendingRequest.value)
        coVerify(exactly = 0) { engine.requestMedia(any(), any(), any(), any()) }
    }

    @Test
    fun `confirming requests the title and flips its badge to requested`() {
        viewModel.onClick(requestable)
        viewModel.confirmRequest()

        assertNull(viewModel.pendingRequest.value)
        coVerify(exactly = 1) { engine.requestMedia(userId, 99652, "tv", "Kevin Can F**k Himself") }
        assertEquals(
            AvailabilityState.REQUESTED,
            viewModel.row.value!!
                .items
                .single()
                .media.availability,
        )
    }
}
