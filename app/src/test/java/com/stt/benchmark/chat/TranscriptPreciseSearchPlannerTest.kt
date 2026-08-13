package com.stt.benchmark.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPreciseSearchPlannerTest {
    @Test
    fun hierarchicalStepCountIncludesScanMergeAndFinal() {
        assertEquals(2, TranscriptPreciseSearchPlanner.totalSteps(1))
        assertEquals(9, TranscriptPreciseSearchPlanner.totalSteps(8))
        assertEquals(21, TranscriptPreciseSearchPlanner.totalSteps(17))
    }

    @Test
    fun integerProgressIsMonotonicAndCompletesAtHundred() {
        val total = TranscriptPreciseSearchPlanner.totalSteps(17)
        val values = (0..total).map { TranscriptPreciseSearchPlanner.progressPercent(it, total) }

        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(100, values.last())
    }
}
