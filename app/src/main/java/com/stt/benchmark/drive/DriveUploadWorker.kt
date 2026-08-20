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
import kotlinx.coroutines.CancellationException

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
        if (store.runnableJob(jobId) == null) return Result.success()

        store.markPreparing(jobId)
        val initial = store.runnableJob(jobId) ?: return Result.success()
        val document = loadDocument(initial) ?: run {
            store.markFailed(jobId, "SOURCE_UNAVAILABLE")
            return Result.failure()
        }
        if (store.runnableJob(jobId) == null) return Result.success()
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
            // KEEP 정책으로 같은 job의 callback을 합치므로, 매 반복마다 최신 영속 상태를 다시 읽는다.
            while (true) {
                val job = store.runnableJob(jobId) ?: return Result.success()
                val artifact = job.pendingArtifacts().firstOrNull() ?: return Result.success()
                token = uploadArtifact(jobId, artifact, document, token) ?: return Result.success()
            }
        } catch (cancelled: CancellationException) {
            // WorkManager cancel은 실패/재시도로 상태를 되돌리지 않는다.
            throw cancelled
        } catch (failure: DriveHttpException) {
            if (store.runnableJob(jobId) == null) return Result.success()
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
            if (store.runnableJob(jobId) == null) return Result.success()
            store.markRetry(jobId, "NETWORK")
            return Result.retry()
        } catch (_: SecurityException) {
            if (store.runnableJob(jobId) == null) return Result.success()
            store.markFailed(jobId, "SECURITY")
            return Result.failure()
        } catch (_: IllegalArgumentException) {
            if (store.runnableJob(jobId) == null) return Result.success()
            store.markFailed(jobId, "INVALID_SOURCE")
            return Result.failure()
        } catch (_: Exception) {
            if (store.runnableJob(jobId) == null) return Result.success()
            store.markFailed(jobId, "UPLOAD_ERROR")
            return Result.failure()
        }
    }

    /** null은 연결 해제·자동 OFF·job 취소로 외부 전송을 멈췄다는 뜻이다. */
    private suspend fun uploadArtifact(
        jobId: String,
        artifact: DriveArtifact,
        document: TranscriptSourceDocument,
        initialToken: String,
    ): String? {
        var token = initialToken
        repeat(MAX_SESSION_ATTEMPTS) {
            if (runnableJobForArtifact(jobId, artifact) == null) return null
            val root = drive.ensureRootFolder(token)
            val activeJob = runnableJobForArtifact(jobId, artifact) ?: return null
            val folder = drive.ensureExportFolder(token, root.id, activeJob.exportId, activeJob.createdAtMs)
            store.markFolder(jobId, folder.id)
            val currentJob = runnableJobForArtifact(jobId, artifact) ?: return null
            val alreadyUploaded = drive.findArtifact(token, currentJob.exportId, artifact, folder.id)
            if (alreadyUploaded != null) {
                store.markArtifactCompleted(jobId, artifact, alreadyUploaded.id)
                return token
            }
            val exportJob = runnableJobForArtifact(jobId, artifact) ?: return null
            val export = when (artifact) {
                DriveArtifact.TRANSCRIPT -> exportFiles.createTranscript(exportJob, document)
                DriveArtifact.SUMMARY -> loadSummary(exportJob)?.let { summary -> exportFiles.createSummary(exportJob, summary) }
                    ?: run {
                        store.markFailed(jobId, "SUMMARY_UNAVAILABLE")
                        throw IllegalArgumentException("summary unavailable")
                    }
            }
            if (runnableJobForArtifact(jobId, artifact) == null) return null
            try {
                val remoteFile = drive.uploadResumable(
                    accessToken = token,
                    file = export.file,
                    folderId = folder.id,
                    exportId = exportJob.exportId,
                    artifact = artifact,
                ) { sent, total ->
                    store.markUploading(jobId, artifact, sent, total)
                }
                // Work 취소가 HTTP 완료와 경합해도 취소한 artifact를 완료 상태로 되살리지 않는다.
                if (runnableJobForArtifact(jobId, artifact) == null) return null
                store.markArtifactCompleted(jobId, artifact, remoteFile.id)
                return token
            } catch (failure: DriveHttpException) {
                if (failure.statusCode == HTTP_UNAUTHORIZED) {
                    authorization.clearToken(token)
                    if (runnableJobForArtifact(jobId, artifact) == null) return null
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

    private fun runnableJobForArtifact(jobId: String, artifact: DriveArtifact): DriveUploadJob? =
        store.runnableJob(jobId)?.takeIf { job ->
            artifact in job.requestedArtifacts && artifact !in job.completedArtifacts
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
