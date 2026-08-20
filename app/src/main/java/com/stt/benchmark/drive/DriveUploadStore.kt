package com.stt.benchmark.drive

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** WorkManager에서 취소·교체할 작업의 최소 목록. */
data class DriveWorkCancellation(
    val cancelJobIds: Set<String> = emptySet(),
    val reenqueueJobs: List<DriveUploadJob> = emptyList(),
)

/**
 * Drive 작업 상태의 private, 원자 저장소.
 * WorkManager에는 여기서 발급한 jobId만 넘기며 원문·경로·token은 저장하지 않는다.
 */
class DriveUploadStore(context: Context) {
    private val file = File(context.filesDir, "drive_uploads/state.json")

    fun snapshot(): DriveUploadSnapshot = synchronized(LOCK) { read() }

    /** 사용자가 상세 화면에서 명시적으로 고른 파일을 등록한다. */
    fun enqueue(
        source: TranscriptSourceRef,
        artifacts: Set<DriveArtifact>,
        nowMs: Long = System.currentTimeMillis(),
    ): DriveUploadJob = synchronized(LOCK) {
        val current = read()
        enqueueLocked(current, source, artifacts, UploadIntent.MANUAL, nowMs)
    }

    /** 완료 callback에서만 쓰는 opt-in 자동 업로드 진입점. */
    fun enqueueAutomatic(
        source: TranscriptSourceRef,
        artifact: DriveArtifact,
        completedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): DriveUploadJob? = synchronized(LOCK) {
        val current = read()
        val settings = current.settings
        if (
            !settings.connected ||
            !settings.autoUploadMode.accepts(artifact) ||
            completedAtMs < settings.autoEnabledAtMs
        ) {
            return null
        }
        enqueueLocked(current, source, setOf(artifact), UploadIntent.AUTOMATIC, nowMs)
    }

    fun updateSettings(
        connected: Boolean = snapshot().settings.connected,
        autoUploadMode: DriveAutoUploadMode = snapshot().settings.autoUploadMode,
        autoEnabledAtMs: Long = snapshot().settings.autoEnabledAtMs,
    ) = synchronized(LOCK) {
        val current = read()
        write(
            current.copy(
                settings = current.settings.copy(
                    connected = connected,
                    autoUploadMode = autoUploadMode,
                    autoEnabledAtMs = autoEnabledAtMs,
                ),
            ),
        )
    }

    /** 연결 해제만 세대를 증가시킨다. 재연결은 새 세대에서 새 작업만 실행한다. */
    fun markConnected() = synchronized(LOCK) {
        val current = read()
        write(current.copy(settings = current.settings.copy(connected = true)))
    }

    /**
     * 자동 OFF는 자동 opt-in으로 생긴 미완료 artifact만 멈춘다.
     * 같은 job 안의 수동 artifact는 보존하고 WorkManager를 교체해 계속 실행한다.
     */
    fun setAutoUploadMode(
        mode: DriveAutoUploadMode,
        nowMs: Long = System.currentTimeMillis(),
    ): DriveWorkCancellation = synchronized(LOCK) {
        val current = read()
        val settings = current.settings.copy(
            autoUploadMode = mode,
            autoEnabledAtMs = if (mode == DriveAutoUploadMode.OFF) 0L else nowMs,
        )
        if (mode != DriveAutoUploadMode.OFF) {
            write(current.copy(settings = settings))
            return DriveWorkCancellation()
        }

        val changed = current.jobs.map { job ->
            if (!job.hasAutomaticPendingArtifact || job.connectionGeneration != current.settings.connectionGeneration) {
                job
            } else {
                val completedAuto = job.automaticArtifacts.intersect(job.completedArtifacts)
                val requested = job.manualArtifacts + completedAuto
                val completed = job.completedArtifacts.intersect(requested)
                job.copy(
                    requestedArtifacts = requested,
                    automaticArtifacts = completedAuto,
                    completedArtifacts = completed,
                    status = statusAfterIntentChange(requested, completed),
                    activeArtifact = null,
                    transferredBytes = 0L,
                    totalBytes = 0L,
                    errorCode = "",
                    updatedAtMs = nowMs,
                )
            }
        }
        val cancelled = current.jobs.zip(changed)
            .filter { (before, after) -> before != after }
            .map { (before, _) -> before.jobId }
            .toSet()
        val reenqueue = changed.filter { job ->
            job.jobId in cancelled && job.hasManualPendingArtifact && job.status == DriveUploadStatus.QUEUED
        }
        write(DriveUploadSnapshot(settings = settings, jobs = changed))
        DriveWorkCancellation(cancelJobIds = cancelled, reenqueueJobs = reenqueue)
    }

    /**
     * 연결 해제는 대기·실행 중인 모든 작업을 취소하고 세대를 올린다.
     * 기존 Drive 파일은 절대 삭제하지 않으며, 과거 job은 UI 이력으로만 남긴다.
     */
    fun clearConnection(nowMs: Long = System.currentTimeMillis()): DriveWorkCancellation = synchronized(LOCK) {
        val current = read()
        val cancelled = current.jobs.filter { job ->
            job.hasPendingArtifact && job.status != DriveUploadStatus.CANCELLED
        }
        val cancelledIds = cancelled.map(DriveUploadJob::jobId).toSet()
        val jobs = current.jobs.map { job ->
            if (job.jobId !in cancelledIds) {
                job
            } else {
                job.copy(
                    status = DriveUploadStatus.CANCELLED,
                    activeArtifact = null,
                    transferredBytes = 0L,
                    totalBytes = 0L,
                    errorCode = "",
                    updatedAtMs = nowMs,
                )
            }
        }
        val nextGeneration = (current.settings.connectionGeneration + 1L).coerceAtLeast(1L)
        write(
            DriveUploadSnapshot(
                settings = DriveUploadSettings(connectionGeneration = nextGeneration),
                jobs = jobs,
            ),
        )
        DriveWorkCancellation(cancelJobIds = cancelledIds)
    }

    /** Worker가 외부 동작 전 매번 확인하는 fail-closed 작업 조회다. */
    fun runnableJob(jobId: String): DriveUploadJob? = synchronized(LOCK) {
        val current = read()
        current.jobs.firstOrNull { job -> job.jobId == jobId && current.isRunnable(job) }
    }

    fun markPreparing(jobId: String, nowMs: Long = System.currentTimeMillis()) = updateRunnableJob(jobId) {
        it.copy(
            status = DriveUploadStatus.PREPARING,
            activeArtifact = null,
            transferredBytes = 0L,
            totalBytes = 0L,
            errorCode = "",
            updatedAtMs = nowMs,
        )
    }

    fun markUploading(
        jobId: String,
        artifact: DriveArtifact,
        transferredBytes: Long,
        totalBytes: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) = updateRunnableJob(jobId) { job ->
        if (artifact !in job.requestedArtifacts || artifact in job.completedArtifacts) {
            job
        } else {
            job.copy(
                status = DriveUploadStatus.UPLOADING,
                activeArtifact = artifact,
                transferredBytes = transferredBytes.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                errorCode = "",
                updatedAtMs = nowMs,
            )
        }
    }

    fun markFolder(jobId: String, folderId: String, nowMs: Long = System.currentTimeMillis()) = updateRunnableJob(jobId) {
        it.copy(driveFolderId = folderId.take(200), updatedAtMs = nowMs)
    }

    fun markArtifactCompleted(
        jobId: String,
        artifact: DriveArtifact,
        driveFileId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) = updateRunnableJob(jobId) { job ->
        if (artifact !in job.requestedArtifacts || artifact in job.completedArtifacts) {
            job
        } else {
            val completed = job.completedArtifacts + artifact
            job.copy(
                completedArtifacts = completed,
                driveFileIds = job.driveFileIds + (artifact to driveFileId.take(200)),
                status = if (job.requestedArtifacts.all { requested -> requested in completed }) {
                    DriveUploadStatus.COMPLETED
                } else {
                    DriveUploadStatus.PARTIAL_COMPLETED
                },
                activeArtifact = null,
                transferredBytes = 0L,
                totalBytes = 0L,
                errorCode = "",
                updatedAtMs = nowMs,
            )
        }
    }

    fun markAuthRequired(jobId: String, nowMs: Long = System.currentTimeMillis()) = updateRunnableJob(jobId) {
        it.copy(
            status = DriveUploadStatus.AUTH_REQUIRED,
            activeArtifact = null,
            errorCode = "AUTH_REQUIRED",
            updatedAtMs = nowMs,
        )
    }

    fun markRetry(jobId: String, errorCode: String, nowMs: Long = System.currentTimeMillis()) = updateRunnableJob(jobId) {
        it.copy(
            status = DriveUploadStatus.RETRY_WAIT,
            activeArtifact = null,
            retryCount = it.retryCount + 1,
            errorCode = errorCode.safeErrorCode(),
            updatedAtMs = nowMs,
        )
    }

    fun markFailed(jobId: String, errorCode: String, nowMs: Long = System.currentTimeMillis()) = updateRunnableJob(jobId) {
        it.copy(
            status = if (it.completedArtifacts.isEmpty()) {
                DriveUploadStatus.FAILED
            } else {
                DriveUploadStatus.PARTIAL_COMPLETED
            },
            activeArtifact = null,
            errorCode = errorCode.safeErrorCode(),
            updatedAtMs = nowMs,
        )
    }

    fun find(jobId: String): DriveUploadJob? = snapshot().jobs.firstOrNull { it.jobId == jobId }

    private fun enqueueLocked(
        current: DriveUploadSnapshot,
        source: TranscriptSourceRef,
        artifacts: Set<DriveArtifact>,
        intent: UploadIntent,
        nowMs: Long,
    ): DriveUploadJob {
        require(source.id.matches(DriveUploadJob.SAFE_ID)) { "invalid Drive source" }
        require(artifacts.isNotEmpty()) { "Drive upload needs an artifact" }
        val previous = current.jobs
            .asSequence()
            .filter { it.source == source && it.connectionGeneration == current.settings.connectionGeneration }
            .maxByOrNull(DriveUploadJob::updatedAtMs)
        val job = if (previous == null) {
            createJob(current.settings.connectionGeneration, source, artifacts, intent, nowMs)
        } else {
            val manual = previous.manualArtifacts + if (intent == UploadIntent.MANUAL) artifacts else emptySet()
            val automatic = previous.automaticArtifacts + if (intent == UploadIntent.AUTOMATIC) artifacts else emptySet()
            val requested = manual + automatic
            val completed = previous.completedArtifacts.intersect(requested)
            val canKeepActiveState = previous.status in ACTIVE_STATUSES && previous.hasPendingArtifact
            previous.copy(
                requestedArtifacts = requested,
                manualArtifacts = manual,
                automaticArtifacts = automatic,
                completedArtifacts = completed,
                status = if (requested.all { it in completed }) DriveUploadStatus.COMPLETED else if (canKeepActiveState) previous.status else DriveUploadStatus.QUEUED,
                activeArtifact = if (canKeepActiveState) previous.activeArtifact else null,
                transferredBytes = if (canKeepActiveState) previous.transferredBytes else 0L,
                totalBytes = if (canKeepActiveState) previous.totalBytes else 0L,
                errorCode = if (canKeepActiveState) previous.errorCode else "",
                updatedAtMs = nowMs,
            )
        }
        write(current.copy(jobs = current.jobs.replace(job)))
        return job
    }

    private fun createJob(
        connectionGeneration: Long,
        source: TranscriptSourceRef,
        artifacts: Set<DriveArtifact>,
        intent: UploadIntent,
        nowMs: Long,
    ): DriveUploadJob = DriveUploadJob(
        jobId = "drive_${UUID.randomUUID().toString().replace("-", "")}",
        exportId = "export_${UUID.randomUUID().toString().replace("-", "")}",
        source = source,
        requestedArtifacts = artifacts,
        manualArtifacts = if (intent == UploadIntent.MANUAL) artifacts else emptySet(),
        automaticArtifacts = if (intent == UploadIntent.AUTOMATIC) artifacts else emptySet(),
        connectionGeneration = connectionGeneration,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )

    private fun statusAfterIntentChange(
        requested: Set<DriveArtifact>,
        completed: Set<DriveArtifact>,
    ): DriveUploadStatus = when {
        requested.isEmpty() -> DriveUploadStatus.CANCELLED
        requested.all { it in completed } -> DriveUploadStatus.COMPLETED
        else -> DriveUploadStatus.QUEUED
    }

    /**
     * Worker 시작 가능 여부와 별개로 현재 연결 세대의 상태 전이는 허용한다.
     * 연결 해제/취소 뒤의 stale Worker는 세대 또는 CANCELLED 상태에서 차단된다.
     */
    private fun updateRunnableJob(jobId: String, transform: (DriveUploadJob) -> DriveUploadJob) = synchronized(LOCK) {
        val current = read()
        val target = current.jobs.firstOrNull { it.jobId == jobId } ?: return
        if (!current.isCurrent(target)) return
        write(current.copy(jobs = current.jobs.replace(transform(target))))
    }

    private fun DriveUploadSnapshot.isCurrent(job: DriveUploadJob): Boolean =
        job.connectionGeneration == settings.connectionGeneration &&
            job.status != DriveUploadStatus.CANCELLED

    private fun DriveUploadSnapshot.isRunnable(job: DriveUploadJob): Boolean =
        settings.connected && isCurrent(job) &&
            job.hasPendingArtifact &&
            job.status !in TERMINAL_OR_AUTH_STATUSES

    private fun read(): DriveUploadSnapshot {
        if (!file.exists()) return DriveUploadSnapshot()
        return runCatching {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { input ->
                val json = JSONObject(input.readText())
                val settingsJson = json.optJSONObject("settings") ?: JSONObject()
                val settings = DriveUploadSettings(
                    connected = settingsJson.optBoolean("connected", false),
                    autoUploadMode = settingsJson.optString("autoUploadMode")
                        .let { name -> DriveAutoUploadMode.entries.firstOrNull { it.name == name } }
                        ?: DriveAutoUploadMode.OFF,
                    autoEnabledAtMs = settingsJson.optLong("autoEnabledAtMs", 0L).coerceAtLeast(0L),
                    connectionGeneration = settingsJson.optLong("connectionGeneration", 0L).coerceAtLeast(0L),
                )
                val jobsArray = json.optJSONArray("jobs") ?: JSONArray()
                val jobs = (0 until jobsArray.length()).mapNotNull { index ->
                    jobsArray.optJSONObject(index)?.let(::jobFromJson)
                }
                DriveUploadSnapshot(settings, jobs)
            }
        }.getOrElse { DriveUploadSnapshot() }
    }

    private fun write(snapshot: DriveUploadSnapshot) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(toJson(snapshot).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    private fun toJson(snapshot: DriveUploadSnapshot): JSONObject = JSONObject().apply {
        put("version", SCHEMA_VERSION)
        put("settings", JSONObject().apply {
            put("connected", snapshot.settings.connected)
            put("autoUploadMode", snapshot.settings.autoUploadMode.name)
            put("autoEnabledAtMs", snapshot.settings.autoEnabledAtMs)
            put("connectionGeneration", snapshot.settings.connectionGeneration)
        })
        put("jobs", JSONArray().apply { snapshot.jobs.forEach { put(jobToJson(it)) } })
    }

    private fun jobToJson(job: DriveUploadJob): JSONObject = JSONObject().apply {
        put("jobId", job.jobId)
        put("exportId", job.exportId)
        put("sourceType", job.source.type.name)
        put("sourceId", job.source.id)
        put("requestedArtifacts", JSONArray(job.requestedArtifacts.map(DriveArtifact::name)))
        put("manualArtifacts", JSONArray(job.manualArtifacts.map(DriveArtifact::name)))
        put("automaticArtifacts", JSONArray(job.automaticArtifacts.map(DriveArtifact::name)))
        put("completedArtifacts", JSONArray(job.completedArtifacts.map(DriveArtifact::name)))
        put("status", job.status.name)
        put("activeArtifact", job.activeArtifact?.name.orEmpty())
        put("transferredBytes", job.transferredBytes)
        put("totalBytes", job.totalBytes)
        put("driveFolderId", job.driveFolderId)
        put("driveFileIds", JSONObject().apply {
            job.driveFileIds.forEach { (artifact, fileId) -> put(artifact.name, fileId) }
        })
        put("retryCount", job.retryCount)
        put("errorCode", job.errorCode)
        put("connectionGeneration", job.connectionGeneration)
        put("createdAtMs", job.createdAtMs)
        put("updatedAtMs", job.updatedAtMs)
    }

    private fun jobFromJson(json: JSONObject): DriveUploadJob? = runCatching {
        val requested = json.optJSONArray("requestedArtifacts").toDriveArtifactSet()
        val status = DriveUploadStatus.valueOf(json.getString("status"))
        val manual = json.optJSONArray("manualArtifacts")?.toDriveArtifactSet() ?: requested
        val automatic = json.optJSONArray("automaticArtifacts")?.toDriveArtifactSet().orEmpty()
        DriveUploadJob(
            jobId = json.getString("jobId"),
            exportId = json.getString("exportId"),
            source = TranscriptSourceRef(
                type = TranscriptSourceType.valueOf(json.getString("sourceType")),
                id = json.getString("sourceId"),
            ),
            requestedArtifacts = requested,
            manualArtifacts = manual,
            automaticArtifacts = automatic,
            completedArtifacts = json.optJSONArray("completedArtifacts").toDriveArtifactSet(),
            status = status,
            activeArtifact = json.optString("activeArtifact").takeIf(String::isNotBlank)
                ?.let(DriveArtifact::valueOf),
            transferredBytes = json.optLong("transferredBytes", 0L).coerceAtLeast(0L),
            totalBytes = json.optLong("totalBytes", 0L).coerceAtLeast(0L),
            driveFolderId = json.optString("driveFolderId").take(200),
            driveFileIds = json.optJSONObject("driveFileIds")?.let { fileIds ->
                buildMap {
                    DriveArtifact.entries.forEach { artifact ->
                        fileIds.optString(artifact.name).takeIf(String::isNotBlank)?.let { fileId ->
                            put(artifact, fileId.take(200))
                        }
                    }
                }
            }.orEmpty(),
            retryCount = json.optInt("retryCount", 0).coerceAtLeast(0),
            errorCode = json.optString("errorCode").safeErrorCode(),
            connectionGeneration = json.optLong("connectionGeneration", 0L).coerceAtLeast(0L),
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
        )
    }.getOrNull()

    private fun JSONArray?.toDriveArtifactSet(): Set<DriveArtifact> = buildSet {
        val array = this@toDriveArtifactSet ?: return@buildSet
        for (index in 0 until array.length()) {
            DriveArtifact.entries.firstOrNull { it.name == array.optString(index) }?.let(::add)
        }
    }

    private fun List<DriveUploadJob>.replace(job: DriveUploadJob): List<DriveUploadJob> =
        if (any { it.jobId == job.jobId }) {
            map { if (it.jobId == job.jobId) job else it }
        } else {
            this + job
        }

    private fun String.safeErrorCode(): String = take(64).filter { it.isLetterOrDigit() || it == '_' }

    private enum class UploadIntent { MANUAL, AUTOMATIC }

    private companion object {
        const val SCHEMA_VERSION = 2
        val LOCK = Any()
        val ACTIVE_STATUSES = setOf(
            DriveUploadStatus.QUEUED,
            DriveUploadStatus.PREPARING,
            DriveUploadStatus.UPLOADING,
            DriveUploadStatus.RETRY_WAIT,
        )
        val TERMINAL_OR_AUTH_STATUSES = setOf(
            DriveUploadStatus.COMPLETED,
            DriveUploadStatus.AUTH_REQUIRED,
            DriveUploadStatus.CANCELLED,
        )
    }
}
