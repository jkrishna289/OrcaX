package com.github.jkrishna289.orcax.ui.playback

import com.github.jkrishna289.orcax.ui.playback.quality.NetworkAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.QualityDecisionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Quality mode controller for the Orca X player.
 *
 * AUTO computes ONE resolution per item from the NetworkAnalyzer's measurement
 * (cached across items and sessions) and applies it at playback start. There is
 * NO live adaptive switching here: no oscillation, no cooldown locks, no
 * buffer-triggered tier flips — downgrades happen only through the rescue
 * engine on observed buffer starvation, never on predictions.
 * See docs/quality-audit.md for the bug history that killed the old design.
 *
 * Persistence:
 *  MOVIE → always resets to AUTO on new item.
 *  TV    → keeps the chosen tier across episodes of the same series
 *          (in-memory; Room persistence lands with the recommendation engine).
 */
class QualityManager(
    private val networkAnalyzer: NetworkAnalyzer,
) {
    // ─── State ────────────────────────────────────────────────────────────────

    private val _selectedTier = MutableStateFlow(QualityTier.AUTO)
    val selectedTier: StateFlow<QualityTier> = _selectedTier

    private val _resolvedTier = MutableStateFlow<QualityTier?>(null)
    val resolvedTier: StateFlow<QualityTier?> = _resolvedTier

    /** True while a bandwidth probe is in flight — drives the picker spinner. */
    val isMeasuring: StateFlow<Boolean> get() = networkAnalyzer.isMeasuring

    /** Peak bitrate of the current media item (bps). 0 = unknown. */
    private var peakBitrateBps: Int = 0

    /** True when a heavy subtitle codec (PGS / ASS / SSA / VOBSUB) is active. */
    private var subtitleRiskActive: Boolean = false

    private var persistedSeriesId: UUID? = null
    private var persistedTier: QualityTier = QualityTier.AUTO

    /**
     * Epoch guard: bumped on every new item and every selection. An async
     * resolve started under an older epoch discards its result — movie A's
     * measurement can never stamp movie B's tier (audit #9/#10).
     */
    private val epoch = AtomicLong(0)

    // ─── Derived properties ───────────────────────────────────────────────────

    val effectiveBitrateBps: Int
        get() = when {
            _selectedTier.value == QualityTier.AUTO ->
                _resolvedTier.value?.maxBitrateBps ?: Int.MAX_VALUE
            else -> _selectedTier.value.maxBitrateBps
        }

    val effectiveEnableDirectPlay: Boolean
        get() = when {
            // AUTO: once resolved, honor the resolved tier's play method — a
            // resolved transcode tier must not direct-play the original.
            // Unresolved (measurement pending) → allow direct play freely.
            _selectedTier.value == QualityTier.AUTO ->
                _resolvedTier.value?.isDirectPlay ?: true
            else -> _selectedTier.value.isDirectPlay
        }

    /**
     * Whether the server may stream-copy the source audio codec.
     * Transcode tiers must re-encode audio: stream-copying can carry an audio
     * codec the device profile overclaims support for, producing silent playback.
     */
    val effectiveAllowAudioStreamCopy: Boolean
        get() = effectiveEnableDirectPlay

    val toolbarLabel: String
        get() = when (_selectedTier.value) {
            QualityTier.AUTO -> _resolvedTier.value?.let { "Auto · ${it.label}" } ?: "Auto"
            else -> _selectedTier.value.label
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    suspend fun onNewItem(seriesId: UUID?, userId: String?) {
        epoch.incrementAndGet()
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
        // Synchronous per-item state reset BEFORE any stream build — kills the
        // stale-tier race where the previous item's flags leak into this one.
        subtitleRiskActive = false
        peakBitrateBps = 0
        if (_selectedTier.value == QualityTier.AUTO) resolveAuto(userId)
    }

    /**
     * User picked a tier. Manual tiers apply immediately; AUTO resolves from
     * the (usually cached) measurement. Runs off the main thread; the picker
     * spinner is driven by [isMeasuring], so the UI never blocks (audit #28).
     */
    suspend fun selectTier(tier: QualityTier, currentSeriesId: UUID?, userId: String?) {
        epoch.incrementAndGet()
        _selectedTier.value = tier
        if (tier == QualityTier.AUTO) {
            _resolvedTier.value = null
            resolveAuto(userId)
        } else {
            if (currentSeriesId != null) {
                persistedSeriesId = currentSeriesId
                persistedTier = tier
                Timber.d("Quality: persisted ${tier.label} for series $currentSeriesId")
            }
            QualityDecisionLog.log(
                event = "MANUAL_SELECT",
                to = tier.label,
                reason = "user selection",
                mediaBps = peakBitrateBps,
                subtitleRisk = subtitleRiskActive,
            )
        }
    }

    // ─── Media / subtitle inputs ──────────────────────────────────────────────

    /** Inform the engine of the current media item's peak bitrate. */
    fun onMediaProfile(peakBitrateBps: Int) {
        this.peakBitrateBps = peakBitrateBps
        Timber.d("Quality: media peak bitrate = ${"%.1f".format(peakBitrateBps / 1_000_000.0)} Mbps")
    }

    private val HEAVY_SUBTITLE_CODECS = setOf(
        "pgssub", "pgs", "hdmv_pgs_subtitle",
        "ass", "ssa",
        "dvd_subtitle", "vobsub",
    )

    /**
     * Call when the active subtitle stream changes. Sets the burn-in risk flag
     * consumed at RESOLVE time as a modifier — never a one-way mutation of an
     * already-resolved tier (audit #11). The subtitle change's own stream
     * rebuild picks up the recomputed result.
     */
    fun onSubtitleSelected(subtitleCodec: String?) {
        subtitleRiskActive = subtitleCodec?.lowercase()?.let { c ->
            HEAVY_SUBTITLE_CODECS.any { c.contains(it) }
        } == true
        Timber.d("Quality: subtitle codec=$subtitleCodec, risk=$subtitleRiskActive")
    }

    // ─── AUTO resolution (one decision per item; no live switching) ──────────

    private suspend fun resolveAuto(userId: String?) {
        val myEpoch = epoch.get()
        val measurement = networkAnalyzer.measure(userId)
        if (epoch.get() != myEpoch) {
            Timber.d("Quality: discarding stale AUTO resolve (epoch moved)")
            return
        }
        val networkType = networkAnalyzer.lastKnownEnv()?.type ?: NetworkType.INTERNET
        val resolved = if (measurement != null) {
            val base = QualityTier.fromMeasuredBps(measurement.bps, networkType)
            // Heavy subtitle burn-in costs transcode headroom: resolve-time modifier,
            // restores automatically when recomputed without the flag.
            if (subtitleRiskActive) lowerThan(base) ?: base else base
        } else {
            // Connection UNKNOWN — never fabricate a number. Optimistic start at
            // Original; the rescue engine handles it if the network can't keep up.
            QualityTier.CINEMA
        }
        _resolvedTier.value = resolved
        QualityDecisionLog.log(
            event = "RESOLVE",
            to = resolved.label,
            reason = measurement?.let { "${it.source} confidence=${it.confidenceAt()}" } ?: "no usable measurement",
            bandwidthBps = measurement?.bps ?: 0L,
            mediaBps = peakBitrateBps,
            subtitleRisk = subtitleRiskActive,
        )
    }

    private fun lowerThan(tier: QualityTier): QualityTier? = when (tier) {
        QualityTier.CINEMA -> QualityTier.BALANCED
        QualityTier.BALANCED -> QualityTier.DATA_SAVER
        else -> null
    }
}
