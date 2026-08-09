package com.stt.benchmark.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStorageEstimatorTest {
    @Test
    fun wavFallbackDeterminesWorstCaseRequirement() {
        val estimate = RecordingStorageEstimator.estimate(20L * 60L * 1_000L)

        assertTrue(estimate.pcmWavBytes > estimate.aacDefaultBytes)
        assertEquals(
            estimate.pcmWavBytes + RecordingStorageEstimator.DEFAULT_RESERVE_BYTES,
            estimate.requiredWorstCaseBytes,
        )
    }

    @Test
    fun preflightReportsExactShortageWithoutOptimisticAacAssumption() {
        val estimate = RecordingStorageEstimator.estimate(60_000)
        val available = estimate.requiredWorstCaseBytes - 1
        val failed = RecordingStorageEstimator.preflight(available, 60_000)
        val passed = RecordingStorageEstimator.preflight(estimate.requiredWorstCaseBytes, 60_000)

        assertFalse(failed.allowed)
        assertEquals(1L, failed.shortageBytes)
        assertTrue(passed.allowed)
        assertEquals(0L, passed.shortageBytes)
    }

    @Test
    fun maximumDurationUsesPcmRateAfterKeepingReserve() {
        val oneHourPcm = 48_000L * 2L * 3_600L
        val available = RecordingStorageEstimator.DEFAULT_RESERVE_BYTES + 44L + oneHourPcm

        assertEquals(3_600_000L, RecordingStorageEstimator.estimateMaxDurationMs(available))
        assertEquals(
            0L,
            RecordingStorageEstimator.estimateMaxDurationMs(
                RecordingStorageEstimator.DEFAULT_RESERVE_BYTES,
            ),
        )
    }
}
