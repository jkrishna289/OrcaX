package com.github.jkrishna289.orcax.ui.playback.quality

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * NetworkAnalyzer over a real MockWebServer. Assertions avoid absolute timing
 * values (localhost speed is unstable); they cover the durable contracts:
 * probe → cache, cache-hit reuse, forced re-probe, and Exo-sample override.
 * The body is throttled so each probe stays above the fast-completion
 * threshold and never triggers the 8 MB re-probe, keeping request counts
 * deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NetworkAnalyzerTest {
    private lateinit var server: MockWebServer
    private lateinit var analyzer: NetworkAnalyzer

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(ByteArray(400_000)))
                    // 64 KB / 60 ms → ~375 ms for 400 KB, above the 300 ms
                    // fast-completion threshold, so no 8 MB re-probe fires.
                    .throttleBody(64L * 1024, 60, TimeUnit.MILLISECONDS)
        }
        server.start()

        val cm = mockk<ConnectivityManager>(relaxed = true) {
            every { isActiveNetworkMetered } returns false
        }
        val context = mockk<Context> {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        }
        val api = mockk<ApiClient> {
            every { baseUrl } returns server.url("/").toString()
            every { accessToken } returns "test-token"
            every { clientInfo } returns ClientInfo("test", "1.0")
            every { deviceInfo } returns DeviceInfo("device-id", "device")
            every { createUrl(any(), any(), any(), any()) } answers {
                server.url(firstArg<String>()).toString()
            }
        }
        analyzer = NetworkAnalyzer(context, api, OkHttpClient())
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `probe measures bandwidth and caches it`() = runBlocking {
        val m = analyzer.measure("user-a")
        assertNotNull(m)
        assertEquals(MeasurementSource.BITRATE_TEST, m!!.source)
        assertTrue("expected a positive bitrate, was ${m.bps}", m.bps > 0)
        assertNotNull(analyzer.cachedMeasurement("user-a"))
    }

    @Test
    fun `second measure hits the cache and issues no new request`() = runBlocking {
        analyzer.measure("user-a")
        val countAfterFirst = server.requestCount
        val m2 = analyzer.measure("user-a")
        assertNotNull(m2)
        assertEquals(countAfterFirst, server.requestCount)
    }

    @Test
    fun `force re-probes even when the cache is warm`() = runBlocking {
        analyzer.measure("user-a")
        val countAfterFirst = server.requestCount
        analyzer.measure("user-a", force = true)
        assertTrue(server.requestCount > countAfterFirst)
    }

    @Test
    fun `concurrent measures share a single probe`() = runBlocking {
        // Two measures launched together must not each fire their own probe.
        val results = listOf(
            async { analyzer.measure("user-a") },
            async { analyzer.measure("user-a") },
        ).awaitAll()
        assertTrue(results.all { it != null })
        // Single-flight (or the first completing and caching) ⇒ exactly one probe.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cache is isolated per user`() = runBlocking {
        analyzer.measure("user-a")
        assertNotNull(analyzer.cachedMeasurement("user-a"))
        // A different user shares neither the entry nor its probe result.
        assertNull(analyzer.cachedMeasurement("user-b"))
    }

    @Test
    fun `exo sample overrides the cached measurement`() = runBlocking {
        analyzer.measure("user-a")
        analyzer.recordExoSample(50_000_000, "user-a")
        val cached = analyzer.cachedMeasurement("user-a")
        assertNotNull(cached)
        assertEquals(MeasurementSource.EXO_DIRECT, cached!!.source)
        assertEquals(50_000_000, cached.bps)
    }

    @Test
    fun `tiny exo samples are ignored`() = runBlocking {
        analyzer.measure("user-a")
        val before = analyzer.cachedMeasurement("user-a")
        analyzer.recordExoSample(1_000, "user-a") // below the sanity floor
        assertEquals(before!!.source, analyzer.cachedMeasurement("user-a")!!.source)
    }
}
