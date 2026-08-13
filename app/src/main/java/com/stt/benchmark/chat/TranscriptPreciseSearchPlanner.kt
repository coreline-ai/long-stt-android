package com.stt.benchmark.chat

object TranscriptPreciseSearchPlanner {
    const val MERGE_BATCH_SIZE = 8

    fun totalSteps(unitCount: Int): Int {
        require(unitCount > 0)
        if (unitCount == 1) return 2 // scan 1회 + 사용자 질문에 대한 최종 답변 1회
        var current = unitCount
        var total = unitCount
        while (current > 1) {
            current = (current + MERGE_BATCH_SIZE - 1) / MERGE_BATCH_SIZE
            total += current
        }
        return total
    }

    fun progressPercent(completed: Int, total: Int): Int = when {
        total <= 0 -> 0
        else -> (completed.coerceIn(0, total) * 100 / total)
    }
}
