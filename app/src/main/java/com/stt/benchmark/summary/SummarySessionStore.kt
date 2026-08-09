package com.stt.benchmark.summary

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Persists only a generated summary and its opaque source key. The selected transcript is kept in
 * the existing local STT checkpoint and is deliberately never copied into this store.
 */
class SummarySessionStore(context: Context) {
    enum class SourceType { TRANSCRIPTION_SESSION, RECORDING_GROUP }

    data class Entry(
        val source: SummaryRequestPolicy.Source,
        val summary: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
    )

    private val summariesDir = File(context.filesDir, SUMMARIES_DIR).apply { mkdirs() }

    fun listAll(): List<Entry> = summariesDir.listFiles()
        .orEmpty()
        .filter { it.name.endsWith(FILE_SUFFIX) }
        .mapNotNull(::read)
        .sortedByDescending { it.updatedAtMs }

    fun find(source: SummaryRequestPolicy.Source): Entry? = read(fileFor(source))

    fun saveCompleted(
        source: SummaryRequestPolicy.Source,
        summary: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Entry {
        require(source.id.matches(SOURCE_ID_REGEX)) { "invalid summary source" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(summary.length <= SummaryRequestPolicy.MAX_SUMMARY_CHARS) { "summary is too long" }

        val previous = find(source)
        val entry = Entry(
            source = source,
            summary = summary.trim(),
            createdAtMs = previous?.createdAtMs ?: nowMs,
            updatedAtMs = nowMs,
        )
        write(fileFor(source), entry)
        return entry
    }

    private fun fileFor(source: SummaryRequestPolicy.Source): File =
        File(summariesDir, "${source.type.name.lowercase()}_${source.id}$FILE_SUFFIX")

    private fun read(file: File): Entry? = try {
        val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        fromJson(JSONObject(json))
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, entry: Entry) {
        val atomic = AtomicFile(file)
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

    private fun toJson(entry: Entry): JSONObject = JSONObject().apply {
        put("version", CURRENT_SCHEMA_VERSION)
        put("sourceType", entry.source.type.name)
        put("sourceId", entry.source.id)
        put("summary", entry.summary)
        put("createdAtMs", entry.createdAtMs)
        put("updatedAtMs", entry.updatedAtMs)
    }

    private fun fromJson(json: JSONObject): Entry {
        require(json.optInt("version", 0) == CURRENT_SCHEMA_VERSION) { "unsupported summary schema" }
        val source = SummaryRequestPolicy.Source(
            type = SourceType.valueOf(json.getString("sourceType")),
            id = json.getString("sourceId"),
        )
        val summary = json.getString("summary").trim()
        require(source.id.matches(SOURCE_ID_REGEX) && summary.isNotEmpty()) { "invalid summary entry" }
        require(summary.length <= SummaryRequestPolicy.MAX_SUMMARY_CHARS) { "summary is too long" }
        return Entry(
            source = source,
            summary = summary,
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
        )
    }

    private companion object {
        const val SUMMARIES_DIR = "summary_sessions"
        const val FILE_SUFFIX = ".json"
        const val CURRENT_SCHEMA_VERSION = 1
        val SOURCE_ID_REGEX = Regex("[A-Za-z0-9_-]+")
    }
}
