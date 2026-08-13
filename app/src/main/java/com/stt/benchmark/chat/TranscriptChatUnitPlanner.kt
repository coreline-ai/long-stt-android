package com.stt.benchmark.chat

import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceSection

data class TranscriptChatPlannedUnit(
    val unitId: String,
    val startMs: Long,
    val endMs: Long,
    val sourceSectionKeys: List<String>,
    val text: String,
)

/** section 순서와 시간 범위를 보존하면서 자연 문장 끝을 우선하는 일시적 원문 unit planner. */
object TranscriptChatUnitPlanner {
    fun plan(document: TranscriptSourceDocument): List<TranscriptChatPlannedUnit> {
        val validation = TranscriptChatPolicy.validate(document)
        require(validation is TranscriptChatPolicy.SourceValidation.Ready) {
            (validation as TranscriptChatPolicy.SourceValidation.Rejected).message
        }
        val slices = document.sections.flatMap(::splitSection)
        val packed = mutableListOf<MutableList<Slice>>()
        slices.forEach { slice ->
            val current = packed.lastOrNull()
            val currentLength = current?.sumOf { it.text.length + 1 } ?: 0
            if (current == null || currentLength + slice.text.length > TranscriptChatPolicy.TARGET_UNIT_CHARS) {
                packed += mutableListOf(slice)
            } else {
                current += slice
            }
        }
        return packed.mapIndexed { index, parts ->
            TranscriptChatPlannedUnit(
                unitId = "U${(index + 1).toString().padStart(4, '0')}",
                startMs = parts.first().startMs,
                endMs = parts.last().endMs,
                sourceSectionKeys = parts.map(Slice::sectionKey).distinct(),
                text = parts.joinToString("\n") { it.text }.trim(),
            )
        }
    }

    private fun splitSection(section: TranscriptSourceSection): List<Slice> {
        val text = section.text.trim()
        if (text.isEmpty()) return emptyList()
        val parts = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            val hardEnd = (start + TranscriptChatPolicy.TARGET_UNIT_CHARS).coerceAtMost(text.length)
            val end = if (hardEnd == text.length) hardEnd else preferredBreak(text, start, hardEnd)
            parts += start to end
            start = end
        }
        val duration = (section.endMs - section.startMs).coerceAtLeast(0L)
        return parts.map { (startIndex, endIndex) ->
            val startRatio = startIndex.toDouble() / text.length
            val endRatio = endIndex.toDouble() / text.length
            Slice(
                sectionKey = section.key,
                startMs = section.startMs + (duration * startRatio).toLong(),
                endMs = section.startMs + (duration * endRatio).toLong(),
                text = text.substring(startIndex, endIndex).trim(),
            )
        }.filter { it.text.isNotEmpty() }
    }

    private fun preferredBreak(text: String, start: Int, hardEnd: Int): Int {
        val earliest = start + TranscriptChatPolicy.TARGET_UNIT_CHARS / 2
        for (index in hardEnd downTo earliest) {
            if (text[index - 1] in BREAKS) return index
        }
        return hardEnd
    }

    private data class Slice(
        val sectionKey: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    private val BREAKS = charArrayOf('\n', '.', '!', '?', '。', '！', '？')
}
