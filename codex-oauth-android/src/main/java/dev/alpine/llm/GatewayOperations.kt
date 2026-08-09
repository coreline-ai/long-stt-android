package dev.alpine.llm

import kotlinx.coroutines.delay
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

enum class GatewayEventType {
    REQUEST_STARTED,
    REQUEST_COMPLETED,
    REQUEST_REJECTED,
    REQUEST_CANCELLED,
    RETRY_SCHEDULED,
    CIRCUIT_STATE_CHANGED,
}

/**
 * Deliberately closed event schema. It cannot carry credentials, request or
 * response bodies, Provider URLs, arbitrary headers, or exception messages.
 */
data class GatewayEvent(
    val type: GatewayEventType,
    val operation: String,
    val requestId: String? = null,
    val attempt: Int? = null,
    val statusCode: Int? = null,
    val elapsedMs: Long? = null,
    val activeRequests: Int? = null,
    val state: String? = null,
)

fun interface GatewayEventSink {
    fun emit(event: GatewayEvent)

    companion object {
        val NONE = GatewayEventSink { }
    }
}

data class ProviderRetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMs: Long = 250L,
    val maxBackoffMs: Long = 2_000L,
    val retryableStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must not be negative" }
        require(maxBackoffMs >= initialBackoffMs) {
            "maxBackoffMs must be at least initialBackoffMs"
        }
        require(retryableStatusCodes.all { it in 400..599 }) {
            "retryableStatusCodes must contain HTTP error statuses"
        }
    }

    fun backoffMs(attempt: Int, retryAfter: String?, nowMs: Long): Long {
        retryAfterMillis(retryAfter, nowMs)?.let { return min(it, maxBackoffMs) }
        var value = initialBackoffMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            value = min(maxBackoffMs, value * 2)
        }
        return value
    }

    private fun retryAfterMillis(value: String?, nowMs: Long): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalized.toLongOrNull()?.let { seconds ->
            val safeSeconds = seconds.coerceAtLeast(0L)
            val millis = if (safeSeconds > Long.MAX_VALUE / 1_000L) {
                Long.MAX_VALUE
            } else {
                safeSeconds * 1_000L
            }
            return millis.coerceAtMost(maxBackoffMs)
        }
        return runCatching {
            val targetMs = ZonedDateTime.parse(
                normalized,
                DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toInstant().toEpochMilli()
            (targetMs - nowMs).coerceAtLeast(0L)
        }.getOrNull()
    }
}

data class ProviderCircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val openDurationMs: Long = 30_000L,
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(openDurationMs > 0) { "openDurationMs must be positive" }
    }
}

class ProviderCircuitOpenException : IOException("Provider circuit is open")

class ProviderCircuitBreaker(
    private val config: ProviderCircuitBreakerConfig = ProviderCircuitBreakerConfig(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val eventSink: GatewayEventSink = GatewayEventSink.NONE,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var consecutiveFailures = 0
    private var openedAtMs = 0L
    private var halfOpenInFlight = false

    @Synchronized
    fun beforeRequest() {
        if (state == State.OPEN && clockMs() - openedAtMs >= config.openDurationMs) {
            transition(State.HALF_OPEN)
            halfOpenInFlight = false
        }
        if (state == State.OPEN || (state == State.HALF_OPEN && halfOpenInFlight)) {
            throw ProviderCircuitOpenException()
        }
        if (state == State.HALF_OPEN) halfOpenInFlight = true
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        halfOpenInFlight = false
        if (state != State.CLOSED) transition(State.CLOSED)
    }

    @Synchronized
    fun recordFailure() {
        halfOpenInFlight = false
        if (state == State.HALF_OPEN) {
            open()
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= config.failureThreshold) open()
    }

    @Synchronized
    fun state(): State = state

    private fun open() {
        openedAtMs = clockMs()
        transition(State.OPEN)
    }

    private fun transition(newState: State) {
        if (state == newState) return
        state = newState
        runCatching {
            eventSink.emit(
                GatewayEvent(
                    type = GatewayEventType.CIRCUIT_STATE_CHANGED,
                    operation = "provider_http",
                    state = newState.name.lowercase(),
                ),
            )
        }
    }
}

/**
 * Retries only requests whose adapter supplied an explicit idempotency contract. A returned
 * stream Flow is never collected here, so an emitted delta can never be duplicated.
 */
class ResilientOAuthHttpTransport(
    private val delegate: OAuthHttpTransport = UrlConnectionOAuthHttpTransport(),
    private val streamingDelegate: OAuthStreamingHttpTransport? =
        delegate as? OAuthStreamingHttpTransport,
    private val retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(),
    private val circuitBreaker: ProviderCircuitBreaker = ProviderCircuitBreaker(),
    private val eventSink: GatewayEventSink = GatewayEventSink.NONE,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : OAuthHttpTransport, OAuthStreamingHttpTransport {
    override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
        executeWithRetry(
            operation = "completion",
            status = ProviderHttpResponse::statusCode,
            headers = ProviderHttpResponse::headers,
            retrySafety = request.retrySafety,
        ) {
            delegate.execute(request)
        }

    override suspend fun executeStream(
        request: ProviderHttpRequest,
    ): ProviderHttpStreamResponse {
        val streamTransport = streamingDelegate
            ?: throw IllegalStateException("streaming transport is not configured")
        return executeWithRetry(
            operation = "stream_open",
            status = ProviderHttpStreamResponse::statusCode,
            headers = ProviderHttpStreamResponse::headers,
            retrySafety = request.retrySafety,
        ) {
            streamTransport.executeStream(request)
        }
    }

    private suspend fun <T> executeWithRetry(
        operation: String,
        status: (T) -> Int,
        headers: (T) -> Map<String, String>,
        retrySafety: ProviderRetrySafety,
        request: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            circuitBreaker.beforeRequest()
            try {
                val response = request()
                val responseStatus = status(response)
                if (responseStatus !in retryPolicy.retryableStatusCodes) {
                    circuitBreaker.recordSuccess()
                    return response
                }
                circuitBreaker.recordFailure()
                if (retrySafety != ProviderRetrySafety.IDEMPOTENT_WITH_STABLE_KEY ||
                    attempt >= retryPolicy.maxAttempts ||
                    circuitBreaker.state() == ProviderCircuitBreaker.State.OPEN
                ) {
                    return response
                }
                waitBeforeRetry(operation, attempt, responseStatus, headers(response))
            } catch (error: IOException) {
                circuitBreaker.recordFailure()
                if (retrySafety != ProviderRetrySafety.IDEMPOTENT_WITH_STABLE_KEY ||
                    error is ProviderCircuitOpenException ||
                    attempt >= retryPolicy.maxAttempts
                ) {
                    throw error
                }
                waitBeforeRetry(operation, attempt, null, emptyMap())
            }
            attempt++
        }
    }

    private suspend fun waitBeforeRetry(
        operation: String,
        attempt: Int,
        statusCode: Int?,
        headers: Map<String, String>,
    ) {
        val delayMs = retryPolicy.backoffMs(
            attempt = attempt,
            retryAfter = headers["retry-after"],
            nowMs = clockMs(),
        )
        runCatching {
            eventSink.emit(
                GatewayEvent(
                    type = GatewayEventType.RETRY_SCHEDULED,
                    operation = operation,
                    attempt = attempt + 1,
                    statusCode = statusCode,
                    elapsedMs = delayMs,
                ),
            )
        }
        sleeper(delayMs)
    }
}
