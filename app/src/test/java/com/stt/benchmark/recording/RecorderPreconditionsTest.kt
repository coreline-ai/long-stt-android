package com.stt.benchmark.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderPreconditionsTest {
    @Test
    fun permissionAndInputFailuresPrecedeStorageChecks() {
        assertEquals(
            RecorderPreconditions.Result.PermissionRequired,
            RecorderPreconditions.evaluate(false, usableInput = false, availableBytes = 0),
        )
        assertEquals(
            RecorderPreconditions.Result.UnsupportedInput,
            RecorderPreconditions.evaluate(true, usableInput = false, availableBytes = Long.MAX_VALUE),
        )
    }

    @Test
    fun wavWorstCaseStorageIsRequired() {
        val estimate = RecordingStorageEstimator.estimate(RecordingSessionStore.DEFAULT_CHUNK_DURATION_MS)
        val failed = RecorderPreconditions.evaluate(
            permissionGranted = true,
            usableInput = true,
            availableBytes = estimate.requiredWorstCaseBytes - 1,
        )
        val ready = RecorderPreconditions.evaluate(
            permissionGranted = true,
            usableInput = true,
            availableBytes = estimate.requiredWorstCaseBytes,
        )

        assertTrue(failed is RecorderPreconditions.Result.InsufficientStorage)
        assertEquals(RecorderPreconditions.Result.Ready, ready)
    }
}
