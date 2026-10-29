package com.github.jkrishna289.orcax.test

import androidx.lifecycle.viewModelScope
import com.github.jkrishna289.orcax.engine.AvailabilityState
import com.github.jkrishna289.orcax.engine.CardDescriptor
import com.github.jkrishna289.orcax.engine.MediaId
import com.github.jkrishna289.orcax.engine.MediaType
import com.github.jkrishna289.orcax.engine.RenderItem
import com.github.jkrishna289.orcax.engine.RenderRow
import com.github.jkrishna289.orcax.engine.SimilarResponse
import com.github.jkrishna289.orcax.services.NavigationManager
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import com.github.jkrishna289.orcax.ui.detail.EngineSimilarViewModel
import com.github.jkrishna289.orcax.ui.nav.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TestEngineSimilarViewModel {
    private val engine = mockk<OrcaEngineClient>()
    private val itemId = UUID.fromString("9d1c6a0e-2f4b-4c1e-8a7d-3b5e6f708192")
    private val navigation = NavigationManager()
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
        viewModel = EngineSimilarViewModel(engine, navigation)
        viewModel.load(itemId)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `clicking a title outside the library opens its page and requests nothing`() {
        viewModel.onClick(requestable)

        assertEquals(Destination.EngineItem(requestable), navigation.backStack.last())
        coVerify(exactly = 0) { engine.requestMedia(any(), any(), any(), any()) }
    }
}
