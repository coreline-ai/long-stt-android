package com.stt.benchmark.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptChatSearchTest {
    private val units = listOf(
        TranscriptChatIndexStore.UnitEntry("U0001", 0, 1, "출시 일정은 다음 주 화요일로 결정했습니다"),
        TranscriptChatIndexStore.UnitEntry("U0002", 1, 2, "마케팅 예산과 광고 채널 논의"),
        TranscriptChatIndexStore.UnitEntry("U0003", 2, 3, "오류 수정과 안정성 테스트 계획"),
    )

    @Test
    fun koreanRankingIsDeterministicAndTieBreaksByUnitId() {
        val first = TranscriptChatSearch.rank("출시 일정은 언제인가요", emptyList(), units)
        val second = TranscriptChatSearch.rank("출시 일정은 언제인가요", emptyList(), units)

        assertEquals(first, second)
        assertEquals("U0001", first.first().unit.unitId)
    }

    @Test
    fun contextIncludesOnlyRankedUnitsWithinBudget() {
        val planned = (1..8).map { index ->
            TranscriptChatPlannedUnit(
                "U${index.toString().padStart(4, '0')}",
                index.toLong(),
                index.toLong() + 1,
                listOf("s$index"),
                "가".repeat(9_000),
            )
        }
        val ranked = planned.map { plannedUnit ->
            TranscriptChatSearch.RankedUnit(
                TranscriptChatIndexStore.UnitEntry(plannedUnit.unitId, 0, 1, "요약"),
                1,
            )
        }

        val context = TranscriptChatSearch.buildContext(ranked, planned)

        assertTrue(context.length <= TranscriptChatPolicy.MAX_CONTEXT_CHARS)
        assertTrue(context.contains("U0001"))
        assertTrue(!context.contains("U0008"))
    }

    @Test
    fun boundedHistoryKeepsRecentTurnsOnly() {
        val messages = (1..10).map { index ->
            TranscriptChatSessionStore.Message(
                TranscriptChatSessionStore.Role.USER,
                "$index" + "가".repeat(1_999),
                timestampMs = index.toLong(),
            )
        }

        val bounded = TranscriptChatSearch.boundedHistory(messages)

        assertTrue(bounded.sumOf { it.text.length } <= TranscriptChatPolicy.MAX_HISTORY_CHARS)
        assertEquals(messages.last(), bounded.last())
        assertTrue(bounded.size < messages.size)
    }

    @Test
    fun historyWindowSeparatesOlderMessagesForDigestAndKeepsRecentTurns() {
        val messages = (1..10).map { index ->
            TranscriptChatSessionStore.Message(
                TranscriptChatSessionStore.Role.USER,
                "$index" + "가".repeat(1_999),
                timestampMs = index.toLong(),
            )
        }

        val window = TranscriptChatSearch.historyWindow(messages, "기존 요약", 2)

        assertEquals(messages.drop(2).take(window.pendingDigest.size), window.pendingDigest)
        assertEquals(messages.takeLast(window.recent.size), window.recent)
        assertEquals(2 + window.pendingDigest.size, window.digestThrough)
        assertTrue(window.existingDigest.length + window.recent.sumOf { it.text.length } <= TranscriptChatPolicy.MAX_HISTORY_CHARS)
    }

    @Test
    fun digestBatchesNeverExceedDigestInputBudget() {
        val messages = (1..25).map { index ->
            TranscriptChatSessionStore.Message(
                TranscriptChatSessionStore.Role.USER,
                "$index" + "나".repeat(1_999),
                timestampMs = index.toLong(),
            )
        }

        val batches = TranscriptChatSearch.digestBatches(messages)

        assertEquals(messages, batches.flatten())
        assertTrue(batches.all { batch ->
            batch.sumOf { it.text.length } <= TranscriptChatPolicy.MAX_HISTORY_DIGEST_INPUT_CHARS
        })
    }
}
