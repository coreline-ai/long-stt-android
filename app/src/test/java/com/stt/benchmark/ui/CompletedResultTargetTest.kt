package com.stt.benchmark.ui

import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedResultTargetTest {
    @Test
    fun completedStandaloneSessionCreatesAvailableOpaqueTarget() {
        val session = session()
        val target = CompletedResultTarget.fromSession(session)!!

        assertEquals(CompletedResultTarget.Type.TRANSCRIPTION_SESSION, target.type)
        assertEquals(session.sessionId, target.id)
        assertTrue(target.isAvailable(listOf(session), emptyList()))
        assertFalse(target.toString().contains(session.audioPath))
        assertFalse(target.toString().contains(session.sessionId))
    }

    @Test
    fun incompleteGroupChildAndUnsafeSessionAreRejected() {
        assertNull(CompletedResultTarget.fromSession(session(status = TranscriptionSessionStore.Status.RUNNING)))
        assertNull(CompletedResultTarget.fromSession(session(recordingGroupId = "recording_group_1")))
        assertNull(CompletedResultTarget.fromSession(session(id = "unsafe/session")))
        assertNull(CompletedResultTarget.restore("UNKNOWN", "stt_1"))
        assertNull(CompletedResultTarget.create(CompletedResultTarget.Type.TRANSCRIPTION_SESSION, "bad/id"))
        assertFalse(
            CompletedResultTarget.create(
                CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
                "stt_missing",
            )!!.isAvailable(listOf(session()), emptyList()),
        )
    }

    @Test
    fun completeAndPartialGroupsCreateTargetsButFailedGroupDoesNot() {
        val complete = group(RecordingTranscriptionGroupStore.GroupStatus.COMPLETED)
        val partial = group(RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED, partial = true)

        assertTrue(CompletedResultTarget.fromGroup(complete)!!.isAvailable(emptyList(), listOf(complete)))
        assertTrue(CompletedResultTarget.fromGroup(partial)!!.isAvailable(emptyList(), listOf(partial)))
        assertNull(CompletedResultTarget.fromGroup(group(RecordingTranscriptionGroupStore.GroupStatus.FAILED)))
    }

    private fun session(
        id: String = "stt_completed_1",
        status: TranscriptionSessionStore.Status = TranscriptionSessionStore.Status.COMPLETED,
        recordingGroupId: String = "",
    ) = TranscriptionSessionStore.Checkpoint(
        sessionId = id,
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
        groupId = "recording_stt_1",
        recordingSessionId = "recording_1",
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
                mediaId = "media_1",
                audioPath = "/private/audio.wav",
                sttSessionId = "stt_child_1",
                status = RecordingTranscriptionGroupStore.ChildStatus.COMPLETED,
            ),
        ),
    )
}
