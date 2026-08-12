package com.stt.benchmark.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLlmRequestCoordinatorTest {
    @Test
    fun summaryLeaseBlocksProbeAndChatUntilMatchingRelease() {
        val coordinator = CodexLlmRequestCoordinator()
        val summary = coordinator.tryAcquire(
            CodexLlmRequestCoordinator.Owner.SUMMARY,
            "summary_TRANSCRIPTION_SESSION:stt_123",
        ) as CodexLlmRequestCoordinator.AcquireResult.Acquired

        val blockedProbe = coordinator.tryAcquire(CodexLlmRequestCoordinator.Owner.PROBE, "probe")
        val blockedChat = coordinator.tryAcquire(CodexLlmRequestCoordinator.Owner.CHAT, "chat_stt_123")

        assertTrue(blockedProbe is CodexLlmRequestCoordinator.AcquireResult.Busy)
        assertTrue(blockedChat is CodexLlmRequestCoordinator.AcquireResult.Busy)
        assertEquals(CodexLlmRequestCoordinator.Owner.SUMMARY, coordinator.snapshot().owner)
        assertTrue(coordinator.release(summary.lease))
        assertTrue(
            coordinator.tryAcquire(CodexLlmRequestCoordinator.Owner.CHAT, "chat_stt_123")
                is CodexLlmRequestCoordinator.AcquireResult.Acquired,
        )
    }

    @Test
    fun foreignOrRepeatedReleaseCannotClearActiveLease() {
        val coordinator = CodexLlmRequestCoordinator()
        val acquired = coordinator.tryAcquire(
            CodexLlmRequestCoordinator.Owner.PROBE,
            "probe",
        ) as CodexLlmRequestCoordinator.AcquireResult.Acquired
        val foreign = acquired.lease.copy(token = "foreign")

        assertFalse(coordinator.release(foreign))
        assertEquals(CodexLlmRequestCoordinator.Owner.PROBE, coordinator.snapshot().owner)
        assertTrue(coordinator.release(acquired.lease))
        assertFalse(coordinator.release(acquired.lease))
    }

    @Test
    fun unsafeWorkIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CodexLlmRequestCoordinator().tryAcquire(
                CodexLlmRequestCoordinator.Owner.CHAT,
                "chat/path",
            )
        }
    }
}
