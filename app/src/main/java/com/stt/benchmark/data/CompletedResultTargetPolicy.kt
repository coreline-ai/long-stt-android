package com.stt.benchmark.data

/** Service terminal state를 원문 없는 완료 결과 target으로 투영하는 단일 계약. */
object CompletedResultTargetPolicy {
    fun fromStandaloneSession(
        checkpoint: TranscriptionSessionStore.Checkpoint,
    ): CompletedResultTargetStore.Target? = if (
        checkpoint.status == TranscriptionSessionStore.Status.COMPLETED &&
        checkpoint.recordingGroupId.isBlank()
    ) {
        CompletedResultTargetStore.Target.create(
            CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION,
            checkpoint.sessionId,
        )
    } else {
        null
    }

    fun fromRecordingGroup(
        group: RecordingTranscriptionGroupStore.Group,
    ): CompletedResultTargetStore.Target? = if (
        group.status in COMPLETED_GROUP_STATUSES
    ) {
        CompletedResultTargetStore.Target.create(
            CompletedResultTargetStore.Type.RECORDING_GROUP,
            group.groupId,
        )
    } else {
        null
    }

    private val COMPLETED_GROUP_STATUSES = setOf(
        RecordingTranscriptionGroupStore.GroupStatus.COMPLETED,
        RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED,
    )
}
