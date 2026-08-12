package com.stt.benchmark.summary

import android.app.Activity
import android.content.Context
import dev.alpine.llm.CodexResponsesOAuthAdapter
import dev.alpine.llm.HostLlmRequestException
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthHttpLlmBridge
import dev.alpine.llm.OAuthLlmSession
import dev.alpine.llm.OAuthManager
import dev.alpine.llm.UrlConnectionOAuthHttpTransport
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * App-owned boundary for Codex OAuth. Tokens never leave the upstream library and this class
 * exposes only coarse authentication state, a fixed non-sensitive parity probe, and an explicit
 * user-approved summary request. Tokens and transcripts are never persisted by this boundary.
 */
class CodexSummaryAuthController(context: Context) {
    private val oauth = OAuthManager(
        context = context.applicationContext,
        config = CodexSummaryProfile.oauthConfig(),
    )
    private val transport = UrlConnectionOAuthHttpTransport()
    private val session = OAuthLlmSession(
        oauth = oauth,
        bridge = OAuthHttpLlmBridge(
            adapter = CodexResponsesOAuthAdapter(),
            streamingTransport = transport,
            transport = transport,
        ),
    )

    fun authenticationState(): OAuthAuthenticationState = oauth.authenticationState()

    suspend fun authorize(activity: Activity) {
        oauth.authorize(activity)
    }

    fun cancelAuthorization(): Boolean = oauth.cancelAuthorization()

    fun logout() = oauth.logout()

    suspend fun runParityProbe(): String {
        return collectText(CodexSummaryProfile.parityProbeRequest(), MAX_VISIBLE_PROBE_CHARS)
    }

    suspend fun runUserApprovedSummary(
        requestJson: String,
        maxChars: Int = SummaryRequestPolicy.MAX_SUMMARY_CHARS,
    ): String = collectText(requestJson, maxChars)

    private suspend fun collectText(requestJson: String, maxChars: Int): String {
        val response = session.stream(requestJson)
        if (response.statusCode !in 200..299) {
            throw HostLlmRequestException("Codex request failed")
        }
        val output = StringBuilder()
        response.events.collect { event ->
            val text = JSONObject(event.dataJson).optString("text")
            if (text.isNotEmpty() && output.length < maxChars) {
                output.append(text.take(maxChars - output.length))
            }
        }
        return output.toString().trim().ifEmpty {
            throw HostLlmRequestException("Codex request returned no text")
        }
    }

    private companion object {
        const val MAX_VISIBLE_PROBE_CHARS = 240
    }
}
