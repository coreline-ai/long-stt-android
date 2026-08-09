package com.stt.benchmark.summary

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryRequestPolicyTest {
    private val source = SummaryRequestPolicy.Source(
        SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
        "stt_safe_123",
    )

    @Test
    fun selectedTranscriptBuildsToolFreeStreamingRequest() {
        val result = SummaryRequestPolicy.prepare(source, "테스트 전사 본문")

        assertTrue(result is SummaryRequestPolicy.Preparation.Ready)
        val request = JSONObject((result as SummaryRequestPolicy.Preparation.Ready).requestJson)
        assertEquals(CodexSummaryProfile.PARITY_MODEL, request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("tools"))
        assertEquals(2, request.getJSONArray("messages").length())
    }

    @Test
    fun blankAndOversizedTranscriptsAreRejectedBeforeTransport() {
        assertTrue(SummaryRequestPolicy.prepare(source, "  ") is SummaryRequestPolicy.Preparation.Rejected)
        assertTrue(
            SummaryRequestPolicy.prepare(
                source,
                "x".repeat(SummaryRequestPolicy.MAX_TRANSCRIPT_CHARS + 1),
            ) is SummaryRequestPolicy.Preparation.Rejected,
        )
    }

    @Test
    fun invalidSourceCannotBeUsedForSummaryStorageOrTransport() {
        val invalid = source.copy(id = "bad/source")

        assertTrue(SummaryRequestPolicy.prepare(invalid, "테스트") is SummaryRequestPolicy.Preparation.Rejected)
    }
}
