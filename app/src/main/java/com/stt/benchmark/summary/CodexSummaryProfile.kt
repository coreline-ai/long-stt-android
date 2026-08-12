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

    /** Builds a final request for a transcript that fits in one bounded transport payload. */
    fun userApprovedSummaryRequest(transcript: String): String = streamingRequest(
        system = "사용자가 명시적으로 선택한 전사를 한국어로 간결하게 요약하세요. " +
            "제목 1개와 핵심 요점 3~6개만 제공하고, 원문을 장문으로 반복하지 마세요. " +
            "도구 호출이나 외부 작업을 제안하지 마세요.",
        user = "[선택된 전사 시작]\n$transcript\n[선택된 전사 끝]",
    )

    /** Builds one bounded first-pass request for a long, explicitly selected transcript. */
    fun partialSummaryRequest(transcriptPart: String, partIndex: Int, totalParts: Int): String = streamingRequest(
        system = "사용자가 선택한 긴 전사의 한 구간을 한국어로 압축하세요. " +
            "이름, 결정, 일정, 수치, 쟁점과 후속 조치를 보존하되 6개 이하의 짧은 요점만 작성하세요. " +
            "도구 호출이나 외부 작업을 제안하지 마세요.",
        user = "[긴 전사 구간 $partIndex/$totalParts 시작]\n$transcriptPart\n[긴 전사 구간 끝]",
    )

    /** Combines bounded intermediate summaries; repeated calls form a hierarchy for very long input. */
    fun synthesisSummaryRequest(partialSummaries: List<String>, finalRound: Boolean): String = streamingRequest(
        system = if (finalRound) {
            "긴 전사의 구간별 요약을 하나의 한국어 최종 요약으로 통합하세요. " +
                "중복을 제거하고 제목 1개, 전체 맥락 1문단, 핵심 요점 3~8개, 후속 조치가 있으면 별도 항목으로 작성하세요. " +
                "제공되지 않은 사실은 만들지 말고 도구 호출이나 외부 작업을 제안하지 마세요."
        } else {
            "긴 전사의 구간별 요약 묶음을 다음 통합 단계에 사용할 수 있도록 한국어로 다시 압축하세요. " +
                "중복을 제거하고 중요한 이름, 결정, 일정, 수치, 쟁점과 후속 조치를 보존하세요."
        },
        user = partialSummaries.mapIndexed { index, summary ->
            "[부분 요약 ${index + 1}]\n$summary"
        }.joinToString("\n\n"),
    )

    private fun streamingRequest(system: String, user: String): String = JSONObject()
        .put("model", PARITY_MODEL)
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", system),
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", user),
                ),
        )
        .put("stream", true)
        .toString()
}
