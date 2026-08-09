package com.stt.benchmark.recording

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 녹음 파일을 앱 관리 경로 안에서만 생성·확정·격리한다.
 * READY 판단은 확장자뿐 아니라 최소 container signature와 SHA-256을 함께 확인한다.
 */
class RecordingFileManager(
    rootDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val mover: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) {
    data class ReadyFile(
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
        val container: String,
    )

    sealed interface FinalizeResult {
        data class Ready(val value: ReadyFile) : FinalizeResult
        data class RetryableFailure(val partFile: File, val reason: String) : FinalizeResult
        data class Quarantined(val file: File?, val reason: String) : FinalizeResult
        data class Rejected(val reason: String) : FinalizeResult
    }

    data class ReconcileResult(
        val session: RecordingSessionStore.RecordingSession,
        val actions: List<String>,
    )

    private val managedRoot = File(rootDir.canonicalFile, RECORDINGS_DIR).apply { mkdirs() }.canonicalFile
    private val quarantineRoot = File(managedRoot, QUARANTINE_DIR).apply { mkdirs() }.canonicalFile

    fun createPart(sessionId: String, chunkIndex: Int, container: String): File {
        require(RecordingStateReducer.isValidSessionId(sessionId)) { "잘못된 녹음 sessionId" }
        require(chunkIndex >= 0) { "chunk index는 음수일 수 없습니다" }
        val normalizedContainer = container.lowercase(Locale.US)
        require(normalizedContainer in SUPPORTED_CONTAINERS) { "지원하지 않는 녹음 container" }
        val sessionDir = File(managedRoot, sessionId).apply { mkdirs() }
        val part = File(sessionDir, "chunk_${chunkIndex.toString().padStart(4, '0')}.$normalizedContainer.part")
        require(isManaged(part) && !part.exists()) { "이미 존재하거나 관리 경로 밖인 part 파일" }
        require(part.createNewFile()) { "part 파일을 만들 수 없습니다" }
        return part
    }

    fun finalizePart(partFile: File): FinalizeResult {
        val part = canonicalOrNull(partFile) ?: return FinalizeResult.Rejected("part 경로를 확인할 수 없습니다")
        if (!isManaged(part) || isQuarantinePath(part)) {
            return FinalizeResult.Rejected("관리 경로 밖의 파일은 확정할 수 없습니다")
        }
        if (!part.name.endsWith(PART_SUFFIX) || !part.isFile) {
            return FinalizeResult.Rejected("유효한 .part 파일이 아닙니다")
        }
        val final = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))
        val validation = validatePayload(part, final.extension)
        if (validation != null) {
            return FinalizeResult.Quarantined(quarantine(part, validation), validation)
        }
        if (final.exists()) {
            return FinalizeResult.RetryableFailure(part, "동일한 final 파일이 이미 존재합니다")
        }
        if (!mover(part, final)) {
            return FinalizeResult.RetryableFailure(part, "part 파일을 final로 원자 이동하지 못했습니다")
        }
        val canonicalFinal = canonicalOrNull(final)
            ?: return FinalizeResult.Quarantined(quarantine(final, "final 경로 확인 실패"), "final 경로 확인 실패")
        val finalValidation = validatePayload(canonicalFinal, canonicalFinal.extension)
        if (finalValidation != null) {
            return FinalizeResult.Quarantined(quarantine(canonicalFinal, finalValidation), finalValidation)
        }
        return FinalizeResult.Ready(
            ReadyFile(
                file = canonicalFinal,
                sizeBytes = canonicalFinal.length(),
                sha256 = sha256(canonicalFinal),
                container = canonicalFinal.extension.lowercase(Locale.US),
            )
        )
    }

    fun quarantineManagedFile(file: File, reason: String): File? = quarantine(file, reason)

    /** RECOVERY_REQUIRED 세션만 호출하며, checkpoint를 직접 저장하지 않는다. */
    fun reconcile(session: RecordingSessionStore.RecordingSession): ReconcileResult {
        require(session.phase == RecordingPhase.RECOVERY_REQUIRED) {
            "RECOVERY_REQUIRED 세션만 파일 reconcile할 수 있습니다"
        }
        val actions = mutableListOf<String>()
        val reconciledChunks = session.chunks.sortedBy { it.index }.map { chunk ->
            when (chunk.status) {
                RecordingSessionStore.ChunkStatus.WRITING -> reconcileWriting(chunk, actions)
                RecordingSessionStore.ChunkStatus.READY -> reconcileReady(chunk, actions)
                RecordingSessionStore.ChunkStatus.QUARANTINED,
                RecordingSessionStore.ChunkStatus.MISSING,
                -> chunk
            }
        }
        quarantineUntrackedParts(session, actions)
        val readyCount = reconciledChunks.count { it.status == RecordingSessionStore.ChunkStatus.READY }
        val issueCount = reconciledChunks.size - readyCount
        // 일부 청크만 보존된 세션을 완전 저장처럼 표시하지 않는다.
        val nextPhase = if (readyCount > 0 && issueCount == 0) RecordingPhase.SAVED else RecordingPhase.FAILED
        val message = when {
            readyCount == 0 -> "복구 가능한 확정 녹음 청크가 없습니다."
            issueCount > 0 -> "확정 청크 ${readyCount}개를 보존하고 문제 청크 ${issueCount}개를 격리했습니다."
            else -> "확정 녹음 청크 ${readyCount}개를 복구했습니다."
        }
        return ReconcileResult(
            session = session.copy(
                phase = nextPhase,
                chunks = reconciledChunks,
                errorMessage = message,
                updatedAtMs = maxOf(nowMs(), session.updatedAtMs),
            ),
            actions = actions,
        )
    }

    fun isManaged(file: File): Boolean = canonicalOrNull(file)?.let {
        it.path.startsWith(managedRoot.path + File.separator)
    } == true

    /** checkpoint 자체가 없는 세션 디렉터리의 .part도 정상 녹음으로 남겨 두지 않는다. */
    fun quarantinePartsWithoutCheckpoint(
        knownSessionIds: Set<String>,
        modifiedBeforeExclusiveMs: Long? = null,
    ): List<String> {
        val actions = mutableListOf<String>()
        managedRoot.listFiles().orEmpty()
            .filter { directory ->
                directory.isDirectory &&
                    directory.name != QUARANTINE_DIR &&
                    RecordingStateReducer.isValidSessionId(directory.name) &&
                    directory.name !in knownSessionIds
            }
            .forEach { directory ->
                directory.listFiles().orEmpty()
                    .filter {
                        it.isFile &&
                            it.name.endsWith(PART_SUFFIX) &&
                            (modifiedBeforeExclusiveMs == null || it.lastModified() < modifiedBeforeExclusiveMs)
                    }
                    .forEach { part ->
                        if (quarantine(part, "checkpoint 없는 part 파일") != null) {
                            actions += "checkpoint 없는 part 격리: ${directory.name}/${part.name}"
                        } else {
                            actions += "checkpoint 없는 part 격리 실패: ${directory.name}/${part.name}"
                        }
                    }
            }
        return actions
    }

    private fun reconcileWriting(
        chunk: RecordingSessionStore.RecordingChunk,
        actions: MutableList<String>,
    ): RecordingSessionStore.RecordingChunk {
        val part = canonicalOrNull(File(chunk.partPath))
        if (part == null || !isManaged(part) || isQuarantinePath(part)) {
            actions += "chunk ${chunk.index}: unmanaged part 거절"
            return chunk.copy(
                status = RecordingSessionStore.ChunkStatus.MISSING,
                issue = "관리 경로 안의 part 파일을 찾을 수 없습니다",
            )
        }
        val final = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))
        if (final.isFile) {
            val validation = validatePayload(final, final.extension)
            if (validation == null) {
                val ready = ReadyFile(final, final.length(), sha256(final), final.extension.lowercase(Locale.US))
                if (part.exists()) {
                    quarantine(part, "final과 part가 동시에 존재함")
                    actions += "chunk ${chunk.index}: 중복 part 격리"
                }
                actions += "chunk ${chunk.index}: checkpoint 이전 final 복구"
                return chunk.toReady(ready)
            }
            val quarantined = quarantine(final, validation)
            if (part.exists()) {
                quarantine(part, "잘못된 final과 함께 남은 part")
            }
            actions += "chunk ${chunk.index}: 잘못된 final 격리"
            return chunk.toQuarantined(quarantined, validation)
        }
        if (!part.exists()) {
            actions += "chunk ${chunk.index}: part 누락"
            return chunk.copy(
                status = RecordingSessionStore.ChunkStatus.MISSING,
                issue = "part와 final 파일이 모두 없습니다",
            )
        }
        val quarantined = quarantine(part, "중단된 part 파일")
        actions += "chunk ${chunk.index}: 중단 part 격리"
        return chunk.toQuarantined(quarantined, "프로세스 중단으로 확정되지 않은 part 파일")
    }

    private fun reconcileReady(
        chunk: RecordingSessionStore.RecordingChunk,
        actions: MutableList<String>,
    ): RecordingSessionStore.RecordingChunk {
        val final = canonicalOrNull(File(chunk.finalPath))
        if (final == null || !isManaged(final) || isQuarantinePath(final) || !final.isFile) {
            actions += "chunk ${chunk.index}: READY final 누락/경로 거절"
            return chunk.copy(
                status = RecordingSessionStore.ChunkStatus.MISSING,
                issue = "READY checkpoint의 final 파일을 찾을 수 없습니다",
            )
        }
        val validation = validatePayload(final, final.extension)
        val actualHash = if (validation == null) sha256(final) else ""
        val issue = validation ?: if (!actualHash.equals(chunk.sha256, ignoreCase = true)) {
            "READY 파일 SHA-256 불일치"
        } else {
            null
        }
        if (issue == null) return chunk.copy(sizeBytes = final.length(), sha256 = actualHash)

        val quarantined = quarantine(final, issue)
        actions += "chunk ${chunk.index}: READY 검증 실패 격리"
        return chunk.toQuarantined(quarantined, issue)
    }

    private fun RecordingSessionStore.RecordingChunk.toReady(ready: ReadyFile) = copy(
        status = RecordingSessionStore.ChunkStatus.READY,
        partPath = "",
        finalPath = ready.file.absolutePath,
        quarantinePath = "",
        container = ready.container,
        sizeBytes = ready.sizeBytes,
        sha256 = ready.sha256,
        finalizedAtMs = nowMs(),
        issue = "",
    )

    private fun RecordingSessionStore.RecordingChunk.toQuarantined(file: File?, reason: String) =
        if (file != null) {
            copy(
                status = RecordingSessionStore.ChunkStatus.QUARANTINED,
                partPath = "",
                finalPath = "",
                quarantinePath = file.absolutePath,
                issue = reason,
            )
        } else {
            copy(
                status = RecordingSessionStore.ChunkStatus.MISSING,
                quarantinePath = "",
                issue = "$reason; 격리 이동에도 실패했습니다",
            )
        }

    private fun quarantineUntrackedParts(
        session: RecordingSessionStore.RecordingSession,
        actions: MutableList<String>,
    ) {
        val tracked = session.chunks.mapNotNull { chunk ->
            chunk.partPath.takeIf { it.isNotBlank() }?.let(::File)?.let(::canonicalOrNull)?.path
        }.toSet()
        val sessionDir = canonicalOrNull(File(managedRoot, session.sessionId)) ?: return
        if (!isManaged(sessionDir) || !sessionDir.isDirectory) return
        sessionDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) && canonicalOrNull(it)?.path !in tracked }
            .forEach { orphan ->
                if (quarantine(orphan, "checkpoint에 없는 part 파일") != null) {
                    actions += "orphan part 격리: ${orphan.name}"
                } else {
                    actions += "orphan part 격리 실패: ${orphan.name}"
                }
            }
    }

    private fun validatePayload(file: File, extension: String): String? {
        if (!file.isFile) return "파일이 없습니다"
        if (file.length() <= 0L) return "0바이트 녹음 파일"
        val normalized = extension.lowercase(Locale.US)
        if (normalized !in SUPPORTED_CONTAINERS) return "지원하지 않는 녹음 확장자"
        val header = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 4) return "녹음 파일 header가 너무 짧습니다"
        val signatureMatches = when (normalized) {
            "wav" -> read >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WAVE"
            "m4a" -> read >= 8 && header.ascii(4, 4) == "ftyp"
            "aac" -> (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xF0) == 0xF0
            else -> false
        }
        return if (signatureMatches) null else "확장자와 container signature가 일치하지 않습니다"
    }

    private fun quarantine(sourceFile: File, reason: String): File? {
        val source = canonicalOrNull(sourceFile) ?: return null
        if (!isManaged(source) || isQuarantinePath(source) || !source.exists()) return null
        val sessionName = source.parentFile?.name?.takeIf { RecordingStateReducer.isValidSessionId(it) } ?: "unknown"
        val targetDir = File(quarantineRoot, sessionName).apply { mkdirs() }
        val safeReason = reason.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            .ifBlank { "invalid" }
        val target = File(targetDir, "${nowMs()}_${safeReason}_${source.name}")
        return if (mover(source, target)) target.canonicalFile else null
    }

    private fun isQuarantinePath(file: File): Boolean = file.path.startsWith(quarantineRoot.path + File.separator)

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.ascii(start: Int, length: Int): String =
        copyOfRange(start, start + length).toString(Charsets.US_ASCII)

    private companion object {
        const val RECORDINGS_DIR = "recordings"
        const val QUARANTINE_DIR = "quarantine"
        const val PART_SUFFIX = ".part"
        val SUPPORTED_CONTAINERS = setOf("m4a", "aac", "wav")
    }
}
