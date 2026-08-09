package dev.alpine.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

/** Credential stays in the Android host and is never written into Alpine. */
data class OAuthCredential(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val accountId: String? = null,
)

data class HostLlmResult(
    val bodyJson: String,
    val statusCode: Int = 200,
)

/**
 * A normalized stream event sent to Alpine as one SSE data record.
 *
 * The JSON payload intentionally contains protocol data only. Credentials,
 * Provider URLs, headers, and raw Provider errors are never accepted here.
 */
data class HostLlmStreamEvent(val dataJson: String) {
    init {
        require(runCatching { JSONObject(dataJson) }.isSuccess) {
            "stream event must be a JSON object"
        }
    }

    companion object {
        fun delta(
            text: String = "",
            finishReason: String? = null,
            usage: JSONObject? = null,
            toolCalls: JSONArray? = null,
        ): HostLlmStreamEvent {
            val value = JSONObject()
                .put("type", "delta")
                .put("text", text)
            finishReason?.let { value.put("finish_reason", it) }
            usage?.let { value.put("usage", it) }
            toolCalls?.let { value.put("tool_calls", it) }
            return HostLlmStreamEvent(value.toString())
        }
    }
}

data class HostLlmStreamResult(
    val statusCode: Int = 200,
    val events: Flow<HostLlmStreamEvent> = flowOf(),
    val errorBodyJson: String? = null,
)

class HostLlmRequestException(message: String) : Exception(message)

/**
 * Implement this in the Android application layer using its Provider client.
 * The default stream implementation preserves compatibility for custom
 * complete-only bridges while native Provider bridges can override [stream].
 */
interface HostLlmBridge {
    suspend fun complete(requestJson: String, credential: OAuthCredential): HostLlmResult

    suspend fun stream(
        requestJson: String,
        credential: OAuthCredential,
    ): HostLlmStreamResult {
        val result = complete(requestJson, credential)
        if (result.statusCode !in 200..299) {
            return HostLlmStreamResult(
                statusCode = result.statusCode,
                errorBodyJson = result.bodyJson,
            )
        }
        val event = runCatching {
            val response = JSONObject(result.bodyJson)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw HostLlmRequestException("completion choices are missing")
            val text = choice.optJSONObject("message")?.optString("content").orEmpty()
            HostLlmStreamEvent.delta(
                text = text,
                finishReason = choice.optString("finish_reason").ifBlank { "stop" },
                usage = response.optJSONObject("usage"),
            )
        }.getOrElse {
            return HostLlmStreamResult(
                statusCode = 502,
                errorBodyJson = JSONObject()
                    .put(
                        "error",
                        JSONObject()
                            .put("code", "provider_invalid_json")
                            .put("message", "Provider returned an invalid JSON response"),
                    )
                    .toString(),
            )
        }
        return HostLlmStreamResult(events = flowOf(event))
    }
}

class OAuthLlmSession(
    private val oauth: OAuthManager,
    private val bridge: HostLlmBridge,
) {
    suspend fun complete(requestJson: String): HostLlmResult {
        val token = oauth.validToken()
            ?: throw OAuthRequiredException(oauth)
        val first = bridge.complete(
            requestJson,
            OAuthCredential(token.accessToken, token.tokenType, token.metadata["account_id"]),
        )
        if (first.statusCode != 401) return first

        val refreshed = oauth.refreshAfterUnauthorized(token.accessToken)
            ?: throw OAuthRequiredException(oauth)
        val retried = bridge.complete(
            requestJson,
            OAuthCredential(
                refreshed.accessToken,
                refreshed.tokenType,
                refreshed.metadata["account_id"],
            ),
        )
        if (retried.statusCode == 401) {
            oauth.invalidateIfCurrent(refreshed.accessToken)
            throw OAuthRequiredException(oauth)
        }
        return retried
    }

    suspend fun stream(requestJson: String): HostLlmStreamResult {
        val token = oauth.validToken()
            ?: throw OAuthRequiredException(oauth)
        val first = bridge.stream(
            requestJson,
            OAuthCredential(token.accessToken, token.tokenType, token.metadata["account_id"]),
        )
        if (first.statusCode != 401) return first

        val refreshed = oauth.refreshAfterUnauthorized(token.accessToken)
            ?: throw OAuthRequiredException(oauth)
        val retried = bridge.stream(
            requestJson,
            OAuthCredential(
                refreshed.accessToken,
                refreshed.tokenType,
                refreshed.metadata["account_id"],
            ),
        )
        if (retried.statusCode == 401) {
            oauth.invalidateIfCurrent(refreshed.accessToken)
            throw OAuthRequiredException(oauth)
        }
        return retried
    }
}

class OAuthRequiredException(@Suppress("UNUSED_PARAMETER") manager: OAuthManager) : Exception(
    "OAuth login is required before calling the LLM provider",
)
