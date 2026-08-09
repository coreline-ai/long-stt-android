package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * OpenAI chat-completion JSON to Anthropic Messages API protocol adapter.
 * Endpoint and Provider-specific beta headers remain application settings.
 */
class AnthropicMessagesOAuthAdapter(
    private val messagesEndpoint: String,
    private val anthropicVersion: String = "2023-06-01",
    private val anthropicBeta: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthStreamingProviderHttpAdapter {
    init {
        ProviderAdapterJson.requireHttps(messagesEndpoint, "messagesEndpoint")
        ProviderAdapterJson.requireSafeHeaders(extraHeaders)
        require(anthropicVersion.isNotBlank()) { "anthropicVersion must not be blank" }
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = false)

    override fun createStreamRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = true)

    private fun request(requestJson: String, stream: Boolean): ProviderHttpRequest {
        val input = ProviderAdapterJson.parseRequest(requestJson)
        val body = JSONObject()
            .put("model", input.model)
            .put("max_tokens", input.maxTokens)
            .put("messages", JSONArray())
            .put("stream", stream)
        input.temperature?.let { body.put("temperature", it) }
        input.stopSequences.takeIf { it.isNotEmpty() }?.let {
            body.put("stop_sequences", JSONArray(it))
        }

        val systems = mutableListOf<String>()
        input.system?.takeIf { it.isNotBlank() }?.let(systems::add)
        val messages = body.getJSONArray("messages")
        input.messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    if (message.parts.any { it !is ProviderAdapterJson.ContentPart.Text }) {
                        throw HostLlmRequestException(
                            "Anthropic system messages support text only",
                        )
                    }
                    message.text.takeIf { it.isNotBlank() }?.let(systems::add)
                }
                "user", "assistant" -> {
                    val content = anthropicContent(message)
                    if (message.role == "assistant") {
                        message.toolCalls.forEach { call ->
                            content.put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("input", call.arguments),
                            )
                        }
                    } else if (message.toolCalls.isNotEmpty()) {
                        throw HostLlmRequestException(
                            "Anthropic tool calls require an assistant message",
                        )
                    }
                    messages.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", content),
                    )
                }
                "tool" -> messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", message.toolCallId)
                                    .put("content", anthropicContent(message)),
                            ),
                        ),
                )
            }
        }
        if (messages.length() == 0) {
            throw HostLlmRequestException("Anthropic request requires user or assistant messages")
        }
        if (systems.isNotEmpty()) body.put("system", systems.joinToString("\n\n"))
        input.tools.takeIf { it.isNotEmpty() }?.let { tools ->
            body.put(
                "tools",
                JSONArray(tools.map { tool ->
                    JSONObject()
                        .put("name", tool.name)
                        .putOpt("description", tool.description)
                        .put("input_schema", tool.parameters)
                }),
            )
        }
        input.toolChoice?.let { choice ->
            when (choice.mode) {
                "auto" -> body.put("tool_choice", JSONObject().put("type", "auto"))
                "required" -> body.put("tool_choice", JSONObject().put("type", "any"))
                "function" -> body.put(
                    "tool_choice",
                    JSONObject().put("type", "tool").put("name", choice.name),
                )
                "none" -> Unit
                else -> Unit
            }
        }

        val headers = linkedMapOf("anthropic-version" to anthropicVersion)
        anthropicBeta?.takeIf { it.isNotBlank() }?.let { headers["anthropic-beta"] = it }
        headers.putAll(extraHeaders)
        return ProviderHttpRequest(messagesEndpoint, body.toString(), headers)
    }

    private fun anthropicContent(message: ProviderAdapterJson.Message): JSONArray =
        JSONArray().apply {
            message.parts.forEach { part ->
                when (part) {
                    is ProviderAdapterJson.ContentPart.Text -> put(
                        JSONObject().put("type", "text").put("text", part.text),
                    )
                    is ProviderAdapterJson.ContentPart.InlineImage -> put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", part.mediaType)
                                    .put("data", part.base64Data),
                            ),
                    )
                }
            }
        }

    override fun createStreamEvent(event: ProviderSseEvent): HostLlmStreamEvent? {
        if (event.data == "[DONE]") return null
        return runCatching {
            val json = JSONObject(event.data)
            when (event.event ?: json.optString("type")) {
                "message_start" -> {
                    val usage = json.optJSONObject("message")?.optJSONObject("usage")
                    usage?.let {
                        HostLlmStreamEvent.delta(
                            usage = ProviderAdapterJson.streamUsage(
                                promptTokens = it.optInt("input_tokens", 0),
                            ),
                        )
                    }
                }
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block")
                    if (block?.optString("type") == "tool_use") {
                        HostLlmStreamEvent.delta(
                            toolCalls = JSONArray().put(
                                ProviderAdapterJson.openAiToolCall(
                                    id = block.optString("id"),
                                    name = block.optString("name"),
                                    arguments = "",
                                ),
                            ),
                        )
                    } else {
                        null
                    }
                }
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta")
                    when (delta?.optString("type")) {
                        "text_delta" -> HostLlmStreamEvent.delta(
                            text = delta.optString("text"),
                        )
                        "input_json_delta" -> HostLlmStreamEvent.delta(
                            toolCalls = JSONArray().put(
                                ProviderAdapterJson.openAiToolCall(
                                    id = "",
                                    name = "",
                                    arguments = delta.optString("partial_json"),
                                ),
                            ),
                        )
                        else -> null
                    }
                }
                "message_delta" -> {
                    val delta = json.optJSONObject("delta")
                    val usage = json.optJSONObject("usage")
                    val finishReason = when (delta?.optString("stop_reason")) {
                        "max_tokens" -> "length"
                        "tool_use" -> "tool_calls"
                        "", null -> null
                        else -> "stop"
                    }
                    if (finishReason == null && usage == null) {
                        null
                    } else {
                        HostLlmStreamEvent.delta(
                            finishReason = finishReason,
                            usage = usage?.let {
                                ProviderAdapterJson.streamUsage(
                                    completionTokens = it.optInt("output_tokens", 0),
                                )
                            },
                        )
                    }
                }
                "error" -> throw ProviderStreamException(
                    "Anthropic Provider returned a stream error",
                )
                else -> null
            }
        }.getOrElse { error ->
            if (error is ProviderStreamException) throw error
            throw ProviderStreamException("Anthropic Provider returned an invalid SSE event")
        }
    }

    override fun createResult(response: ProviderHttpResponse): HostLlmResult {
        if (response.statusCode !in 200..299) {
            return ProviderAdapterJson.redactedError("anthropic", response.statusCode)
        }
        return runCatching {
            val json = JSONObject(response.bodyJson)
            val text = buildString {
                val content = json.optJSONArray("content") ?: JSONArray()
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
            val toolCalls = JSONArray()
            val content = json.optJSONArray("content") ?: JSONArray()
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == "tool_use") {
                    toolCalls.put(
                        ProviderAdapterJson.openAiToolCall(
                            id = block.optString("id"),
                            name = block.optString("name"),
                            arguments = block.optJSONObject("input") ?: JSONObject(),
                        ),
                    )
                }
            }
            val usage = json.optJSONObject("usage")
            val result = ProviderAdapterJson.completion(
                id = json.optString("id"),
                model = json.optString("model"),
                text = text,
                finishReason = when (json.optString("stop_reason")) {
                    "max_tokens" -> "length"
                    "tool_use" -> "tool_calls"
                    else -> "stop"
                },
                promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
                toolCalls = toolCalls,
            )
            HostLlmResult(result.toString())
        }.getOrElse {
            ProviderAdapterJson.invalidResponse("anthropic")
        }
    }
}

/**
 * OpenAI chat-completion JSON to Gemini generateContent protocol adapter.
 * [endpointTemplate] must contain `{model}` and use HTTPS.
 */
class GeminiGenerateContentOAuthAdapter(
    private val endpointTemplate: String,
    private val streamEndpointTemplate: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthStreamingProviderHttpAdapter {
    init {
        ProviderAdapterJson.requireHttps(endpointTemplate, "endpointTemplate")
        require(endpointTemplate.contains("{model}")) {
            "endpointTemplate must contain {model}"
        }
        streamEndpointTemplate?.let {
            ProviderAdapterJson.requireHttps(it, "streamEndpointTemplate")
            require(it.contains("{model}")) {
                "streamEndpointTemplate must contain {model}"
            }
        }
        ProviderAdapterJson.requireSafeHeaders(extraHeaders)
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = false)

    override fun createStreamRequest(requestJson: String): ProviderHttpRequest =
        request(requestJson, stream = true)

    private fun request(requestJson: String, stream: Boolean): ProviderHttpRequest {
        val input = ProviderAdapterJson.parseRequest(requestJson)
        val body = JSONObject()
            .put("contents", JSONArray())
        val systems = mutableListOf<String>()
        input.system?.takeIf { it.isNotBlank() }?.let(systems::add)
        val contents = body.getJSONArray("contents")
        input.messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    if (message.parts.any { it !is ProviderAdapterJson.ContentPart.Text }) {
                        throw HostLlmRequestException("Gemini system messages support text only")
                    }
                    message.text.takeIf { it.isNotBlank() }?.let(systems::add)
                }
                "user", "assistant" -> {
                    val parts = geminiParts(message)
                    if (message.role == "assistant") {
                        message.toolCalls.forEach { call ->
                            parts.put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("args", call.arguments),
                                ),
                            )
                        }
                    } else if (message.toolCalls.isNotEmpty()) {
                        throw HostLlmRequestException(
                            "Gemini tool calls require an assistant message",
                        )
                    }
                    contents.put(
                        JSONObject()
                            .put("role", if (message.role == "assistant") "model" else "user")
                            .put("parts", parts),
                    )
                }
                "tool" -> {
                    val response = runCatching { JSONObject(message.text) }.getOrElse {
                        JSONObject().put("output", message.text)
                    }
                    contents.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "functionResponse",
                                        JSONObject()
                                            .put("name", message.name ?: message.toolCallId)
                                            .put("response", response),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        if (contents.length() == 0) {
            throw HostLlmRequestException("Gemini request requires user or assistant messages")
        }
        if (systems.isNotEmpty()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systems.joinToString("\n\n"))),
                ),
            )
        }
        val generationConfig = JSONObject().put("maxOutputTokens", input.maxTokens)
        input.temperature?.let { generationConfig.put("temperature", it) }
        input.stopSequences.takeIf { it.isNotEmpty() }?.let {
            generationConfig.put("stopSequences", JSONArray(it))
        }
        body.put("generationConfig", generationConfig)
        input.tools.takeIf { it.isNotEmpty() }?.let { tools ->
            body.put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        JSONArray(tools.map { tool ->
                            JSONObject()
                                .put("name", tool.name)
                                .putOpt("description", tool.description)
                                .put("parameters", tool.parameters)
                        }),
                    ),
                ),
            )
        }
        input.toolChoice?.let { choice ->
            val functionConfig = JSONObject().put(
                "mode",
                when (choice.mode) {
                    "none" -> "NONE"
                    "required", "function" -> "ANY"
                    else -> "AUTO"
                },
            )
            choice.name?.let {
                functionConfig.put("allowedFunctionNames", JSONArray().put(it))
            }
            body.put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", functionConfig),
            )
        }

        val encodedModel = URLEncoder.encode(
            input.model,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        val targetTemplate = if (stream) {
            streamEndpointTemplate ?: ProviderAdapterJson.geminiStreamEndpoint(endpointTemplate)
        } else {
            endpointTemplate
        }
        return ProviderHttpRequest(
            url = targetTemplate.replace("{model}", encodedModel),
            bodyJson = body.toString(),
            headers = extraHeaders,
        )
    }

    private fun geminiParts(message: ProviderAdapterJson.Message): JSONArray =
        JSONArray().apply {
            message.parts.forEach { part ->
                when (part) {
                    is ProviderAdapterJson.ContentPart.Text -> put(
                        JSONObject().put("text", part.text),
                    )
                    is ProviderAdapterJson.ContentPart.InlineImage -> put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", part.mediaType)
                                .put("data", part.base64Data),
                        ),
                    )
                }
            }
        }

    override fun createStreamEvent(event: ProviderSseEvent): HostLlmStreamEvent? {
        if (event.data == "[DONE]") return null
        return runCatching {
            val json = JSONObject(event.data)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val toolCalls = JSONArray()
            val text = buildString {
                if (parts != null) {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                        part.optJSONObject("functionCall")?.let { call ->
                            toolCalls.put(
                                ProviderAdapterJson.openAiToolCall(
                                    id = "call_${index}_${call.optString("name")}",
                                    name = call.optString("name"),
                                    arguments = call.optJSONObject("args") ?: JSONObject(),
                                ),
                            )
                        }
                    }
                }
            }
            val finishReason = when (candidate?.optString("finishReason")) {
                "MAX_TOKENS" -> "length"
                "STOP" -> "stop"
                "", null -> null
                else -> candidate.optString("finishReason").lowercase()
            }
            val usage = json.optJSONObject("usageMetadata")?.let {
                ProviderAdapterJson.streamUsage(
                    promptTokens = it.optInt("promptTokenCount", 0),
                    completionTokens = it.optInt("candidatesTokenCount", 0),
                )
            }
            if (text.isEmpty() && finishReason == null && usage == null &&
                toolCalls.length() == 0
            ) {
                null
            } else {
                HostLlmStreamEvent.delta(
                    text,
                    finishReason,
                    usage,
                    toolCalls.takeIf { it.length() > 0 },
                )
            }
        }.getOrElse {
            throw ProviderStreamException("Gemini Provider returned an invalid SSE event")
        }
    }

    override fun createResult(response: ProviderHttpResponse): HostLlmResult {
        if (response.statusCode !in 200..299) {
            return ProviderAdapterJson.redactedError("gemini", response.statusCode)
        }
        return runCatching {
            val json = JSONObject(response.bodyJson)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: error("candidate is missing")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val toolCalls = JSONArray()
            val text = buildString {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    append(part.optString("text"))
                    part.optJSONObject("functionCall")?.let { call ->
                        toolCalls.put(
                            ProviderAdapterJson.openAiToolCall(
                                id = "call_${index}_${call.optString("name")}",
                                name = call.optString("name"),
                                arguments = call.optJSONObject("args") ?: JSONObject(),
                            ),
                        )
                    }
                }
            }
            val usage = json.optJSONObject("usageMetadata")
            val result = ProviderAdapterJson.completion(
                id = json.optString("responseId"),
                model = json.optString("modelVersion"),
                text = text,
                finishReason = when (candidate.optString("finishReason")) {
                    "MAX_TOKENS" -> "length"
                    "STOP" -> "stop"
                    else -> candidate.optString("finishReason", "stop").lowercase()
                },
                promptTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
                completionTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0,
                toolCalls = toolCalls,
            )
            HostLlmResult(result.toString())
        }.getOrElse {
            ProviderAdapterJson.invalidResponse("gemini")
        }
    }
}

internal object ProviderAdapterJson {
    sealed interface ContentPart {
        data class Text(val text: String) : ContentPart

        data class InlineImage(
            val mediaType: String,
            val base64Data: String,
        ) : ContentPart
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    )

    data class Message(
        val role: String,
        val parts: List<ContentPart>,
        val toolCallId: String? = null,
        val name: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) {
        val text: String
            get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    }

    data class ToolDefinition(
        val name: String,
        val description: String?,
        val parameters: JSONObject,
    )

    data class ToolChoice(
        val mode: String,
        val name: String? = null,
    )

    data class Request(
        val model: String,
        val messages: List<Message>,
        val system: String?,
        val maxTokens: Int,
        val temperature: Double?,
        val stopSequences: List<String>,
        val tools: List<ToolDefinition>,
        val toolChoice: ToolChoice?,
    )

    fun parseRequest(requestJson: String): Request {
        val json = runCatching { JSONObject(requestJson) }.getOrElse {
            throw HostLlmRequestException("request must be a JSON object")
        }
        val model = json.optString("model").takeIf { it.isNotBlank() }
            ?: throw HostLlmRequestException("model is required")
        val sourceMessages = json.optJSONArray("messages")
            ?: throw HostLlmRequestException("messages are required")
        val messages = buildList {
            for (index in 0 until sourceMessages.length()) {
                val source = sourceMessages.optJSONObject(index)
                    ?: throw HostLlmRequestException("message must be an object")
                val role = source.optString("role").lowercase().takeIf { it.isNotBlank() }
                    ?: throw HostLlmRequestException("message role is required")
                if (role !in setOf("system", "user", "assistant", "tool")) {
                    throw HostLlmRequestException("unsupported message role")
                }
                val parts = parseContent(source.opt("content"), role)
                val toolCalls = parseToolCalls(source.optJSONArray("tool_calls"))
                if (parts.isEmpty() && toolCalls.isEmpty()) {
                    throw HostLlmRequestException("message content must not be empty")
                }
                val toolCallId = source.optString("tool_call_id").ifBlank { null }
                if (role == "tool" && toolCallId == null) {
                    throw HostLlmRequestException("tool message requires tool_call_id")
                }
                add(
                    Message(
                        role = role,
                        parts = parts,
                        toolCallId = toolCallId,
                        name = source.optString("name").ifBlank { null },
                        toolCalls = toolCalls,
                    ),
                )
            }
        }
        if (messages.isEmpty()) throw HostLlmRequestException("messages must not be empty")
        val maxTokens = json.optInt("max_tokens", 1024)
        if (maxTokens <= 0) throw HostLlmRequestException("max_tokens must be positive")
        val temperature = json.opt("temperature")?.takeUnless { it == JSONObject.NULL }?.let {
            (it as? Number)?.toDouble()
                ?: throw HostLlmRequestException("temperature must be numeric")
        }
        val stopSequences = when (val stop = json.opt("stop")) {
            null, JSONObject.NULL -> emptyList()
            is String -> listOf(stop)
            is JSONArray -> buildList {
                for (index in 0 until stop.length()) {
                    val value = stop.optString(index)
                    if (value.isNotEmpty()) add(value)
                }
            }
            else -> throw HostLlmRequestException("stop must be a string or array")
        }
        val tools = parseTools(json.optJSONArray("tools"))
        return Request(
            model = model,
            messages = messages,
            system = json.optString("system").ifBlank { null },
            maxTokens = maxTokens,
            temperature = temperature,
            stopSequences = stopSequences,
            tools = tools,
            toolChoice = parseToolChoice(json.opt("tool_choice"), tools),
        )
    }

    fun validateOpenAiExtensions(json: JSONObject) {
        json.optJSONArray("messages")?.let { messages ->
            for (index in 0 until messages.length()) {
                val source = messages.optJSONObject(index)
                    ?: throw HostLlmRequestException("message must be an object")
                val role = source.optString("role").ifBlank { "user" }
                parseContent(source.opt("content"), role)
                parseToolCalls(source.optJSONArray("tool_calls"))
            }
        }
        val tools = parseTools(json.optJSONArray("tools"))
        parseToolChoice(json.opt("tool_choice"), tools)
    }

    fun completion(
        id: String,
        model: String,
        text: String,
        finishReason: String,
        promptTokens: Int,
        completionTokens: Int,
        toolCalls: JSONArray? = null,
    ): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", text)
        toolCalls?.takeIf { it.length() > 0 }?.let { message.put("tool_calls", it) }
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finishReason),
                ),
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", promptTokens)
                    .put("completion_tokens", completionTokens)
                    .put("total_tokens", promptTokens + completionTokens),
            )
    }

    fun openAiToolCall(id: String, name: String, arguments: Any): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put(
                        "arguments",
                        when (arguments) {
                            is String -> arguments
                            is JSONObject -> arguments.toString()
                            else -> arguments.toString()
                        },
                    ),
            )

    private fun parseContent(value: Any?, role: String): List<ContentPart> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is String -> {
            if (value.isBlank()) emptyList() else listOf(ContentPart.Text(value))
        }
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index)
                    ?: throw HostLlmRequestException("content part must be an object")
                when (part.optString("type")) {
                    "text" -> {
                        val text = part.optString("text")
                        if (text.isBlank()) {
                            throw HostLlmRequestException("text content must not be blank")
                        }
                        add(ContentPart.Text(text))
                    }
                    "image_url" -> {
                        if (role !in setOf("user", "tool")) {
                            throw HostLlmRequestException(
                                "image content is only supported for user/tool messages",
                            )
                        }
                        add(parseInlineImage(part))
                    }
                    else -> throw HostLlmRequestException("unsupported content part type")
                }
            }
        }
        else -> throw HostLlmRequestException("message content must be text or an array")
    }

    private fun parseInlineImage(part: JSONObject): ContentPart.InlineImage {
        val url = part.optJSONObject("image_url")?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: throw HostLlmRequestException("image_url.url is required")
        val match = DATA_IMAGE.matchEntire(url)
            ?: throw HostLlmRequestException("only inline base64 image data URLs are supported")
        val mediaType = match.groupValues[1].lowercase()
        if (mediaType !in ALLOWED_IMAGE_TYPES) {
            throw HostLlmRequestException("unsupported inline image media type")
        }
        val encoded = match.groupValues[2]
        val decodedSize = runCatching { Base64.getDecoder().decode(encoded).size }.getOrElse {
            throw HostLlmRequestException("inline image is not valid base64")
        }
        if (decodedSize > MAX_INLINE_IMAGE_BYTES) {
            throw HostLlmRequestException("inline image exceeds size limit")
        }
        return ContentPart.InlineImage(mediaType, encoded)
    }

    private fun parseToolCalls(source: JSONArray?): List<ToolCall> {
        if (source == null) return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val call = source.optJSONObject(index)
                    ?: throw HostLlmRequestException("tool call must be an object")
                if (call.optString("type", "function") != "function") {
                    throw HostLlmRequestException("only function tool calls are supported")
                }
                val function = call.optJSONObject("function")
                    ?: throw HostLlmRequestException("tool call function is required")
                val name = requireToolName(function.optString("name"))
                val arguments = runCatching {
                    JSONObject(function.optString("arguments", "{}"))
                }.getOrElse {
                    throw HostLlmRequestException("tool arguments must be a JSON object")
                }
                add(
                    ToolCall(
                        id = call.optString("id").takeIf { it.isNotBlank() }
                            ?: "call_${index}_$name",
                        name = name,
                        arguments = arguments,
                    ),
                )
            }
        }
    }

    private fun parseTools(source: JSONArray?): List<ToolDefinition> {
        if (source == null) return emptyList()
        if (source.length() > MAX_TOOLS) {
            throw HostLlmRequestException("too many tools")
        }
        return buildList {
            for (index in 0 until source.length()) {
                val tool = source.optJSONObject(index)
                    ?: throw HostLlmRequestException("tool must be an object")
                if (tool.optString("type", "function") != "function") {
                    throw HostLlmRequestException("only function tools are supported")
                }
                val function = tool.optJSONObject("function")
                    ?: throw HostLlmRequestException("tool function is required")
                add(
                    ToolDefinition(
                        name = requireToolName(function.optString("name")),
                        description = function.optString("description").ifBlank { null },
                        parameters = function.optJSONObject("parameters") ?: JSONObject(),
                    ),
                )
            }
        }
    }

    private fun parseToolChoice(value: Any?, tools: List<ToolDefinition>): ToolChoice? =
        when (value) {
            null, JSONObject.NULL -> null
            is String -> {
                if (value !in setOf("auto", "none", "required")) {
                    throw HostLlmRequestException("unsupported tool_choice")
                }
                ToolChoice(value)
            }
            is JSONObject -> {
                val name = requireToolName(
                    value.optJSONObject("function")?.optString("name").orEmpty(),
                )
                if (tools.none { it.name == name }) {
                    throw HostLlmRequestException("tool_choice references an unknown tool")
                }
                ToolChoice("function", name)
            }
            else -> throw HostLlmRequestException("tool_choice must be a string or object")
        }

    private fun requireToolName(value: String): String {
        if (!TOOL_NAME.matches(value)) {
            throw HostLlmRequestException("invalid tool name")
        }
        return value
    }

    fun streamUsage(
        promptTokens: Int = 0,
        completionTokens: Int = 0,
    ): JSONObject = JSONObject()
        .put("prompt_tokens", promptTokens)
        .put("completion_tokens", completionTokens)
        .put("total_tokens", promptTokens + completionTokens)

    fun geminiStreamEndpoint(endpointTemplate: String): String {
        if (!endpointTemplate.contains(":generateContent")) {
            throw HostLlmRequestException(
                "Gemini streaming requires streamEndpointTemplate or a :generateContent endpoint",
            )
        }
        val streamEndpoint = endpointTemplate.replace(
            ":generateContent",
            ":streamGenerateContent",
        )
        return streamEndpoint + if (streamEndpoint.contains("?")) "&alt=sse" else "?alt=sse"
    }

    fun redactedError(provider: String, statusCode: Int): HostLlmResult =
        HostLlmResult(
            bodyJson = JSONObject()
                .put(
                    "error",
                    JSONObject()
                        .put("code", "provider_error")
                        .put("provider", provider)
                        .put("message", "Provider request failed"),
                )
                .toString(),
            statusCode = statusCode.takeIf { it in 400..599 } ?: 502,
        )

    fun invalidResponse(provider: String): HostLlmResult =
        HostLlmResult(
            bodyJson = JSONObject()
                .put(
                    "error",
                    JSONObject()
                        .put("code", "invalid_provider_response")
                        .put("provider", provider)
                        .put("message", "Provider returned an invalid response"),
                )
                .toString(),
            statusCode = 502,
        )

    fun requireHttps(url: String, name: String) {
        require(url.startsWith("https://")) { "$name must use HTTPS" }
    }

    fun requireSafeHeaders(headers: Map<String, String>) {
        require(headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            "extraHeaders must not contain Authorization"
        }
        headers.forEach { (name, value) ->
            require(name.none { it == '\r' || it == '\n' }) { "invalid header name" }
            require(value.none { it == '\r' || it == '\n' }) { "invalid header value" }
        }
    }

    private const val MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_TOOLS = 64
    private val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    private val DATA_IMAGE = Regex("^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$")
    private val ALLOWED_IMAGE_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
    )
}
