package com.stt.benchmark.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingRuntimeSnapshot(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sessionId: String = "",
    val currentChunkIndex: Int = 0,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val inputRoute: RecordingInputRoute = RecordingInputRoute.UNKNOWN,
    val message: String = "",
)

object RecordingRuntime {
    private val mutable = MutableStateFlow(RecordingRuntimeSnapshot())
    val snapshot: StateFlow<RecordingRuntimeSnapshot> = mutable.asStateFlow()

    internal fun publish(value: RecordingRuntimeSnapshot) {
        mutable.value = value
    }
}
