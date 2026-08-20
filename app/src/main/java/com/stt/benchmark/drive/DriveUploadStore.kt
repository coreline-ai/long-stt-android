package com.stt.benchmark.drive

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Drive 작업 상태의 private, 원자 저장소.
 * WorkManager에는 여기서 발급한 jobId만 넘기며 원문·경로·token은 저장하지 않는다.
 */
class DriveUploadStore(context: Context) {
    private val file = File(context.filesDir, "drive_uploads/state.json")

    fun snapshot(): DriveUploadSnapshot = synchronized(LOCK) { read() }

    fun enqueue(
        source: TranscriptSourceRef,
        artifacts: Set<DriveArtifact>,
        nowMs: Long = System.currentTimeMillis(),
    ): DriveUploadJob = synchronized(LOCK) {
        require(source.id.matches(DriveUploadJob.SAFE_ID)) { "invalid Drive source" }
        require(artifacts.isNotEmpty()) { "Drive upload needs an artifact" }
        val current = read()
        val previous = current.latestFor(source)
        val job = if (previous == null) {
            DriveUploadJob(
                jobId = "drive_${UUID.randomUUID().toString().replace("-", "")}",
                exportId = "export_${UUID.randomUUID().toString().replace("-", "")}",
                source = source,
                requestedArtifacts = artifacts,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        } else {
            previous.copy(
                requestedArtifacts = previous.requestedArtifacts + artifacts,
                status = if ((previous.requestedArtifacts + artifacts).all { it in previous.completedArtifacts }) {
                    DriveUploadStatus.COMPLETED
                } else {
                    DriveUploadStatus.QUEUED
                },
                activeArtifact = null,
                transferredBytes = 0L,
                totalBytes = 0L,
                errorCode = "",
                updatedAtMs = nowMs,
            )
        }
        write(current.copy(jobs = current.jobs.replace(job)))
        job
    }

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
        enqueueLocked(current, source, setOf(artifact), nowMs)
    }

    fun updateSettings(
        connected: Boolean = snapshot().settings.connected,
        autoUploadMode: DriveAutoUploadMode = snapshot().settings.autoUploadMode,
        autoEnabledAtMs: Long = snapshot().settings.autoEnabledAtMs,
    ) = synchronized(LOCK) {
        val current = read()
        write(current.copy(settings = DriveUploadSettings(connected, autoUploadMode, autoEnabledAtMs)))
    }

    fun markConnected() = synchronized(LOCK) {
        val current = read()
        val settings = current.settings.copy(connected = true)
        write(current.copy(settings = settings))
    }

    fun setAutoUploadMode(mode: DriveAutoUploadMode, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        val current = read()
        val settings = current.settings.copy(
            autoUploadMode = mode,
            autoEnabledAtMs = if (mode == DriveAutoUploadMode.OFF) 0L else nowMs,
        )
        write(current.copy(settings = settings))
    }

    fun clearConnection() = synchronized(LOCK) {
        val current = read()
        write(current.copy(settings = DriveUploadSettings()))
    }

    fun markPreparing(jobId: String, nowMs: Long = System.currentTimeMillis()) = updateJob(jobId) {
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
    ) = updateJob(jobId) {
        it.copy(
            status = DriveUploadStatus.UPLOADING,
            activeArtifact = artifact,
            transferredBytes = transferredBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            errorCode = "",
            updatedAtMs = nowMs,
        )
    }

    fun markFolder(jobId: String, folderId: String, nowMs: Long = System.currentTimeMillis()) = updateJob(jobId) {
        it.copy(driveFolderId = folderId.take(200), updatedAtMs = nowMs)
    }

    fun markArtifactCompleted(
        jobId: String,
        artifact: DriveArtifact,
        driveFileId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) = updateJob(jobId) {
        val completed = it.completedArtifacts + artifact
        it.copy(
            completedArtifacts = completed,
            driveFileIds = it.driveFileIds + (artifact to driveFileId.take(200)),
            status = if (it.requestedArtifacts.all { requested -> requested in completed }) {
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

    fun markAuthRequired(jobId: String, nowMs: Long = System.currentTimeMillis()) = updateJob(jobId) {
        it.copy(
            status = DriveUploadStatus.AUTH_REQUIRED,
            activeArtifact = null,
            errorCode = "AUTH_REQUIRED",
            updatedAtMs = nowMs,
        )
    }

    fun markRetry(jobId: String, errorCode: String, nowMs: Long = System.currentTimeMillis()) = updateJob(jobId) {
        it.copy(
            status = DriveUploadStatus.RETRY_WAIT,
            activeArtifact = null,
            retryCount = it.retryCount + 1,
            errorCode = errorCode.safeErrorCode(),
            updatedAtMs = nowMs,
        )
    }

    fun markFailed(jobId: String, errorCode: String, nowMs: Long = System.currentTimeMillis()) = updateJob(jobId) {
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

    private fun updateJob(jobId: String, transform: (DriveUploadJob) -> DriveUploadJob) = synchronized(LOCK) {
        val current = read()
        val target = current.jobs.firstOrNull { it.jobId == jobId } ?: return
        write(current.copy(jobs = current.jobs.replace(transform(target))))
    }

    private fun enqueueLocked(
        current: DriveUploadSnapshot,
        source: TranscriptSourceRef,
        artifacts: Set<DriveArtifact>,
        nowMs: Long,
    ): DriveUploadJob {
        require(source.id.matches(DriveUploadJob.SAFE_ID)) { "invalid Drive source" }
        val previous = current.latestFor(source)
        val job = if (previous == null) {
            DriveUploadJob(
                jobId = "drive_${UUID.randomUUID().toString().replace("-", "")}",
                exportId = "export_${UUID.randomUUID().toString().replace("-", "")}",
                source = source,
                requestedArtifacts = artifacts,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        } else {
            val requested = previous.requestedArtifacts + artifacts
            previous.copy(
                requestedArtifacts = requested,
                status = if (requested.all { it in previous.completedArtifacts }) {
                    DriveUploadStatus.COMPLETED
                } else {
                    DriveUploadStatus.QUEUED
                },
                activeArtifact = null,
                transferredBytes = 0L,
                totalBytes = 0L,
                errorCode = "",
                updatedAtMs = nowMs,
            )
        }
        write(current.copy(jobs = current.jobs.replace(job)))
        return job
    }

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
        })
        put("jobs", JSONArray().apply { snapshot.jobs.forEach { put(jobToJson(it)) } })
    }

    private fun jobToJson(job: DriveUploadJob): JSONObject = JSONObject().apply {
        put("jobId", job.jobId)
        put("exportId", job.exportId)
        put("sourceType", job.source.type.name)
        put("sourceId", job.source.id)
        put("requestedArtifacts", JSONArray(job.requestedArtifacts.map(DriveArtifact::name)))
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
        put("createdAtMs", job.createdAtMs)
        put("updatedAtMs", job.updatedAtMs)
    }

    private fun jobFromJson(json: JSONObject): DriveUploadJob? = runCatching {
        val requested = json.optJSONArray("requestedArtifacts").toDriveArtifactSet()
        val completed = json.optJSONArray("completedArtifacts").toDriveArtifactSet()
        DriveUploadJob(
            jobId = json.getString("jobId"),
            exportId = json.getString("exportId"),
            source = TranscriptSourceRef(
                type = TranscriptSourceType.valueOf(json.getString("sourceType")),
                id = json.getString("sourceId"),
            ),
            requestedArtifacts = requested,
            completedArtifacts = completed,
            status = DriveUploadStatus.valueOf(json.getString("status")),
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

    private companion object {
        const val SCHEMA_VERSION = 1
        val LOCK = Any()
    }
}
