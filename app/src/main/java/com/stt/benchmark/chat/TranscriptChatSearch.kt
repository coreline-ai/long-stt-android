package com.stt.benchmark.chat

/** 외부 embedding 없이 한국어 token과 문자 n-gram으로 결정적인 unit 순위를 계산한다. */
object TranscriptChatSearch {
    data class RankedUnit(val unit: TranscriptChatIndexStore.UnitEntry, val score: Int)
    data class HistoryWindow(
        val recent: List<TranscriptChatSessionStore.Message>,
        val pendingDigest: List<TranscriptChatSessionStore.Message>,
        val existingDigest: String,
        /** pendingDigest까지 반영한 뒤의 messages 인덱스. */
        val digestThrough: Int,
    )

    fun rank(
        question: String,
        recentConversation: List<String>,
        units: List<TranscriptChatIndexStore.UnitEntry>,
        limit: Int = TranscriptChatPolicy.DEFAULT_RELEVANT_UNITS,
    ): List<RankedUnit> {
        require(limit in 1..TranscriptChatPolicy.MAX_RELEVANT_UNITS)
        val queryFeatures = features((recentConversation.takeLast(4) + question).joinToString(" "))
        if (queryFeatures.tokens.isEmpty() && queryFeatures.grams.isEmpty()) return emptyList()
        return units.map { unit ->
            val target = features(unit.summary)
            val tokenMatches = queryFeatures.tokens.count(target.tokens::contains)
            val gramMatches = queryFeatures.grams.count(target.grams::contains)
            RankedUnit(unit, tokenMatches * 10 + gramMatches)
        }.filter { it.score > 0 }
            .sortedWith(compareByDescending<RankedUnit> { it.score }.thenBy { it.unit.unitId })
            .take(limit)
    }

    fun buildContext(
        ranked: List<RankedUnit>,
        plannedUnits: List<TranscriptChatPlannedUnit>,
    ): String {
        val plannedById = plannedUnits.associateBy(TranscriptChatPlannedUnit::unitId)
        val output = StringBuilder()
        ranked.take(TranscriptChatPolicy.MAX_RELEVANT_UNITS).forEach { rankedUnit ->
            val planned = plannedById[rankedUnit.unit.unitId] ?: return@forEach
            val block = "[${planned.unitId} ${planned.startMs}-${planned.endMs}ms]\n${planned.text}\n"
            if (output.length + block.length <= TranscriptChatPolicy.MAX_CONTEXT_CHARS) output.append(block)
        }
        return output.toString().trim()
    }

    fun boundedHistory(
        messages: List<TranscriptChatSessionStore.Message>,
        maxChars: Int = TranscriptChatPolicy.MAX_HISTORY_CHARS,
    ): List<TranscriptChatSessionStore.Message> {
        require(maxChars >= 0)
        var remaining = maxChars
        val selected = ArrayDeque<TranscriptChatSessionStore.Message>()
        for (message in messages.asReversed()) {
            if (message.text.length > remaining) break
            selected.addFirst(message)
            remaining -= message.text.length
        }
        return selected.toList()
    }

    fun historyWindow(
        messages: List<TranscriptChatSessionStore.Message>,
        existingDigest: String,
        digestThrough: Int,
    ): HistoryWindow {
        require(digestThrough in 0..messages.size)
        val safeDigest = existingDigest.take(TranscriptChatPolicy.MAX_HISTORY_DIGEST_CHARS)
        val recent = boundedHistory(
            messages.drop(digestThrough),
            TranscriptChatPolicy.MAX_HISTORY_CHARS - safeDigest.length,
        )
        val recentStart = messages.size - recent.size
        return HistoryWindow(
            recent = recent,
            pendingDigest = messages.subList(digestThrough, recentStart),
            existingDigest = safeDigest,
            digestThrough = recentStart,
        )
    }

    fun digestBatches(messages: List<TranscriptChatSessionStore.Message>): List<List<TranscriptChatSessionStore.Message>> {
        if (messages.isEmpty()) return emptyList()
        val batches = mutableListOf<MutableList<TranscriptChatSessionStore.Message>>()
        var current = mutableListOf<TranscriptChatSessionStore.Message>()
        var currentChars = 0
        messages.forEach { message ->
            if (current.isNotEmpty() && currentChars + message.text.length > TranscriptChatPolicy.MAX_HISTORY_DIGEST_INPUT_CHARS) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += message
            currentChars += message.text.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private data class Features(val tokens: Set<String>, val grams: Set<String>)

    private fun features(value: String): Features {
        val normalized = value.lowercase().replace(NON_TEXT, " ")
        val tokens = normalized.split(Regex("\\s+")).filter { it.length >= 2 }.toSet()
        val compact = normalized.replace(" ", "")
        val grams = buildSet {
            for (size in 2..3) {
                if (compact.length >= size) {
                    for (index in 0..compact.length - size) add(compact.substring(index, index + size))
                }
            }
        }
        return Features(tokens, grams)
    }

    private val NON_TEXT = Regex("[^0-9a-z가-힣]+")
}
