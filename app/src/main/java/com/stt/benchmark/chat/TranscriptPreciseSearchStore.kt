package com.stt.benchmark.chat

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 정밀 탐색의 완료 unit 파생 finding만 저장하는 재개 checkpoint. */
class TranscriptPreciseSearchStore private constructor(private val rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))
    constructor(rootDir: File, create: Boolean = true) : this(rootDir) {
        if (create) rootDir.mkdirs()
    }

    data class Finding(val unitId: String, val text: String)

    data class Entry(
        val source: TranscriptSourceRef,
        val sourceFingerprint: String,
        val question: String,
        val findings: List<Finding>,
        val totalUnits: Int,
        val schemaVersion: Int = TranscriptChatPolicy.CHECKPOINT_SCHEMA_VERSION,
        val promptVersion: String = TranscriptChatPolicy.PROMPT_VERSION,
        val modelVersion: String = TranscriptChatPolicy.MODEL_VERSION,
        val updatedAtMs: Long,
    ) {
        fun isReusable(fingerprint: String, currentQuestion: String, unitCount: Int): Boolean =
            sourceFingerprint == fingerprint && question == currentQuestion && totalUnits == unitCount &&
                schemaVersion == TranscriptChatPolicy.CHECKPOINT_SCHEMA_VERSION &&
                promptVersion == TranscriptChatPolicy.PROMPT_VERSION &&
                modelVersion == TranscriptChatPolicy.MODEL_VERSION
    }

    init { rootDir.mkdirs() }

    fun read(source: TranscriptSourceRef): Entry? = fileForOrNull(source)?.let { file ->
        try {
            val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            fromJson(JSONObject(raw)).also(::validate)
        } catch (_: Exception) {
            null
        }
    }

    fun save(entry: Entry) {
        validate(entry)
        val atomic = AtomicFile(requireNotNull(fileForOrNull(entry.source)))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(toJson(entry).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    fun delete(source: TranscriptSourceRef): Boolean = fileForOrNull(source)?.let {
        AtomicFile(it).delete()
        !it.exists()
    } ?: false

    private fun validate(entry: Entry) {
        require(TranscriptChatPolicy.isSafeSource(entry.source)) { "invalid source" }
        require(TranscriptChatPolicy.FINGERPRINT_REGEX.matches(entry.sourceFingerprint)) { "invalid fingerprint" }
        require(entry.question.isNotBlank() && entry.question.length <= TranscriptChatPolicy.MAX_QUESTION_CHARS) { "invalid question" }
        require(entry.totalUnits in 1..128) { "invalid total" }
        require(entry.schemaVersion == TranscriptChatPolicy.CHECKPOINT_SCHEMA_VERSION) { "invalid schema" }
        require(entry.promptVersion == TranscriptChatPolicy.PROMPT_VERSION) { "invalid prompt" }
        require(entry.modelVersion == TranscriptChatPolicy.MODEL_VERSION) { "invalid model" }
        require(entry.findings.map(Finding::unitId).distinct().size == entry.findings.size) { "duplicate finding" }
        entry.findings.forEach {
            require(TranscriptChatPolicy.UNIT_ID_REGEX.matches(it.unitId)) { "invalid unit" }
            require(it.text.isNotBlank() && it.text.length <= TranscriptChatPolicy.MAX_FINDING_CHARS) { "invalid finding" }
        }
    }

    private fun toJson(entry: Entry) = JSONObject().apply {
        put("schemaVersion", entry.schemaVersion)
        put("sourceType", entry.source.type.name)
        put("sourceId", entry.source.id)
        put("sourceFingerprint", entry.sourceFingerprint)
        put("question", entry.question)
        put("totalUnits", entry.totalUnits)
        put("promptVersion", entry.promptVersion)
        put("modelVersion", entry.modelVersion)
        put("updatedAtMs", entry.updatedAtMs)
        put("findings", JSONArray().apply {
            entry.findings.forEach { put(JSONObject().put("unitId", it.unitId).put("text", it.text)) }
        })
    }

    private fun fromJson(json: JSONObject): Entry {
        val findingsJson = json.getJSONArray("findings")
        return Entry(
            source = TranscriptSourceRef(TranscriptSourceType.valueOf(json.getString("sourceType")), json.getString("sourceId")),
            sourceFingerprint = json.getString("sourceFingerprint"),
            question = json.getString("question"),
            findings = buildList {
                for (index in 0 until findingsJson.length()) {
                    val finding = findingsJson.getJSONObject(index)
                    add(Finding(finding.getString("unitId"), finding.getString("text")))
                }
            },
            totalUnits = json.getInt("totalUnits"),
            schemaVersion = json.getInt("schemaVersion"),
            promptVersion = json.getString("promptVersion"),
            modelVersion = json.getString("modelVersion"),
            updatedAtMs = json.getLong("updatedAtMs"),
        )
    }

    private fun fileForOrNull(source: TranscriptSourceRef): File? = source.takeIf(TranscriptChatPolicy::isSafeSource)
        ?.let { File(rootDir, "${it.type.name.lowercase()}_${it.id}.json") }

    private companion object { const val DIRECTORY = "transcript_chat_precise" }
}
