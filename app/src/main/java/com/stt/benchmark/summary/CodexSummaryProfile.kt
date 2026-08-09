package com.stt.benchmark.summary

import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.OAuthProviderConfig
import org.json.JSONArray
import org.json.JSONObject

/** Fixed compatibility profile used only by the LongSTT summary feature. */
object CodexSummaryProfile {
    const val PROVIDER_ID = "codex_summary_v1"
    const val PUBLIC_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val PARITY_MODEL = "gpt-5.3-codex-spark"

    val ALLOWED_MODELS = listOf(PARITY_MODEL)

    fun oauthConfig(): OAuthProviderConfig = CodexOAuthContract.providerConfig(
        providerId = PROVIDER_ID,
        clientId = PUBLIC_CLIENT_ID,
    )

    /** Non-sensitive, tool-free request used before any transcript summary is enabled. */
    fun parityProbeRequest(): String = JSONObject()
        .put("model", PARITY_MODEL)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with exactly: LongSTT OAuth OK"),
            ),
        )
        .put("stream", true)
        .toString()
}
