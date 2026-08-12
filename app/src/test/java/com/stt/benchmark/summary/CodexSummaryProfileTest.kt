package com.stt.benchmark.summary

import dev.alpine.llm.CodexOAuthContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSummaryProfileTest {
    @Test
    fun profileKeepsFixedCodexCompatibilityContract() {
        val config = CodexSummaryProfile.oauthConfig()

        assertEquals("codex_summary_v1", config.providerId)
        assertEquals(CodexOAuthContract.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
        assertEquals(CodexOAuthContract.TOKEN_ENDPOINT, config.tokenEndpoint)
        assertEquals("http://localhost:1455/auth/callback", config.redirectUri())
        assertEquals(CodexOAuthContract.SCOPES, config.scopes)
        assertEquals(CodexSummaryProfile.PUBLIC_CLIENT_ID, config.clientId)
        assertEquals(listOf("gpt-5.3-codex-spark"), CodexSummaryProfile.ALLOWED_MODELS)
    }

    @Test
    fun parityProbeIsFixedNonSensitiveAndToolFree() {
        val request = JSONObject(CodexSummaryProfile.parityProbeRequest())

        assertEquals(CodexSummaryProfile.PARITY_MODEL, request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("tools"))
        assertFalse(request.toString().contains("transcript", ignoreCase = true))
        assertEquals(1, request.getJSONArray("messages").length())
    }

    @Test
    fun longTranscriptRequestsStayStreamingAndToolFree() {
        val partial = JSONObject(CodexSummaryProfile.partialSummaryRequest("선택 구간", 2, 4))
        val synthesis = JSONObject(
            CodexSummaryProfile.synthesisSummaryRequest(listOf("부분 1", "부분 2"), finalRound = true),
        )

        listOf(partial, synthesis).forEach { request ->
            assertEquals(CodexSummaryProfile.PARITY_MODEL, request.getString("model"))
            assertTrue(request.getBoolean("stream"))
            assertFalse(request.has("tools"))
            assertEquals(2, request.getJSONArray("messages").length())
        }
    }
}
