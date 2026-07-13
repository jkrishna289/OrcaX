package com.github.jkrishna289.orcax.ui.playback

import com.github.jkrishna289.orcax.data.QualityPreferenceDao
import com.github.jkrishna289.orcax.data.model.QualityPreference
import com.github.jkrishna289.orcax.ui.playback.quality.BandwidthMeasurement
import com.github.jkrishna289.orcax.ui.playback.quality.NetworkAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.PlaybackConstraints
import com.github.jkrishna289.orcax.ui.playback.quality.PlaybackHealthTracker
import com.github.jkrishna289.orcax.ui.playback.quality.QualityDecisionLog
import com.github.jkrishna289.orcax.ui.playback.quality.QualityRecommendation
import com.github.jkrishna289.orcax.ui.playback.quality.QualityResolver
import com.github.jkrishna289.orcax.ui.playback.quality.QualityRung
import com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Quality engine → UI/pipeline notifications. Collected by PlaybackViewModel. */
sealed interface QualityEvent {
    /** Rescue: observed starvation → rebuild at [rung] + toast. */
    data class RescueDowngrade(val rung: QualityRung, val secondStage: Boolean) : QualityEvent

    /** Manual pick is struggling — informational toast ONLY, never auto-switched. */
    data object ManualStruggling : QualityEvent

    /** Warm-cache pre-empt: AUTO started reduced because the cache proves Original can't play. */
    data class StartedReduced(val rung: QualityRung, val measuredBps: Long?) : QualityEvent
}

/**
 * Quality controller for the Orca X player.
 *
 * AUTO semantics (product rulings 2026-07-13, docs/quality-audit.md):
 *  - Starts at Original. Exception: a fresh HIGH-confidence cached measurement
 *    proving Original can't play starts at the recommended rung (+ toast), and
 *    known-incompatible source audio starts re-encoded (silent-playback guard).
 *  - Downgrades ONLY on observed buffer starvation (rescue), never predictions.
 *    After a rescue: speed test only — hold if the current rung fits, else one
 *    more resolver-guided downgrade. One-way ratchet, max 2 rescues per item.
 *  - No upgrades mid-session; fresh measurements benefit the picker and the
 *    NEXT item.
 *  - Manual picks are never auto-switched (informational toast on starvation).
 *
 * Persistence: manual picks per userId+subjectId (seriesId for episodes,
 * itemId for movies) in Room; AUTO deletes the row.
 */
class QualityManager(
    private val networkAnalyzer: NetworkAnalyzer,
    private val qualityPreferenceDao: QualityPreferenceDao,
    private val scope: CoroutineScope,
) {
    // ─── State ────────────────────────────────────────────────────────────────

    private val _mode = MutableStateFlow<QualitySelection>(QualitySelection.Auto)
    val mode: StateFlow<QualitySelection> = _mode

    private val _recommendation = MutableStateFlow<QualityRecommendation?>(null)
    val recommendation: StateFlow<QualityRecommendation?> = _recommendation

    /** True while a bandwidth probe is in flight — drives the picker spinner. */
    val isMeasuring: StateFlow<Boolean> get() = networkAnalyzer.isMeasuring

    private val _events = MutableSharedFlow<QualityEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<QualityEvent> = _events.asSharedFlow()

    val healthTracker = PlaybackHealthTracker()

    private var media: QualityResolver.MediaQualityProfile? = null
    private var deviceCaps = QualityResolver.DeviceQualityCaps()
    private var subtitleRiskActive = false
    private var userRowId: Int? = null
    private var userId: String? = null
    private var subjectId: UUID? = null

    /**
     * AUTO's decision for THIS session, frozen at item start. Later measurement
     * updates refresh the picker/next item but never silently change the
     * constraints an unrelated rebuild (e.g. a subtitle toggle) would pick up.
     */
    private var sessionSelection: QualitySelection = QualitySelection.Original

    private var rescueRung: QualityRung? = null
    private var rescueCount = 0
    private var manualToastShown = false

    /**
     * Epoch guard: bumped on every new item and selection; async work from an
     * older epoch discards its result (audit #9/#10).
     */
    private val epoch = AtomicLong(0)

    // ─── Pipeline contract (consumed by changeStreams) ────────────────────────

    private fun activeConstraints(): PlaybackConstraints {
        rescueRung?.let { rung ->
            return PlaybackConstraints(
                maxBitrateBps = rung.maxBitrateBps,
                directPlayAllowed = false,
                audioStreamCopyAllowed = _recommendation.value?.audioStreamCopySafe == true,
            )
        }
        return when (val m = _mode.value) {
            QualitySelection.Auto ->
                _recommendation.value?.constraintsFor(sessionSelection)
                    ?: PlaybackConstraints.ORIGINAL
            QualitySelection.Original -> PlaybackConstraints.ORIGINAL
            is QualitySelection.Rung -> PlaybackConstraints(
                maxBitrateBps = m.rung.maxBitrateBps,
                directPlayAllowed = false,
                audioStreamCopyAllowed = _recommendation.value?.audioStreamCopySafe == true,
            )
        }
    }

    val effectiveBitrateBps: Int get() = activeConstraints().maxBitrateBps

    val effectiveEnableDirectPlay: Boolean get() = activeConstraints().directPlayAllowed

    /**
     * Whether the server may stream-copy the source audio codec. Transcodes
     * re-encode audio unless the codec is KNOWN supported: stream-copying a
     * codec the device profile overclaims produces silent playback.
     */
    val effectiveAllowAudioStreamCopy: Boolean get() = activeConstraints().audioStreamCopyAllowed

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * New item starting. Synchronous state reset BEFORE any stream build (kills
     * the stale-tier race), then loads the persisted manual pick if any.
     * Call before choosing the media source.
     */
    suspend fun onNewItem(
        seriesId: UUID?,
        itemId: UUID,
        userRowId: Int?,
        userId: String?,
    ) {
        epoch.incrementAndGet()
        this.userRowId = userRowId
        this.userId = userId
        this.subjectId = seriesId ?: itemId
        media = null
        subtitleRiskActive = false
        sessionSelection = QualitySelection.Original
        rescueRung = null
        rescueCount = 0
        manualToastShown = false
        healthTracker.reset()
        _recommendation.value = null

        val persisted = userRowId?.let { rowId ->
            subjectId?.let { subject ->
                QualitySelection.fromPersisted(qualityPreferenceDao.get(rowId, subject)?.selection)
            }
        }
        _mode.value = persisted ?: QualitySelection.Auto
        if (persisted != null) {
            Timber.d("Quality: restored persisted ${persisted.persistKey()} for subject $subjectId")
        }
    }

    /**
     * Media profile known (after StreamChoiceService picked the source).
     * Synchronously decides the start selection from the CACHED measurement
     * only, then refines in the background for the picker and future items.
     * Must complete before the first changeStreams call.
     */
    fun onMediaProfile(
        profile: QualityResolver.MediaQualityProfile,
        caps: QualityResolver.DeviceQualityCaps,
    ) {
        media = profile
        deviceCaps = caps
        val cached = networkAnalyzer.cachedMeasurement(userId)
        val rec = recompute(cached)
        if (_mode.value == QualitySelection.Auto) {
            sessionSelection = rec.startSelection
            (sessionSelection as? QualitySelection.Rung)?.let { start ->
                QualityDecisionLog.log(
                    event = "START_REDUCED",
                    to = start.rung.name,
                    reason = "warm cache pre-empt / audio compatibility",
                    bandwidthBps = cached?.bps ?: 0L,
                    mediaBps = profile.videoBitrateBps,
                    subtitleRisk = subtitleRiskActive,
                    sessionId = sessionId(),
                )
                _events.tryEmit(QualityEvent.StartedReduced(start.rung, cached?.bps))
            }
        }
        QualityDecisionLog.log(
            event = "RESOLVE",
            to = describe(rec.recommended),
            reason = cached?.let { "${it.source} confidence=${it.confidenceAt()}" } ?: "no cached measurement",
            bandwidthBps = cached?.bps ?: 0L,
            mediaBps = profile.videoBitrateBps,
            subtitleRisk = subtitleRiskActive,
            sessionId = sessionId(),
        )

        // Background refinement: probe if the cache is cold. Updates the picker
        // and future items — NEVER this session's start decision.
        val myEpoch = epoch.get()
        scope.launch {
            val measured = networkAnalyzer.measure(userId)
            if (epoch.get() == myEpoch && measured != null && media != null) {
                recompute(measured)
            }
        }
    }

    /**
     * User picked a quality. Manual rungs are persisted (absolute, deterministic
     * across sessions); AUTO deletes the row. Non-blocking probe via
     * [isMeasuring]; caller rebuilds the stream afterwards.
     */
    suspend fun selectMode(selection: QualitySelection) {
        epoch.incrementAndGet()
        rescueRung = null
        rescueCount = 0
        _mode.value = selection

        when (selection) {
            QualitySelection.Auto -> {
                val cached = networkAnalyzer.cachedMeasurement(userId)
                val rec = if (media != null) recompute(cached) else _recommendation.value
                // Explicit user action: apply the recommendation now (the caller
                // rebuilds anyway), not just at item start.
                sessionSelection = rec?.let {
                    if (it.originalVerdict.playable) QualitySelection.Original else it.recommended
                } ?: QualitySelection.Original
                userRowId?.let { rowId -> subjectId?.let { qualityPreferenceDao.delete(rowId, it) } }
            }
            else -> {
                userRowId?.let { rowId ->
                    subjectId?.let { subject ->
                        selection.persistKey()?.let { key ->
                            qualityPreferenceDao.save(
                                QualityPreference(userId = rowId, subjectId = subject, selection = key),
                            )
                        }
                    }
                }
            }
        }
        QualityDecisionLog.log(
            event = "MANUAL_SELECT",
            to = describe(selection),
            reason = "user selection",
            mediaBps = media?.videoBitrateBps ?: 0,
            subtitleRisk = subtitleRiskActive,
            sessionId = sessionId(),
        )
    }

    /**
     * Subtitle stream changed. Sets the burn-in flag and recomputes the
     * recommendation (resolve-time modifier — restores automatically when the
     * heavy subtitle is disabled). The subtitle change's own single rebuild in
     * changeSubtitleStream picks up any constraint change; no extra emit.
     */
    fun onSubtitleSelected(subtitleCodec: String?) {
        subtitleRiskActive = subtitleCodec?.lowercase()?.let { c ->
            HEAVY_SUBTITLE_CODECS.any { c.contains(it) }
        } == true
        Timber.d("Quality: subtitle codec=$subtitleCodec, risk=$subtitleRiskActive")
        if (media != null) recompute(networkAnalyzer.cachedMeasurement(userId))
    }

    // ─── Rescue engine (observed starvation only — ruling 1) ─────────────────

    fun onPlaybackStarted() = healthTracker.onPlaybackStarted()

    fun onSeek() = healthTracker.onSeek()

    fun onStreamRebuilt() = healthTracker.onStreamRebuilt()

    fun onRebuffer(positionMs: Long, durationMs: Long, isSwitchingStream: Boolean) {
        if (healthTracker.onRebuffer(positionMs, durationMs, isSwitchingStream) ==
            PlaybackHealthTracker.Verdict.RESCUE
        ) {
            triggerRescue("repeated rebuffering")
        }
    }

    fun onStallExceeded(positionMs: Long, durationMs: Long, isSwitchingStream: Boolean) {
        if (healthTracker.onStallExceeded(positionMs, durationMs, isSwitchingStream) ==
            PlaybackHealthTracker.Verdict.RESCUE
        ) {
            triggerRescue("stall > ${PlaybackHealthTracker.STALL_LIMIT_MS} ms")
        }
    }

    private fun triggerRescue(cause: String) {
        // Manual picks are never auto-switched (ruling 6).
        if (_mode.value != QualitySelection.Auto) {
            if (!manualToastShown) {
                manualToastShown = true
                QualityDecisionLog.log(
                    event = "MANUAL_STRUGGLING",
                    to = describe(_mode.value),
                    reason = cause,
                    sessionId = sessionId(),
                )
                _events.tryEmit(QualityEvent.ManualStruggling)
            }
            return
        }
        if (rescueCount >= MAX_RESCUES) return

        val current = effectiveBitrateBps
        val target = QualityResolver.rescueTarget(_recommendation.value, current) ?: return
        val from = describe(rescueRung?.let { QualitySelection.Rung(it) } ?: sessionSelection)
        rescueRung = target
        rescueCount++
        QualityDecisionLog.log(
            event = "RESCUE",
            from = from,
            to = target.name,
            reason = cause,
            mediaBps = media?.videoBitrateBps ?: 0,
            subtitleRisk = subtitleRiskActive,
            sessionId = sessionId(),
        )
        _events.tryEmit(QualityEvent.RescueDowngrade(target, secondStage = false))

        // Post-switch verification: speed test ONLY (ruling 1). Hold if the
        // current rung fits the measured bandwidth; otherwise one more
        // resolver-guided downgrade. Never an upgrade.
        val myEpoch = epoch.get()
        scope.launch {
            val measured = networkAnalyzer.measure(userId, force = true) ?: return@launch
            if (epoch.get() != myEpoch) return@launch
            if (media != null) recompute(measured)
            val currentRung = rescueRung ?: return@launch
            val safeBps = (measured.bps * QualityResolver.SAFETY_FACTOR).toLong()
            if (currentRung.maxBitrateBps <= safeBps) {
                QualityDecisionLog.log(
                    event = "RESCUE_HOLD",
                    to = currentRung.name,
                    reason = "verified: rung fits measured bandwidth",
                    bandwidthBps = measured.bps,
                    sessionId = sessionId(),
                )
            } else if (rescueCount < MAX_RESCUES) {
                val next = QualityResolver.rescueTarget(_recommendation.value, currentRung.maxBitrateBps)
                if (next != null) {
                    rescueRung = next
                    rescueCount++
                    QualityDecisionLog.log(
                        event = "RESCUE",
                        from = currentRung.name,
                        to = next.name,
                        reason = "verified: rung still exceeds measured bandwidth",
                        bandwidthBps = measured.bps,
                        sessionId = sessionId(),
                    )
                    _events.tryEmit(QualityEvent.RescueDowngrade(next, secondStage = true))
                }
            }
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun recompute(measurement: BandwidthMeasurement?): QualityRecommendation {
        val profile = requireNotNull(media)
        val rec = QualityResolver.resolve(
            measurement = measurement,
            media = profile,
            device = deviceCaps,
            subtitleBurnInRisk = subtitleRiskActive,
        ).let { r ->
            r.copy(connection = r.connection.copy(networkType = networkAnalyzer.lastKnownEnv()?.type))
        }
        _recommendation.value = rec
        return rec
    }

    private fun describe(selection: QualitySelection): String = when (selection) {
        QualitySelection.Auto -> "AUTO"
        QualitySelection.Original -> "ORIGINAL"
        is QualitySelection.Rung -> selection.rung.name
    }

    private fun sessionId(): String = "${subjectId?.toString()?.take(8) ?: "-"}#${epoch.get()}"

    companion object {
        const val MAX_RESCUES = 2

        private val HEAVY_SUBTITLE_CODECS = setOf(
            "pgssub", "pgs", "hdmv_pgs_subtitle",
            "ass", "ssa",
            "dvd_subtitle", "vobsub",
        )
    }
}
