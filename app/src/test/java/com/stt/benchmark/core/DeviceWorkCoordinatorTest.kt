package com.stt.benchmark.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWorkCoordinatorTest {
    @Test
    fun onlyOneLongRunningOwnerCanHoldTheDevice() {
        val coordinator = DeviceWorkCoordinator()
        val recording = coordinator.tryAcquire(DeviceWorkCoordinator.Owner.RECORDING, "recording_1")
        assertTrue(recording is DeviceWorkCoordinator.AcquireResult.Acquired)

        val transcription = coordinator.tryAcquire(DeviceWorkCoordinator.Owner.TRANSCRIPTION, "stt_1")
        assertTrue(transcription is DeviceWorkCoordinator.AcquireResult.Busy)
        val busy = transcription as DeviceWorkCoordinator.AcquireResult.Busy
        assertEquals(DeviceWorkCoordinator.Owner.RECORDING, busy.snapshot.owner)
    }

    @Test
    fun leaseIsReleasedOnlyByItsTokenAfterTerminalCheckpoint() {
        val coordinator = DeviceWorkCoordinator()
        val lease = (coordinator.tryAcquire(
            DeviceWorkCoordinator.Owner.RECORDING,
            "recording_1",
        ) as DeviceWorkCoordinator.AcquireResult.Acquired).lease
        val otherCoordinator = DeviceWorkCoordinator()
        val foreignLease = (otherCoordinator.tryAcquire(
            DeviceWorkCoordinator.Owner.RECORDING,
            "recording_1",
        ) as DeviceWorkCoordinator.AcquireResult.Acquired).lease

        assertFalse(coordinator.beginFinalization(foreignLease))
        assertFalse(
            coordinator.releaseAfterTerminal(
                foreignLease,
                DeviceWorkCoordinator.TerminalOutcome.FAILED,
            )
        )
        assertTrue(coordinator.beginFinalization(lease))
        assertEquals(DeviceWorkCoordinator.LeaseState.FINALIZING, coordinator.snapshot().state)
        assertTrue(
            coordinator.releaseAfterTerminal(
                lease,
                DeviceWorkCoordinator.TerminalOutcome.COMPLETED,
            )
        )
        assertNull(coordinator.snapshot().owner)
    }

    @Test
    fun summaryWaitsForTranscriptionAndCanStartAfterTerminalRelease() {
        val coordinator = DeviceWorkCoordinator()
        val transcription = (coordinator.tryAcquire(
            DeviceWorkCoordinator.Owner.TRANSCRIPTION,
            "stt_1",
        ) as DeviceWorkCoordinator.AcquireResult.Acquired).lease

        assertTrue(
            coordinator.tryAcquire(DeviceWorkCoordinator.Owner.SUMMARY, "summary_stt_1")
                is DeviceWorkCoordinator.AcquireResult.Busy,
        )
        coordinator.beginFinalization(transcription)
        coordinator.releaseAfterTerminal(transcription, DeviceWorkCoordinator.TerminalOutcome.COMPLETED)

        assertTrue(
            coordinator.tryAcquire(DeviceWorkCoordinator.Owner.SUMMARY, "summary_stt_1")
                is DeviceWorkCoordinator.AcquireResult.Acquired,
        )
    }

    @Test
    fun chatBlocksSummaryAndTranscriptionUntilTerminalRelease() {
        val coordinator = DeviceWorkCoordinator()
        val chat = coordinator.tryAcquire(DeviceWorkCoordinator.Owner.CHAT, "chat_1")
            as DeviceWorkCoordinator.AcquireResult.Acquired

        assertTrue(
            coordinator.tryAcquire(DeviceWorkCoordinator.Owner.SUMMARY, "summary_1")
                is DeviceWorkCoordinator.AcquireResult.Busy,
        )
        assertTrue(
            coordinator.tryAcquire(DeviceWorkCoordinator.Owner.TRANSCRIPTION, "stt_1")
                is DeviceWorkCoordinator.AcquireResult.Busy,
        )
        assertTrue(
            coordinator.releaseAfterTerminal(chat.lease, DeviceWorkCoordinator.TerminalOutcome.COMPLETED),
        )
    }
}
