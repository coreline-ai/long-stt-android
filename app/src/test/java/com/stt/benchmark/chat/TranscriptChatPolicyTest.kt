package com.stt.benchmark.chat

import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptChatPolicyTest {
    @Test
    fun acceptsCompletedReaderDocumentAtMaximumBoundaryAndCreatesStableFingerprint() {
        val document = document("가".repeat(TranscriptChatPolicy.MAX_TRANSCRIPT_CHARS))

        val first = TranscriptChatPolicy.validate(document) as TranscriptChatPolicy.SourceValidation.Ready
        val second = TranscriptChatPolicy.validate(document) as TranscriptChatPolicy.SourceValidation.Ready

        assertEquals(TranscriptChatPolicy.MAX_TRANSCRIPT_CHARS, first.characterCount)
        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(TranscriptChatPolicy.FINGERPRINT_REGEX.matches(first.fingerprint))
    }

    @Test
    fun rejectsBlankOversizedAndUnsafeSources() {
        assertTrue(TranscriptChatPolicy.validate(document(" ")) is TranscriptChatPolicy.SourceValidation.Rejected)
        assertTrue(
            TranscriptChatPolicy.validate(document("가".repeat(TranscriptChatPolicy.MAX_TRANSCRIPT_CHARS + 1)))
                is TranscriptChatPolicy.SourceValidation.Rejected,
        )
        val unsafe = document("내용").copy(source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "bad/path"))
        assertTrue(TranscriptChatPolicy.validate(unsafe) is TranscriptChatPolicy.SourceValidation.Rejected)
    }

    @Test
    fun fingerprintChangesWhenTextTimeOrSourceVersionChanges() {
        val base = document("기준 문장")

        assertNotEquals(TranscriptChatPolicy.fingerprint(base), TranscriptChatPolicy.fingerprint(document("다른 문장")))
        assertNotEquals(
            TranscriptChatPolicy.fingerprint(base),
            TranscriptChatPolicy.fingerprint(base.copy(updatedAtMs = base.updatedAtMs + 1)),
        )
    }

    private fun document(text: String) = TranscriptSourceDocument(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_safe_1"),
        updatedAtMs = 10L,
        sections = listOf(TranscriptSourceSection("section_1", "구간", 0L, 10_000L, text)),
    )
}
