package com.stt.benchmark.summary

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-approved transcript summary input boundary.
 *
 * This object only prepares an in-memory request. It never persists the transcript and callers
 * must show their consent UI before invoking [prepare].
 */
object SummaryRequestPolicy {
    const val MAX_TRANSCRIPT_CHARS = 12_000
    const val MAX_SUMMARY_CHARS = 4_000

    data class Source(
        val type: SummarySessionStore.SourceType,
        val id: String,
    ) {
        val key: String get() = "${type.name}:$id"
    }

    sealed interface Preparation {
        data class Ready(val source: Source, val requestJson: String) : Preparation
        data class Rejected(val message: String) : Preparation
    }

    fun prepare(source: Source, transcript: String): Preparation {
        if (!source.id.matches(SOURCE_ID_REGEX)) {
            return Preparation.Rejected("요약할 전사 식별자를 확인할 수 없습니다.")
        }
        val selectedText = transcript.trim()
        if (selectedText.isEmpty()) {
            return Preparation.Rejected("내용이 있는 완료 전사만 요약할 수 있습니다.")
        }
        if (selectedText.length > MAX_TRANSCRIPT_CHARS) {
            return Preparation.Rejected("이번 요약은 ${MAX_TRANSCRIPT_CHARS}자 이하의 전사만 지원합니다.")
        }
        return Preparation.Ready(
            source = source,
            requestJson = CodexSummaryProfile.userApprovedSummaryRequest(selectedText),
        )
    }

    private val SOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
}
