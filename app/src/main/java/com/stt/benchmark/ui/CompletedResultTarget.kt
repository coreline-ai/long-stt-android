package com.stt.benchmark.ui

import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore

/** 전사 완료 화면에서 보관함 상세로 전달하는 원문 없는 불투명 결과 식별자. */
class CompletedResultTarget private constructor(
    val type: Type,
    val id: String,
) {
    enum class Type { TRANSCRIPTION_SESSION, RECORDING_GROUP }

    fun isAvailable(
        sessions: List<TranscriptionSessionStore.Checkpoint>,
        groups: List<RecordingTranscriptionGroupStore.Group>,
    ): Boolean = when (type) {
        Type.TRANSCRIPTION_SESSION -> sessions.any { session ->
            session.sessionId == id &&
                session.status == TranscriptionSessionStore.Status.COMPLETED &&
                session.recordingGroupId.isBlank()
        }

        Type.RECORDING_GROUP -> groups.any { group ->
            group.groupId == id && group.status in COMPLETED_GROUP_STATUSES
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CompletedResultTarget && other.type == type && other.id == id

    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()

    /** session/group ID가 일반 로그에 우발적으로 포함되지 않게 유형만 표시한다. */
    override fun toString(): String = "CompletedResultTarget(type=$type)"

    companion object {
        fun create(type: Type, id: String): CompletedResultTarget? = id
            .takeIf(SAFE_ID::matches)
            ?.let { CompletedResultTarget(type, it) }

        fun restore(typeName: String, id: String): CompletedResultTarget? = Type.entries
            .firstOrNull { it.name == typeName }
            ?.let { create(it, id) }

        fun fromSession(session: TranscriptionSessionStore.Checkpoint): CompletedResultTarget? =
            if (
                session.status == TranscriptionSessionStore.Status.COMPLETED &&
                session.recordingGroupId.isBlank()
            ) {
                create(Type.TRANSCRIPTION_SESSION, session.sessionId)
            } else {
                null
            }

        fun fromGroup(group: RecordingTranscriptionGroupStore.Group): CompletedResultTarget? =
            if (group.status in COMPLETED_GROUP_STATUSES) {
                create(Type.RECORDING_GROUP, group.groupId)
            } else {
                null
            }

        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private val COMPLETED_GROUP_STATUSES = setOf(
            RecordingTranscriptionGroupStore.GroupStatus.COMPLETED,
            RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED,
        )
    }
}
