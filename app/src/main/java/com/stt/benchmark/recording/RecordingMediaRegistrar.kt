package com.stt.benchmark.recording

import android.content.Context
import com.stt.benchmark.data.MediaLibraryStore
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹음 checkpoint를 MediaLibrary 항목으로 투영하는 단일 경계.
 * READY 표기만 신뢰하지 않고 관리 경로·크기·SHA-256을 다시 확인한다.
 */
class RecordingMediaRegistrar(
    context: Context,
    private val mediaLibrary: MediaLibraryStore = MediaLibraryStore(context),
) {
    sealed interface RegistrationResult {
        val entries: List<MediaLibraryStore.AudioEntry>

        data class Complete(
            override val entries: List<MediaLibraryStore.AudioEntry>,
        ) : RegistrationResult

        data class Partial(
            override val entries: List<MediaLibraryStore.AudioEntry>,
            val excludedSequences: List<Int>,
            val reason: String,
        ) : RegistrationResult

        data class Blocked(
            val reason: String,
        ) : RegistrationResult {
            override val entries: List<MediaLibraryStore.AudioEntry> = emptyList()
        }
    }

    private val filesRoot = context.filesDir.canonicalFile

    fun register(session: RecordingSessionStore.RecordingSession): RegistrationResult {
        if (session.phase in ACTIVE_PHASES) {
            return RegistrationResult.Blocked("진행 중인 녹음 세션은 등록할 수 없습니다.")
        }
        if (session.chunks.isEmpty()) {
            return RegistrationResult.Blocked("확정된 녹음 청크가 없습니다.")
        }
        val allIndices = session.chunks.map { it.index }
        if (allIndices.distinct().size != allIndices.size) {
            return RegistrationResult.Blocked("중복된 녹음 청크 sequence가 있습니다.")
        }

        val ordered = session.chunks.sortedBy { it.index }
        val indexedByPath = mediaLibrary.listAudios().associateBy { it.path }
        val expected = (0..ordered.last().index).toList()
        val excluded = linkedSetOf<Int>()
        excluded += expected.filterNot { it in allIndices }
        val validReady = ordered.mapNotNull { chunk ->
            if (chunk.status != RecordingSessionStore.ChunkStatus.READY) {
                excluded += chunk.index
                null
            } else if (!isTrustedReady(chunk, indexedByPath[chunk.finalPath])) {
                excluded += chunk.index
                null
            } else {
                chunk
            }
        }
        if (validReady.isEmpty()) {
            return RegistrationResult.Blocked("무결성 검사를 통과한 READY 녹음 청크가 없습니다.")
        }

        val total = expected.size
        val entries = validReady.map { chunk ->
            mediaLibrary.registerAudio(
                file = File(chunk.finalPath),
                displayName = recordingDisplayName(session.createdAtMs, chunk.index, total),
                durationMs = chunk.durationMs,
                source = MediaLibraryStore.AudioSource.RECORDED,
                recordingSessionId = session.sessionId,
                sequence = chunk.index,
                codec = chunk.codec,
                sha256 = chunk.sha256,
            )
        }.sortedBy { it.sequence }

        val complete = session.phase == RecordingPhase.SAVED &&
            excluded.isEmpty() &&
            allIndices.sorted() == expected &&
            ordered.all { it.status == RecordingSessionStore.ChunkStatus.READY }
        return if (complete) {
            RegistrationResult.Complete(entries)
        } else {
            RegistrationResult.Partial(
                entries = entries,
                excludedSequences = excluded.sorted(),
                reason = "확정 ${entries.size}개만 사용할 수 있으며 제외 ${excluded.size}개가 있습니다.",
            )
        }
    }

    fun registerAll(sessions: List<RecordingSessionStore.RecordingSession>): List<RegistrationResult> =
        sessions.map(::register)

    private fun isTrustedReady(
        chunk: RecordingSessionStore.RecordingChunk,
        indexed: MediaLibraryStore.AudioEntry?,
    ): Boolean {
        val file = runCatching { File(chunk.finalPath).canonicalFile }.getOrNull() ?: return false
        if (!file.isFile || !file.canRead()) return false
        if (!file.path.startsWith(filesRoot.path + File.separator)) return false
        if (chunk.sizeBytes <= 0L || file.length() != chunk.sizeBytes) return false
        if (!chunk.sha256.matches(SHA256_REGEX)) return false
        // 이미 같은 checkpoint 메타데이터로 등록된 파일은 UI 재진입마다 대용량 해시를 다시 읽지 않는다.
        if (indexed != null && indexed.source == MediaLibraryStore.AudioSource.RECORDED &&
            indexed.recordingSessionId.isNotBlank() && indexed.sizeBytes == chunk.sizeBytes &&
            indexed.sha256.equals(chunk.sha256, ignoreCase = true)
        ) return true
        return runCatching { sha256(file).equals(chunk.sha256, ignoreCase = true) }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recordingDisplayName(createdAtMs: Long, sequence: Int, total: Int): String {
        val date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(createdAtMs))
        return "녹음 $date · ${sequence + 1}/$total"
    }

    private companion object {
        val SHA256_REGEX = Regex("[a-f0-9]{64}")
        val ACTIVE_PHASES = setOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.ROLLING_OVER,
            RecordingPhase.FINALIZING,
        )
    }
}
