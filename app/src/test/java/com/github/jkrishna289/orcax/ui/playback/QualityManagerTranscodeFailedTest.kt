package com.github.jkrishna289.orcax.ui.playback

import com.github.jkrishna289.orcax.data.QualityPreferenceDao
import com.github.jkrishna289.orcax.ui.playback.quality.BandwidthMeasurement
import com.github.jkrishna289.orcax.ui.playback.quality.MeasurementSource
import com.github.jkrishna289.orcax.ui.playback.quality.NetworkAnalyzer
import com.github.jkrishna289.orcax.ui.playback.quality.QualityResolver
import com.github.jkrishna289.orcax.ui.playback.quality.QualityRung
import com.github.jkrishna289.orcax.ui.playback.quality.QualitySelection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QualityManagerTranscodeFailedTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val dao = mockk<QualityPreferenceDao>(relaxed = true)
    private val network =
        mockk<NetworkAnalyzer>(relaxed = true) {
            // A fresh, fully trusted 3 Mbps reading: too slow for a 20 Mbps file, so AUTO starts capped
            every { cachedMeasurement(any()) } returns BandwidthMeasurement(3_000_000, MeasurementSource.EXO_DIRECT)
            coEvery { measure(any(), any()) } returns null
        }
    private val manager = QualityManager(network, dao, scope)
    private val profile =
        QualityResolver.MediaQualityProfile(videoBitrateBps = 20_000_000, width = 1920, height = 1080, videoCodec = "h264")

    @After
    fun tearDown() = scope.cancel()

    private suspend fun startItem() {
        coEvery { dao.get(any(), any()) } returns null
        manager.onNewItem(seriesId = null, itemId = UUID.randomUUID(), userRowId = 1, userId = "u")
        manager.onMediaProfile(profile, QualityResolver.DeviceQualityCaps())
    }

    @Test
    fun `a failed transcode lifts an AUTO cap once, then the error is real`() =
        runTest {
            startItem()
            // Items start in Original; switching to Auto on a slow link is what caps it.
            manager.selectMode(QualitySelection.Auto)
            assertFalse("AUTO should start capped to a transcode", manager.effectiveEnableDirectPlay)

            assertTrue(manager.onTranscodeFailed())
            assertTrue("direct play allowed again", manager.effectiveEnableDirectPlay)

            // Nothing left to lift, so a second failure must surface instead of looping
            assertFalse(manager.onTranscodeFailed())
        }

    @Test
    fun `a manual quality pick is never overridden`() =
        runTest {
            startItem()
            manager.selectMode(QualitySelection.Rung(QualityRung.R4))

            assertFalse(manager.onTranscodeFailed())
            assertFalse(manager.effectiveEnableDirectPlay)
        }
}
