package com.stt.benchmark.chat

import com.stt.benchmark.summary.CodexSummaryAuthController

interface TranscriptChatLlmClient {
    fun isAuthenticated(): Boolean
    suspend fun stream(requestJson: String, maxChars: Int, onDelta: (String) -> Unit): String
}
class CodexTranscriptChatLlmClient(
    private val controller: CodexSummaryAuthController,
) : TranscriptChatLlmClient {
    override fun isAuthenticated(): Boolean = controller.isAuthenticated()

    override suspend fun stream(
        requestJson: String,
        maxChars: Int,
        onDelta: (String) -> Unit,
    ): String = controller.runUserApprovedStreaming(requestJson, maxChars, onDelta)
}
