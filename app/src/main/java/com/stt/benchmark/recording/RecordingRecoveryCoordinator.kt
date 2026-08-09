package com.stt.benchmark.recording

import android.content.Context

/** 앱 시작 시 세션 정본과 녹음 파일을 대조하되 자동 녹음/자동 삭제는 하지 않는다. */
class RecordingRecoveryCoordinator(
    context: Context,
    private val store: RecordingSessionStore = RecordingSessionStore(context),
    private val fileManager: RecordingFileManager = RecordingFileManager(context.filesDir),
) {
    data class Report(
        val reconciledSessionIds: List<String>,
        val actions: List<String>,
    )

    @Synchronized
    fun reconcile(
        nowMs: Long = System.currentTimeMillis(),
        startupCutoffExclusiveMs: Long? = null,
    ): Report {
        // 읽을 수 없는/미래 schema checkpoint도 파일 소유권은 유지한다.
        val checkpointIds = store.listSessionIds()
        store.reconcileAfterProcessDeath(
            nowMs = nowMs,
            checkpointModifiedBeforeExclusiveMs = startupCutoffExclusiveMs,
        )
        val known = store.listAll()
        val actions = mutableListOf<String>()
        val reconciled = known
            .filter { it.phase == RecordingPhase.RECOVERY_REQUIRED }
            .map { session ->
                val result = fileManager.reconcile(session)
                store.save(result.session)
                actions += result.actions
                result.session.sessionId
            }
        // 복구 도중 새 checkpoint가 생겼다면 그 소유권도 존중한다. 앱 시작 복구에서는
        // 현재 프로세스가 새로 만든 part가 백그라운드 검사에 격리되지 않도록 시작 시각을 경계로 둔다.
        val protectedCheckpointIds = checkpointIds + store.listSessionIds()
        actions += fileManager.quarantinePartsWithoutCheckpoint(
            knownSessionIds = protectedCheckpointIds,
            modifiedBeforeExclusiveMs = startupCutoffExclusiveMs,
        )
        return Report(reconciledSessionIds = reconciled, actions = actions)
    }
}
