package com.stt.benchmark.chat

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 화면에 표시된 완료 대화만 저장한다. 진행 중 delta와 전사 원문은 저장 대상이 아니다. */
class TranscriptChatSessionStore private constructor(private val rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))
    constructor(rootDir: File, create: Boolean = true) : this(rootDir) {
        if (create) rootDir.mkdirs()
    }

    enum class Role { USER, ASSISTANT }

    data class Message(
        val role: Role,
        val text: String,
        val citationUnitIds: List<String> = emptyList(),
        val timestampMs: Long,
    )

    data class Entry(
        val source: TranscriptSourceRef,
        val sourceFingerprint: String,
        val messages: List<Message>,
        val historyDigest: String = "",
        /** 현재 messages 앞에서부터 digest에 이미 반영된 메시지 수. */
        val historyDigestThrough: Int = 0,
        val schemaVersion: Int = TranscriptChatPolicy.SESSION_SCHEMA_VERSION,
        val promptVersion: String = TranscriptChatPolicy.PROMPT_VERSION,
        val modelVersion: String = TranscriptChatPolicy.MODEL_VERSION,
        val updatedAtMs: Long,
    ) {
        fun isReusable(fingerprint: String): Boolean =
            sourceFingerprint == fingerprint &&
                schemaVersion == TranscriptChatPolicy.SESSION_SCHEMA_VERSION &&
                promptVersion == TranscriptChatPolicy.PROMPT_VERSION &&
                modelVersion == TranscriptChatPolicy.MODEL_VERSION
    }

    init {
        rootDir.mkdirs()
    }

    fun read(source: TranscriptSourceRef): Entry? = fileForOrNull(source)?.let(::readFile)

    fun save(entry: Entry) {
        validate(entry)
        write(requireNotNull(fileForOrNull(entry.source)), toJson(entry))
    }

    fun delete(source: TranscriptSourceRef): Boolean = fileForOrNull(source)?.let {
        AtomicFile(it).delete()
        !it.exists()
    } ?: false

    private fun validate(entry: Entry) {
        require(TranscriptChatPolicy.isSafeSource(entry.source)) { "invalid chat source" }
        require(TranscriptChatPolicy.FINGERPRINT_REGEX.matches(entry.sourceFingerprint)) { "invalid fingerprint" }
        require(entry.schemaVersion == TranscriptChatPolicy.SESSION_SCHEMA_VERSION) { "invalid schema" }
        require(entry.promptVersion == TranscriptChatPolicy.PROMPT_VERSION) { "invalid prompt" }
        require(entry.modelVersion == TranscriptChatPolicy.MODEL_VERSION) { "invalid model" }
        require(entry.messages.size <= TranscriptChatPolicy.MAX_MESSAGES) { "too many messages" }
        require(entry.historyDigest.length <= TranscriptChatPolicy.MAX_HISTORY_DIGEST_CHARS) { "digest too long" }
        require(entry.historyDigestThrough in 0..entry.messages.size) { "invalid digest boundary" }
        entry.messages.forEach { message ->
            require(message.text.isNotBlank()) { "blank message" }
            val limit = if (message.role == Role.USER) {
                TranscriptChatPolicy.MAX_QUESTION_CHARS
            } else {
                TranscriptChatPolicy.MAX_ANSWER_CHARS
            }
            require(message.text.length <= limit) { "message too long" }
            require(message.citationUnitIds.distinct().size == message.citationUnitIds.size) { "duplicate citation" }
            require(message.citationUnitIds.all(TranscriptChatPolicy.UNIT_ID_REGEX::matches)) { "invalid citation" }
            require(message.citationUnitIds.all { id -> "[$id]" in message.text }) { "citation is not present in answer" }
            require(message.timestampMs >= 0L) { "invalid timestamp" }
        }
    }

    private fun toJson(entry: Entry) = JSONObject().apply {
        put("schemaVersion", entry.schemaVersion)
        put("sourceType", entry.source.type.name)
        put("sourceId", entry.source.id)
        put("sourceFingerprint", entry.sourceFingerprint)
        put("promptVersion", entry.promptVersion)
        put("modelVersion", entry.modelVersion)
        put("historyDigest", entry.historyDigest)
        put("historyDigestThrough", entry.historyDigestThrough)
        put("updatedAtMs", entry.updatedAtMs)
        put("messages", JSONArray().apply {
            entry.messages.forEach { message ->
                put(JSONObject().apply {
                    put("role", message.role.name)
                    put("text", message.text)
                    put("citationUnitIds", JSONArray(message.citationUnitIds))
                    put("timestampMs", message.timestampMs)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Entry {
        val messagesJson = json.getJSONArray("messages")
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val value = messagesJson.getJSONObject(index)
                val citations = value.getJSONArray("citationUnitIds")
                add(Message(
                    role = Role.valueOf(value.getString("role")),
                    text = value.getString("text"),
                    citationUnitIds = buildList {
                        for (citationIndex in 0 until citations.length()) add(citations.getString(citationIndex))
                    },
                    timestampMs = value.getLong("timestampMs"),
                ))
            }
        }
        return Entry(
            source = TranscriptSourceRef(TranscriptSourceType.valueOf(json.getString("sourceType")), json.getString("sourceId")),
            sourceFingerprint = json.getString("sourceFingerprint"),
            messages = messages,
            historyDigest = json.optString("historyDigest"),
            historyDigestThrough = json.optInt("historyDigestThrough", 0),
            schemaVersion = json.getInt("schemaVersion"),
            promptVersion = json.getString("promptVersion"),
            modelVersion = json.getString("modelVersion"),
            updatedAtMs = json.getLong("updatedAtMs"),
        )
    }

    private fun readFile(file: File): Entry? = try {
        val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        fromJson(JSONObject(raw)).also(::validate)
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    private fun fileForOrNull(source: TranscriptSourceRef): File? = source.takeIf(TranscriptChatPolicy::isSafeSource)
        ?.let { File(rootDir, "${it.type.name.lowercase()}_${it.id}.json") }

    private companion object {
        const val DIRECTORY = "transcript_chat_sessions"
    }
}
