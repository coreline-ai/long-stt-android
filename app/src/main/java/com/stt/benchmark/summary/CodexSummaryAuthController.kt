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
 * exposes only coarse authentication state plus a fixed non-sensitive parity probe.
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
        val response = session.stream(CodexSummaryProfile.parityProbeRequest())
        if (response.statusCode !in 200..299) {
            throw HostLlmRequestException("Codex parity probe failed")
        }
        val output = StringBuilder()
        response.events.collect { event ->
            val text = JSONObject(event.dataJson).optString("text")
            if (text.isNotEmpty() && output.length < MAX_VISIBLE_PROBE_CHARS) {
                output.append(text.take(MAX_VISIBLE_PROBE_CHARS - output.length))
            }
        }
        return output.toString().trim().ifEmpty {
            throw HostLlmRequestException("Codex parity probe returned no text")
        }
    }

    private companion object {
        const val MAX_VISIBLE_PROBE_CHARS = 240
    }
}
