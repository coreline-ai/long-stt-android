package com.stt.benchmark.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletedResultTargetPolicyTest {
    @Test
    fun onlyCompletedStandaloneSessionCreatesSessionTarget() {
        val completed = session()

        assertEquals(
            CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION,
            CompletedResultTargetPolicy.fromStandaloneSession(completed)?.type,
        )
        assertNull(
            CompletedResultTargetPolicy.fromStandaloneSession(
                session(status = TranscriptionSessionStore.Status.RUNNING),
            ),
        )
        assertNull(
            CompletedResultTargetPolicy.fromStandaloneSession(
                session(recordingGroupId = "recording_group_1"),
            ),
        )
    }

    @Test
    fun onlyCompletedOrPartialCompletedGroupCreatesGroupTarget() {
        assertEquals(
            CompletedResultTargetStore.Type.RECORDING_GROUP,
            CompletedResultTargetPolicy.fromRecordingGroup(
                group(RecordingTranscriptionGroupStore.GroupStatus.COMPLETED),
            )?.type,
        )
        assertEquals(
            CompletedResultTargetStore.Type.RECORDING_GROUP,
            CompletedResultTargetPolicy.fromRecordingGroup(
                group(RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED, partial = true),
            )?.type,
        )
        assertNull(
            CompletedResultTargetPolicy.fromRecordingGroup(
                group(RecordingTranscriptionGroupStore.GroupStatus.RUNNING),
            ),
        )
        assertNull(
            CompletedResultTargetPolicy.fromRecordingGroup(
                group(RecordingTranscriptionGroupStore.GroupStatus.FAILED),
            ),
        )
    }

    private fun session(
        status: TranscriptionSessionStore.Status = TranscriptionSessionStore.Status.COMPLETED,
        recordingGroupId: String = "",
    ) = TranscriptionSessionStore.Checkpoint(
        sessionId = "stt_policy_1",
        status = status,
        modelPath = "/private/model.bin",
        audioPath = "/private/audio.wav",
        note = "private",
        durationMs = 1_000L,
        totalChunks = 1,
        currentChunk = 1,
        createdAtMs = 1L,
        updatedAtMs = 2L,
        recordingGroupId = recordingGroupId,
    )

    private fun group(
        status: RecordingTranscriptionGroupStore.GroupStatus,
        partial: Boolean = false,
    ) = RecordingTranscriptionGroupStore.Group(
        groupId = "recording_group_policy_1",
        recordingSessionId = "recording_policy_1",
        modelPath = "/private/model.bin",
        status = status,
        isPartial = partial,
        excludedSequences = if (partial) listOf(1) else emptyList(),
        currentChildIndex = 0,
        createdAtMs = 1L,
        updatedAtMs = 2L,
        children = listOf(
            RecordingTranscriptionGroupStore.Child(
                sequence = 0,
                mediaId = "media_policy_1",
                audioPath = "/private/audio.wav",
                sttSessionId = "stt_child_policy_1",
                status = if (status in setOf(
                        RecordingTranscriptionGroupStore.GroupStatus.COMPLETED,
                        RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED,
                    )
                ) {
                    RecordingTranscriptionGroupStore.ChildStatus.COMPLETED
                } else {
                    RecordingTranscriptionGroupStore.ChildStatus.RUNNING
                },
            ),
        ),
    )
}
