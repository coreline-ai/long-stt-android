package dev.alpine.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayOperationsTest {
    @Test
    fun retriesRetryableStatusAndEmitsClosedSchemaEvent() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val events = mutableListOf<GatewayEvent>()
        val transport = ResilientOAuthHttpTransport(
            delegate = OAuthHttpTransport {
                calls++
                ProviderHttpResponse(
                    statusCode = if (calls == 1) 503 else 200,
                    bodyJson = "{}",
                    headers = if (calls == 1) mapOf("retry-after" to "1") else emptyMap(),
                )
            },
            retryPolicy = ProviderRetryPolicy(
                maxAttempts = 3,
                initialBackoffMs = 10,
                maxBackoffMs = 2_000,
            ),
            circuitBreaker = ProviderCircuitBreaker(
                ProviderCircuitBreakerConfig(failureThreshold = 5),
            ),
            eventSink = GatewayEventSink(events::add),
            sleeper = { waits += it },
        )

        val response = transport.execute(idempotentRequest())

        assertEquals(200, response.statusCode)
        assertEquals(2, calls)
        assertEquals(listOf(1_000L), waits)
        assertEquals(GatewayEventType.RETRY_SCHEDULED, events.single().type)
        assertEquals(2, events.single().attempt)
        assertTrue(events.single().toString().contains("statusCode=503"))
    }

    @Test
    fun doesNotRetryNonRetryableStatus() = runBlocking {
        var calls = 0
        val transport = ResilientOAuthHttpTransport(
            delegate = OAuthHttpTransport {
                calls++
                ProviderHttpResponse(400, "{}")
            },
            sleeper = { error("must not sleep") },
        )

        assertEquals(
            400,
            transport.execute(ProviderHttpRequest("https://example.com", "{}")).statusCode,
        )
        assertEquals(1, calls)
    }

    @Test
    fun inferencePostIsNotRetriedWithoutAnExplicitIdempotencyContract() = runBlocking {
        var calls = 0
        val transport = ResilientOAuthHttpTransport(
            delegate = OAuthHttpTransport {
                calls++
                ProviderHttpResponse(503, "{}")
            },
            retryPolicy = ProviderRetryPolicy(maxAttempts = 3, initialBackoffMs = 0),
            sleeper = { error("an unsafe request must not be scheduled for retry") },
        )

        assertEquals(
            503,
            transport.execute(ProviderHttpRequest("https://example.com", "{}")).statusCode,
        )
        assertEquals(1, calls)
    }

    @Test
    fun idempotentRetryContractRequiresTheDeclaredStableHeader() {
        val error = runCatching {
            ProviderHttpRequest(
                url = "https://example.com",
                bodyJson = "{}",
                retrySafety = ProviderRetrySafety.IDEMPOTENT_WITH_STABLE_KEY,
                idempotencyKeyHeader = "Idempotency-Key",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun retriesIoFailureAndStreamOpenOnly() = runBlocking {
        var completeCalls = 0
        var streamCalls = 0
        var collected = 0
        val completeDelegate = OAuthHttpTransport {
            completeCalls++
            if (completeCalls == 1) throw IOException("temporary")
            ProviderHttpResponse(200, "{}")
        }
        val streamDelegate = OAuthStreamingHttpTransport {
            streamCalls++
            if (streamCalls == 1) {
                ProviderHttpStreamResponse(503, flowOf())
            } else {
                ProviderHttpStreamResponse(
                    200,
                    flowOf(ProviderSseEvent(null, """{"ok":true}""")),
                )
            }
        }
        val transport = ResilientOAuthHttpTransport(
            delegate = completeDelegate,
            streamingDelegate = streamDelegate,
            retryPolicy = ProviderRetryPolicy(
                maxAttempts = 3,
                initialBackoffMs = 0,
                maxBackoffMs = 0,
            ),
            circuitBreaker = ProviderCircuitBreaker(
                ProviderCircuitBreakerConfig(failureThreshold = 10),
            ),
            sleeper = { },
        )

        assertEquals(
            200,
            transport.execute(idempotentRequest()).statusCode,
        )
        val stream = transport.executeStream(idempotentRequest())
        val values = stream.events.toList().also { collected += it.size }

        assertEquals(2, completeCalls)
        assertEquals(2, streamCalls)
        assertEquals(1, values.size)
        assertEquals(1, collected)
    }

    @Test
    fun circuitTransitionsOpenHalfOpenClosed() {
        var now = 1_000L
        val states = mutableListOf<String>()
        val breaker = ProviderCircuitBreaker(
            config = ProviderCircuitBreakerConfig(
                failureThreshold = 2,
                openDurationMs = 100,
            ),
            clockMs = { now },
            eventSink = GatewayEventSink { event ->
                event.state?.let(states::add)
            },
        )

        breaker.beforeRequest()
        breaker.recordFailure()
        breaker.beforeRequest()
        breaker.recordFailure()
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.state())
        assertTrue(runCatching { breaker.beforeRequest() }.exceptionOrNull() is ProviderCircuitOpenException)

        now += 101
        breaker.beforeRequest()
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.state())
        breaker.recordSuccess()

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.state())
        assertEquals(listOf("open", "half_open", "closed"), states)
    }

    @Test
    fun halfOpenCircuitAllowsOnlyOneConcurrentProbe() {
        var now = 1_000L
        val breaker = ProviderCircuitBreaker(
            config = ProviderCircuitBreakerConfig(
                failureThreshold = 1,
                openDurationMs = 100,
            ),
            clockMs = { now },
        )
        breaker.beforeRequest()
        breaker.recordFailure()
        now += 101

        val start = CountDownLatch(1)
        val finished = CountDownLatch(16)
        val allowed = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(16)
        repeat(16) {
            executor.execute {
                try {
                    start.await()
                    if (runCatching { breaker.beforeRequest() }.isSuccess) {
                        allowed.incrementAndGet()
                    }
                } finally {
                    finished.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, allowed.get())
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.state())
    }

    private fun idempotentRequest() = ProviderHttpRequest(
        url = "https://example.com",
        bodyJson = "{}",
        headers = mapOf("Idempotency-Key" to "stable-test-key"),
        retrySafety = ProviderRetrySafety.IDEMPOTENT_WITH_STABLE_KEY,
        idempotencyKeyHeader = "Idempotency-Key",
    )
}
