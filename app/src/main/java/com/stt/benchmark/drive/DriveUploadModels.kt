package com.stt.benchmark.drive

import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType

/** Google Drive 직접 업로드에만 쓰는 최소 상태 계약. 원문과 token은 이 모델에 넣지 않는다. */
enum class DriveAutoUploadMode {
    OFF,
    TRANSCRIPT_ONLY,
    TRANSCRIPT_AND_SUMMARY,
    ;

    fun accepts(artifact: DriveArtifact): Boolean = when (this) {
        OFF -> false
        TRANSCRIPT_ONLY -> artifact == DriveArtifact.TRANSCRIPT
        TRANSCRIPT_AND_SUMMARY -> true
    }
}

enum class DriveArtifact { TRANSCRIPT, SUMMARY }

enum class DriveUploadStatus {
    QUEUED,
    PREPARING,
    UPLOADING,
    PARTIAL_COMPLETED,
    COMPLETED,
    AUTH_REQUIRED,
    RETRY_WAIT,
    FAILED,
    CANCELLED,
}

data class DriveUploadSettings(
    val connected: Boolean = false,
    val autoUploadMode: DriveAutoUploadMode = DriveAutoUploadMode.OFF,
    val autoEnabledAtMs: Long = 0L,
    /** 연결 해제 뒤 이전 Worker가 전송을 재개하지 못하게 하는 로컬 세대값. */
    val connectionGeneration: Long = 0L,
)

data class DriveUploadJob(
    val jobId: String,
    val exportId: String,
    val source: TranscriptSourceRef,
    val requestedArtifacts: Set<DriveArtifact>,
    /** 사용자가 상세 화면에서 직접 선택한 artifact. */
    val manualArtifacts: Set<DriveArtifact> = requestedArtifacts,
    /** 자동 업로드 opt-in으로 추가된 artifact. 자동 OFF 때 미완료 항목만 제거한다. */
    val automaticArtifacts: Set<DriveArtifact> = emptySet(),
    val completedArtifacts: Set<DriveArtifact> = emptySet(),
    val status: DriveUploadStatus = DriveUploadStatus.QUEUED,
    val activeArtifact: DriveArtifact? = null,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val driveFolderId: String = "",
    /** 멱등 재개 및 복구 판정용 Drive 파일 ID. UI나 로그로 노출하지 않는다. */
    val driveFileIds: Map<DriveArtifact, String> = emptyMap(),
    val retryCount: Int = 0,
    val errorCode: String = "",
    /** 이 작업을 만든 Drive 연결 세대. token·계정 식별자는 저장하지 않는다. */
    val connectionGeneration: Long = 0L,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    init {
        require(jobId.matches(SAFE_ID)) { "invalid Drive upload job" }
        require(exportId.matches(SAFE_ID)) { "invalid Drive export" }
        require(source.id.matches(SAFE_ID)) { "invalid Drive source" }
        require(requestedArtifacts.isNotEmpty() || status == DriveUploadStatus.CANCELLED) {
            "Drive upload needs an artifact"
        }
        require(manualArtifacts.all { it in requestedArtifacts }) { "invalid manual artifact" }
        require(automaticArtifacts.all { it in requestedArtifacts }) { "invalid automatic artifact" }
        require(requestedArtifacts.all { it in manualArtifacts || it in automaticArtifacts }) {
            "Drive artifact has no upload intent"
        }
        require(completedArtifacts.all { it in requestedArtifacts }) { "invalid completed artifact" }
        require(driveFileIds.keys.all { it in requestedArtifacts }) { "invalid Drive artifact id" }
    }

    val hasPendingArtifact: Boolean get() = requestedArtifacts.any { it !in completedArtifacts }
    val hasManualPendingArtifact: Boolean get() = manualArtifacts.any { it !in completedArtifacts }
    val hasAutomaticPendingArtifact: Boolean get() = automaticArtifacts.any { it !in completedArtifacts }
    val isComplete: Boolean get() = !hasPendingArtifact
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            if (isComplete) 1f else 0f
        }

    companion object {
        internal val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}

data class DriveUploadSnapshot(
    val settings: DriveUploadSettings = DriveUploadSettings(),
    val jobs: List<DriveUploadJob> = emptyList(),
) {
    fun latestFor(source: TranscriptSourceRef): DriveUploadJob? = jobs
        .asSequence()
        .filter { it.source == source }
        .maxByOrNull(DriveUploadJob::updatedAtMs)

    fun needsReauthorization(): Boolean = jobs.any { it.status == DriveUploadStatus.AUTH_REQUIRED }
}

internal fun DriveArtifact.fileName(): String = when (this) {
    DriveArtifact.TRANSCRIPT -> "transcript.txt"
    DriveArtifact.SUMMARY -> "summary.txt"
}

internal fun TranscriptSourceType.toSummarySourceType(): com.stt.benchmark.summary.SummarySessionStore.SourceType = when (this) {
    TranscriptSourceType.TRANSCRIPTION_SESSION -> com.stt.benchmark.summary.SummarySessionStore.SourceType.TRANSCRIPTION_SESSION
    TranscriptSourceType.RECORDING_GROUP -> com.stt.benchmark.summary.SummarySessionStore.SourceType.RECORDING_GROUP
}
