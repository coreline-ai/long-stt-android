package dev.alpine.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Automatic retry is fail-closed because an inference POST may have been accepted even when
 * its response is lost. Adapters may opt in only after their Provider idempotency contract is
 * reviewed and the same idempotency key is retained for every transport attempt.
 */
enum class ProviderRetrySafety {
    NEVER_AUTOMATIC,
    IDEMPOTENT_WITH_STABLE_KEY,
}

data class ProviderHttpRequest(
    val url: String,
    val bodyJson: String,
    val headers: Map<String, String> = emptyMap(),
    /** Header name that receives the sanitized OAuth account id at the credential boundary. */
    val credentialAccountIdHeader: String? = null,
    val retrySafety: ProviderRetrySafety = ProviderRetrySafety.NEVER_AUTOMATIC,
    val idempotencyKeyHeader: String? = null,
) {
    init {
        if (retrySafety == ProviderRetrySafety.IDEMPOTENT_WITH_STABLE_KEY) {
            require(!idempotencyKeyHeader.isNullOrBlank()) {
                "idempotent retry requires an idempotency header"
            }
            require(idempotencyKeyHeader.matches(Regex("[A-Za-z0-9-]{1,80}"))) {
                "idempotency header name is invalid"
            }
            val key = headers.entries.firstOrNull {
                it.key.equals(idempotencyKeyHeader, ignoreCase = true)
            }?.value
            require(!key.isNullOrBlank() && key.length <= 256) {
                "idempotency header value is missing or invalid"
            }
        } else {
            require(idempotencyKeyHeader == null) {
                "idempotency header requires an idempotent retry contract"
            }
        }
    }
}

data class ProviderHttpResponse(
    val statusCode: Int,
    val bodyJson: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ProviderSseEvent(
    val event: String?,
    val data: String,
)

data class ProviderHttpStreamResponse(
    val statusCode: Int,
    val events: Flow<ProviderSseEvent>,
    val errorBodyJson: String = "",
    val headers: Map<String, String> = emptyMap(),
)

class ProviderStreamException(message: String) : Exception(message)

/**
 * Provider adapters transform protocol data only. They never receive the
 * OAuth credential, which limits accidental token logging or serialization.
 */
interface OAuthProviderHttpAdapter {
    fun createRequest(requestJson: String): ProviderHttpRequest

    fun createResult(response: ProviderHttpResponse): HostLlmResult =
        HostLlmResult(response.bodyJson, response.statusCode)
}

interface OAuthStreamingProviderHttpAdapter : OAuthProviderHttpAdapter {
    fun createStreamRequest(requestJson: String): ProviderHttpRequest

    fun createStreamEvent(event: ProviderSseEvent): HostLlmStreamEvent?
}

fun interface OAuthHttpTransport {
    suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse
}

fun interface OAuthStreamingHttpTransport {
    suspend fun executeStream(request: ProviderHttpRequest): ProviderHttpStreamResponse
}

/**
 * Adds the OAuth credential after Provider adaptation and performs the Host
 * request. The resulting bridge can be passed directly to [OAuthLlmSession].
 */
class OAuthHttpLlmBridge(
    private val adapter: OAuthProviderHttpAdapter,
    private val streamingTransport: OAuthStreamingHttpTransport? = null,
    private val transport: OAuthHttpTransport = UrlConnectionOAuthHttpTransport(),
) : HostLlmBridge {
    override suspend fun complete(
        requestJson: String,
        credential: OAuthCredential,
    ): HostLlmResult {
        val adapted = authenticated(adapter.createRequest(requestJson), credential)
        return adapter.createResult(transport.execute(adapted))
    }

    override suspend fun stream(
        requestJson: String,
        credential: OAuthCredential,
    ): HostLlmStreamResult {
        val streamAdapter = adapter as? OAuthStreamingProviderHttpAdapter
            ?: return super<HostLlmBridge>.stream(requestJson, credential)
        val streamTransport = streamingTransport ?: transport as? OAuthStreamingHttpTransport
            ?: return super<HostLlmBridge>.stream(requestJson, credential)
        val adapted = authenticated(streamAdapter.createStreamRequest(requestJson), credential)
        val response = streamTransport.executeStream(adapted)
        if (response.statusCode !in 200..299) {
            val mapped = adapter.createResult(
                ProviderHttpResponse(
                    statusCode = response.statusCode,
                    bodyJson = response.errorBodyJson,
                    headers = response.headers,
                ),
            )
            return HostLlmStreamResult(
                statusCode = mapped.statusCode,
                errorBodyJson = mapped.bodyJson,
            )
        }
        return HostLlmStreamResult(
            statusCode = response.statusCode,
            events = response.events.mapNotNull(streamAdapter::createStreamEvent),
        )
    }

    private fun authenticated(
        request: ProviderHttpRequest,
        credential: OAuthCredential,
    ): ProviderHttpRequest {
        require(request.headers.keys.none { it.equals(AUTHORIZATION, ignoreCase = true) }) {
            "Provider adapter must not set Authorization"
        }
        val headers = request.headers.toMutableMap()
        request.credentialAccountIdHeader?.let { headerName ->
            require(headers.keys.none { it.equals(headerName, ignoreCase = true) }) {
                "Provider adapter must not pre-set the OAuth account header"
            }
            credential.accountId?.takeIf { it.isNotBlank() }?.let { accountId ->
                ProviderAdapterJson.requireSafeHeaders(mapOf(headerName to accountId))
                headers[headerName] = accountId
            }
        }
        return request.copy(
            headers = headers + (AUTHORIZATION to "${credential.tokenType} ${credential.accessToken}"),
        )
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * Minimal built-in adapter for OAuth-enabled OpenAI-compatible endpoints.
 * Provider endpoint/model/client registration remain application settings.
 */
class OpenAiCompatibleOAuthAdapter(
    private val completionEndpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthStreamingProviderHttpAdapter {
    init {
        require(completionEndpoint.startsWith("https://")) {
            "completionEndpoint must use HTTPS"
        }
        ProviderAdapterJson.requireSafeHeaders(extraHeaders)
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = false)

    override fun createStreamRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = true)

    override fun createStreamEvent(event: ProviderSseEvent): HostLlmStreamEvent? {
        if (event.data == "[DONE]") return null
        return runCatching {
            val json = JSONObject(event.data)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            val text = delta?.optString("content").orEmpty()
            val toolCalls = delta?.optJSONArray("tool_calls")
            val finishReason = choice?.optString("finish_reason")
                ?.takeIf { it.isNotBlank() && it != "null" }
            val usage = json.optJSONObject("usage")
            if (text.isEmpty() && finishReason == null && usage == null &&
                toolCalls == null
            ) {
                null
            } else {
                HostLlmStreamEvent.delta(text, finishReason, usage, toolCalls)
            }
        }.getOrElse {
            throw ProviderStreamException("OpenAI-compatible Provider returned an invalid SSE event")
        }
    }

    private fun request(requestJson: String, stream: Boolean): ProviderHttpRequest {
        val body = runCatching { JSONObject(requestJson) }.getOrElse {
            throw HostLlmRequestException("requestJson must be a JSON object")
        }
        ProviderAdapterJson.validateOpenAiExtensions(body)
        moveSystemInstructionIntoMessages(body)
        body.put("stream", stream)
        return ProviderHttpRequest(
            url = completionEndpoint,
            bodyJson = body.toString(),
            headers = extraHeaders,
        )
    }

    private fun moveSystemInstructionIntoMessages(body: JSONObject) {
        if (!body.has("system") || body.isNull("system")) return
        val system = body.opt("system") as? String
            ?: throw HostLlmRequestException("system must be a string")
        body.remove("system")
        if (system.isBlank()) return
        val source = body.optJSONArray("messages")
            ?: throw HostLlmRequestException("messages are required")
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        for (index in 0 until source.length()) messages.put(source.get(index))
        body.put("messages", messages)
    }
}

class UrlConnectionOAuthHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
    private val maxStreamEventBytes: Int = 1 * 1024 * 1024,
    private val maxStreamBytes: Long = 32L * 1024 * 1024,
) : OAuthHttpTransport, OAuthStreamingHttpTransport {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxStreamEventBytes > 0) { "maxStreamEventBytes must be positive" }
        require(maxStreamBytes > 0) { "maxStreamBytes must be positive" }
    }

    override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = configure(request, accept = "application/json")
            val cancellation = currentCoroutineContext().job.invokeOnCompletion {
                connection.disconnect()
            }
            try {
                writeBody(connection, request.bodyJson)
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                ProviderHttpResponse(
                    statusCode = status,
                    bodyJson = stream?.use { readLimited(it, maxResponseBytes) }.orEmpty(),
                    headers = responseHeaders(connection),
                )
            } finally {
                cancellation.dispose()
                connection.disconnect()
            }
        }

    override suspend fun executeStream(
        request: ProviderHttpRequest,
    ): ProviderHttpStreamResponse = withContext(Dispatchers.IO) {
        val connection = configure(request, accept = "text/event-stream")
        val openingCancellation = currentCoroutineContext().job.invokeOnCompletion {
            connection.disconnect()
        }
        try {
            writeBody(connection, request.bodyJson)
            val status = connection.responseCode
            val headers = responseHeaders(connection)
            if (status !in 200..299) {
                val body = connection.errorStream
                    ?.use { readLimited(it, maxResponseBytes) }
                    .orEmpty()
                openingCancellation.dispose()
                connection.disconnect()
                return@withContext ProviderHttpStreamResponse(
                    statusCode = status,
                    events = flow { },
                    errorBodyJson = body,
                    headers = headers,
                )
            }
            val input = connection.inputStream
            openingCancellation.dispose()
            val events = flow {
                val cancellation = currentCoroutineContext().job.invokeOnCompletion {
                    connection.disconnect()
                }
                try {
                    SseEventParser.parse(
                        input = input,
                        maxEventBytes = maxStreamEventBytes,
                        maxTotalBytes = maxStreamBytes,
                    ).collect { emit(it) }
                } finally {
                    cancellation.dispose()
                    runCatching { input.close() }
                    connection.disconnect()
                }
            }.flowOn(Dispatchers.IO)
            ProviderHttpStreamResponse(status, events, headers = headers)
        } catch (error: Exception) {
            openingCancellation.dispose()
            connection.disconnect()
            throw error
        }
    }

    private fun configure(request: ProviderHttpRequest, accept: String): HttpURLConnection {
        val url = URL(request.url)
        require(url.protocol == "https") { "OAuth Provider requests must use HTTPS" }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", accept)
            request.headers.forEach { (name, value) ->
                require(name.none { it == '\r' || it == '\n' }) {
                    "invalid HTTP header name"
                }
                require(value.none { it == '\r' || it == '\n' }) {
                    "invalid HTTP header value"
                }
                setRequestProperty(name, value)
            }
        }
    }

    private fun writeBody(connection: HttpURLConnection, bodyJson: String) {
        connection.outputStream.use {
            it.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> =
        SAFE_RESPONSE_HEADERS.mapNotNull { name ->
            connection.getHeaderField(name)?.let { name.lowercase() to it }
        }.toMap()

    private fun readLimited(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalStateException("Provider response exceeds limit")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        val SAFE_RESPONSE_HEADERS = listOf("Retry-After", "Content-Type", "X-Request-Id")
    }
}

internal object SseEventParser {
    fun parse(
        input: InputStream,
        maxEventBytes: Int,
        maxTotalBytes: Long,
    ): Flow<ProviderSseEvent> = flow {
        val buffered = BufferedInputStream(input)
        var totalBytes = 0L
        var eventName: String? = null
        var eventBytes = 0
        val dataLines = mutableListOf<String>()

        suspend fun emitPending() {
            if (dataLines.isNotEmpty()) {
                emit(ProviderSseEvent(eventName, dataLines.joinToString("\n")))
            }
            eventName = null
            eventBytes = 0
            dataLines.clear()
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = readLine(buffered, maxEventBytes) { consumed ->
                totalBytes += consumed
                if (totalBytes > maxTotalBytes) {
                    throw ProviderStreamException("Provider SSE response exceeds limit")
                }
            }
            if (line == null) {
                emitPending()
                break
            }
            if (line.isEmpty()) {
                emitPending()
                continue
            }
            if (line.startsWith(":")) continue

            val separator = line.indexOf(':')
            val field = if (separator >= 0) line.substring(0, separator) else line
            val rawValue = if (separator >= 0) line.substring(separator + 1) else ""
            val value = rawValue.removePrefix(" ")
            when (field) {
                "event" -> eventName = value
                "data" -> {
                    eventBytes += value.toByteArray(StandardCharsets.UTF_8).size
                    if (eventBytes > maxEventBytes) {
                        throw ProviderStreamException("Provider SSE event exceeds limit")
                    }
                    dataLines += value
                }
            }
        }
    }

    private fun readLine(
        input: InputStream,
        maxBytes: Int,
        consumed: (Long) -> Unit,
    ): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (output.size() == 0) null else decodeLine(output.toByteArray())
            }
            consumed(1)
            if (value == '\n'.code) return decodeLine(output.toByteArray())
            output.write(value)
            if (output.size() > maxBytes) {
                throw ProviderStreamException("Provider SSE line exceeds limit")
            }
        }
    }

    private fun decodeLine(bytes: ByteArray): String {
        val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }
}
