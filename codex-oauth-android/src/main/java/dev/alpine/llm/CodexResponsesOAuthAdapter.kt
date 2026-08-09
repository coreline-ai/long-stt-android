package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * OpenAI chat-completion JSON to the Codex account Responses protocol.
 *
 * The endpoint is deliberately fixed so a Codex account token cannot be sent
 * to a user-configured relay. Authorization and account headers are injected
 * later by [OAuthHttpLlmBridge].
 */
class CodexResponsesOAuthAdapter(
    private val clientVersion: String = DEFAULT_CLIENT_VERSION,
) : OAuthStreamingProviderHttpAdapter {
    init {
        require(CLIENT_VERSION.matches(clientVersion)) { "invalid Codex clientVersion" }
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = false)

    override fun createStreamRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = true)

    override fun createResult(response: ProviderHttpResponse): HostLlmResult {
        if (response.statusCode !in 200..299) {
            return ProviderAdapterJson.redactedError(PROVIDER_NAME, response.statusCode)
        }
        return runCatching {
            val json = JSONObject(response.bodyJson)
            if (json.optString("status") == "failed" || json.has("error")) {
                return ProviderAdapterJson.invalidResponse(PROVIDER_NAME)
            }
            val output = json.optJSONArray("output") ?: JSONArray()
            val text = buildString {
                for (index in 0 until output.length()) {
                    val item = output.optJSONObject(index) ?: continue
                    if (item.optString("type") != "message") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val part = content.optJSONObject(contentIndex) ?: continue
                        if (part.optString("type") == "output_text") {
                            append(part.optString("text"))
                        }
                    }
                }
            }
            val toolCalls = JSONArray()
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") == "function_call") {
                    toolCalls.put(openAiToolCall(item))
                }
            }
            val usage = json.optJSONObject("usage")
            val incompleteReason = json.optJSONObject("incomplete_details")
                ?.optString("reason")
            val finishReason = when {
                toolCalls.length() > 0 -> "tool_calls"
                incompleteReason == "max_output_tokens" -> "length"
                incompleteReason == "content_filter" -> "content_filter"
                else -> "stop"
            }
            HostLlmResult(
                ProviderAdapterJson.completion(
                    id = json.optString("id"),
                    model = json.optString("model"),
                    text = text,
                    finishReason = finishReason,
                    promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                    completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
                    toolCalls = toolCalls,
                ).toString(),
            )
        }.getOrElse {
            ProviderAdapterJson.invalidResponse(PROVIDER_NAME)
        }
    }

    override fun createStreamEvent(event: ProviderSseEvent): HostLlmStreamEvent? {
        if (event.data == "[DONE]") return null
        return runCatching {
            val json = JSONObject(event.data)
            if (json.has("error")) {
                throw ProviderStreamException("Codex Provider returned a stream error")
            }
            when (json.optString("type")) {
                "response.output_text.delta" -> json.optString("delta")
                    .takeIf { it.isNotEmpty() }
                    ?.let { HostLlmStreamEvent.delta(text = it) }
                "response.output_item.done" -> {
                    val item = json.optJSONObject("item")
                    if (item?.optString("type") == "function_call") {
                        HostLlmStreamEvent.delta(
                            toolCalls = JSONArray().put(openAiToolCall(item)),
                        )
                    } else {
                        null
                    }
                }
                "response.completed" -> {
                    val completed = json.optJSONObject("response")
                        ?: throw ProviderStreamException(
                            "Codex Provider returned an invalid completion event",
                        )
                    val output = completed.optJSONArray("output") ?: JSONArray()
                    val hasToolCalls = (0 until output.length()).any { index ->
                        output.optJSONObject(index)?.optString("type") == "function_call"
                    }
                    val usage = completed.optJSONObject("usage")?.let(::streamUsage)
                    HostLlmStreamEvent.delta(
                        finishReason = if (hasToolCalls) "tool_calls" else "stop",
                        usage = usage,
                    )
                }
                "response.incomplete" -> {
                    val completed = json.optJSONObject("response")
                    val reason = completed?.optJSONObject("incomplete_details")
                        ?.optString("reason")
                    val finishReason = when (reason) {
                        "max_output_tokens" -> "length"
                        "content_filter" -> "content_filter"
                        else -> throw ProviderStreamException(
                            "Codex Provider returned an incomplete response",
                        )
                    }
                    HostLlmStreamEvent.delta(
                        finishReason = finishReason,
                        usage = completed.optJSONObject("usage")?.let(::streamUsage),
                    )
                }
                "response.failed", "error" -> throw ProviderStreamException(
                    "Codex Provider returned a stream error",
                )
                else -> null
            }
        }.getOrElse { error ->
            if (error is ProviderStreamException) throw error
            throw ProviderStreamException("Codex Provider returned an invalid SSE event")
        }
    }

    private fun request(requestJson: String, stream: Boolean): ProviderHttpRequest {
        val input = ProviderAdapterJson.parseRequest(requestJson)
        val instructions = buildList {
            input.system?.takeIf { it.isNotBlank() }?.let(::add)
            input.messages.filter { it.role == "system" }
                .map { it.text }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }
        val responsesInput = responsesInput(input.messages)
        if (responsesInput.length() == 0) {
            throw HostLlmRequestException("Codex request requires a non-system message")
        }
        val body = JSONObject()
            .put("model", input.model)
            .put("stream", stream)
            .put("store", false)
            .put("parallel_tool_calls", true)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            .put("input", responsesInput)
        if (instructions.isNotEmpty()) body.put("instructions", instructions.joinToString("\n\n"))
        if (input.tools.isNotEmpty()) {
            body.put(
                "tools",
                JSONArray(input.tools.map { tool ->
                    JSONObject()
                        .put("type", "function")
                        .put("name", tool.name)
                        .putOpt("description", tool.description)
                        .put("parameters", tool.parameters)
                }),
            )
            body.put("tool_choice", responsesToolChoice(input.toolChoice))
        }
        return ProviderHttpRequest(
            url = CodexOAuthContract.RESPONSES_ENDPOINT,
            bodyJson = body.toString(),
            headers = mapOf(
                "Version" to clientVersion,
                "Openai-Beta" to "responses=experimental",
                "User-Agent" to "codex_cli_rs/$clientVersion (Android; arm64)",
                "Originator" to "codex_cli_rs",
            ),
            credentialAccountIdHeader = ACCOUNT_ID_HEADER,
        )
    }

    private fun responsesInput(messages: List<ProviderAdapterJson.Message>): JSONArray =
        JSONArray().apply {
            messages.forEach { message ->
                when (message.role) {
                    "system" -> {
                        if (message.toolCalls.isNotEmpty()) {
                            throw HostLlmRequestException(
                                "Codex system messages cannot contain tool calls",
                            )
                        }
                    }
                    "user" -> {
                        if (message.toolCalls.isNotEmpty()) {
                            throw HostLlmRequestException(
                                "Codex user messages cannot contain tool calls",
                            )
                        }
                        put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", responsesUserContent(message)),
                        )
                    }
                    "assistant" -> {
                        if (message.text.isNotBlank()) {
                            put(JSONObject().put("role", "assistant").put("content", message.text))
                        }
                        message.toolCalls.forEach { call ->
                            val ids = responsesIds(call.id)
                            put(
                                JSONObject()
                                    .put("type", "function_call")
                                    .put("id", ids.itemId)
                                    .put("call_id", ids.callId)
                                    .put("name", call.name)
                                    .put("arguments", call.arguments.toString()),
                            )
                        }
                    }
                    "tool" -> {
                        if (message.toolCalls.isNotEmpty()) {
                            throw HostLlmRequestException(
                                "Codex tool messages cannot contain tool calls",
                            )
                        }
                        if (message.parts.any { it is ProviderAdapterJson.ContentPart.InlineImage }) {
                            throw HostLlmRequestException(
                                "Codex tool outputs support text only",
                            )
                        }
                        val ids = responsesIds(requireNotNull(message.toolCallId))
                        put(
                            JSONObject()
                                .put("type", "function_call_output")
                                .put("call_id", ids.callId)
                                .put("output", message.text),
                        )
                    }
                }
            }
        }

    private fun responsesUserContent(message: ProviderAdapterJson.Message): Any {
        if (message.parts.none { it is ProviderAdapterJson.ContentPart.InlineImage }) {
            return message.text
        }
        return JSONArray().apply {
            message.parts.forEach { part ->
                when (part) {
                    is ProviderAdapterJson.ContentPart.Text -> put(
                        JSONObject().put("type", "input_text").put("text", part.text),
                    )
                    is ProviderAdapterJson.ContentPart.InlineImage -> put(
                        JSONObject()
                            .put("type", "input_image")
                            .put(
                                "image_url",
                                "data:${part.mediaType};base64,${part.base64Data}",
                            ),
                    )
                }
            }
        }
    }

    private fun responsesToolChoice(choice: ProviderAdapterJson.ToolChoice?): Any =
        when (choice?.mode) {
            null, "auto" -> "auto"
            "required" -> "required"
            "none" -> "none"
            "function" -> JSONObject()
                .put("type", "function")
                .put("name", choice.name)
            else -> throw HostLlmRequestException("unsupported Codex tool_choice")
        }

    private fun openAiToolCall(item: JSONObject): JSONObject {
        val callId = item.optString("call_id").ifBlank { item.optString("id") }
        val itemId = item.optString("id")
        val name = item.optString("name")
        require(callId.isNotBlank() && name.isNotBlank()) {
            "Codex function call is missing required fields"
        }
        val combinedId = if (itemId.isBlank() || itemId == callId) {
            callId
        } else {
            "$callId|$itemId"
        }
        return ProviderAdapterJson.openAiToolCall(
            id = combinedId,
            name = name,
            arguments = item.optString("arguments", "{}"),
        )
    }

    private fun streamUsage(usage: JSONObject): JSONObject = ProviderAdapterJson.streamUsage(
        promptTokens = usage.optInt("input_tokens", 0),
        completionTokens = usage.optInt("output_tokens", 0),
    )

    private fun responsesIds(raw: String): ResponsesIds {
        val callId = capId(raw.substringBefore('|').ifBlank { "call_unknown" })
        val suppliedItemId = raw.substringAfter('|', "").takeIf { it.isNotBlank() }
        val itemId = capId(suppliedItemId ?: "fc_syn_${sha256(raw).take(32)}")
        return ResponsesIds(callId, itemId)
    }

    private fun capId(value: String): String =
        if (value.length <= MAX_RESPONSE_ID_LENGTH) value else value.take(MAX_RESPONSE_ID_LENGTH)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class ResponsesIds(val callId: String, val itemId: String)

    companion object {
        const val DEFAULT_CLIENT_VERSION = "0.144.1"
        const val ACCOUNT_ID_HEADER = "Chatgpt-Account-Id"
        private const val PROVIDER_NAME = "codex"
        private const val MAX_RESPONSE_ID_LENGTH = 64
        private val CLIENT_VERSION = Regex("[A-Za-z0-9._-]{1,32}")
    }
}
