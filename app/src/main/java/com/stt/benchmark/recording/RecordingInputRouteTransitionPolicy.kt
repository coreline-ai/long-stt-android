package com.stt.benchmark.recording

/**
 * Reduces generic input-route observations without retaining a device name or identifier.
 * UNKNOWN after a confirmed route is treated as transient until the service confirms it.
 */
object RecordingInputRouteTransitionPolicy {
    enum class Decision {
        INITIALIZED,
        UNCHANGED,
        AWAITING_REPLACEMENT,
        CHANGED,
    }

    fun decide(
        confirmedRoute: RecordingInputRoute,
        observedRoute: RecordingInputRoute,
    ): Decision = when {
        observedRoute == RecordingInputRoute.UNKNOWN && confirmedRoute == RecordingInputRoute.UNKNOWN -> {
            Decision.UNCHANGED
        }
        observedRoute == RecordingInputRoute.UNKNOWN -> Decision.AWAITING_REPLACEMENT
        confirmedRoute == RecordingInputRoute.UNKNOWN -> Decision.INITIALIZED
        observedRoute == confirmedRoute -> Decision.UNCHANGED
        else -> Decision.CHANGED
    }

    fun isCurrentObservation(
        expectedSessionId: String,
        expectedChunkIndex: Int,
        expectedRouteEpoch: Long,
        observedSessionId: String,
        observedChunkIndex: Int,
        observedRouteEpoch: Long,
    ): Boolean =
        expectedSessionId == observedSessionId &&
            expectedChunkIndex == observedChunkIndex &&
            expectedRouteEpoch == observedRouteEpoch
}
