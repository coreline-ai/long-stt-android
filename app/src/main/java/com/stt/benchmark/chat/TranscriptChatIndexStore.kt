package com.stt.benchmark.chat

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 원문 없이 unit 시간 범위와 LLM 파생 요약만 checkpoint하는 AtomicFile 저장소. */
class TranscriptChatIndexStore private constructor(private val rootDir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))
    constructor(rootDir: File, create: Boolean = true) : this(rootDir) {
        if (create) rootDir.mkdirs()
    }

    data class UnitEntry(
        val unitId: String,
        val startMs: Long,
        val endMs: Long,
        val summary: String,
    )

    data class Entry(
        val source: TranscriptSourceRef,
        val sourceFingerprint: String,
        val schemaVersion: Int = TranscriptChatPolicy.INDEX_SCHEMA_VERSION,
        val promptVersion: String = TranscriptChatPolicy.PROMPT_VERSION,
        val modelVersion: String = TranscriptChatPolicy.MODEL_VERSION,
        val units: List<UnitEntry>,
        val isComplete: Boolean,
        val updatedAtMs: Long,
    ) {
        fun isReusable(fingerprint: String): Boolean =
            sourceFingerprint == fingerprint &&
                schemaVersion == TranscriptChatPolicy.INDEX_SCHEMA_VERSION &&
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

    private fun validate(entry: Entry) {
        require(TranscriptChatPolicy.isSafeSource(entry.source)) { "invalid chat source" }
        require(TranscriptChatPolicy.FINGERPRINT_REGEX.matches(entry.sourceFingerprint)) { "invalid fingerprint" }
        require(entry.schemaVersion == TranscriptChatPolicy.INDEX_SCHEMA_VERSION) { "invalid schema" }
        require(entry.promptVersion == TranscriptChatPolicy.PROMPT_VERSION) { "invalid prompt version" }
        require(entry.modelVersion == TranscriptChatPolicy.MODEL_VERSION) { "invalid model version" }
        require(entry.units.size <= MAX_UNITS) { "too many units" }
        require(entry.units.map(UnitEntry::unitId).distinct().size == entry.units.size) { "duplicate unit" }
        entry.units.forEach { unit ->
            require(TranscriptChatPolicy.UNIT_ID_REGEX.matches(unit.unitId)) { "invalid unit" }
            require(unit.startMs >= 0 && unit.endMs >= unit.startMs) { "invalid unit range" }
            require(unit.summary.isNotBlank() && unit.summary.length <= TranscriptChatPolicy.MAX_UNIT_SUMMARY_CHARS) {
                "invalid unit summary"
            }
        }
    }

    private fun toJson(entry: Entry) = JSONObject().apply {
        put("schemaVersion", entry.schemaVersion)
        put("sourceType", entry.source.type.name)
        put("sourceId", entry.source.id)
        put("sourceFingerprint", entry.sourceFingerprint)
        put("promptVersion", entry.promptVersion)
        put("modelVersion", entry.modelVersion)
        put("isComplete", entry.isComplete)
        put("updatedAtMs", entry.updatedAtMs)
        put("units", JSONArray().apply {
            entry.units.forEach { unit ->
                put(JSONObject().apply {
                    put("unitId", unit.unitId)
                    put("startMs", unit.startMs)
                    put("endMs", unit.endMs)
                    put("summary", unit.summary)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Entry {
        val unitsJson = json.getJSONArray("units")
        val units = buildList {
            for (index in 0 until unitsJson.length()) {
                val unit = unitsJson.getJSONObject(index)
                add(UnitEntry(unit.getString("unitId"), unit.getLong("startMs"), unit.getLong("endMs"), unit.getString("summary")))
            }
        }
        return Entry(
            source = TranscriptSourceRef(TranscriptSourceType.valueOf(json.getString("sourceType")), json.getString("sourceId")),
            sourceFingerprint = json.getString("sourceFingerprint"),
            schemaVersion = json.getInt("schemaVersion"),
            promptVersion = json.getString("promptVersion"),
            modelVersion = json.getString("modelVersion"),
            units = units,
            isComplete = json.getBoolean("isComplete"),
            updatedAtMs = json.getLong("updatedAtMs"),
        )
    }

    private fun fileForOrNull(source: TranscriptSourceRef): File? = source.takeIf(TranscriptChatPolicy::isSafeSource)
        ?.let { File(rootDir, "${it.type.name.lowercase()}_${it.id}.json") }

    private companion object {
        const val DIRECTORY = "transcript_chat_indexes"
        const val MAX_UNITS = 128
    }
}
