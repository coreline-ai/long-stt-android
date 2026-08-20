package com.stt.benchmark.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceReader
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import java.io.IOException

/**
 * 네트워크가 가능한 경우에만 실행되는 Drive 전송기.
 * UI resolution을 절대 열지 않으며, 필요할 때는 AUTH_REQUIRED만 저장한다.
 */
class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val store = DriveUploadStore(appContext)
    private val authorization = GoogleDriveAuthorizationGateway(appContext)
    private val exportFiles = DriveExportFileFactory(appContext)
    private val drive = GoogleDriveRestClient()
    private val transcriptionStore = TranscriptionSessionStore(appContext)
    private val groupStore = RecordingTranscriptionGroupStore(appContext)
    private val summaryStore = SummarySessionStore(appContext)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        if (!jobId.matches(DriveUploadJob.SAFE_ID)) return Result.failure()
        val initial = store.find(jobId) ?: return Result.success()
        if (!initial.hasPendingArtifact) return Result.success()

        store.markPreparing(jobId)
        var job = store.find(jobId) ?: return Result.success()
        val document = loadDocument(job) ?: run {
            store.markFailed(jobId, "SOURCE_UNAVAILABLE")
            return Result.failure()
        }
        var token = when (val outcome = authorization.authorize()) {
            is GoogleDriveAuthorizationGateway.Outcome.Granted -> outcome.token
            is GoogleDriveAuthorizationGateway.Outcome.NeedsUserAction,
            is GoogleDriveAuthorizationGateway.Outcome.Failure,
            -> {
                store.markAuthRequired(jobId)
                return Result.success()
            }
        }

        try {
            job.pendingArtifacts().forEach { artifact ->
                token = uploadArtifact(jobId, artifact, document, token)
                job = store.find(jobId) ?: return Result.success()
            }
            return Result.success()
        } catch (failure: DriveHttpException) {
            return when {
                failure.statusCode == HTTP_UNAUTHORIZED -> {
                    authorization.clearToken(token)
                    store.markAuthRequired(jobId)
                    Result.success()
                }

                failure.statusCode == HTTP_TOO_MANY_REQUESTS || failure.statusCode >= HTTP_SERVER_ERROR -> {
                    store.markRetry(jobId, "HTTP_${failure.statusCode}")
                    Result.retry()
                }

                else -> {
                    store.markFailed(jobId, "HTTP_${failure.statusCode}")
                    Result.failure()
                }
            }
        } catch (_: IOException) {
            store.markRetry(jobId, "NETWORK")
            return Result.retry()
        } catch (_: SecurityException) {
            store.markFailed(jobId, "SECURITY")
            return Result.failure()
        } catch (_: IllegalArgumentException) {
            store.markFailed(jobId, "INVALID_SOURCE")
            return Result.failure()
        } catch (_: Exception) {
            store.markFailed(jobId, "UPLOAD_ERROR")
            return Result.failure()
        }
    }

    private suspend fun uploadArtifact(
        jobId: String,
        artifact: DriveArtifact,
        document: TranscriptSourceDocument,
        initialToken: String,
    ): String {
        var token = initialToken
        repeat(MAX_SESSION_ATTEMPTS) {
            val job = store.find(jobId) ?: return token
            val root = drive.ensureRootFolder(token)
            val folder = drive.ensureExportFolder(token, root.id, job.exportId, job.createdAtMs)
            store.markFolder(jobId, folder.id)
            val alreadyUploaded = drive.findArtifact(token, job.exportId, artifact, folder.id)
            if (alreadyUploaded != null) {
                store.markArtifactCompleted(jobId, artifact, alreadyUploaded.id)
                return token
            }
            val export = when (artifact) {
                DriveArtifact.TRANSCRIPT -> exportFiles.createTranscript(job, document)
                DriveArtifact.SUMMARY -> loadSummary(job)?.let { summary -> exportFiles.createSummary(job, summary) }
                    ?: run {
                        store.markFailed(jobId, "SUMMARY_UNAVAILABLE")
                        throw IllegalArgumentException("summary unavailable")
                    }
            }
            try {
                val remoteFile = drive.uploadResumable(
                    accessToken = token,
                    file = export.file,
                    folderId = folder.id,
                    exportId = job.exportId,
                    artifact = artifact,
                ) { sent, total ->
                    store.markUploading(jobId, artifact, sent, total)
                }
                store.markArtifactCompleted(jobId, artifact, remoteFile.id)
                return token
            } catch (failure: DriveHttpException) {
                if (failure.statusCode == HTTP_UNAUTHORIZED) {
                    authorization.clearToken(token)
                    token = when (val refreshed = authorization.authorize()) {
                        is GoogleDriveAuthorizationGateway.Outcome.Granted -> refreshed.token
                        is GoogleDriveAuthorizationGateway.Outcome.NeedsUserAction,
                        is GoogleDriveAuthorizationGateway.Outcome.Failure,
                        -> {
                            store.markAuthRequired(jobId)
                            return token
                        }
                    }
                    return@repeat
                }
                // Google Drive resumable session의 data PUT 4xx는 기존 session을 재사용하지 않는다.
                if (failure.phase == DriveRequestPhase.UPLOAD && failure.statusCode in 400..499) return@repeat
                throw failure
            }
        }
        throw IOException("Drive resumable upload retry exhausted")
    }

    private fun loadDocument(job: DriveUploadJob): TranscriptSourceDocument? = TranscriptSourceReader.resolve(
        source = job.source,
        sessions = transcriptionStore.listAll(),
        groups = groupStore.listAll(),
    )

    private fun loadSummary(job: DriveUploadJob): String? = summaryStore.find(
        SummaryRequestPolicy.Source(job.source.type.toSummarySourceType(), job.source.id),
    )?.summary

    private fun DriveUploadJob.pendingArtifacts(): List<DriveArtifact> = requestedArtifacts
        .filterNot { it in completedArtifacts }
        .sortedBy(DriveArtifact::ordinal)

    companion object {
        const val KEY_JOB_ID = "drive_upload_job_id"
        private const val MAX_SESSION_ATTEMPTS = 3
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
