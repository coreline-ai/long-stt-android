package com.stt.benchmark.data

/** 완료 전사 원문을 export·요약·채팅이 같은 순서와 시간 범위로 읽기 위한 in-memory 계약. */
enum class TranscriptSourceType { TRANSCRIPTION_SESSION, RECORDING_GROUP }

data class TranscriptSourceRef(
    val type: TranscriptSourceType,
    val id: String,
)

data class TranscriptSourceSection(
    val key: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscriptSourceDocument(
    val source: TranscriptSourceRef,
    val updatedAtMs: Long,
    val sections: List<TranscriptSourceSection>,
) {
    init {
        require(sections.isNotEmpty()) { "전사 구간이 필요합니다." }
    }

    fun joinedText(): String = sections.joinToString("\n") { it.text }.trim()
}

/**
 * 저장소 객체를 수정하거나 원문을 복제 저장하지 않고 완료 source만 문서 형태로 투영한다.
 * 그룹 시간은 child 녹음 길이를 누적해 하나의 연속 시간축으로 변환한다.
 */
object TranscriptSourceReader {
    fun resolve(
        source: TranscriptSourceRef,
        sessions: List<TranscriptionSessionStore.Checkpoint>,
        groups: List<RecordingTranscriptionGroupStore.Group>,
    ): TranscriptSourceDocument? {
        if (!SOURCE_ID_REGEX.matches(source.id)) return null
        return when (source.type) {
            TranscriptSourceType.TRANSCRIPTION_SESSION -> sessions
                .firstOrNull { it.sessionId == source.id }
                ?.let(::fromCompletedSession)

            TranscriptSourceType.RECORDING_GROUP -> groups
                .firstOrNull { it.groupId == source.id }
                ?.let { fromCompletedGroup(it, sessions) }
        }
    }

    fun fromCompletedSession(
        session: TranscriptionSessionStore.Checkpoint,
    ): TranscriptSourceDocument? {
        if (
            session.status != TranscriptionSessionStore.Status.COMPLETED ||
            !SOURCE_ID_REGEX.matches(session.sessionId)
        ) {
            return null
        }
        val source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, session.sessionId)
        val sections = session.chunks.sortedBy { it.index }.mapNotNull { chunk ->
            chunk.text.trim().takeIf(String::isNotEmpty)?.let { text ->
                TranscriptSourceSection(
                    key = sectionKey(chunk.index),
                    label = "구간 ${chunk.index + 1}/${session.totalChunks}",
                    startMs = chunk.primaryStartMs.coerceAtLeast(0L),
                    endMs = chunk.primaryEndMs.coerceAtLeast(chunk.primaryStartMs.coerceAtLeast(0L)),
                    text = text,
                )
            }
        }
        return sections.takeIf(List<*>::isNotEmpty)?.let {
            TranscriptSourceDocument(source, session.updatedAtMs, it)
        }
    }

    fun fromCompletedGroup(
        group: RecordingTranscriptionGroupStore.Group,
        sessions: List<TranscriptionSessionStore.Checkpoint>,
    ): TranscriptSourceDocument? {
        if (
            !SOURCE_ID_REGEX.matches(group.groupId) ||
            group.status != RecordingTranscriptionGroupStore.GroupStatus.COMPLETED ||
            group.isPartial ||
            group.children.isEmpty() ||
            group.children.any {
                it.status != RecordingTranscriptionGroupStore.ChildStatus.COMPLETED ||
                    !SOURCE_ID_REGEX.matches(it.sttSessionId)
            }
        ) {
            return null
        }

        val sessionsById = sessions.associateBy(TranscriptionSessionStore.Checkpoint::sessionId)
        val sections = mutableListOf<TranscriptSourceSection>()
        var elapsedOffsetMs = 0L
        var sectionIndex = 0
        for ((childPosition, child) in group.children.sortedBy { it.sequence }.withIndex()) {
            val session = sessionsById[child.sttSessionId]
                ?.takeIf { it.status == TranscriptionSessionStore.Status.COMPLETED }
                ?: return null
            val childSections = session.chunks.sortedBy { it.index }.mapNotNull { chunk ->
                chunk.text.trim().takeIf(String::isNotEmpty)?.let { text ->
                    val localStartMs = chunk.primaryStartMs.coerceAtLeast(0L)
                    val localEndMs = chunk.primaryEndMs.coerceAtLeast(localStartMs)
                    TranscriptSourceSection(
                        key = sectionKey(sectionIndex++),
                        label = "녹음 ${childPosition + 1} · 구간 ${chunk.index + 1}",
                        startMs = elapsedOffsetMs + localStartMs,
                        endMs = elapsedOffsetMs + localEndMs,
                        text = text,
                    )
                }
            }
            if (childSections.isEmpty()) return null
            sections += childSections
            elapsedOffsetMs += session.durationMs.coerceAtLeast(0L)
        }

        return TranscriptSourceDocument(
            source = TranscriptSourceRef(TranscriptSourceType.RECORDING_GROUP, group.groupId),
            updatedAtMs = group.updatedAtMs,
            sections = sections,
        )
    }

    private fun sectionKey(index: Int): String = "U${(index + 1).toString().padStart(4, '0')}"

    private val SOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
}
