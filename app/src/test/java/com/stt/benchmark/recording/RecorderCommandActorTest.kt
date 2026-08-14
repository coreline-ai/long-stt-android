package com.stt.benchmark.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCommandActorTest {
    @Test
    fun stopWaitsUntilStartHandlerCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val actor = RecorderCommandActor(scope) { command ->
            when (command) {
                is RecorderCommandActor.Command.Start -> {
                    order += "start-begin"
                    startEntered.complete(Unit)
                    releaseStart.await()
                    order += "start-end"
                }
                is RecorderCommandActor.Command.Stop -> order += "stop"
                else -> Unit
            }
            RecorderCommandActor.Outcome.Accepted
        }

        val start = async { actor.submit(RecorderCommandActor.Command.Start(startId = 1)) }
        startEntered.await()
        val stop = async { actor.submit(RecorderCommandActor.Command.Stop(startId = 2)) }
        releaseStart.complete(Unit)
        start.await()
        stop.await()

        assertEquals(listOf("start-begin", "start-end", "stop"), order)
        actor.close()
        scope.cancel()
    }

    @Test
    fun staleRolloverCanBeIgnoredWithoutMutatingCurrentSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = RecorderCommandActor(scope) { command ->
            if (command is RecorderCommandActor.Command.Rollover && command.sessionId != "recording_current") {
                RecorderCommandActor.Outcome.Ignored("오래된 rollover")
            } else {
                RecorderCommandActor.Outcome.Accepted
            }
        }

        val result = actor.submit(RecorderCommandActor.Command.Rollover("recording_old", 0))

        assertTrue(result is RecorderCommandActor.Outcome.Ignored)
        actor.close()
        scope.cancel()
    }

    @Test
    fun inputRouteObservationCannotOvertakeAnEarlierStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val order = mutableListOf<String>()
        val actor = RecorderCommandActor(scope) { command ->
            order += when (command) {
                is RecorderCommandActor.Command.Stop -> "stop"
                is RecorderCommandActor.Command.InputRouteObserved -> "route"
                else -> "other"
            }
            RecorderCommandActor.Outcome.Accepted
        }

        val stop = async { actor.submit(RecorderCommandActor.Command.Stop(startId = 2)) }
        val route = async {
            actor.submit(
                RecorderCommandActor.Command.InputRouteObserved(
                    sessionId = "recording_current",
                    chunkIndex = 0,
                    routeEpoch = 1L,
                    route = RecordingInputRoute.USB,
                )
            )
        }

        stop.await()
        route.await()
        assertEquals(listOf("stop", "route"), order)
        actor.close()
        scope.cancel()
    }
}
