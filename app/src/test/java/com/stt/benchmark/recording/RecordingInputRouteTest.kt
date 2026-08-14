package com.stt.benchmark.recording

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingInputRouteTest {
    @Test
    fun routeMappingUsesOnlySafeGenericCategories() {
        assertEquals(RecordingInputRoute.UNKNOWN, RecordingInputRoute.fromDeviceType(null))
        assertEquals(
            RecordingInputRoute.BUILT_IN,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
        assertEquals(
            RecordingInputRoute.BLUETOOTH,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(
            RecordingInputRoute.USB,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE),
        )
        assertEquals(
            RecordingInputRoute.WIRED,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(RecordingInputRoute.OTHER, RecordingInputRoute.fromDeviceType(-1))
    }

    @Test
    fun transitionPolicyDistinguishesInitializationDuplicatesAndChanges() {
        assertEquals(
            RecordingInputRouteTransitionPolicy.Decision.UNCHANGED,
            RecordingInputRouteTransitionPolicy.decide(
                RecordingInputRoute.UNKNOWN,
                RecordingInputRoute.UNKNOWN,
            ),
        )
        assertEquals(
            RecordingInputRouteTransitionPolicy.Decision.INITIALIZED,
            RecordingInputRouteTransitionPolicy.decide(
                RecordingInputRoute.UNKNOWN,
                RecordingInputRoute.BUILT_IN,
            ),
        )
        assertEquals(
            RecordingInputRouteTransitionPolicy.Decision.UNCHANGED,
            RecordingInputRouteTransitionPolicy.decide(
                RecordingInputRoute.BUILT_IN,
                RecordingInputRoute.BUILT_IN,
            ),
        )
        assertEquals(
            RecordingInputRouteTransitionPolicy.Decision.AWAITING_REPLACEMENT,
            RecordingInputRouteTransitionPolicy.decide(
                RecordingInputRoute.BUILT_IN,
                RecordingInputRoute.UNKNOWN,
            ),
        )
        assertEquals(
            RecordingInputRouteTransitionPolicy.Decision.CHANGED,
            RecordingInputRouteTransitionPolicy.decide(
                RecordingInputRoute.BUILT_IN,
                RecordingInputRoute.USB,
            ),
        )
    }

    @Test
    fun staleRouteObservationsAreRejectedBySessionChunkAndEpoch() {
        fun current(sessionId: String, chunkIndex: Int, routeEpoch: Long) =
            RecordingInputRouteTransitionPolicy.isCurrentObservation(
                expectedSessionId = "recording_current",
                expectedChunkIndex = 3,
                expectedRouteEpoch = 7L,
                observedSessionId = sessionId,
                observedChunkIndex = chunkIndex,
                observedRouteEpoch = routeEpoch,
            )

        assertEquals(true, current("recording_current", 3, 7L))
        assertEquals(false, current("recording_old", 3, 7L))
        assertEquals(false, current("recording_current", 2, 7L))
        assertEquals(false, current("recording_current", 3, 6L))
    }
}
