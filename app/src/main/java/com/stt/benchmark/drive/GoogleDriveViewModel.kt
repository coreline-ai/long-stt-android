package com.stt.benchmark.drive

import android.app.Application
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stt.benchmark.data.TranscriptSourceRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DriveConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, REAUTH_REQUIRED, ERROR }

data class GoogleDriveUiState(
    val connectionPhase: DriveConnectionPhase = DriveConnectionPhase.DISCONNECTED,
    val settings: DriveUploadSettings = DriveUploadSettings(),
    val jobs: List<DriveUploadJob> = emptyList(),
    val statusMessage: String = "Google Drive를 연결하면 완료 전사와 요약을 직접 저장할 수 있습니다.",
) {
    fun latestJob(source: TranscriptSourceRef): DriveUploadJob? = jobs
        .asSequence()
        .filter { it.source == source }
        .maxByOrNull(DriveUploadJob::updatedAtMs)
}

/** Compose 화면과 WorkManager 사이의 Drive 상태·승인 launcher 경계. */
class GoogleDriveViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DriveUploadStore(application)
    private val scheduler = DriveUploadScheduler(application, store)
    private val authorization = GoogleDriveAuthorizationGateway(application)
    private val _uiState = MutableStateFlow(render(store.snapshot()))
    private val _authorizationRequests = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    private var pendingAction: PendingAction? = null

    val uiState: StateFlow<GoogleDriveUiState> = _uiState.asStateFlow()
    val authorizationRequests: SharedFlow<IntentSenderRequest> = _authorizationRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(if (_uiState.value.jobs.any { it.status in ACTIVE_JOB_STATUSES }) 1_000L else 5_000L)
            }
        }
    }

    fun refresh(message: String? = null) {
        val prior = _uiState.value
        _uiState.value = render(store.snapshot(), message ?: prior.statusMessage)
    }

    fun requestConnection() = beginAuthorization(PendingAction.CONNECT)

    fun disconnect() = beginAuthorization(PendingAction.DISCONNECT)

    fun onAuthorizationResult(intent: Intent?) {
        val action = pendingAction ?: PendingAction.CONNECT
        pendingAction = null
        viewModelScope.launch { handleOutcome(action, authorization.consumeAuthorizationResult(intent)) }
    }

    fun setAutoUploadMode(mode: DriveAutoUploadMode) {
        val settings = store.snapshot().settings
        if (!settings.connected) {
            refresh("자동 업로드를 사용하려면 먼저 Google Drive를 연결하세요.")
            return
        }
        store.setAutoUploadMode(mode)
        refresh(
            when (mode) {
                DriveAutoUploadMode.OFF -> "자동 업로드를 끕니다. 이미 완료된 Drive 파일은 유지됩니다."
                DriveAutoUploadMode.TRANSCRIPT_ONLY -> "지금 이후 완료되는 전체 전사를 자동 업로드합니다."
                DriveAutoUploadMode.TRANSCRIPT_AND_SUMMARY -> "지금 이후 완료되는 전사와 완료된 요약을 자동 업로드합니다."
            },
        )
    }

    fun enqueueManual(source: TranscriptSourceRef, artifacts: Set<DriveArtifact>) {
        if (!store.snapshot().settings.connected) {
            refresh("Google Drive를 연결한 뒤 업로드할 수 있습니다.")
            return
        }
        val job = scheduler.enqueueManual(source, artifacts)
        refresh("Google Drive 업로드를 준비합니다.")
        if (!job.hasPendingArtifact) refresh("선택한 파일은 이미 Google Drive에 저장되어 있습니다.")
    }

    fun retry(jobId: String) {
        if (!store.snapshot().settings.connected) {
            refresh("Google Drive를 다시 연결한 뒤 재시도하세요.")
            return
        }
        if (scheduler.retry(jobId)) refresh("Google Drive 업로드를 다시 시도합니다.")
    }

    private fun beginAuthorization(action: PendingAction) {
        _uiState.value = _uiState.value.copy(
            connectionPhase = DriveConnectionPhase.CONNECTING,
            statusMessage = if (action == PendingAction.CONNECT) "Google Drive 권한을 확인하고 있습니다." else "Google Drive 연결을 해제하고 있습니다.",
        )
        viewModelScope.launch { handleOutcome(action, authorization.authorize()) }
    }

    private suspend fun handleOutcome(action: PendingAction, outcome: GoogleDriveAuthorizationGateway.Outcome) {
        when (outcome) {
            is GoogleDriveAuthorizationGateway.Outcome.Granted -> when (action) {
                PendingAction.CONNECT -> {
                    store.markConnected()
                    refresh("Google Drive가 연결되었습니다. 자동 업로드는 꺼져 있습니다.")
                }

                PendingAction.DISCONNECT -> {
                    val revoked = authorization.revoke(outcome)
                    store.clearConnection()
                    refresh(
                        if (revoked) "Google Drive 권한과 자동 업로드를 해제했습니다. 기존 Drive 파일은 유지됩니다."
                        else "기기 내 Drive 연결과 자동 업로드를 해제했습니다. 기존 권한은 Google 계정 설정에서 확인할 수 있습니다.",
                    )
                }
            }

            is GoogleDriveAuthorizationGateway.Outcome.NeedsUserAction -> {
                pendingAction = action
                _authorizationRequests.emit(
                    IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                )
            }

            is GoogleDriveAuthorizationGateway.Outcome.Failure -> {
                if (action == PendingAction.DISCONNECT) {
                    store.clearConnection()
                    refresh("기기 내 Drive 연결과 자동 업로드를 해제했습니다.")
                } else {
                    _uiState.value = _uiState.value.copy(
                        connectionPhase = DriveConnectionPhase.ERROR,
                        statusMessage = "Google Drive 권한을 확인하지 못했습니다. 다시 시도하세요.",
                    )
                }
            }
        }
    }

    private fun render(snapshot: DriveUploadSnapshot, message: String = DEFAULT_MESSAGE): GoogleDriveUiState {
        val phase = when {
            snapshot.needsReauthorization() -> DriveConnectionPhase.REAUTH_REQUIRED
            snapshot.settings.connected -> DriveConnectionPhase.CONNECTED
            else -> DriveConnectionPhase.DISCONNECTED
        }
        return GoogleDriveUiState(
            connectionPhase = phase,
            settings = snapshot.settings,
            jobs = snapshot.jobs,
            statusMessage = message,
        )
    }

    private enum class PendingAction { CONNECT, DISCONNECT }

    private companion object {
        const val DEFAULT_MESSAGE = "Google Drive를 연결하면 완료 전사와 요약을 직접 저장할 수 있습니다."
        val ACTIVE_JOB_STATUSES = setOf(
            DriveUploadStatus.QUEUED,
            DriveUploadStatus.PREPARING,
            DriveUploadStatus.UPLOADING,
            DriveUploadStatus.RETRY_WAIT,
        )
    }
}
