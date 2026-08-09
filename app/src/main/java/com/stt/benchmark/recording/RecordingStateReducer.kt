package com.stt.benchmark.recording

/**
 * RecorderService와 파일 I/O보다 먼저 고정하는 순수 상태 머신이다.
 * 거절된 이벤트는 기존 상태를 보존하므로 START/STOP 연타가 새 세션을 만들지 않는다.
 */
enum class RecordingPhase {
    IDLE,
    PREPARING,
    RECORDING,
    ROLLING_OVER,
    FINALIZING,
    SAVED,
    FAILED,
    RECOVERY_REQUIRED,
}

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sessionId: String = "",
    val currentChunkIndex: Int = 0,
    val message: String = "",
)

sealed interface RecordingEvent {
    data class StartRequested(val sessionId: String) : RecordingEvent
    data object RecorderStarted : RecordingEvent
    data object RolloverRequested : RecordingEvent
    data object RolloverCompleted : RecordingEvent
    data object StopRequested : RecordingEvent
    data object Finalized : RecordingEvent
    data class Failed(val message: String, val mayHaveRecoverableFiles: Boolean) : RecordingEvent
    data object ProcessRestarted : RecordingEvent
    data class RecoveryCompleted(val hasReadyChunks: Boolean, val message: String = "") : RecordingEvent
    data object Reset : RecordingEvent
}

data class RecordingTransition(
    val state: RecordingState,
    val accepted: Boolean,
    val reason: String = "",
)

object RecordingStateReducer {
    fun reduce(current: RecordingState, event: RecordingEvent): RecordingTransition {
        val next = when (event) {
            is RecordingEvent.StartRequested -> when {
                current.phase != RecordingPhase.IDLE -> null
                !isValidSessionId(event.sessionId) -> null
                else -> RecordingState(
                    phase = RecordingPhase.PREPARING,
                    sessionId = event.sessionId,
                )
            }

            RecordingEvent.RecorderStarted -> current.takeIfPhase(RecordingPhase.PREPARING)?.copy(
                phase = RecordingPhase.RECORDING,
            )

            RecordingEvent.RolloverRequested -> current.takeIfPhase(RecordingPhase.RECORDING)?.copy(
                phase = RecordingPhase.ROLLING_OVER,
            )

            RecordingEvent.RolloverCompleted -> current.takeIfPhase(RecordingPhase.ROLLING_OVER)?.copy(
                phase = RecordingPhase.RECORDING,
                currentChunkIndex = current.currentChunkIndex + 1,
            )

            RecordingEvent.StopRequested -> when (current.phase) {
                RecordingPhase.PREPARING,
                RecordingPhase.RECORDING,
                RecordingPhase.ROLLING_OVER,
                -> current.copy(phase = RecordingPhase.FINALIZING)

                RecordingPhase.FINALIZING -> current // idempotent STOP
                else -> null
            }

            RecordingEvent.Finalized -> current.takeIfPhase(RecordingPhase.FINALIZING)?.copy(
                phase = RecordingPhase.SAVED,
                message = "",
            )

            is RecordingEvent.Failed -> when (current.phase) {
                RecordingPhase.PREPARING,
                RecordingPhase.RECORDING,
                RecordingPhase.ROLLING_OVER,
                RecordingPhase.FINALIZING,
                -> current.copy(
                    phase = if (event.mayHaveRecoverableFiles) {
                        RecordingPhase.RECOVERY_REQUIRED
                    } else {
                        RecordingPhase.FAILED
                    },
                    message = event.message,
                )

                else -> null
            }

            RecordingEvent.ProcessRestarted -> when (current.phase) {
                RecordingPhase.PREPARING,
                RecordingPhase.RECORDING,
                RecordingPhase.ROLLING_OVER,
                RecordingPhase.FINALIZING,
                -> current.copy(
                    phase = RecordingPhase.RECOVERY_REQUIRED,
                    message = "프로세스 중단 뒤 파일 검사가 필요합니다.",
                )

                else -> current // terminal/idle 상태는 재시작에도 그대로 유지한다.
            }

            is RecordingEvent.RecoveryCompleted -> current
                .takeIfPhase(RecordingPhase.RECOVERY_REQUIRED)
                ?.copy(
                    phase = if (event.hasReadyChunks) RecordingPhase.SAVED else RecordingPhase.FAILED,
                    message = event.message,
                )

            RecordingEvent.Reset -> when (current.phase) {
                RecordingPhase.SAVED,
                RecordingPhase.FAILED,
                -> RecordingState()

                RecordingPhase.IDLE -> current
                else -> null
            }
        }

        return if (next == null) {
            RecordingTransition(
                state = current,
                accepted = false,
                reason = "${current.phase} 상태에서 ${event::class.simpleName} 이벤트를 처리할 수 없습니다.",
            )
        } else {
            RecordingTransition(state = next, accepted = true)
        }
    }

    private fun RecordingState.takeIfPhase(expected: RecordingPhase): RecordingState? =
        takeIf { it.phase == expected }

    fun isValidSessionId(sessionId: String): Boolean =
        sessionId.matches(Regex("recording_[A-Za-z0-9_-]{1,96}"))
}
