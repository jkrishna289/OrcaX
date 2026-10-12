package com.github.jkrishna289.orcax.services.torrent

import com.github.jkrishna289.orcax.engine.EngineJson
import com.github.jkrishna289.orcax.engine.PlaybackProgressBody
import com.github.jkrishna289.orcax.engine.ProgressReportResult
import com.github.jkrishna289.orcax.engine.ServerPlaybackProgress
import com.github.jkrishna289.orcax.services.OrcaEngineClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PlaybackProgressContractTest {
    @Test
    fun `progress accepts the compact GUID emitted by Jellyfin`() = runTest {
        val progress = EngineJson.decodeFromString<ServerPlaybackProgress>(
            """{"SessionId":"0123456789abcdef0123456789abcdef","PositionMs":40000,"DurationMs":100000,"Completed":false,"JellyfinItemId":null}""",
        )
        val requestPath = AtomicReference<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestPath.set(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val api = mockk<ApiClient>()
        every { api.baseUrl } returns "http://localhost:8096"
        val client = OrcaEngineClient(http, api)
        assertEquals(ProgressReportResult.SAVED, client.reportPlaybackProgress(
            progress.sessionId, PlaybackProgressBody(1, 40_000, 100_000, stopped = true),
        ))
        assertEquals("/OrcaEngine/Playback/Sessions/01234567-89ab-cdef-0123-456789abcdef/Progress", requestPath.get())
    }
}
