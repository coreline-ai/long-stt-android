package com.stt.benchmark.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptionPlanTest {

    @Test
    fun `599999ms is one complete chunk`() {
        val plan = TranscriptionPlan.create(599_999L)

        assertEquals(1, plan.totalChunks)
        assertEquals(0L, plan.chunks.single().primaryStartMs)
        assertEquals(599_999L, plan.chunks.single().primaryEndMs)
        assertEquals(599_999L, plan.chunks.single().decodeEndMs)
    }

    @Test
    fun `600000ms is exactly one chunk`() {
        val plan = TranscriptionPlan.create(600_000L)

        assertEquals(1, plan.totalChunks)
        assertEquals(600_000L, plan.chunks.single().primaryEndMs)
    }

    @Test
    fun `600001ms creates a second chunk without a primary gap`() {
        val plan = TranscriptionPlan.create(600_001L)

        assertEquals(2, plan.totalChunks)
        assertEquals(600_000L, plan.chunks[0].primaryEndMs)
        assertEquals(600_000L, plan.chunks[1].primaryStartMs)
        assertEquals(599_000L, plan.chunks[1].decodeStartMs)
        assertEquals(600_001L, plan.chunks[1].decodeEndMs)
    }

    @Test
    fun `reference six hour session creates 38 chunks with full coverage`() {
        val durationMs = 22_403_578L
        val plan = TranscriptionPlan.create(durationMs)

        assertEquals(38, plan.totalChunks)
        assertEquals(0L, plan.chunks.first().primaryStartMs)
        assertEquals(durationMs, plan.chunks.last().primaryEndMs)
        plan.chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.primaryEndMs, right.primaryStartMs)
        }
    }

    @Test
    fun `zero duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptionPlan.create(0L)
        }
    }
}
