package com.stt.benchmark.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateReducerTest {
    @Test
    fun normalRecordingTransitionsOnlyThroughSafePhases() {
        var state = RecordingState()
        state = accepted(state, RecordingEvent.StartRequested("recording_1000_test"))
        assertEquals(RecordingPhase.PREPARING, state.phase)
        state = accepted(state, RecordingEvent.RecorderStarted)
        state = accepted(state, RecordingEvent.RolloverRequested)
        state = accepted(state, RecordingEvent.RolloverCompleted)
        assertEquals(1, state.currentChunkIndex)
        state = accepted(state, RecordingEvent.StopRequested)
        state = accepted(state, RecordingEvent.StopRequested)
        assertEquals(RecordingPhase.FINALIZING, state.phase)
        state = accepted(state, RecordingEvent.Finalized)
        state = accepted(state, RecordingEvent.Reset)
        assertEquals(RecordingState(), state)
    }

    @Test
    fun duplicateStartAndInvalidEventsPreserveCurrentState() {
        val preparing = accepted(
            RecordingState(),
            RecordingEvent.StartRequested("recording_1000_test"),
        )
        val duplicate = RecordingStateReducer.reduce(
            preparing,
            RecordingEvent.StartRequested("recording_2000_duplicate"),
        )
        assertFalse(duplicate.accepted)
        assertEquals(preparing, duplicate.state)

        val invalidId = RecordingStateReducer.reduce(
            RecordingState(),
            RecordingEvent.StartRequested("../escape"),
        )
        assertFalse(invalidId.accepted)
        assertEquals(RecordingPhase.IDLE, invalidId.state.phase)
    }

    @Test
    fun processRestartRequiresRecoveryForEveryActivePhase() {
        val activePhases = listOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.ROLLING_OVER,
            RecordingPhase.FINALIZING,
        )
        activePhases.forEach { phase ->
            val transition = RecordingStateReducer.reduce(
                RecordingState(phase = phase, sessionId = "recording_1000_test"),
                RecordingEvent.ProcessRestarted,
            )
            assertTrue(transition.accepted)
            assertEquals(RecordingPhase.RECOVERY_REQUIRED, transition.state.phase)
        }
    }

    @Test
    fun recoveryNeverPretendsMissingAudioWasSaved() {
        val recovering = RecordingState(
            phase = RecordingPhase.RECOVERY_REQUIRED,
            sessionId = "recording_1000_test",
        )
        assertEquals(
            RecordingPhase.SAVED,
            accepted(recovering, RecordingEvent.RecoveryCompleted(hasReadyChunks = true)).phase,
        )
        assertEquals(
            RecordingPhase.FAILED,
            accepted(recovering, RecordingEvent.RecoveryCompleted(hasReadyChunks = false)).phase,
        )
    }

    private fun accepted(state: RecordingState, event: RecordingEvent): RecordingState {
        val transition = RecordingStateReducer.reduce(state, event)
        assertTrue(transition.reason, transition.accepted)
        return transition.state
    }
}
