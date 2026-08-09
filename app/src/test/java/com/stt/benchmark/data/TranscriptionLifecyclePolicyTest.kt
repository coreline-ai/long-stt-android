package com.stt.benchmark.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class TranscriptionLifecyclePolicyTest {

    @Test
    fun `user cancellation becomes cancelled and service loss becomes interrupted`() {
        assertEquals(
            TranscriptionSessionStore.Status.CANCELLED,
            TranscriptionLifecyclePolicy.terminalStatusForCancellation(userRequested = true)
        )
        assertEquals(
            TranscriptionSessionStore.Status.INTERRUPTED,
            TranscriptionLifecyclePolicy.terminalStatusForCancellation(userRequested = false)
        )
    }

    @Test
    fun `only active and interrupted states are resumable`() {
        val resumable = setOf(
            TranscriptionSessionStore.Status.PREPARING,
            TranscriptionSessionStore.Status.RUNNING,
            TranscriptionSessionStore.Status.COOLING,
            TranscriptionSessionStore.Status.INTERRUPTED
        )

        TranscriptionSessionStore.Status.entries.forEach { status ->
            assertEquals(status in resumable, TranscriptionLifecyclePolicy.canResume(status))
        }
    }

    @Test
    fun `startup reconciliation preserves progress and changes running state only`() {
        val checkpoint = checkpoint(TranscriptionSessionStore.Status.RUNNING).copy(currentChunk = 4)

        val reconciled = TranscriptionLifecyclePolicy.reconcileAfterProcessDeath(checkpoint, nowMs = 2_000L)

        assertEquals(TranscriptionSessionStore.Status.INTERRUPTED, reconciled.status)
        assertEquals(4, reconciled.currentChunk)
        assertEquals(checkpoint.chunks, reconciled.chunks)
        assertEquals(2_000L, reconciled.updatedAtMs)
        assertTrue(reconciled.errorMessage.isNotBlank())
    }

    @Test
    fun `terminal and already interrupted checkpoints are not rewritten`() {
        val unchangedStatuses = setOf(
            TranscriptionSessionStore.Status.INTERRUPTED,
            TranscriptionSessionStore.Status.COMPLETED,
            TranscriptionSessionStore.Status.FAILED,
            TranscriptionSessionStore.Status.CANCELLED
        )

        unchangedStatuses.forEach { status ->
            val checkpoint = checkpoint(status)
            val reconciled = TranscriptionLifecyclePolicy.reconcileAfterProcessDeath(checkpoint, nowMs = 9_999L)
            assertSame(checkpoint, reconciled)
            assertFalse(TranscriptionLifecyclePolicy.needsStartupReconciliation(status))
        }
    }

    @Test
    fun `terminal checkpoint is saved even after parent coroutine cancellation`() = runBlocking {
        val initial = checkpoint(TranscriptionSessionStore.Status.RUNNING)
        var saved: TranscriptionSessionStore.Checkpoint? = null
        var published: TranscriptionSessionStore.Checkpoint? = null
        val order = mutableListOf<String>()
        val job = launch {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                TerminalCheckpointPersistence.persist(
                    initial = initial,
                    status = TranscriptionSessionStore.Status.CANCELLED,
                    errorMessage = "사용자 취소",
                    nowMs = 3_000L,
                    loadLatest = { initial },
                    save = { saved = it; order += "saved" },
                    afterSave = { order += "after-save" },
                    publish = { published = it; order += "published" },
                )
                throw cancelled
            }
        }

        yield()
        job.cancelAndJoin()

        assertEquals(TranscriptionSessionStore.Status.CANCELLED, saved?.status)
        assertEquals(3_000L, saved?.updatedAtMs)
        assertEquals(saved, published)
        assertEquals(listOf("saved", "after-save", "published"), order)
    }

    private fun checkpoint(status: TranscriptionSessionStore.Status) =
        TranscriptionSessionStore.Checkpoint(
            sessionId = "stt_test",
            status = status,
            modelPath = "/data/model.bin",
            audioPath = "/data/audio.wav",
            note = "",
            durationMs = 600_001L,
            totalChunks = 2,
            currentChunk = 0,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L
        )
}
