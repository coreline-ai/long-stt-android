package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class CodexResponsesOAuthAdapterTest {
    @Test
    fun codexContractUsesExactLoopbackAndFirstPartyTokenRequestFormat() {
        val config = CodexOAuthContract.providerConfig("codex-profile", "public-client")

        assertEquals(CodexOAuthContract.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
        assertEquals(CodexOAuthContract.TOKEN_ENDPOINT, config.tokenEndpoint)
        assertEquals("http://localhost:1455/auth/callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals(OAuthTokenRequestEncoding.FORM_URLENCODED, config.tokenRequestEncoding)
        assertEquals(3, config.tokenRequestMaxAttempts)
        assertEquals(1_000L, config.tokenRetryInitialDelayMs)
        assertEquals("true", config.extraAuthorizationParams["codex_cli_simplified_flow"])
        assertEquals("codex_cli_rs", config.extraAuthorizationParams["originator"])

        val authorizationParams = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                OAuthTokenGrantType.AUTHORIZATION_CODE,
                mapOf("grant_type" to "authorization_code"),
            ),
        )
        val refreshParams = config.tokenRequestAdapter.adapt(
            OAuthTokenRequestContext(
                OAuthTokenGrantType.REFRESH_TOKEN,
                mapOf("grant_type" to "refresh_token"),
            ),
        )
        assertFalse("scope" in authorizationParams)
        assertEquals(CodexOAuthContract.SCOPES.joinToString(" "), refreshParams["scope"])
    }

    @Test
    fun codexContractExtractsDisplayClaimsWithoutRetainingRawIdToken() {
        val payload = JSONObject()
            .put("chatgpt_account_id", "account-123")
            .put("chatgpt_plan_type", "pro")
            .toString()
        val idToken = "${base64Url("{\"alg\":\"none\"}")}.${base64Url(payload)}."
        val config = CodexOAuthContract.providerConfig("codex-profile", "public-client")

        val token = config.tokenResponseAdapter.parse(
            JSONObject().put("access_token", "access").put("id_token", idToken),
            nowMs = 1_000L,
        )

        assertEquals("account-123", token.metadata["account_id"])
        assertEquals("pro", token.metadata["plan_type"])
        assertFalse("id_token" in token.metadata)
    }

    @Test
    fun requestUsesFixedEndpointHeadersAndResponsesBody() {
        val adapter = CodexResponsesOAuthAdapter(clientVersion = "1.2.3")
        val request = adapter.createStreamRequest(
            """
            {
              "model":"gpt-test",
              "system":"base instruction",
              "messages":[
                {"role":"system","content":"second instruction"},
                {"role":"user","content":[
                  {"type":"text","text":"inspect"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8="}}
                ]},
                {"role":"assistant","content":"calling", "tool_calls":[{
                  "id":"call_1|fc_1","type":"function",
                  "function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}
                }]},
                {"role":"tool","tool_call_id":"call_1|fc_1","content":"result"}
              ],
              "tools":[{"type":"function","function":{
                "name":"lookup","description":"Lookup","parameters":{"type":"object"}
              }}],
              "tool_choice":"auto",
              "max_tokens":2048,
              "stream":false
            }
            """.trimIndent(),
        )

        assertEquals(CodexOAuthContract.RESPONSES_ENDPOINT, request.url)
        assertEquals("1.2.3", request.headers["Version"])
        assertEquals("codex_cli_rs", request.headers["Originator"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertFalse(request.headers.containsKey(CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER))
        assertEquals(
            CodexResponsesOAuthAdapter.ACCOUNT_ID_HEADER,
            request.credentialAccountIdHeader,
        )

        val body = JSONObject(request.bodyJson)
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("messages"))
        assertFalse(body.has("max_output_tokens"))
        assertEquals("base instruction\n\nsecond instruction", body.getString("instructions"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))

        val input = body.getJSONArray("input")
        val userContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", userContent.getJSONObject(0).getString("type"))
        assertEquals("input_image", userContent.getJSONObject(1).getString("type"))
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        assertEquals("fc_1", input.getJSONObject(2).getString("id"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("call_1", input.getJSONObject(3).getString("call_id"))
        assertEquals("lookup", body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun nonStreamingResponseNormalizesTextToolsAndUsage() {
        val adapter = CodexResponsesOAuthAdapter()
        val response = JSONObject()
            .put("id", "resp_1")
            .put("model", "gpt-test")
            .put("status", "completed")
            .put(
                "output",
                JSONArray()
                    .put(
                        JSONObject().put("type", "message").put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", "hello"),
                            ),
                        ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("id", "fc_1")
                            .put("call_id", "call_1")
                            .put("name", "lookup")
                            .put("arguments", "{\"q\":\"x\"}"),
                    ),
            )
            .put(
                "usage",
                JSONObject().put("input_tokens", 10).put("output_tokens", 4),
            )

        val result = adapter.createResult(ProviderHttpResponse(200, response.toString()))
        val normalized = JSONObject(result.bodyJson)
        val choice = normalized.getJSONArray("choices").getJSONObject(0)

        assertEquals(200, result.statusCode)
        assertEquals("hello", choice.getJSONObject("message").getString("content"))
        assertEquals("tool_calls", choice.getString("finish_reason"))
        assertEquals(
            "call_1|fc_1",
            choice.getJSONObject("message").getJSONArray("tool_calls")
                .getJSONObject(0).getString("id"),
        )
        assertEquals(14, normalized.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun streamNormalizesTextToolUsageAndTerminalErrors() {
        val adapter = CodexResponsesOAuthAdapter()

        val textEvent = adapter.createStreamEvent(
            ProviderSseEvent(null, """{"type":"response.output_text.delta","delta":"hi"}"""),
        )
        val toolEvent = adapter.createStreamEvent(
            ProviderSseEvent(
                null,
                """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{}"}}""",
            ),
        )
        val completedEvent = adapter.createStreamEvent(
            ProviderSseEvent(
                null,
                """{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}],"usage":{"input_tokens":2,"output_tokens":3}}}""",
            ),
        )

        assertEquals("hi", JSONObject(requireNotNull(textEvent).dataJson).getString("text"))
        assertEquals(
            "call_1|fc_1",
            JSONObject(requireNotNull(toolEvent).dataJson).getJSONArray("tool_calls")
                .getJSONObject(0).getString("id"),
        )
        val completed = JSONObject(requireNotNull(completedEvent).dataJson)
        assertEquals("tool_calls", completed.getString("finish_reason"))
        assertEquals(5, completed.getJSONObject("usage").getInt("total_tokens"))
        assertNull(adapter.createStreamEvent(ProviderSseEvent(null, "[DONE]")))

        val error = runCatching {
            adapter.createStreamEvent(
                ProviderSseEvent(
                    null,
                    """{"type":"response.failed","response":{"error":{"message":"secret-provider-detail"}}}""",
                ),
            )
        }.exceptionOrNull()
        assertTrue(error is ProviderStreamException)
        assertFalse(error?.message.orEmpty().contains("secret-provider-detail"))

        val incomplete = adapter.createStreamEvent(
            ProviderSseEvent(
                null,
                """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":5,"output_tokens":7}}}""",
            ),
        )
        val incompleteJson = JSONObject(requireNotNull(incomplete).dataJson)
        assertEquals("length", incompleteJson.getString("finish_reason"))
        assertEquals(12, incompleteJson.getJSONObject("usage").getInt("total_tokens"))

        listOf(
            "not-json-secret",
            """{"error":{"message":"inline-provider-secret"}}""",
        ).forEach { payload ->
            val malformedError = runCatching {
                adapter.createStreamEvent(ProviderSseEvent(null, payload))
            }.exceptionOrNull()
            assertTrue(malformedError is ProviderStreamException)
            assertFalse(malformedError?.message.orEmpty().contains("provider-secret"))
        }
    }

    @Test
    fun malformedAndHttpErrorResponsesAreRedacted() {
        val adapter = CodexResponsesOAuthAdapter()

        val malformed = adapter.createResult(ProviderHttpResponse(200, "not-json-secret"))
        val httpError = adapter.createResult(
            ProviderHttpResponse(429, "{\"error\":\"provider-secret\"}"),
        )

        assertEquals(502, malformed.statusCode)
        assertFalse(malformed.bodyJson.contains("not-json-secret"))
        assertEquals(429, httpError.statusCode)
        assertFalse(httpError.bodyJson.contains("provider-secret"))
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
        )
}
