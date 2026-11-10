package com.github.jkrishna289.orcax.services

import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.github.jkrishna289.orcax.data.ServerPreferencesDao
import com.github.jkrishna289.orcax.data.ServerRepository
import com.github.jkrishna289.orcax.data.model.JellyfinUser
import com.github.jkrishna289.orcax.ui.nav.NavDrawerItem
import com.github.jkrishna289.orcax.ui.nav.ServerNavDrawerItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavDrawerServiceTest {
    private val userId = UUID.fromString("4500c774-de23-4fed-8e0d-1fdc0d3cca85")
    private val user = JellyfinUser(id = userId, name = "Krishna", serverId = UUID.randomUUID(), accessToken = "t")
    private val userDto = mockk<UserDto> { every { id } returns userId; every { policy } returns null }
    private val currentUser = MutableLiveData<JellyfinUser?>()
    private val currentUserDto = MutableLiveData<UserDto?>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val movies =
        mockk<BaseItemDto>(relaxed = true) {
            every { id } returns UUID.fromString("f137a2dd-21bb-c1b9-9aa5-c0f6bf02a805")
            every { name } returns "Movies"
            every { collectionType } returns CollectionType.MOVIES
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a failed library fetch does not stop later user updates from filling the drawer`() {
        val userViewsApi = mockk<UserViewsApi>()
        var calls = 0
        coEvery { userViewsApi.getUserViews(userId = userId) } answers {
            if (calls++ == 0) throw IllegalStateException("401") else mockQueryResult(listOf(movies))
        }
        val api = mockk<ApiClient> { every { this@mockk.userViewsApi } returns userViewsApi }
        val serverRepository =
            mockk<ServerRepository> {
                every { currentUser } returns this@NavDrawerServiceTest.currentUser
                every { currentUserDto } returns this@NavDrawerServiceTest.currentUserDto
            }
        val dao = mockk<ServerPreferencesDao> { every { getNavDrawerPinnedItems(any<JellyfinUser>()) } returns emptyList() }
        val musicService = mockk<MusicService> { every { state } returns MutableStateFlow(MusicServiceState.EMPTY) }
        val service = NavDrawerService(ApplicationProvider.getApplicationContext(), scope, api, serverRepository, dao, musicService)

        currentUser.value = user
        currentUserDto.value = userDto
        awaitTrue("the first fetch was never attempted") { calls == 1 }
        assertEquals("the first fetch fails", emptyList<NavDrawerItem>(), service.state.value.items)

        // e.g. signing in again: the user is re-emitted and this time the server answers
        currentUserDto.value = userDto
        awaitTrue("the drawer stayed empty after a later successful fetch") {
            service.state.value.items.map { it.id } == listOf(ServerNavDrawerItem.getId(movies.id))
        }
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }
}
