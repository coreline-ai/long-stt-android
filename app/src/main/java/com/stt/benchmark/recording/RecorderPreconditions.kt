package com.stt.benchmark.recording

object RecorderPreconditions {
    sealed interface Result {
        data object Ready : Result
        data object PermissionRequired : Result
        data object UnsupportedInput : Result
        data class InsufficientStorage(val shortageBytes: Long) : Result
    }

    fun evaluate(
        permissionGranted: Boolean,
        usableInput: Boolean,
        availableBytes: Long,
        chunkDurationMs: Long = RecordingSessionStore.DEFAULT_CHUNK_DURATION_MS,
    ): Result {
        if (!permissionGranted) return Result.PermissionRequired
        if (!usableInput) return Result.UnsupportedInput
        val storage = RecordingStorageEstimator.preflight(availableBytes, chunkDurationMs)
        return if (storage.allowed) Result.Ready else Result.InsufficientStorage(storage.shortageBytes)
    }
}
