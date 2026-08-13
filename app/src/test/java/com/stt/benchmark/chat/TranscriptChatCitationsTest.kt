package com.stt.benchmark.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptChatCitationsTest {
    @Test
    fun acceptsOnlyAllowlistedUniqueUnitIdsAndMapsTime() {
        val units = listOf(
            TranscriptChatPlannedUnit("U0001", 100, 200, listOf("section-a"), "a"),
            TranscriptChatPlannedUnit("U0002", 200, 300, listOf("section-b"), "b"),
        )

        val citations = TranscriptChatCitations.validate(
            "첫 근거 [U0001], 중복 [U0001], 허위 [U9999], 형식오류 [X0002]",
            units,
        )

        assertEquals(listOf(TranscriptCitation("U0001", 100, 200, "section-a")), citations)
    }
}
