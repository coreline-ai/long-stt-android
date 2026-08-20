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

    fun retry(jobId: String): Boolean {
        val job = store.find(jobId) ?: return false
        if (!job.hasPendingArtifact) return false
        enqueue(job)
        return true
    }

    private fun enqueue(job: DriveUploadJob) {
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
        // 같은 결과의 작업은 순차 실행한다. 완료 callback이 중복되어도 원문 payload는 추가되지 않는다.
        workManager.enqueueUniqueWork(workName(job.jobId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_TAG = "long_stt_drive_upload"
        private const val MIN_BACKOFF_SECONDS = 10L

        fun workName(jobId: String): String = "long_stt_drive_$jobId"
    }
}
