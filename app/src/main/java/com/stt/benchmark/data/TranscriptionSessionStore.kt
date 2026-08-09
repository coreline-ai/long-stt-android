package com.stt.benchmark.data

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.whisper.ChunkCoverage
import com.stt.benchmark.whisper.TranscriptSegment
import com.stt.benchmark.whisper.TranscriptionResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 장시간 전사의 재개 지점과 완료 청크 결과를 앱 내부 저장소에 보존한다.
 * 각 save는 [AtomicFile]을 사용하므로 전원/프로세스 중단으로 반쪽 JSON이
 * 완료 checkpoint로 읽히지 않는다.
 */
class TranscriptionSessionStore(context: Context) {

    enum class Status { PREPARING, RUNNING, COOLING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

    data class CompletedChunk(
        val index: Int,
        val primaryStartMs: Long,
        val primaryEndMs: Long,
        val decodedStartMs: Long,
        val decodedEndMs: Long,
        val decodedSamples: Int,
        val retryCount: Int,
        val elapsedMs: Long,
        val text: String,
        val segments: List<TranscriptSegment>
    )

    data class Checkpoint(
        val sessionId: String,
        val status: Status,
        val modelPath: String,
        val audioPath: String,
        val note: String,
        val durationMs: Long,
        val totalChunks: Int,
        val currentChunk: Int,
        val errorMessage: String = "",
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val chunks: List<CompletedChunk> = emptyList(),
        /** 직접 녹음에서 시작된 child 전사만 채우는 optional 연결 메타데이터. */
        val recordingSessionId: String = "",
        val recordingGroupId: String = "",
        val mediaId: String = "",
        val recordingSequence: Int = -1,
    ) {
        /** schema v1/Java instrumentation source compatibility constructor. */
        constructor(
            sessionId: String,
            status: Status,
            modelPath: String,
            audioPath: String,
            note: String,
            durationMs: Long,
            totalChunks: Int,
            currentChunk: Int,
            errorMessage: String,
            createdAtMs: Long,
            updatedAtMs: Long,
            chunks: List<CompletedChunk>,
        ) : this(
            sessionId = sessionId,
            status = status,
            modelPath = modelPath,
            audioPath = audioPath,
            note = note,
            durationMs = durationMs,
            totalChunks = totalChunks,
            currentChunk = currentChunk,
            errorMessage = errorMessage,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            chunks = chunks,
            recordingSessionId = "",
            recordingGroupId = "",
            mediaId = "",
            recordingSequence = -1,
        )

        val progress: Float
            get() = if (totalChunks > 0) chunks.size.toFloat() / totalChunks.toFloat() else 0f

        fun toResult(modelSize: String = "", engineName: String = "whisper.cpp"): TranscriptionResult {
            val ordered = chunks.sortedBy { it.index }
            val segments = ordered.flatMap { it.segments }
            return TranscriptionResult(
                text = ordered.joinToString(" ") { it.text.trim() }.trim(),
                segments = segments,
                elapsedMs = ordered.sumOf { it.elapsedMs },
                audioDurationMs = durationMs,
                modelSize = modelSize,
                engineName = engineName,
                chunkCoverage = ordered.map {
                    ChunkCoverage(
                        chunkIndex = it.index,
                        primaryStartMs = it.primaryStartMs,
                        primaryEndMs = it.primaryEndMs,
                        decodedStartMs = it.decodedStartMs,
                        decodedEndMs = it.decodedEndMs,
                        decodedSamples = it.decodedSamples
                    )
                }
            )
        }
    }

    private val sessionsDir = File(context.filesDir, SESSIONS_DIR).apply { mkdirs() }

    fun newSessionId(): String = "stt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

    fun save(checkpoint: Checkpoint) {
        val file = checkpointFile(checkpoint.sessionId)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(toJson(checkpoint).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
    }

    fun load(sessionId: String): Checkpoint? {
        val file = checkpointFile(sessionId)
        return try {
            val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    /** 결과 보관함용 전체 세션 목록. 손상된 checkpoint는 목록에서 제외한다. */
    fun listAll(): List<Checkpoint> = sessionsDir.listFiles()
        .orEmpty()
        .mapNotNull { file ->
            when {
                file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                file.name.endsWith(".json.bak") -> file.name.removeSuffix(".json.bak")
                else -> null
            }?.let { sessionId -> sessionId to file.lastModified() }
        }
        .distinctBy { it.first }
        .sortedByDescending { it.second }
        .mapNotNull { (sessionId, _) -> load(sessionId) }

    /** 활성·재개 대상 세션은 ViewModel에서 먼저 차단한 뒤 삭제한다. */
    fun delete(sessionId: String): Boolean {
        if (!sessionId.matches(Regex("[A-Za-z0-9_-]+"))) return false
        return try {
            AtomicFile(checkpointFile(sessionId)).delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 이전 process가 사라졌는데 RUNNING 계열로 남은 세션을 자동 실행하지 않고 재개 대기로 바꾼다.
     * COMPLETED/FAILED/CANCELLED 및 이미 INTERRUPTED인 세션은 저장하지 않는다.
     */
    @Synchronized
    fun reconcileAfterProcessDeath(nowMs: Long = System.currentTimeMillis()): List<Checkpoint> =
        listAll().mapNotNull { checkpoint ->
            val reconciled = TranscriptionLifecyclePolicy.reconcileAfterProcessDeath(checkpoint, nowMs)
            if (reconciled === checkpoint) {
                null
            } else {
                save(reconciled)
                reconciled
            }
        }

    fun hasIncompleteForAudio(audioPath: String): Boolean = listAll().any {
        it.audioPath == audioPath && it.status in INCOMPLETE_STATUSES
    }

    fun countForAudio(audioPath: String): Int = listAll().count { it.audioPath == audioPath }

    fun latestIncomplete(): Checkpoint? = listAll()
        .firstOrNull { it.status in INCOMPLETE_STATUSES }

    fun latestIncompleteFor(modelPath: String, audioPath: String): Checkpoint? =
        listAll()
            .firstOrNull {
                it.status in INCOMPLETE_STATUSES &&
                    it.modelPath == modelPath && it.audioPath == audioPath
            }

    fun latestIncompleteForGroup(recordingGroupId: String, mediaId: String): Checkpoint? =
        listAll().firstOrNull {
            it.status in INCOMPLETE_STATUSES &&
                it.recordingGroupId == recordingGroupId && it.mediaId == mediaId
        }

    private fun checkpointFile(sessionId: String): File = File(sessionsDir, "$sessionId.json")

    private fun toJson(checkpoint: Checkpoint): JSONObject = JSONObject().apply {
        put("version", CURRENT_SCHEMA_VERSION)
        put("sessionId", checkpoint.sessionId)
        put("status", checkpoint.status.name)
        put("modelPath", checkpoint.modelPath)
        put("audioPath", checkpoint.audioPath)
        put("note", checkpoint.note)
        put("durationMs", checkpoint.durationMs)
        put("totalChunks", checkpoint.totalChunks)
        put("currentChunk", checkpoint.currentChunk)
        put("errorMessage", checkpoint.errorMessage)
        put("createdAtMs", checkpoint.createdAtMs)
        put("updatedAtMs", checkpoint.updatedAtMs)
        put("recordingSessionId", checkpoint.recordingSessionId)
        put("recordingGroupId", checkpoint.recordingGroupId)
        put("mediaId", checkpoint.mediaId)
        put("recordingSequence", checkpoint.recordingSequence)
        put("chunks", JSONArray().apply {
            checkpoint.chunks.sortedBy { it.index }.forEach { chunk ->
                put(JSONObject().apply {
                    put("index", chunk.index)
                    put("primaryStartMs", chunk.primaryStartMs)
                    put("primaryEndMs", chunk.primaryEndMs)
                    put("decodedStartMs", chunk.decodedStartMs)
                    put("decodedEndMs", chunk.decodedEndMs)
                    put("decodedSamples", chunk.decodedSamples)
                    put("retryCount", chunk.retryCount)
                    put("elapsedMs", chunk.elapsedMs)
                    put("text", chunk.text)
                    put("segments", JSONArray().apply {
                        chunk.segments.forEach { segment ->
                            put(JSONObject().apply {
                                put("startMs", segment.startMs)
                                put("endMs", segment.endMs)
                                put("text", segment.text)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Checkpoint {
        val version = json.optInt("version", 1)
        require(version in 1..CURRENT_SCHEMA_VERSION) { "지원하지 않는 STT session schema: $version" }
        val chunks = json.optJSONArray("chunks") ?: JSONArray()
        return Checkpoint(
            sessionId = json.getString("sessionId"),
            status = Status.valueOf(json.getString("status")),
            modelPath = json.getString("modelPath"),
            audioPath = json.getString("audioPath"),
            note = json.optString("note"),
            durationMs = json.getLong("durationMs"),
            totalChunks = json.getInt("totalChunks"),
            currentChunk = json.optInt("currentChunk", 0),
            errorMessage = json.optString("errorMessage"),
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
            recordingSessionId = json.optString("recordingSessionId"),
            recordingGroupId = json.optString("recordingGroupId"),
            mediaId = json.optString("mediaId"),
            recordingSequence = json.optInt("recordingSequence", -1),
            chunks = List(chunks.length()) { index ->
                val chunk = chunks.getJSONObject(index)
                val segmentArray = chunk.optJSONArray("segments") ?: JSONArray()
                CompletedChunk(
                    index = chunk.getInt("index"),
                    primaryStartMs = chunk.getLong("primaryStartMs"),
                    primaryEndMs = chunk.getLong("primaryEndMs"),
                    decodedStartMs = chunk.getLong("decodedStartMs"),
                    decodedEndMs = chunk.getLong("decodedEndMs"),
                    decodedSamples = chunk.getInt("decodedSamples"),
                    retryCount = chunk.optInt("retryCount", 0),
                    elapsedMs = chunk.getLong("elapsedMs"),
                    text = chunk.optString("text"),
                    segments = List(segmentArray.length()) { segmentIndex ->
                        val segment = segmentArray.getJSONObject(segmentIndex)
                        TranscriptSegment(
                            startMs = segment.getLong("startMs"),
                            endMs = segment.getLong("endMs"),
                            text = segment.optString("text")
                        )
                    }
                )
            }
        )
    }

    private companion object {
        const val SESSIONS_DIR = "stt_sessions"
        const val CURRENT_SCHEMA_VERSION = 2
        val INCOMPLETE_STATUSES = TranscriptionLifecyclePolicy.resumableStatuses
    }
}
