package com.github.jkrishna289.orcax.ui.playback.quality

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.github.jkrishna289.orcax.services.hilt.StandardOkHttpClient
import com.github.jkrishna289.orcax.ui.playback.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Measures and caches the usable bandwidth between this device and the
 * current Jellyfin server.
 *
 * - Probes the server's own /Playback/BitrateTest endpoint (adaptive 1 MB → 8 MB),
 *   measuring from the first byte so TTFB is excluded, and never rejecting a
 *   probe for being "too fast".
 * - Classifies the network by the SERVER address (RFC1918 / ULA / loopback = LAN;
 *   CGNAT 100.64/10 and cellular = INTERNET) — classification only selects cache
 *   buckets and probe policy; the measurement always dominates.
 * - Caches per server + user + network type + network fingerprint (interface +
 *   default gateway — the primary path on TV, where SSID needs a location
 *   permission that is never granted), so home-LAN 900 Mbps and hotspot 12 Mbps
 *   to the same server never share an entry.
 * - Metered networks are never probed; the OS link estimate is used instead.
 * - Singleton: the cache outlives playback so details pages and the next item
 *   can read it without probing.
 */
@Singleton
class NetworkAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ApiClient,
    @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
) {
    data class NetworkEnv(
        val serverHost: String,
        val type: NetworkType,
        val fingerprint: String,
        val metered: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring

    private val cache = ConcurrentHashMap<String, BandwidthMeasurement>()

    private val flightMutex = Mutex()
    private var inFlight: Deferred<BandwidthMeasurement?>? = null

    @Volatile
    private var lastEnv: NetworkEnv? = null

    @Volatile
    private var lastEnvAtMs: Long = 0L

    /**
     * Returns the current measurement, probing if the cache is cold or stale.
     * Single-flight: concurrent callers share one probe. Callers may be
     * cancelled independently without killing the shared probe.
     * Returns null when nothing usable could be measured (connection UNKNOWN).
     */
    suspend fun measure(userId: String?, force: Boolean = false): BandwidthMeasurement? {
        val env = env() ?: return null
        val key = cacheKey(userId, env)
        if (!force) {
            cache[key]
                ?.takeIf { it.isFresh() && it.confidenceAt() >= BandwidthMeasurement.USABLE_CONFIDENCE }
                ?.let { return it }
        }
        val deferred = flightMutex.withLock {
            inFlight?.takeIf { it.isActive }
                ?: scope.async { runProbeSequence(key, env) }.also { inFlight = it }
        }
        return deferred.await()
    }

    /**
     * Fresh cached measurement for the current network, or null. Fast path for
     * UI (details-page preview, warm-cache start decision) — never probes.
     * Uses the last computed environment; returns null if none exists yet.
     */
    fun cachedMeasurement(userId: String?): BandwidthMeasurement? {
        val env = lastEnv?.takeIf { it.serverHost == serverHost() } ?: return null
        return cache[cacheKey(userId, env)]
            ?.takeIf { it.isFresh() && it.confidenceAt() >= BandwidthMeasurement.USABLE_CONFIDENCE }
    }

    /**
     * Record an ExoPlayer bandwidth estimate. The CALLER must gate this on
     * direct play/direct stream: during transcodes Exo measures the server's
     * transcode speed (Jellyfin throttles ffmpeg when buffered ahead), and
     * recording it would poison the cache with compounding low estimates.
     */
    fun recordExoSample(bps: Long, userId: String?) {
        if (bps < MIN_SANE_BPS) return
        val env = lastEnv?.takeIf { it.serverHost == serverHost() } ?: return
        cache[cacheKey(userId, env)] = BandwidthMeasurement(bps, MeasurementSource.EXO_DIRECT)
    }

    /** Last classified environment, if any (UI hint only). */
    fun lastKnownEnv(): NetworkEnv? = lastEnv

    // ─── Probe ────────────────────────────────────────────────────────────────

    private suspend fun runProbeSequence(key: String, env: NetworkEnv): BandwidthMeasurement? {
        _isMeasuring.value = true
        try {
            if (env.metered) {
                Timber.i("Quality: metered network — skipping probe, using OS link estimate")
                return osLinkMeasurement()?.also { cache[key] = it }
            }
            if (api.accessToken == null) return osLinkMeasurement()?.also { cache[key] = it }

            val first = probeOnce(PROBE_SMALL_BYTES)
            val sample =
                if (first != null && first.wallTimeMs < FAST_COMPLETION_MS) {
                    // Finished so fast the sample is noise-dominated — re-probe bigger.
                    probeOnce(PROBE_LARGE_BYTES) ?: first
                } else {
                    first
                }
            return if (sample != null) {
                Timber.i(
                    "Quality: probe measured %.1f Mbps (%s, %s)",
                    sample.bps / 1_000_000.0, env.type, env.fingerprint,
                )
                BandwidthMeasurement(sample.bps, MeasurementSource.BITRATE_TEST).also { cache[key] = it }
            } else {
                osLinkMeasurement()?.also { cache[key] = it }
            }
        } finally {
            _isMeasuring.value = false
        }
    }

    private class ProbeProgress {
        @Volatile var bytes = 0L

        @Volatile var firstChunkBytes = 0L

        @Volatile var firstByteAtNs = 0L

        @Volatile var lastByteAtNs = 0L
    }

    private data class ProbeSample(val bps: Long, val wallTimeMs: Long)

    private suspend fun probeOnce(sizeBytes: Int): ProbeSample? {
        val url = api.createUrl("/Playback/BitrateTest?size=$sizeBytes&cb=${System.currentTimeMillis()}")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val progress = ProbeProgress()
        val startNs = System.nanoTime()
        try {
            withTimeout(PROBE_TIMEOUT_MS) { executeStreaming(request, progress) }
        } catch (e: TimeoutCancellationException) {
            // Timeout is not failure: whatever bytes flowed are still a valid sample.
            Timber.w("Quality: probe timed out after ${PROBE_TIMEOUT_MS}ms (${progress.bytes} bytes read)")
        } catch (e: IOException) {
            Timber.w("Quality: probe failed: ${e.message}")
            return null
        }
        val wallTimeMs = (System.nanoTime() - startNs) / 1_000_000
        return sampleFrom(progress, wallTimeMs)
    }

    /**
     * Streams the response body, timestamping bytes as they arrive.
     * Uses enqueue + suspendCancellableCoroutine so cancellation (including
     * withTimeout) aborts the socket via call.cancel() — a blocking execute()
     * inside withContext(IO) is NOT interruptible by withTimeout.
     */
    private suspend fun executeStreaming(request: Request, progress: ProbeProgress): Unit =
        suspendCancellableCoroutine { cont ->
            val call = okHttpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            val body = r.body ?: throw IOException("empty body")
                            val buf = ByteArray(64 * 1024)
                            val stream = body.byteStream()
                            while (true) {
                                val n = stream.read(buf)
                                if (n == -1) break
                                val now = System.nanoTime()
                                if (progress.firstByteAtNs == 0L) {
                                    progress.firstByteAtNs = now
                                    progress.firstChunkBytes = n.toLong()
                                }
                                progress.lastByteAtNs = now
                                progress.bytes += n
                            }
                        }
                        if (cont.isActive) cont.resume(Unit)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    private fun sampleFrom(progress: ProbeProgress, wallTimeMs: Long): ProbeSample? {
        // Rate is measured from the first byte (TTFB excluded); the first chunk
        // carries no elapsed time so its bytes are excluded from the numerator too.
        val bytes = progress.bytes - progress.firstChunkBytes
        val elapsedNs = progress.lastByteAtNs - progress.firstByteAtNs
        if (bytes < MIN_MEASURED_BYTES || elapsedNs <= 0L) return null
        val bps = bytes * 8 * 1_000_000_000L / elapsedNs
        return ProbeSample(bps, wallTimeMs)
    }

    private fun authorizationHeader(): String {
        val client = api.clientInfo
        val device = api.deviceInfo
        return "MediaBrowser Client=\"${client.name}\", Device=\"${device.name}\"," +
            " DeviceId=\"${device.id}\", Version=\"${client.version}\", Token=\"${api.accessToken}\""
    }

    /** OS-reported link capacity at 80% TCP hedge, or null — never a fabricated number. */
    private fun osLinkMeasurement(): BandwidthMeasurement? {
        val cm = connectivityManager() ?: return null
        val kbps = try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps ?: -1
        } catch (e: Exception) {
            -1
        }
        if (kbps <= 0) return null
        return BandwidthMeasurement(kbps * 1_000L * 80 / 100, MeasurementSource.OS_LINK)
    }

    // ─── Environment: classification + fingerprint ────────────────────────────

    private suspend fun env(): NetworkEnv? {
        val host = serverHost() ?: return null
        lastEnv
            ?.takeIf { it.serverHost == host && System.currentTimeMillis() - lastEnvAtMs < ENV_TTL_MS }
            ?.let { return it }
        return withContext(Dispatchers.IO) {
            val cm = connectivityManager()
            val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
            val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val type = classifyServer(host, cellular)
            NetworkEnv(
                serverHost = host,
                type = type,
                fingerprint = fingerprint(cm),
                metered = cm?.isActiveNetworkMetered == true,
            ).also {
                lastEnv = it
                lastEnvAtMs = System.currentTimeMillis()
                Timber.d("Quality: network env = $it")
            }
        }
    }

    private fun serverHost(): String? = api.baseUrl?.toHttpUrlOrNull()?.host

    /**
     * Classify by the SERVER's resolved address — a client on private Wi-Fi
     * talking to a remote server is INTERNET, not LAN (audit #5).
     * Conservative on failure: INTERNET.
     */
    private fun classifyServer(host: String, cellular: Boolean): NetworkType {
        if (cellular) return NetworkType.INTERNET
        return try {
            val addr = InetAddress.getByName(host)
            when {
                addr.isLoopbackAddress || addr.isLinkLocalAddress -> NetworkType.LAN
                addr is Inet4Address -> {
                    val b = addr.address
                    val isCgnat = (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
                    when {
                        isCgnat -> NetworkType.INTERNET // 100.64/10: Tailscale/CGNAT — remote path
                        addr.isSiteLocalAddress -> NetworkType.LAN // RFC1918
                        else -> NetworkType.INTERNET
                    }
                }
                addr is Inet6Address && (addr.address[0].toInt() and 0xFE) == 0xFC -> NetworkType.LAN // ULA fc00::/7
                else -> NetworkType.INTERNET
            }
        } catch (e: Exception) {
            Timber.w("Quality: server address classification failed (${e.message}) — assuming INTERNET")
            NetworkType.INTERNET
        }
    }

    /** Interface name + default gateway — distinguishes networks without needing SSID/location permission. */
    private fun fingerprint(cm: ConnectivityManager?): String {
        return try {
            val lp = cm?.getLinkProperties(cm.activeNetwork) ?: return "unknown"
            val gateway = lp.routes
                .firstOrNull { it.gateway != null && it.destination?.prefixLength == 0 }
                ?.gateway?.hostAddress
            "${lp.interfaceName ?: "?"}@${gateway ?: "?"}"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun cacheKey(userId: String?, env: NetworkEnv): String =
        "${env.serverHost}|${userId ?: "-"}|${env.type}|${env.fingerprint}"

    companion object {
        private const val PROBE_SMALL_BYTES = 1_000_000
        private const val PROBE_LARGE_BYTES = 8_000_000
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val FAST_COMPLETION_MS = 300L
        private const val MIN_MEASURED_BYTES = 128L * 1024
        private const val MIN_SANE_BPS = 100_000L
        private const val ENV_TTL_MS = 30_000L
    }
}
