package com.stt.benchmark.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** START/STOP/rollover/input-route/backend completion을 단일 mailbox에서 순서대로 처리한다. */
class RecorderCommandActor(
    scope: CoroutineScope,
    private val handler: suspend (Command) -> Outcome,
) {
    sealed interface Command {
        /** Android startIds prevent a terminal stop from racing a newer foreground-service start. */
        data class Start(val startId: Int) : Command
        data class Stop(val startId: Int) : Command
        data class Rollover(val sessionId: String, val chunkIndex: Int) : Command
        data class InputRouteObserved(
            val sessionId: String,
            val chunkIndex: Int,
            val routeEpoch: Long,
            val route: RecordingInputRoute,
        ) : Command
        data class InputRouteUnavailable(
            val sessionId: String,
            val chunkIndex: Int,
            val routeEpoch: Long,
            val previousRoute: RecordingInputRoute,
        ) : Command
        data class BackendFailure(
            val sessionId: String,
            val chunkIndex: Int,
            val errorType: String,
        ) : Command
    }

    sealed interface Outcome {
        data object Accepted : Outcome
        data class Ignored(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private data class Envelope(
        val command: Command,
        val response: CompletableDeferred<Outcome>,
    )

    private val mailbox = Channel<Envelope>(Channel.UNLIMITED)
    private val processor: Job = scope.launch {
        for (envelope in mailbox) {
            val outcome = runCatching { handler(envelope.command) }
                .getOrElse { Outcome.Failed(it.javaClass.simpleName) }
            envelope.response.complete(outcome)
        }
    }

    suspend fun submit(command: Command): Outcome {
        val response = CompletableDeferred<Outcome>()
        mailbox.send(Envelope(command, response))
        return response.await()
    }

    fun close() {
        mailbox.close()
        processor.cancel()
    }
}
