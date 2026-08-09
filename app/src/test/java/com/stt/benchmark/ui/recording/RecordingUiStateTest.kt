package com.stt.benchmark.ui.recording

import com.stt.benchmark.recording.RecorderPreconditions
import com.stt.benchmark.recording.RecordingPhase
import com.stt.benchmark.recording.RecordingRuntimeSnapshot
import com.stt.benchmark.recording.RecordingSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingUiStateTest {
    @Test
    fun everyPreconditionMapsToOneExplicitAvailability() {
        assertEquals(
            RecordingAvailability.READY,
            RecordingViewModel.mapAvailability(RecorderPreconditions.Result.Ready),
        )
        assertEquals(
            RecordingAvailability.PERMISSION_REQUIRED,
            RecordingViewModel.mapAvailability(RecorderPreconditions.Result.PermissionRequired),
        )
        assertEquals(
            RecordingAvailability.UNSUPPORTED_INPUT,
            RecordingViewModel.mapAvailability(RecorderPreconditions.Result.UnsupportedInput),
        )
        assertEquals(
            RecordingAvailability.INSUFFICIENT_STORAGE,
            RecordingViewModel.mapAvailability(RecorderPreconditions.Result.InsufficientStorage(1L)),
        )
    }

    @Test
    fun pendingCommandPreventsRapidDuplicateAction() {
        val ready = RecordingUiState(
            availability = RecordingAvailability.READY,
            runtime = RecordingRuntimeSnapshot(phase = RecordingPhase.IDLE),
        )
        val gate = RecordingActionGate()

        assertTrue(gate.request(ready, PendingRecordingCommand.START))
        assertFalse(
            gate.request(
                ready.copy(pendingCommand = PendingRecordingCommand.START),
                PendingRecordingCommand.START,
            )
        )

        val recording = ready.copy(runtime = RecordingRuntimeSnapshot(phase = RecordingPhase.RECORDING))
        assertTrue(gate.request(recording, PendingRecordingCommand.STOP))
        assertFalse(
            gate.request(
                recording.copy(pendingCommand = PendingRecordingCommand.STOP),
                PendingRecordingCommand.STOP,
            )
        )
    }

    @Test
    fun runtimeAcknowledgementClearsOnlyMatchingPendingCommand() {
        assertEquals(
            PendingRecordingCommand.START,
            RecordingViewModel.clearPending(PendingRecordingCommand.START, RecordingPhase.IDLE),
        )
        assertEquals(
            PendingRecordingCommand.NONE,
            RecordingViewModel.clearPending(PendingRecordingCommand.START, RecordingPhase.RECORDING),
        )
        assertEquals(
            PendingRecordingCommand.STOP,
            RecordingViewModel.clearPending(PendingRecordingCommand.STOP, RecordingPhase.RECORDING),
        )
        assertEquals(
            PendingRecordingCommand.NONE,
            RecordingViewModel.clearPending(PendingRecordingCommand.STOP, RecordingPhase.SAVED),
        )
    }

    @Test
    fun recentSessionSeparatesReadyQuarantineAndMissingChunks() {
        val session = RecordingSessionStore.RecordingSession(
            sessionId = "recording_1000_recent",
            phase = RecordingPhase.FAILED,
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            errorMessage = "일부 청크 확인 필요",
            chunks = listOf(
                chunk(0, RecordingSessionStore.ChunkStatus.READY, durationMs = 3_000L),
                chunk(1, RecordingSessionStore.ChunkStatus.QUARANTINED),
                chunk(2, RecordingSessionStore.ChunkStatus.MISSING),
            ),
        )

        val ui = RecordingViewModel.toRecentUi(session)

        assertEquals(1, ui.readyChunkCount)
        assertEquals(1, ui.quarantinedChunkCount)
        assertEquals(1, ui.missingChunkCount)
        assertEquals(3_000L, ui.durationMs)
        assertEquals("일부 청크 확인 필요", ui.message)
    }

    @Test
    fun everyServicePhaseHasAnExplicitPresentation() {
        val expected = mapOf(
            RecordingPhase.IDLE to "녹음 준비",
            RecordingPhase.PREPARING to "녹음 준비",
            RecordingPhase.RECORDING to "녹음 중",
            RecordingPhase.ROLLING_OVER to "청크 저장",
            RecordingPhase.FINALIZING to "안전 저장 중",
            RecordingPhase.SAVED to "저장 완료",
            RecordingPhase.FAILED to "확인 필요",
            RecordingPhase.RECOVERY_REQUIRED to "복구 확인",
        )

        expected.forEach { (phase, label) ->
            val presentation = recordingPresentation(
                RecordingUiState(
                    availability = RecordingAvailability.READY,
                    runtime = RecordingRuntimeSnapshot(phase = phase),
                )
            )
            assertEquals(label, presentation.label)
            assertTrue(presentation.detail.isNotBlank())
        }
    }

    private fun chunk(
        index: Int,
        status: RecordingSessionStore.ChunkStatus,
        durationMs: Long = 0L,
    ) = RecordingSessionStore.RecordingChunk(
        index = index,
        status = status,
        partPath = if (status == RecordingSessionStore.ChunkStatus.WRITING) "/tmp/chunk.part" else "",
        finalPath = if (status == RecordingSessionStore.ChunkStatus.READY) "/tmp/chunk.m4a" else "",
        quarantinePath = if (status == RecordingSessionStore.ChunkStatus.QUARANTINED) "/tmp/chunk.bad" else "",
        container = "m4a",
        durationMs = durationMs,
        sizeBytes = if (status == RecordingSessionStore.ChunkStatus.READY) 10L else 0L,
        sha256 = if (status == RecordingSessionStore.ChunkStatus.READY) "a".repeat(64) else "",
        createdAtMs = 1_000L + index,
        issue = if (status == RecordingSessionStore.ChunkStatus.MISSING) "파일 없음" else "",
    )
}
