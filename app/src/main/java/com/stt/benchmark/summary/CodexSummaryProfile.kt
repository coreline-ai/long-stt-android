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

    /** Builds the only transcript-bearing request. Callers must obtain explicit user consent first. */
    fun userApprovedSummaryRequest(transcript: String): String = JSONObject()
        .put("model", PARITY_MODEL)
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "사용자가 명시적으로 선택한 전사를 한국어로 간결하게 요약하세요. " +
                                "제목 1개와 핵심 요점 3~6개만 제공하고, 원문을 장문으로 반복하지 마세요. " +
                                "도구 호출이나 외부 작업을 제안하지 마세요.",
                        ),
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "[선택된 전사 시작]\\n$transcript\\n[선택된 전사 끝]"),
                ),
        )
        .put("stream", true)
        .toString()
}
