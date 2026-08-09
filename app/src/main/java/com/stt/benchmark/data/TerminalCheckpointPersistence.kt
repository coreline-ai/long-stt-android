package com.stt.benchmark.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 취소된 부모 coroutine에서도 terminal checkpoint 저장 순서를 보장한다. */
object TerminalCheckpointPersistence {

    suspend fun persist(
        initial: TranscriptionSessionStore.Checkpoint,
        status: TranscriptionSessionStore.Status,
        errorMessage: String,
        nowMs: Long = System.currentTimeMillis(),
        loadLatest: () -> TranscriptionSessionStore.Checkpoint?,
        save: (TranscriptionSessionStore.Checkpoint) -> Unit,
        afterSave: (TranscriptionSessionStore.Checkpoint) -> Unit = {},
        publish: (TranscriptionSessionStore.Checkpoint) -> Unit
    ): TranscriptionSessionStore.Checkpoint = withContext(NonCancellable + Dispatchers.IO) {
        val latest = loadLatest() ?: initial
        val terminal = latest.copy(
            status = status,
            errorMessage = errorMessage,
            updatedAtMs = maxOf(nowMs, latest.updatedAtMs)
        )
        save(terminal)
        afterSave(terminal)
        publish(terminal)
        terminal
    }
}
