package com.stt.benchmark.recording

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** AtomicFile 기반 녹음 세션 정본. 오디오 본문은 별도 파일로 유지한다. */
class RecordingSessionStore(context: Context) {
    enum class ChunkStatus { WRITING, READY, QUARANTINED, MISSING }

    data class RecordingChunk(
        val index: Int,
        val status: ChunkStatus,
        val partPath: String = "",
        val finalPath: String = "",
        val quarantinePath: String = "",
        val container: String = "",
        val codec: String = "",
        val sampleRateHz: Int = 0,
        val channelCount: Int = 0,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val sha256: String = "",
        val createdAtMs: Long,
        val finalizedAtMs: Long = 0L,
        val issue: String = "",
    )

    data class RecordingSession(
        val sessionId: String,
        val phase: RecordingPhase,
        val preferredContainer: String = "m4a",
        val chunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS,
        val currentChunkIndex: Int = 0,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val startedAtMs: Long = 0L,
        val stoppedAtMs: Long = 0L,
        val errorMessage: String = "",
        val chunks: List<RecordingChunk> = emptyList(),
    ) {
        val readyChunks: List<RecordingChunk>
            get() = chunks.filter { it.status == ChunkStatus.READY }.sortedBy { it.index }
    }

    sealed interface ReadResult {
        data class Loaded(val session: RecordingSession) : ReadResult
        data object Missing : ReadResult
        data class UnsupportedSchema(val version: Int) : ReadResult
        data class Corrupt(val message: String) : ReadResult
    }

    private val sessionsDir = File(context.filesDir, SESSIONS_DIR).apply { mkdirs() }

    fun newSessionId(nowMs: Long = System.currentTimeMillis()): String =
        "recording_${nowMs}_${UUID.randomUUID().toString().take(8)}"

    @Synchronized
    fun save(session: RecordingSession) {
        validate(session)
        val atomic = AtomicFile(checkpointFile(session.sessionId))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(toJson(session).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    fun read(sessionId: String): ReadResult {
        if (!RecordingStateReducer.isValidSessionId(sessionId)) {
            return ReadResult.Corrupt("잘못된 녹음 sessionId")
        }
        val file = checkpointFile(sessionId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return ReadResult.Missing
        return try {
            val text = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val version = json.optInt("version", 1)
            if (version !in 1..CURRENT_SCHEMA_VERSION) {
                ReadResult.UnsupportedSchema(version)
            } else {
                ReadResult.Loaded(fromJson(json).also(::validate))
            }
        } catch (error: Exception) {
            ReadResult.Corrupt(error.message ?: error.javaClass.simpleName)
        }
    }

    fun load(sessionId: String): RecordingSession? =
        (read(sessionId) as? ReadResult.Loaded)?.session

    fun listSessionIds(): Set<String> = buildSet {
        sessionsDir.listFiles().orEmpty().forEach { file ->
            val sessionId = when {
                file.name.endsWith(".json.bak") -> file.name.removeSuffix(".json.bak")
                file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                else -> null
            }
            if (sessionId != null && RecordingStateReducer.isValidSessionId(sessionId)) {
                add(sessionId)
            }
        }
    }

    fun listAll(): List<RecordingSession> = listSessionIds()
        .mapNotNull { sessionId -> load(sessionId) }
        .sortedByDescending { it.updatedAtMs }

    /** 활성 상태를 자동 재개하지 않고 파일 검사가 필요한 상태로만 바꾼다. */
    @Synchronized
    fun reconcileAfterProcessDeath(
        nowMs: Long = System.currentTimeMillis(),
        checkpointModifiedBeforeExclusiveMs: Long? = null,
    ): List<RecordingSession> =
        listAll().filter { session ->
            checkpointModifiedBeforeExclusiveMs == null ||
                checkpointFile(session.sessionId).lastModified() < checkpointModifiedBeforeExclusiveMs
        }.mapNotNull { session ->
            val transition = RecordingStateReducer.reduce(
                RecordingState(
                    phase = session.phase,
                    sessionId = session.sessionId,
                    currentChunkIndex = session.currentChunkIndex,
                    message = session.errorMessage,
                ),
                RecordingEvent.ProcessRestarted,
            )
            if (transition.state.phase == session.phase) {
                null
            } else {
                session.copy(
                    phase = transition.state.phase,
                    errorMessage = transition.state.message,
                    updatedAtMs = maxOf(nowMs, session.updatedAtMs),
                ).also(::save)
            }
        }

    fun delete(sessionId: String): Boolean {
        if (!RecordingStateReducer.isValidSessionId(sessionId)) return false
        return runCatching { AtomicFile(checkpointFile(sessionId)).delete() }.isSuccess
    }

    internal fun checkpointFile(sessionId: String): File = File(sessionsDir, "$sessionId.json")

    private fun validate(session: RecordingSession) {
        require(RecordingStateReducer.isValidSessionId(session.sessionId)) { "잘못된 녹음 sessionId" }
        require(session.chunkDurationMs in MIN_CHUNK_DURATION_MS..MAX_CHUNK_DURATION_MS) {
            "지원하지 않는 녹음 청크 길이"
        }
        require(session.currentChunkIndex >= 0) { "currentChunkIndex는 음수일 수 없습니다" }
        require(session.createdAtMs > 0L && session.updatedAtMs >= session.createdAtMs) {
            "잘못된 녹음 session timestamp"
        }
        require(session.chunks.map { it.index }.distinct().size == session.chunks.size) {
            "중복 녹음 chunk index"
        }
        session.chunks.forEach { chunk ->
            require(chunk.index >= 0) { "chunk index는 음수일 수 없습니다" }
            require(chunk.createdAtMs > 0L) { "잘못된 chunk timestamp" }
            when (chunk.status) {
                ChunkStatus.WRITING -> require(chunk.partPath.isNotBlank()) { "WRITING chunk에는 partPath가 필요합니다" }
                ChunkStatus.READY -> {
                    require(chunk.finalPath.isNotBlank() && chunk.sizeBytes > 0L) { "READY chunk 메타데이터가 불완전합니다" }
                    require(chunk.sha256.matches(SHA256_REGEX)) { "READY chunk SHA-256이 잘못되었습니다" }
                }
                ChunkStatus.QUARANTINED -> require(chunk.quarantinePath.isNotBlank()) {
                    "QUARANTINED chunk에는 quarantinePath가 필요합니다"
                }
                ChunkStatus.MISSING -> require(chunk.issue.isNotBlank()) {
                    "MISSING chunk에는 진단 사유가 필요합니다"
                }
            }
        }
    }

    private fun toJson(session: RecordingSession): JSONObject = JSONObject().apply {
        put("version", CURRENT_SCHEMA_VERSION)
        put("sessionId", session.sessionId)
        put("phase", session.phase.name)
        put("preferredContainer", session.preferredContainer)
        put("chunkDurationMs", session.chunkDurationMs)
        put("currentChunkIndex", session.currentChunkIndex)
        put("createdAtMs", session.createdAtMs)
        put("updatedAtMs", session.updatedAtMs)
        put("startedAtMs", session.startedAtMs)
        put("stoppedAtMs", session.stoppedAtMs)
        put("errorMessage", session.errorMessage)
        put("chunks", JSONArray().apply {
            session.chunks.sortedBy { it.index }.forEach { chunk ->
                put(JSONObject().apply {
                    put("index", chunk.index)
                    put("status", chunk.status.name)
                    put("partPath", chunk.partPath)
                    put("finalPath", chunk.finalPath)
                    put("quarantinePath", chunk.quarantinePath)
                    put("container", chunk.container)
                    put("codec", chunk.codec)
                    put("sampleRateHz", chunk.sampleRateHz)
                    put("channelCount", chunk.channelCount)
                    put("durationMs", chunk.durationMs)
                    put("sizeBytes", chunk.sizeBytes)
                    put("sha256", chunk.sha256)
                    put("createdAtMs", chunk.createdAtMs)
                    put("finalizedAtMs", chunk.finalizedAtMs)
                    put("issue", chunk.issue)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): RecordingSession {
        val chunks = json.optJSONArray("chunks") ?: JSONArray()
        return RecordingSession(
            sessionId = json.getString("sessionId"),
            phase = RecordingPhase.valueOf(json.getString("phase")),
            preferredContainer = json.optString("preferredContainer", "m4a"),
            chunkDurationMs = json.optLong("chunkDurationMs", DEFAULT_CHUNK_DURATION_MS),
            currentChunkIndex = json.optInt("currentChunkIndex", 0),
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
            startedAtMs = json.optLong("startedAtMs"),
            stoppedAtMs = json.optLong("stoppedAtMs"),
            errorMessage = json.optString("errorMessage"),
            chunks = List(chunks.length()) { index ->
                val chunk = chunks.getJSONObject(index)
                RecordingChunk(
                    index = chunk.getInt("index"),
                    status = ChunkStatus.valueOf(chunk.getString("status")),
                    partPath = chunk.optString("partPath"),
                    finalPath = chunk.optString("finalPath"),
                    quarantinePath = chunk.optString("quarantinePath"),
                    container = chunk.optString("container"),
                    codec = chunk.optString("codec"),
                    sampleRateHz = chunk.optInt("sampleRateHz"),
                    channelCount = chunk.optInt("channelCount"),
                    durationMs = chunk.optLong("durationMs"),
                    sizeBytes = chunk.optLong("sizeBytes"),
                    sha256 = chunk.optString("sha256"),
                    createdAtMs = chunk.getLong("createdAtMs"),
                    finalizedAtMs = chunk.optLong("finalizedAtMs"),
                    issue = chunk.optString("issue"),
                )
            },
        )
    }

    companion object {
        const val DEFAULT_CHUNK_DURATION_MS = 20L * 60L * 1_000L
        const val MIN_CHUNK_DURATION_MS = 60_000L
        const val MAX_CHUNK_DURATION_MS = 60L * 60L * 1_000L
        private const val SESSIONS_DIR = "recording_sessions"
        private const val CURRENT_SCHEMA_VERSION = 1
        private val SHA256_REGEX = Regex("[a-f0-9]{64}")
    }
}
