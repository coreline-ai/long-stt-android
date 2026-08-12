package com.stt.benchmark.summary

/**
 * User-approved transcript summary input boundary.
 *
 * This object only prepares an in-memory request. It never persists the transcript and callers
 * must show their consent UI before invoking [prepare].
 */
object SummaryRequestPolicy {
    /** Maximum transcript payload sent in a single request. */
    const val MAX_TRANSCRIPT_CHARS = 12_000
    /** Upper bound for one explicitly selected local transcript. */
    const val MAX_TOTAL_TRANSCRIPT_CHARS = 500_000
    const val MAX_SUMMARY_CHARS = 4_000
    const val MAX_INTERMEDIATE_SUMMARY_CHARS = 900

    private const val TARGET_PART_CHARS = 10_000
    private const val MAX_SYNTHESIS_INPUTS = 8

    data class Source(
        val type: SummarySessionStore.SourceType,
        val id: String,
    ) {
        val key: String get() = "${type.name}:$id"
    }

    sealed interface Preparation {
        data class Ready(
            val source: Source,
            val transcriptParts: List<String>,
        ) : Preparation {
            val totalRequestCount: Int
                get() = estimatedRequestCount(transcriptParts.size)
        }

        data class Rejected(val message: String) : Preparation
    }

    fun prepare(source: Source, transcript: String): Preparation {
        if (!source.id.matches(SOURCE_ID_REGEX)) {
            return Preparation.Rejected("요약할 전사 식별자를 확인할 수 없습니다.")
        }
        val selectedText = transcript.trim()
        if (selectedText.isEmpty()) {
            return Preparation.Rejected("내용이 있는 완료 전사만 요약할 수 있습니다.")
        }
        if (selectedText.length > MAX_TOTAL_TRANSCRIPT_CHARS) {
            return Preparation.Rejected(
                "이번 요약은 ${MAX_TOTAL_TRANSCRIPT_CHARS}자 이하의 전사만 지원합니다.",
            )
        }
        return Preparation.Ready(
            source = source,
            transcriptParts = splitTranscript(selectedText),
        )
    }

    /** Keeps every transport payload bounded while preferring natural sentence boundaries. */
    internal fun splitTranscript(transcript: String): List<String> {
        val selectedText = transcript.trim()
        if (selectedText.length <= TARGET_PART_CHARS) return listOf(selectedText)

        val parts = mutableListOf<String>()
        var start = 0
        while (start < selectedText.length) {
            val hardEnd = (start + TARGET_PART_CHARS).coerceAtMost(selectedText.length)
            val end = if (hardEnd == selectedText.length) {
                hardEnd
            } else {
                findPreferredBreak(selectedText, start, hardEnd)
            }
            selectedText.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(parts::add)
            start = end
        }
        return parts
    }

    internal fun synthesisBatches(summaries: List<String>): List<List<String>> =
        summaries.chunked(MAX_SYNTHESIS_INPUTS)

    internal fun estimatedRequestCount(partCount: Int): Int {
        require(partCount > 0) { "요약 구간이 필요합니다." }
        if (partCount == 1) return 1
        var levelCount = partCount
        var total = partCount
        while (levelCount > 1) {
            levelCount = (levelCount + MAX_SYNTHESIS_INPUTS - 1) / MAX_SYNTHESIS_INPUTS
            total += levelCount
        }
        return total
    }

    private fun findPreferredBreak(text: String, start: Int, hardEnd: Int): Int {
        val earliest = start + TARGET_PART_CHARS / 2
        for (index in hardEnd downTo earliest) {
            if (text[index - 1] in BREAK_CHARACTERS) return index
        }
        return hardEnd
    }

    private val SOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
    private val BREAK_CHARACTERS = charArrayOf('\n', '.', '!', '?', '。', '！', '？')
}
