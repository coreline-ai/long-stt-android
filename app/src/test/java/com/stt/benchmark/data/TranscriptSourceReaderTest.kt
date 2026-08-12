package com.stt.benchmark.data

import com.stt.benchmark.whisper.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSourceReaderTest {
    @Test
    fun completedSessionUsesOrderedNonBlankChunksAndPrimaryTimeRanges() {
        val session = session(
            id = "stt_session_1",
            durationMs = 20_000L,
            chunks = listOf(
                chunk(index = 1, startMs = 10_000L, endMs = 20_000L, text = " 두 번째 "),
                chunk(index = 0, startMs = 0L, endMs = 10_000L, text = "첫 번째"),
                chunk(index = 2, startMs = 20_000L, endMs = 20_000L, text = "  "),
            ),
        )

        val document = TranscriptSourceReader.fromCompletedSession(session)!!

        assertEquals(listOf("첫 번째", "두 번째"), document.sections.map { it.text })
        assertEquals(listOf("U0001", "U0002"), document.sections.map { it.key })
        assertEquals(0L, document.sections.first().startMs)
        assertEquals(20_000L, document.sections.last().endMs)
        assertEquals("첫 번째\n두 번째", document.joinedText())
    }

    @Test
    fun completedGroupUsesChildSequenceAndCumulativeTimeline() {
        val first = session(
            id = "stt_child_first",
            durationMs = 10_000L,
            chunks = listOf(chunk(0, 0L, 10_000L, "첫 녹음")),
        )
        val second = session(
            id = "stt_child_second",
            durationMs = 8_000L,
            chunks = listOf(chunk(0, 0L, 8_000L, "두 번째 녹음")),
        )
        val group = group(
            children = listOf(
                child(sequence = 2, sessionId = second.sessionId),
                child(sequence = 1, sessionId = first.sessionId),
            ),
        )

        val document = TranscriptSourceReader.fromCompletedGroup(group, listOf(second, first))!!

        assertEquals(listOf("첫 녹음", "두 번째 녹음"), document.sections.map { it.text })
        assertEquals(0L, document.sections[0].startMs)
        assertEquals(10_000L, document.sections[0].endMs)
        assertEquals(10_000L, document.sections[1].startMs)
        assertEquals(18_000L, document.sections[1].endMs)
        assertTrue(document.sections[1].label.startsWith("녹음 2"))
    }

    @Test
    fun incompleteBlankPartialOrMissingSourcesAreRejected() {
        val incomplete = session(status = TranscriptionSessionStore.Status.RUNNING)
        val blank = session(chunks = listOf(chunk(0, 0L, 1_000L, " ")))
        val complete = session(id = "stt_complete")
        val unsafeSession = session(id = "unsafe/session")
        val partialGroup = group(partial = true, children = listOf(child(0, complete.sessionId)))
        val missingGroup = group(children = listOf(child(0, "stt_missing")))
        val unsafeGroup = group(
            id = "unsafe/group",
            children = listOf(child(0, complete.sessionId)),
        )

        assertNull(TranscriptSourceReader.fromCompletedSession(incomplete))
        assertNull(TranscriptSourceReader.fromCompletedSession(blank))
        assertNull(TranscriptSourceReader.fromCompletedSession(unsafeSession))
        assertNull(TranscriptSourceReader.fromCompletedGroup(partialGroup, listOf(complete)))
        assertNull(TranscriptSourceReader.fromCompletedGroup(missingGroup, listOf(complete)))
        assertNull(TranscriptSourceReader.fromCompletedGroup(unsafeGroup, listOf(complete)))
        assertNull(
            TranscriptSourceReader.resolve(
                TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "bad/id"),
                listOf(complete),
                emptyList(),
            ),
        )
    }

    private fun session(
        id: String = "stt_default",
        status: TranscriptionSessionStore.Status = TranscriptionSessionStore.Status.COMPLETED,
        durationMs: Long = 1_000L,
        chunks: List<TranscriptionSessionStore.CompletedChunk> = listOf(chunk(0, 0L, 1_000L, "본문")),
    ) = TranscriptionSessionStore.Checkpoint(
        sessionId = id,
        status = status,
        modelPath = "/private/model.bin",
        audioPath = "/private/audio.wav",
        note = "private note",
        durationMs = durationMs,
        totalChunks = chunks.size,
        currentChunk = chunks.size,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        chunks = chunks,
    )

    private fun chunk(index: Int, startMs: Long, endMs: Long, text: String) =
        TranscriptionSessionStore.CompletedChunk(
            index = index,
            primaryStartMs = startMs,
            primaryEndMs = endMs,
            decodedStartMs = startMs,
            decodedEndMs = endMs,
            decodedSamples = 16_000,
            retryCount = 0,
            elapsedMs = 100L,
            text = text,
            segments = listOf(TranscriptSegment(startMs, endMs, text)),
        )

    private fun group(
        id: String = "recording_stt_group",
        partial: Boolean = false,
        children: List<RecordingTranscriptionGroupStore.Child>,
    ) = RecordingTranscriptionGroupStore.Group(
        groupId = id,
        recordingSessionId = "recording_group",
        modelPath = "/private/model.bin",
        status = RecordingTranscriptionGroupStore.GroupStatus.COMPLETED,
        isPartial = partial,
        excludedSequences = emptyList(),
        currentChildIndex = 0,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        children = children,
    )

    private fun child(sequence: Int, sessionId: String) = RecordingTranscriptionGroupStore.Child(
        sequence = sequence,
        mediaId = "media-$sequence",
        audioPath = "/private/audio-$sequence.wav",
        sttSessionId = sessionId,
        status = RecordingTranscriptionGroupStore.ChildStatus.COMPLETED,
    )
}
