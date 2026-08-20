package com.stt.benchmark.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stt.benchmark.data.TranscriptSourceRef
import java.util.concurrent.TimeUnit

/** Drive job 영속화와 WorkManager enqueue의 유일한 진입점. */
class DriveUploadScheduler(
    context: Context,
    private val store: DriveUploadStore = DriveUploadStore(context.applicationContext),
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun enqueueManual(source: TranscriptSourceRef, artifacts: Set<DriveArtifact>): DriveUploadJob =
        store.enqueue(source, artifacts).also(::enqueue)

    fun enqueueAutomatic(
        source: TranscriptSourceRef,
        artifact: DriveArtifact,
        completedAtMs: Long,
    ): DriveUploadJob? = store.enqueueAutomatic(source, artifact, completedAtMs)?.also(::enqueue)

    fun setAutoUploadMode(mode: DriveAutoUploadMode) {
        applyCancellation(store.setAutoUploadMode(mode))
    }

    fun clearConnection() {
        applyCancellation(store.clearConnection())
    }

    fun retry(jobId: String): Boolean {
        val job = store.runnableJob(jobId) ?: return false
        enqueue(job)
        return true
    }

    private fun applyCancellation(cancellation: DriveWorkCancellation) {
        cancellation.cancelJobIds.forEach { jobId ->
            workManager.cancelUniqueWork(workName(jobId))
        }
        // 수동 artifact가 남은 job은 이전 Worker를 교체해 자동 artifact가 다시 전송되지 않게 한다.
        cancellation.reenqueueJobs.forEach { job -> enqueue(job, ExistingWorkPolicy.REPLACE) }
    }

    private fun enqueue(job: DriveUploadJob, policy: ExistingWorkPolicy = UNIQUE_WORK_POLICY) {
        val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setInputData(workDataOf(DriveUploadWorker.KEY_JOB_ID to job.jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        // 같은 job의 중복 callback은 새 대기열을 만들지 않는다. Worker는 최신 store 상태를 반복 조회한다.
        workManager.enqueueUniqueWork(workName(job.jobId), policy, request)
    }

    companion object {
        const val WORK_TAG = "long_stt_drive_upload"
        internal val UNIQUE_WORK_POLICY = ExistingWorkPolicy.KEEP
        private const val MIN_BACKOFF_SECONDS = 10L

        fun workName(jobId: String): String = "long_stt_drive_$jobId"
    }
}
