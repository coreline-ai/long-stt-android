package com.stt.benchmark.data

/** 화면과 Service가 공유하는 장시간 전사 청크 계획. */
data class TranscriptionPlan(
    val durationMs: Long,
    val chunkDurationMs: Long,
    val overlapMs: Long,
    val chunks: List<Chunk>
) {
    data class Chunk(
        val index: Int,
        val primaryStartMs: Long,
        val primaryEndMs: Long,
        val decodeStartMs: Long,
        val decodeEndMs: Long
    )

    val totalChunks: Int get() = chunks.size

    init {
        require(durationMs > 0L) { "오디오 길이는 0보다 커야 합니다" }
        require(chunkDurationMs > 0L) { "청크 길이는 0보다 커야 합니다" }
        require(overlapMs >= 0L) { "overlap은 음수일 수 없습니다" }
        require(chunks.isNotEmpty()) { "청크 계획이 비어 있습니다" }

        var cursor = 0L
        chunks.forEachIndexed { listIndex, chunk ->
            require(chunk.index == listIndex + 1) { "청크 index가 연속적이지 않습니다" }
            require(chunk.primaryStartMs == cursor) { "primary coverage에 gap/overlap이 있습니다" }
            require(chunk.primaryEndMs > chunk.primaryStartMs) { "빈 primary 청크입니다" }
            require(chunk.decodeStartMs <= chunk.primaryStartMs) { "decode 시작이 primary 뒤입니다" }
            require(chunk.decodeEndMs >= chunk.primaryEndMs) { "decode 종료가 primary 앞입니다" }
            require(chunk.decodeStartMs >= 0L && chunk.decodeEndMs <= durationMs) { "decode 범위가 오디오 밖입니다" }
            cursor = chunk.primaryEndMs
        }
        require(cursor == durationMs) { "청크 계획이 전체 길이를 덮지 않습니다" }
    }

    companion object {
        const val DEFAULT_CHUNK_DURATION_MS = 10 * 60 * 1000L
        const val DEFAULT_OVERLAP_MS = 1_000L

        fun create(
            durationMs: Long,
            chunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS,
            overlapMs: Long = DEFAULT_OVERLAP_MS
        ): TranscriptionPlan {
            require(durationMs > 0L) { "오디오 길이는 0보다 커야 합니다" }
            require(chunkDurationMs > 0L) { "청크 길이는 0보다 커야 합니다" }
            require(overlapMs >= 0L) { "overlap은 음수일 수 없습니다" }

            val totalChunks = ((durationMs + chunkDurationMs - 1L) / chunkDurationMs).toInt()
            val chunks = (1..totalChunks).map { index ->
                val primaryStartMs = (index - 1L) * chunkDurationMs
                val primaryEndMs = minOf(durationMs, primaryStartMs + chunkDurationMs)
                Chunk(
                    index = index,
                    primaryStartMs = primaryStartMs,
                    primaryEndMs = primaryEndMs,
                    decodeStartMs = (primaryStartMs - overlapMs).coerceAtLeast(0L),
                    decodeEndMs = (primaryEndMs + overlapMs).coerceAtMost(durationMs)
                )
            }
            return TranscriptionPlan(durationMs, chunkDurationMs, overlapMs, chunks)
        }
    }
}
