package dev.alpine.llm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthHttpLlmBridgeTest {
    @Test
    fun credentialIsAddedAfterProviderAdaptation() = runBlocking {
        var captured: ProviderHttpRequest? = null
        val adapter = RecordingAdapter()
        val bridge = OAuthHttpLlmBridge(adapter) { request ->
            captured = request
            ProviderHttpResponse(200, """{"choices":[]}""")
        }

        val result = bridge.complete(
            """{"model":"test","messages":[]}""",
            OAuthCredential("secret-access-token", "Bearer"),
        )

        assertFalse(adapter.sawAuthorization)
        assertEquals("Bearer secret-access-token", captured?.headers?.get("Authorization"))
        assertEquals(200, result.statusCode)
        assertEquals("""{"choices":[]}""", result.bodyJson)
    }

    @Test
    fun openAiAdapterForcesNonStreamingRequest() {
        val adapter = OpenAiCompatibleOAuthAdapter(
            completionEndpoint = "https://provider.example.com/v1/chat/completions",
            extraHeaders = mapOf("X-Provider-Version" to "1"),
        )

        val request = adapter.createRequest("""{"model":"test","stream":true}""")

        assertEquals("1", request.headers["X-Provider-Version"])
        assertFalse(JSONObject(request.bodyJson).getBoolean("stream"))
    }

    @Test
    fun openAiAdapterMovesTopLevelSystemGuidanceIntoFirstMessage() {
        val adapter = OpenAiCompatibleOAuthAdapter(
            completionEndpoint = "https://provider.example.com/v1/chat/completions",
        )

        val request = adapter.createStreamRequest(
            """{"model":"test","system":"be concise","messages":[{"role":"user","content":"hello"}]}""",
        )
        val body = JSONObject(request.bodyJson)

        assertFalse(body.has("system"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals(
            "be concise",
            body.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
        assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertTrue(body.getBoolean("stream"))
    }

    @Test
    fun adapterCannotInjectAuthorization() {
        val bridge = OAuthHttpLlmBridge(
            adapter = object : OAuthProviderHttpAdapter {
                override fun createRequest(requestJson: String) = ProviderHttpRequest(
                    url = "https://provider.example.com",
                    bodyJson = requestJson,
                    headers = mapOf("authorization" to "attacker-controlled"),
                )
            },
            transport = OAuthHttpTransport { error("transport must not run") },
        )

        val error = runCatching {
            runBlocking { bridge.complete("{}", OAuthCredential("secret")) }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun accountMetadataIsAddedOnlyAtCredentialBoundary() = runBlocking {
        var captured: ProviderHttpRequest? = null
        val adapter = object : OAuthProviderHttpAdapter {
            override fun createRequest(requestJson: String) = ProviderHttpRequest(
                url = CodexOAuthContract.RESPONSES_ENDPOINT,
                bodyJson = requestJson,
                credentialAccountIdHeader = CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER,
            )
        }
        val bridge = OAuthHttpLlmBridge(adapter) { request ->
            captured = request
            ProviderHttpResponse(200, "{}")
        }

        bridge.complete("{}", OAuthCredential("secret", accountId = "account-123"))

        assertEquals(
            "account-123",
            captured?.headers?.get(CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER),
        )
        assertFalse(
            adapter.createRequest("{}").headers.containsKey(
                CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER,
            ),
        )
    }

    @Test
    fun unsafeAccountMetadataIsRejectedBeforeTransport() {
        val bridge = OAuthHttpLlmBridge(
            adapter = object : OAuthProviderHttpAdapter {
                override fun createRequest(requestJson: String) = ProviderHttpRequest(
                    url = CodexOAuthContract.RESPONSES_ENDPOINT,
                    bodyJson = requestJson,
                    credentialAccountIdHeader = CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER,
                )
            },
            transport = OAuthHttpTransport { error("transport must not run") },
        )

        val error = runCatching {
            runBlocking {
                bridge.complete("{}", OAuthCredential("secret", accountId = "bad\r\nheader"))
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun nonSuccessfulStreamMapsStatusAndSafeErrorBodyWithoutCollectingEvents() = runBlocking {
        val adapter = OpenAiCompatibleOAuthAdapter(
            completionEndpoint = "https://provider.example.com/v1/chat/completions",
        )
        val bridge = OAuthHttpLlmBridge(
            adapter = adapter,
            streamingTransport = OAuthStreamingHttpTransport {
                ProviderHttpStreamResponse(
                    statusCode = 429,
                    events = flow { error("non-success events must not be collected") },
                    errorBodyJson = """{"error":{"code":"rate_limit"}}""",
                )
            },
            transport = OAuthHttpTransport { error("complete transport must not run") },
        )

        val result = bridge.stream("""{"model":"test","messages":[]}""", OAuthCredential("secret"))

        assertEquals(429, result.statusCode)
        assertEquals("""{"error":{"code":"rate_limit"}}""", result.errorBodyJson)
        assertTrue(result.events.toList().isEmpty())
    }

    @Test
    fun malformedSuccessfulSseEventBecomesRedactedProviderStreamException() = runBlocking {
        val adapter = OpenAiCompatibleOAuthAdapter(
            completionEndpoint = "https://provider.example.com/v1/chat/completions",
        )
        val bridge = OAuthHttpLlmBridge(
            adapter = adapter,
            streamingTransport = OAuthStreamingHttpTransport {
                ProviderHttpStreamResponse(
                    statusCode = 200,
                    events = flowOf(ProviderSseEvent(event = null, data = "token=raw-secret")),
                )
            },
            transport = OAuthHttpTransport { error("complete transport must not run") },
        )

        val error = runCatching {
            bridge.stream(
                """{"model":"test","messages":[]}""",
                OAuthCredential("secret"),
            ).events.toList()
        }.exceptionOrNull()

        assertTrue(error is ProviderStreamException)
        assertFalse(requireNotNull(error).message.orEmpty().contains("raw-secret"))
    }

    private class RecordingAdapter : OAuthProviderHttpAdapter {
        var sawAuthorization = false

        override fun createRequest(requestJson: String): ProviderHttpRequest {
            val request = ProviderHttpRequest(
                url = "https://provider.example.com/v1/chat/completions",
                bodyJson = requestJson,
                headers = mapOf("X-Test" to "true"),
            )
            sawAuthorization = request.headers.keys.any {
                it.equals("Authorization", ignoreCase = true)
            }
            return request
        }
    }
}
