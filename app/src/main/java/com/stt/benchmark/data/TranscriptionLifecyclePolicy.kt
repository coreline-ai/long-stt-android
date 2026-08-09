package com.stt.benchmark.data

/**
 * Android Service 수명주기와 분리한 checkpoint 상태 전이 규칙.
 *
 * 상태 판단을 순수 Kotlin으로 유지해 process death/취소 회귀를 JVM 테스트에서 고정한다.
 */
object TranscriptionLifecyclePolicy {

    val activelyRunningStatuses = setOf(
        TranscriptionSessionStore.Status.PREPARING,
        TranscriptionSessionStore.Status.RUNNING,
        TranscriptionSessionStore.Status.COOLING
    )

    val resumableStatuses = activelyRunningStatuses + TranscriptionSessionStore.Status.INTERRUPTED

    val terminalStatuses = setOf(
        TranscriptionSessionStore.Status.COMPLETED,
        TranscriptionSessionStore.Status.FAILED,
        TranscriptionSessionStore.Status.CANCELLED
    )

    fun terminalStatusForCancellation(userRequested: Boolean): TranscriptionSessionStore.Status =
        if (userRequested) {
            TranscriptionSessionStore.Status.CANCELLED
        } else {
            TranscriptionSessionStore.Status.INTERRUPTED
        }

    fun canResume(status: TranscriptionSessionStore.Status): Boolean = status in resumableStatuses

    fun needsStartupReconciliation(status: TranscriptionSessionStore.Status): Boolean =
        status in activelyRunningStatuses

    fun reconcileAfterProcessDeath(
        checkpoint: TranscriptionSessionStore.Checkpoint,
        nowMs: Long,
        message: String = DEFAULT_INTERRUPTED_MESSAGE
    ): TranscriptionSessionStore.Checkpoint = if (needsStartupReconciliation(checkpoint.status)) {
        checkpoint.copy(
            status = TranscriptionSessionStore.Status.INTERRUPTED,
            errorMessage = message,
            updatedAtMs = maxOf(nowMs, checkpoint.updatedAtMs)
        )
    } else {
        checkpoint
    }

    const val DEFAULT_INTERRUPTED_MESSAGE = "이전 앱 실행이 종료되어 재개 대기 상태로 전환됨"
}
