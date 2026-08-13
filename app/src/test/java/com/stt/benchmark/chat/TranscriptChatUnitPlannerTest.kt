package com.stt.benchmark.chat

import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptChatUnitPlannerTest {
    @Test
    fun keepsSingleSectionRangeAndText() {
        val document = document(listOf(section("s1", 0, 5_000, "하나의 짧은 문장입니다.")))

        val units = TranscriptChatUnitPlanner.plan(document)

        assertEquals(1, units.size)
        assertEquals("U0001", units.single().unitId)
        assertEquals(listOf("s1"), units.single().sourceSectionKeys)
        assertEquals(0L, units.single().startMs)
        assertEquals(5_000L, units.single().endMs)
    }

    @Test
    fun packsSectionsInOrderAndPreservesOuterTimeRange() {
        val document = document(
            listOf(
                section("s1", 0, 1_000, "첫 문장."),
                section("s2", 1_000, 2_000, "둘째 문장."),
            ),
        )

        val unit = TranscriptChatUnitPlanner.plan(document).single()

        assertEquals(listOf("s1", "s2"), unit.sourceSectionKeys)
        assertEquals(0L, unit.startMs)
        assertEquals(2_000L, unit.endMs)
        assertTrue(unit.text.indexOf("첫") < unit.text.indexOf("둘째"))
    }

    @Test
    fun splitsVeryLongSentenceAtHardLimitWithMonotonicRanges() {
        val units = TranscriptChatUnitPlanner.plan(
            document(listOf(section("long", 0, 30_000, "가".repeat(25_000)))),
        )

        assertEquals(3, units.size)
        assertTrue(units.all { it.text.length <= TranscriptChatPolicy.TARGET_UNIT_CHARS })
        assertTrue(units.zipWithNext().all { (a, b) -> a.endMs <= b.startMs && a.unitId < b.unitId })
        assertEquals(25_000, units.sumOf { it.text.length })
    }

    private fun section(key: String, start: Long, end: Long, text: String) =
        TranscriptSourceSection(key, key, start, end, text)

    private fun document(sections: List<TranscriptSourceSection>) = TranscriptSourceDocument(
        TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_unit"),
        updatedAtMs = 1L,
        sections = sections,
    )
}
