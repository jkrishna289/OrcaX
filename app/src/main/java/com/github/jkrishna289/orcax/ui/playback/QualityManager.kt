package com.github.jkrishna289.orcax.ui.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.net.Inet4Address
import java.util.UUID
import kotlin.time.measureTime

/**
 * QualityManager — AUTO quality engine for the Orca X player.
 *
 * Decision order (spec §4 priority):
 *  1. DIRECT PLAY  (CINEMA tier, isDirectPlay = true)
 *  2. DIRECT STREAM
 *  3. BALANCED / DATA_SAVER transcode
 *
 * AUTO resolution path:
 *  1. Detect network type (LAN private-IP vs Internet vs Unstable high-variance).
 *  2. Run 1–2 HTTP probes; compute avgThroughput and probe-to-probe variance.
 *  3. Compute: usableBandwidth = avgThroughput × safetyFactor(networkType)
 *              safeBitrate     = usableBandwidth × 0.85
 *  4. Apply subtitle risk penalty (PGS/ASS/SSA → reduce tier by one).
 *  5. Apply peak protection (peakBitrate > usable × 1.3 → block CINEMA).
 *  6. Select the highest tier whose maxBitrateBps ≤ safeBitrate.
 *
 * Monitoring loop (caller feeds stats via onPlaybackStats every ~3 s):
 *  - Buffer < 2 s   → immediate downgrade.
 *  - Dropped frames > 5 %  → immediate downgrade (overrides network speed).
 *  - Buffer > 30 s stable  → upgrade after network-specific delay:
 *      LAN=8 s, Internet=20 s, post-downgrade lock=45 s.
 *
 * Persistence:
 *  MOVIE  → always resets to AUTO on new item.
 *  TV     → persists chosen tier across episodes of the same series.
 */
class QualityManager(
    private val context: Context,
    private val api: ApiClient,
    private val okHttpClient: OkHttpClient,
) {

    // ─── State ────────────────────────────────────────────────────────────────

    private val _selectedTier = MutableStateFlow(QualityTier.AUTO)
    val selectedTier: StateFlow<QualityTier> = _selectedTier

    private val _resolvedTier = MutableStateFlow<QualityTier?>(null)
    val resolvedTier: StateFlow<QualityTier?> = _resolvedTier

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring

    /**
     * Emits whenever AUTO detects a change significant enough to warrant
     * restarting the stream. Caller should re-invoke changeStreams().
     */
    private val _forceSwitchRequired = MutableSharedFlow<Unit>(replay = 0)
    val forceSwitchRequired: SharedFlow<Unit> = _forceSwitchRequired.asSharedFlow()

    // ─── Internal tracking ────────────────────────────────────────────────────

    var networkType: NetworkType = NetworkType.INTERNET
        private set

    /** Last measured throughput from the probe (bps). Used for peak-protection checks. */
    private var lastMeasuredThroughputBps: Long = 0L

    /** Peak bitrate of the current media item (bps). 0 = unknown. */
    private var peakBitrateBps: Int = 0

    /** True when a heavy subtitle codec (PGS / ASS / SSA) is active. */
    private var subtitleRiskActive: Boolean = false

    private var persistedSeriesId: UUID? = null
    private var persistedTier: QualityTier = QualityTier.AUTO

    private var lastSwitchTime: Long = 0L
    private var lastDowngradeTime: Long = 0L

    // Upgrade delay constants (spec §7)
    private val UPGRADE_DELAY_LAN_MS = 8_000L
    private val UPGRADE_DELAY_INTERNET_MS = 20_000L
    private val DOWNGRADE_LOCK_MS = 45_000L
    private val DROPPED_FRAME_THRESHOLD = 0.05f   // 5 %
    private val BUFFER_CRITICAL_MS = 2_000L        // immediate downgrade
    private val BUFFER_HEALTHY_MS = 30_000L        // upgrade consideration threshold

    // ─── Derived properties ───────────────────────────────────────────────────

    val effectiveBitrateBps: Int
        get() = when {
            _selectedTier.value == QualityTier.AUTO ->
                _resolvedTier.value?.maxBitrateBps ?: Int.MAX_VALUE
            else -> _selectedTier.value.maxBitrateBps
        }

    val effectiveEnableDirectPlay: Boolean
        get() = when {
            _selectedTier.value == QualityTier.AUTO -> true
            else -> _selectedTier.value.isDirectPlay
        }

    val toolbarLabel: String
        get() = when (_selectedTier.value) {
            QualityTier.AUTO -> _resolvedTier.value?.let { "Auto · ${it.label}" } ?: "Auto"
            else -> _selectedTier.value.label
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    suspend fun onNewItem(seriesId: UUID?) {
        if (seriesId == null) {
            _selectedTier.value = QualityTier.AUTO
            _resolvedTier.value = null
            persistedSeriesId = null
            persistedTier = QualityTier.AUTO
        } else {
            if (seriesId == persistedSeriesId) {
                _selectedTier.value = persistedTier
                Timber.d("Quality: restored ${persistedTier.label} for series $seriesId")
            } else {
                _selectedTier.value = QualityTier.AUTO
                _resolvedTier.value = null
                persistedSeriesId = seriesId
                persistedTier = QualityTier.AUTO
            }
        }
        // Reset per-item tracking
        subtitleRiskActive = false
        peakBitrateBps = 0
        if (_selectedTier.value == QualityTier.AUTO) runSpeedTest()
    }

    suspend fun selectTier(tier: QualityTier, currentSeriesId: UUID?) {
        _selectedTier.value = tier
        if (tier == QualityTier.AUTO) {
            _resolvedTier.value = null
            runSpeedTest()
        } else {
            if (currentSeriesId != null) {
                persistedSeriesId = currentSeriesId
                persistedTier = tier
                Timber.d("Quality: persisted ${tier.label} for series $currentSeriesId")
            }
        }
    }

    // ─── Media profile (spec §1, §6 peak protection) ─────────────────────────

    /**
     * Inform the engine of the current media item's peak bitrate.
     * Must be called before or shortly after [onNewItem].
     * Spec §6: if peakBitrate > usableBandwidth × 1.3, block CINEMA.
     */
    fun onMediaProfile(peakBitrateBps: Int) {
        this.peakBitrateBps = peakBitrateBps
        Timber.d("Quality: media peak bitrate = ${"%.1f".format(peakBitrateBps / 1_000_000.0)} Mbps")
    }

    // ─── Subtitle risk (spec §5) ──────────────────────────────────────────────

    private val HEAVY_SUBTITLE_CODECS = setOf(
        "pgssub", "pgs", "hdmv_pgs_subtitle",
        "ass", "ssa",
        "dvd_subtitle", "vobsub",
    )

    /**
     * Call when the active subtitle stream changes.
     * PGS / ASS / SSA subtitles carry a +30% risk score and reduce the
     * resolved tier by one level (spec §5).
     */
    fun onSubtitleSelected(subtitleCodec: String?) {
        val wasHeavy = subtitleRiskActive
        subtitleRiskActive = subtitleCodec?.lowercase()?.let { c ->
            HEAVY_SUBTITLE_CODECS.any { c.contains(it) }
        } == true
        Timber.d("Quality: subtitle codec=$subtitleCodec, risk=$subtitleRiskActive")
        if (subtitleRiskActive != wasHeavy &&
            _selectedTier.value == QualityTier.AUTO &&
            _resolvedTier.value != null
        ) {
            applySubtitlePenalty()
        }
    }

    private fun applySubtitlePenalty() {
        if (!subtitleRiskActive) return
        val current = _resolvedTier.value ?: return
        val penalized = lowerThan(current) ?: return
        if (_resolvedTier.value != penalized) {
            logDecision(
                event = "SUBTITLE_PENALTY",
                from = current.label,
                to = penalized.label,
                reason = "PGS/ASS/SSA subtitle active — risk +30%",
            )
            _resolvedTier.value = penalized
            _forceSwitchRequired.tryEmit(Unit)
        }
    }

    // ─── Live playback monitoring (spec §7, §8, §9) ──────────────────────────

    /**
     * Call from a periodic monitoring loop (every ~3 s) while a stream is playing.
     * Implements spec §7 buffer rules and §8 decoder safety rule.
     *
     * @param forwardBufferMs  ms of content buffered ahead of current position
     * @param deltaRendered    frames rendered since last call
     * @param deltaDropped     frames dropped since last call
     */
    fun onPlaybackStats(forwardBufferMs: Long, deltaRendered: Int, deltaDropped: Int) {
        if (_selectedTier.value != QualityTier.AUTO) return
        val currentTier = _resolvedTier.value ?: return

        // §8 Decoder safety rule: > 5% dropped → immediate downgrade (ignores network)
        val droppedRatio = if (deltaRendered > 20) deltaDropped.toFloat() / deltaRendered else 0f
        if (droppedRatio > DROPPED_FRAME_THRESHOLD) {
            Timber.w("Quality: dropped %.1f%% frames — downgrading".format(droppedRatio * 100))
            val lower = lowerThan(currentTier) ?: return
            triggerTierSwitch(currentTier, lower, "decoder overload")
            return
        }

        // §7 Buffer critical (< 2 s) → immediate downgrade
        if (forwardBufferMs < BUFFER_CRITICAL_MS) {
            val lower = lowerThan(currentTier) ?: return
            Timber.w("Quality: buffer critical (${forwardBufferMs}ms) — downgrading")
            triggerTierSwitch(currentTier, lower, "buffer critical")
            return
        }

        // §7 Buffer healthy and stable → consider upgrade
        if (forwardBufferMs >= BUFFER_HEALTHY_MS && currentTier != QualityTier.CINEMA) {
            val upgradeDelay = upgradeDelayMs()
            val now = System.currentTimeMillis()
            val cooldownOver = (now - lastSwitchTime) > upgradeDelay
            val notLockedAfterDowngrade = (now - lastDowngradeTime) > DOWNGRADE_LOCK_MS
            if (cooldownOver && notLockedAfterDowngrade) {
                val higher = higherThan(currentTier) ?: return
                if (!isPeakSafe(higher)) {
                    Timber.d("Quality: peak protection blocks upgrade to ${higher.label}")
                    return
                }
                Timber.i("Quality: buffer healthy (${forwardBufferMs}ms) → upgrading to ${higher.label}")
                triggerTierSwitch(currentTier, higher, "buffer healthy")
            }
        }
    }

    // ─── Live bandwidth adaptation (spec §7 — ExoPlayer BandwidthMeter) ──────

    /**
     * Feed ExoPlayer BandwidthMeter readings here for live adaptive switching.
     * Only acts in AUTO mode after the initial probe has completed.
     *
     * Drops  → immediate (prevent buffering).
     * Gains  → debounced per network type; blocked during post-downgrade lock.
     */
    fun onBandwidthMeasured(bitrateBps: Long) {
        if (_selectedTier.value != QualityTier.AUTO) return
        val currentTier = _resolvedTier.value ?: return
        val newTier = QualityTier.fromMeasuredBps(bitrateBps, networkType)
        if (newTier == currentTier) return

        val isDrop = newTier.maxBitrateBps < currentTier.maxBitrateBps
        if (isDrop) {
            Timber.i("Quality: live drop — ${currentTier.label} → ${newTier.label} " +
                    "(${"%.2f".format(bitrateBps / 1_000_000.0)} Mbps)")
            triggerTierSwitch(currentTier, newTier, "bandwidth drop")
        } else {
            val now = System.currentTimeMillis()
            val postDowngradeLock = (now - lastDowngradeTime) < DOWNGRADE_LOCK_MS
            val cooldownOver = (now - lastSwitchTime) > upgradeDelayMs()
            if (!postDowngradeLock && cooldownOver) {
                if (!isPeakSafe(newTier)) {
                    Timber.d("Quality: peak protection blocks upgrade to ${newTier.label}")
                    return
                }
                Timber.i("Quality: live gain — ${currentTier.label} → ${newTier.label} " +
                        "(${"%.2f".format(bitrateBps / 1_000_000.0)} Mbps)")
                triggerTierSwitch(currentTier, newTier, "bandwidth gain")
            }
        }
    }

    // ─── Speed probe ──────────────────────────────────────────────────────────

    private suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
        _isMeasuring.value = true
        _resolvedTier.value = null
        Timber.d("Quality: starting speed probe")

        try {
            // Step 2: detect network type
            networkType = detectNetworkType()
            Timber.d("Quality: network type = $networkType")

            // Run first probe
            val bps1 = runSingleProbe()

            // Run second probe to check variance (only if first succeeded)
            val bps2 = if (bps1 > 0) runSingleProbe() else -1L

            val validProbes = listOf(bps1, bps2).filter { it > 0 }
            if (validProbes.isEmpty()) throw Exception("All probes failed or unreliable")

            val avgBps = validProbes.map { it.toDouble() }.average().toLong()

            // Variance check: if two probes differ by > 30%, mark UNSTABLE
            if (validProbes.size >= 2) {
                val (lo, hi) = validProbes.sorted().let { it[0] to it[1] }
                val cv = (hi - lo).toDouble() / avgBps
                if (cv > 0.30) {
                    networkType = NetworkType.UNSTABLE
                    Timber.w("Quality: high probe variance (${"%.0f".format(cv * 100)}%%) → UNSTABLE")
                }
            }

            lastMeasuredThroughputBps = avgBps
            val resolved = QualityTier.fromMeasuredBps(avgBps, networkType)
            _resolvedTier.value = resolved
            logDecision(
                event = "INITIAL_PROBE",
                to = resolved.label,
                reason = "probe=${"%.1f".format(avgBps / 1_000_000.0)}Mbps network=$networkType",
            )

            // Apply subtitle penalty if a heavy subtitle was already active
            if (subtitleRiskActive) applySubtitlePenalty()

        } catch (ex: Exception) {
            Timber.w("Quality: probe failed: ${ex.message} — falling back to OS link speed")
            val osBps = getOsLinkSpeedBps()
            lastMeasuredThroughputBps = osBps
            val resolved = QualityTier.fromMeasuredBps(osBps, networkType)
            _resolvedTier.value = resolved
            logDecision(
                event = "INITIAL_PROBE_FALLBACK",
                to = resolved.label,
                reason = "os_link=${"%.1f".format(osBps / 1_000_000.0)}Mbps err=${ex.message}",
            )
        } finally {
            _isMeasuring.value = false
        }
    }

    /** Perform a single cache-busted HTTP probe against the Jellyfin server. Returns bps or -1. */
    private suspend fun runSingleProbe(): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            val cb = System.currentTimeMillis()
            val probeUrl = api.createUrl("/Branding/Splashscreen?quality=100&tag=speed-probe&cb=$cb")
            val request = Request.Builder()
                .url(probeUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            var bytesRead = 0L
            val elapsed = measureTime {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val body = response.body ?: throw Exception("Empty body")
                    val buf = ByteArray(8_192)
                    val stream = body.byteStream()
                    var n: Int
                    while (stream.read(buf).also { n = it } != -1) bytesRead += n
                }
            }
            val seconds = elapsed.inWholeMilliseconds / 1_000.0
            when {
                seconds < 0.05 -> {
                    Timber.w("Quality: probe suspiciously fast (${seconds}s) — cache hit?")
                    -1L
                }
                bytesRead == 0L -> -1L
                else -> ((bytesRead * 8) / seconds).toLong()
            }
        } catch (ex: Exception) {
            Timber.w("Quality: single probe failed: ${ex.message}")
            -1L
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun triggerTierSwitch(from: QualityTier, to: QualityTier, reason: String) {
        val isDrop = to.maxBitrateBps < from.maxBitrateBps
        if (isDrop) lastDowngradeTime = System.currentTimeMillis()
        logDecision(
            event = if (isDrop) "DOWNGRADE" else "UPGRADE",
            from = from.label,
            to = to.label,
            reason = reason,
        )
        _resolvedTier.value = to
        lastSwitchTime = System.currentTimeMillis()
        _forceSwitchRequired.tryEmit(Unit)
    }

    /**
     * Emit a structured INFO-level log line for every quality decision.
     * Tag: [AUTO-QUALITY] — filter in Logcat with: adb logcat -s Timber:I | grep AUTO-QUALITY
     *
     * Format:
     *   [AUTO-QUALITY] EVENT | from=X to=Y | reason=Z | network=N | peak=P Mbps | subtitle_risk=B
     */
    private fun logDecision(
        event: String,
        from: String? = null,
        to: String,
        reason: String,
    ) {
        val peakStr = if (peakBitrateBps > 0) "%.1f Mbps".format(peakBitrateBps / 1_000_000.0) else "unknown"
        val measured = if (lastMeasuredThroughputBps > 0) "%.1f Mbps".format(lastMeasuredThroughputBps / 1_000_000.0) else "unknown"
        val fromPart = if (from != null) "from=$from to=$to" else "tier=$to"
        Timber.i(
            "[AUTO-QUALITY] $event | $fromPart | reason=$reason" +
            " | network=$networkType | measured=$measured | peak=$peakStr | subtitle_risk=$subtitleRiskActive"
        )
    }

    private fun upgradeDelayMs(): Long = when (networkType) {
        NetworkType.LAN -> UPGRADE_DELAY_LAN_MS
        NetworkType.INTERNET -> UPGRADE_DELAY_INTERNET_MS
        NetworkType.UNSTABLE -> UPGRADE_DELAY_INTERNET_MS * 2L
    }

    /** Spec §6 peak protection: block CINEMA if peakBitrate > usable × 1.3. */
    private fun isPeakSafe(tier: QualityTier): Boolean {
        if (tier != QualityTier.CINEMA) return true
        if (peakBitrateBps <= 0 || lastMeasuredThroughputBps <= 0L) return true
        val usable = (lastMeasuredThroughputBps * QualityTier.safetyFactor(networkType)).toLong()
        return peakBitrateBps.toLong() <= (usable * 1.3).toLong()
    }

    private fun lowerThan(tier: QualityTier): QualityTier? = when (tier) {
        QualityTier.CINEMA -> QualityTier.BALANCED
        QualityTier.BALANCED -> QualityTier.DATA_SAVER
        else -> null
    }

    private fun higherThan(tier: QualityTier): QualityTier? = when (tier) {
        QualityTier.DATA_SAVER -> QualityTier.BALANCED
        QualityTier.BALANCED -> QualityTier.CINEMA
        else -> null
    }

    /**
     * Detect network type by checking the active network's IP addresses.
     * Private IP ranges (RFC 1918) → LAN; everything else → INTERNET.
     * Cellular transport → always INTERNET regardless of IP.
     */
    private fun detectNetworkType(): NetworkType {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return NetworkType.INTERNET
            val caps = cm.getNetworkCapabilities(network)

            // Cellular → definitely Internet
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                return NetworkType.INTERNET
            }

            // Check IP address for private range
            val linkProps = cm.getLinkProperties(network)
            val isPrivate = linkProps?.linkAddresses?.any { linkAddr ->
                val addr = linkAddr.address
                addr is Inet4Address && addr.isSiteLocalAddress
            } == true

            if (isPrivate) NetworkType.LAN else NetworkType.INTERNET
        } catch (e: Exception) {
            Timber.w(e, "Quality: network type detection failed")
            NetworkType.INTERNET
        }
    }

    /**
     * Reads OS-reported downstream link capacity at 80% for TCP overhead hedge.
     * Returns 2 Mbps as a safe last-resort default (→ BALANCED, never CINEMA).
     */
    private fun getOsLinkSpeedBps(): Long {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val kbps = caps?.linkDownstreamBandwidthKbps ?: -1
            if (kbps > 0) {
                (kbps.toLong() * 1_000L * 80L) / 100L
            } else {
                Timber.w("Quality: OS link speed unavailable — using 2 Mbps safe default")
                2_000_000L
            }
        } catch (e: Exception) {
            Timber.w(e, "Quality: ConnectivityManager failed")
            2_000_000L
        }
    }
}
