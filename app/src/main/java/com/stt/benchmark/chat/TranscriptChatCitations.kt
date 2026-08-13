package com.stt.benchmark.chat

data class TranscriptCitation(
    val unitId: String,
    val startMs: Long,
    val endMs: Long,
    val sourceSectionKey: String,
)

object TranscriptChatCitations {
    fun validate(answer: String, allowedUnits: List<TranscriptChatPlannedUnit>): List<TranscriptCitation> {
        val allowed = allowedUnits.associateBy(TranscriptChatPlannedUnit::unitId)
        return CITATION.findAll(answer)
            .map { it.groupValues[1] }
            .distinct()
            .mapNotNull { id -> allowed[id]?.let { unit ->
                TranscriptCitation(id, unit.startMs, unit.endMs, unit.sourceSectionKeys.first())
            } }
            .toList()
    }

    private val CITATION = Regex("\\[(U[0-9]{4,6})]")
}
