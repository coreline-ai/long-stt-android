package com.stt.benchmark.chat

import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import java.security.MessageDigest

/** P2 전사 채팅의 입력·저장·요청 상한과 버전 정본. */
object TranscriptChatPolicy {
    const val MAX_TRANSCRIPT_CHARS = 500_000
    const val TARGET_UNIT_CHARS = 10_000
    const val MAX_QUESTION_CHARS = 2_000
    const val MAX_ANSWER_CHARS = 8_000
    const val MAX_UNIT_SUMMARY_CHARS = 1_000
    const val MAX_FINDING_CHARS = 1_000
    const val MAX_HISTORY_CHARS = 12_000
    const val MAX_HISTORY_DIGEST_CHARS = 2_000
    const val MAX_HISTORY_DIGEST_INPUT_CHARS = 40_000
    const val MAX_CONTEXT_CHARS = 48_000
    const val MAX_OUTPUT_TOKENS = 2_000
    const val DEFAULT_RELEVANT_UNITS = 4
    const val MAX_RELEVANT_UNITS = 8
    const val MAX_MESSAGES = 200

    const val INDEX_SCHEMA_VERSION = 1
    const val SESSION_SCHEMA_VERSION = 2
    const val CHECKPOINT_SCHEMA_VERSION = 1
    const val PROMPT_VERSION = "chat_prompt_v1"
    const val MODEL_VERSION = "gpt-5.3-codex-spark"

    val SOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
    val FINGERPRINT_REGEX = Regex("[0-9a-f]{64}")
    val UNIT_ID_REGEX = Regex("U[0-9]{4,6}")

    sealed interface SourceValidation {
        data class Ready(
            val document: TranscriptSourceDocument,
            val characterCount: Int,
            val fingerprint: String,
        ) : SourceValidation

        data class Rejected(val message: String) : SourceValidation
    }

    fun validate(document: TranscriptSourceDocument?): SourceValidation {
        if (document == null || !isSafeSource(document.source)) {
            return SourceValidation.Rejected("대화할 완료 전사를 찾을 수 없습니다.")
        }
        val count = document.sections.sumOf { it.text.trim().length }
        if (count <= 0) {
            return SourceValidation.Rejected("내용이 있는 완료 전사만 대화할 수 있습니다.")
        }
        if (count > MAX_TRANSCRIPT_CHARS) {
            return SourceValidation.Rejected("전사와 대화는 ${MAX_TRANSCRIPT_CHARS}자 이하에서 지원합니다.")
        }
        return SourceValidation.Ready(document, count, fingerprint(document))
    }

    fun isSafeSource(source: TranscriptSourceRef): Boolean = SOURCE_ID_REGEX.matches(source.id)

    /** 원문 자체를 반환하거나 저장하지 않고 source 변경 판정용 해시만 만든다. */
    fun fingerprint(document: TranscriptSourceDocument): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        add(document.source.type.name)
        add(document.source.id)
        add(document.updatedAtMs.toString())
        document.sections.forEach { section ->
            add(section.key)
            add(section.startMs.toString())
            add(section.endMs.toString())
            add(section.text)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
