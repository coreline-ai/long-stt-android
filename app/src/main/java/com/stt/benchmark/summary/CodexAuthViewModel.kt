package com.stt.benchmark.summary

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stt.benchmark.core.DeviceWorkCoordinator
import com.stt.benchmark.core.DeviceWorkRuntime
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CodexAuthPhase {
    SIGNED_OUT,
    AUTHORIZING,
    AUTHENTICATED,
    TESTING,
    REAUTHENTICATION_REQUIRED,
    ERROR,
}

data class CodexAuthUiState(
    val phase: CodexAuthPhase = CodexAuthPhase.SIGNED_OUT,
    val expiresAtMs: Long? = null,
    val statusMessage: String = "ChatGPT 계정을 연결하지 않았습니다.",
    val probeResponse: String? = null,
)

/** Summary output is separate from authentication state so a transcript never becomes auth state. */
data class SummaryUiState(
    val entries: List<SummarySessionStore.Entry> = emptyList(),
    val activeSourceKey: String = "",
    val statusSourceKey: String = "",
    val isRunning: Boolean = false,
    val stage: SummaryStage = SummaryStage.IDLE,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val statusMessage: String = "완료된 전사를 선택하면 외부 요약을 시작할 수 있습니다.",
) {
    val progressFraction: Float
        get() = if (totalSteps <= 0) 0f else (completedSteps.toFloat() / totalSteps).coerceIn(0f, 1f)
}

enum class SummaryStage {
    IDLE,
    PREPARING,
    SUMMARIZING,
    SYNTHESIZING,
    SAVING,
    ERROR,
}

class CodexAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = CodexSummaryAuthController(application)
    private val summaryStore = SummarySessionStore(application)
    private val _uiState = MutableStateFlow(CodexAuthUiState())
    val uiState: StateFlow<CodexAuthUiState> = _uiState.asStateFlow()
    private val _summaryUiState = MutableStateFlow(SummaryUiState())
    val summaryUiState: StateFlow<SummaryUiState> = _summaryUiState.asStateFlow()
    private var activeJob: Job? = null

    init {
        refreshAuthenticationState()
        refreshSummaryEntries()
    }

    fun refreshAuthenticationState() {
        if (activeJob?.isActive == true) return
        _uiState.value = controller.authenticationState().toUiState()
    }

    fun authorize(activity: Activity) {
        if (activeJob?.isActive == true) return
        activeJob = viewModelScope.launch {
            _uiState.value = CodexAuthUiState(
                phase = CodexAuthPhase.AUTHORIZING,
                statusMessage = "브라우저에서 ChatGPT 연결을 완료하세요.",
            )
            try {
                controller.authorize(activity)
                _uiState.value = controller.authenticationState().toUiState(
                    statusOverride = "ChatGPT 계정 연결이 완료되었습니다.",
                )
            } catch (_: CancellationException) {
                _uiState.value = controller.authenticationState().toUiState()
            } catch (error: OAuthException) {
                _uiState.value = CodexAuthUiState(
                    phase = if (error.kind == OAuthFailureKind.USER_DENIED) {
                        CodexAuthPhase.SIGNED_OUT
                    } else {
                        CodexAuthPhase.ERROR
                    },
                    statusMessage = oauthFailureMessage(error.kind),
                )
            } catch (_: Exception) {
                _uiState.value = CodexAuthUiState(
                    phase = CodexAuthPhase.ERROR,
                    statusMessage = "ChatGPT 연결에 실패했습니다. 네트워크 상태를 확인하고 다시 시도하세요.",
                )
            }
        }.also { job -> job.invokeOnCompletion { if (activeJob === job) activeJob = null } }
    }

    fun cancelAuthorization() {
        controller.cancelAuthorization()
    }

    fun logout() {
        controller.cancelAuthorization()
        activeJob?.cancel()
        controller.logout()
        _uiState.value = CodexAuthUiState(statusMessage = "ChatGPT 연결을 해제했습니다.")
    }

    fun runParityProbe() {
        if (activeJob?.isActive == true ||
            controller.authenticationState() !is OAuthAuthenticationState.Authenticated
        ) {
            refreshAuthenticationState()
            return
        }
        val llmLease = when (val result = CodexLlmRequestRuntime.coordinator.tryAcquire(
            owner = CodexLlmRequestCoordinator.Owner.PROBE,
            workId = "probe",
        )) {
            is CodexLlmRequestCoordinator.AcquireResult.Acquired -> result.lease
            is CodexLlmRequestCoordinator.AcquireResult.Busy -> {
                _uiState.update {
                    it.copy(statusMessage = "다른 ChatGPT 작업이 끝난 뒤 연결을 다시 확인하세요.")
                }
                return
            }
        }
        activeJob = viewModelScope.launch {
            val authenticated = controller.authenticationState().toUiState()
            _uiState.value = authenticated.copy(
                phase = CodexAuthPhase.TESTING,
                statusMessage = "비민감 고정 문장으로 Codex 연결을 확인하는 중입니다.",
                probeResponse = null,
            )
            try {
                val response = controller.runParityProbe()
                _uiState.value = controller.authenticationState().toUiState(
                    statusOverride = "Codex Responses 연결 테스트가 성공했습니다.",
                ).copy(probeResponse = response)
            } catch (error: OAuthException) {
                _uiState.value = controller.authenticationState().toUiState(
                    statusOverride = oauthFailureMessage(error.kind),
                )
            } catch (_: Exception) {
                _uiState.value = controller.authenticationState().toUiState(
                    statusOverride = "Codex 연결 테스트에 실패했습니다. 민감한 오류 상세는 표시하지 않습니다.",
                )
            } finally {
                CodexLlmRequestRuntime.coordinator.release(llmLease)
            }
        }.also { job -> job.invokeOnCompletion { if (activeJob === job) activeJob = null } }
    }

    /**
     * Executes a bounded, hierarchical summary only after explicit consent. Raw transcript parts
     * stay in memory and [SummarySessionStore] receives only generated text plus an opaque key.
     */
    fun runUserApprovedSummary(source: SummaryRequestPolicy.Source, transcript: String) {
        if (activeJob?.isActive == true) {
            _summaryUiState.update {
                it.copy(
                    statusSourceKey = source.key,
                    stage = SummaryStage.ERROR,
                    statusMessage = "다른 연결 작업이 끝난 뒤 요약을 다시 시작하세요.",
                )
            }
            return
        }
        if (controller.authenticationState() !is OAuthAuthenticationState.Authenticated) {
            refreshAuthenticationState()
            _summaryUiState.update {
                it.copy(
                    statusSourceKey = source.key,
                    stage = SummaryStage.ERROR,
                    statusMessage = "ChatGPT 연결을 완료한 뒤 선택한 전사를 요약할 수 있습니다.",
                )
            }
            return
        }
        val preparation = when (val result = SummaryRequestPolicy.prepare(source, transcript)) {
            is SummaryRequestPolicy.Preparation.Ready -> result
            is SummaryRequestPolicy.Preparation.Rejected -> {
                _summaryUiState.update {
                    it.copy(
                        statusSourceKey = source.key,
                        stage = SummaryStage.ERROR,
                        statusMessage = result.message,
                    )
                }
                return
            }
        }

        _summaryUiState.update {
            it.copy(
                activeSourceKey = source.key,
                statusSourceKey = source.key,
                isRunning = true,
                stage = SummaryStage.PREPARING,
                completedSteps = 0,
                totalSteps = preparation.totalRequestCount,
                statusMessage = if (preparation.transcriptParts.size == 1) {
                    "요약 요청을 준비하고 있습니다."
                } else {
                    "긴 전사를 ${preparation.transcriptParts.size}개 구간으로 나눠 준비했습니다."
                },
            )
        }

        activeJob = viewModelScope.launch {
            var outcome = DeviceWorkCoordinator.TerminalOutcome.FAILED
            var lease: DeviceWorkCoordinator.Lease? = null
            var llmLease: CodexLlmRequestCoordinator.Lease? = null
            try {
                llmLease = when (val result = CodexLlmRequestRuntime.coordinator.tryAcquire(
                    owner = CodexLlmRequestCoordinator.Owner.SUMMARY,
                    workId = "summary_${source.key}",
                )) {
                    is CodexLlmRequestCoordinator.AcquireResult.Acquired -> result.lease
                    is CodexLlmRequestCoordinator.AcquireResult.Busy -> {
                        _summaryUiState.update {
                            it.copy(
                                stage = SummaryStage.ERROR,
                                statusSourceKey = source.key,
                                statusMessage = "다른 ChatGPT 작업이 끝난 뒤 요약을 다시 시작하세요.",
                            )
                        }
                        return@launch
                    }
                }
                lease = awaitSummaryLease(source) ?: return@launch
                val summary = executeSummaryPlan(preparation)
                _summaryUiState.update {
                    it.copy(
                        stage = SummaryStage.SAVING,
                        completedSteps = preparation.totalRequestCount,
                        statusMessage = "최종 요약을 기기에 저장하고 있습니다.",
                    )
                }
                DeviceWorkRuntime.coordinator.beginFinalization(requireNotNull(lease))
                withContext(Dispatchers.IO) {
                    summaryStore.saveCompleted(source, summary)
                }
                val entries = withContext(Dispatchers.IO) { summaryStore.listAll() }
                _summaryUiState.value = SummaryUiState(
                    entries = entries,
                    statusMessage = "선택한 전사의 요약이 저장되었습니다.",
                )
                outcome = DeviceWorkCoordinator.TerminalOutcome.COMPLETED
            } catch (error: CancellationException) {
                outcome = DeviceWorkCoordinator.TerminalOutcome.CANCELLED
                _summaryUiState.update {
                    it.copy(stage = SummaryStage.ERROR, statusMessage = "요약이 취소되었습니다.")
                }
                throw error
            } catch (error: OAuthException) {
                _uiState.value = controller.authenticationState().toUiState(
                    statusOverride = oauthFailureMessage(error.kind),
                )
                _summaryUiState.update {
                    it.copy(
                        statusSourceKey = source.key,
                        stage = SummaryStage.ERROR,
                        statusMessage = "요약 연결에 실패했습니다. 다시 시도하세요.",
                    )
                }
            } catch (_: Exception) {
                _summaryUiState.update {
                    it.copy(
                        stage = SummaryStage.ERROR,
                        statusSourceKey = source.key,
                        statusMessage = "요약에 실패했습니다. 민감한 오류 상세는 표시하지 않습니다.",
                    )
                }
            } finally {
                lease?.let { heldLease ->
                    DeviceWorkRuntime.coordinator.beginFinalization(heldLease)
                    DeviceWorkRuntime.coordinator.releaseAfterTerminal(heldLease, outcome)
                }
                llmLease?.let(CodexLlmRequestRuntime.coordinator::release)
                _summaryUiState.update { it.copy(activeSourceKey = "", isRunning = false) }
            }
        }.also { job -> job.invokeOnCompletion { if (activeJob === job) activeJob = null } }
    }

    private suspend fun awaitSummaryLease(source: SummaryRequestPolicy.Source): DeviceWorkCoordinator.Lease? {
        repeat(TRANSCRIPTION_FINALIZATION_RETRIES) { attempt ->
            when (val result = DeviceWorkRuntime.coordinator.tryAcquire(
                owner = DeviceWorkCoordinator.Owner.SUMMARY,
                workId = "summary_${source.key}",
            )) {
                is DeviceWorkCoordinator.AcquireResult.Acquired -> return result.lease
                is DeviceWorkCoordinator.AcquireResult.Busy -> {
                    if (result.snapshot.owner == DeviceWorkCoordinator.Owner.TRANSCRIPTION) {
                        _summaryUiState.update {
                            it.copy(
                                stage = SummaryStage.PREPARING,
                                statusMessage = "완료된 전사를 정리하는 중입니다. 끝나면 요약을 자동 시작합니다.",
                            )
                        }
                        if (attempt < TRANSCRIPTION_FINALIZATION_RETRIES - 1) {
                            delay(TRANSCRIPTION_FINALIZATION_RETRY_MS)
                            return@repeat
                        }
                    }
                    _summaryUiState.update {
                        it.copy(
                            stage = SummaryStage.ERROR,
                            statusSourceKey = source.key,
                            statusMessage = "녹음 또는 전사 작업이 끝난 뒤 요약을 다시 시작하세요.",
                        )
                    }
                    return null
                }
            }
        }
        return null
    }

    private suspend fun executeSummaryPlan(preparation: SummaryRequestPolicy.Preparation.Ready): String {
        val parts = preparation.transcriptParts
        val totalRequests = preparation.totalRequestCount
        var completed = 0

        if (parts.size == 1) {
            updateSummaryProgress(
                stage = SummaryStage.SUMMARIZING,
                completed = completed,
                total = totalRequests,
                message = "선택한 전사를 요약하고 있습니다.",
            )
            val summary = controller.runUserApprovedSummary(
                CodexSummaryProfile.userApprovedSummaryRequest(parts.single()),
            )
            updateSummaryProgress(SummaryStage.SUMMARIZING, ++completed, totalRequests, "요약 생성이 완료되었습니다.")
            return summary
        }

        var summaries = parts.mapIndexed { index, part ->
            updateSummaryProgress(
                stage = SummaryStage.SUMMARIZING,
                completed = completed,
                total = totalRequests,
                message = "긴 전사 구간 ${index + 1}/${parts.size} 요약 중입니다.",
            )
            controller.runUserApprovedSummary(
                requestJson = CodexSummaryProfile.partialSummaryRequest(part, index + 1, parts.size),
                maxChars = SummaryRequestPolicy.MAX_INTERMEDIATE_SUMMARY_CHARS,
            ).also {
                completed += 1
                updateSummaryProgress(
                    SummaryStage.SUMMARIZING,
                    completed,
                    totalRequests,
                    "긴 전사 구간 ${index + 1}/${parts.size} 요약을 마쳤습니다.",
                )
            }
        }

        var synthesisLevel = 1
        while (summaries.size > 1) {
            val batches = SummaryRequestPolicy.synthesisBatches(summaries)
            val finalRound = batches.size == 1
            summaries = batches.mapIndexed { index, batch ->
                updateSummaryProgress(
                    stage = SummaryStage.SYNTHESIZING,
                    completed = completed,
                    total = totalRequests,
                    message = if (finalRound) {
                        "구간 요약을 최종 결과로 통합하고 있습니다."
                    } else {
                        "구간 요약 통합 ${synthesisLevel}단계 ${index + 1}/${batches.size} 진행 중입니다."
                    },
                )
                controller.runUserApprovedSummary(
                    requestJson = CodexSummaryProfile.synthesisSummaryRequest(batch, finalRound),
                    maxChars = if (finalRound) {
                        SummaryRequestPolicy.MAX_SUMMARY_CHARS
                    } else {
                        SummaryRequestPolicy.MAX_INTERMEDIATE_SUMMARY_CHARS
                    },
                ).also {
                    completed += 1
                    updateSummaryProgress(
                        SummaryStage.SYNTHESIZING,
                        completed,
                        totalRequests,
                        if (finalRound) "최종 요약 생성이 완료되었습니다." else "통합 요약을 생성했습니다.",
                    )
                }
            }
            synthesisLevel += 1
        }
        return summaries.single()
    }

    private fun updateSummaryProgress(
        stage: SummaryStage,
        completed: Int,
        total: Int,
        message: String,
    ) {
        _summaryUiState.update {
            it.copy(
                stage = stage,
                completedSteps = completed,
                totalSteps = total,
                statusMessage = message,
            )
        }
    }

    fun refreshSummaryEntries() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { summaryStore.listAll() }
            _summaryUiState.update { it.copy(entries = entries) }
        }
    }

    override fun onCleared() {
        controller.cancelAuthorization()
        super.onCleared()
    }

    private fun OAuthAuthenticationState.toUiState(statusOverride: String? = null): CodexAuthUiState =
        when (this) {
            OAuthAuthenticationState.SignedOut -> CodexAuthUiState(
                statusMessage = statusOverride ?: "ChatGPT 계정을 연결하지 않았습니다.",
            )
            is OAuthAuthenticationState.Authenticated -> CodexAuthUiState(
                phase = CodexAuthPhase.AUTHENTICATED,
                expiresAtMs = expiresAtMs,
                statusMessage = statusOverride ?: "ChatGPT 계정이 연결되어 있습니다.",
            )
            is OAuthAuthenticationState.ReauthenticationRequired -> CodexAuthUiState(
                phase = CodexAuthPhase.REAUTHENTICATION_REQUIRED,
                statusMessage = statusOverride ?: "저장된 연결을 사용할 수 없어 다시 로그인이 필요합니다.",
            )
        }

    private fun oauthFailureMessage(kind: OAuthFailureKind): String = when (kind) {
        OAuthFailureKind.USER_DENIED -> "ChatGPT 연결이 취소되었습니다."
        OAuthFailureKind.CALLBACK_TIMEOUT -> "로그인 시간이 만료되었습니다. 다시 시도하세요."
        OAuthFailureKind.STATE_MISMATCH,
        OAuthFailureKind.TRANSACTION_EXPIRED,
        -> "로그인 검증에 실패했습니다. 처음부터 다시 시도하세요."
        OAuthFailureKind.INVALID_GRANT,
        OAuthFailureKind.STORAGE_INVALIDATED,
        OAuthFailureKind.STORAGE_FAILURE,
        -> "저장된 인증 정보를 사용할 수 없습니다. 다시 로그인하세요."
        OAuthFailureKind.NETWORK -> "네트워크 오류로 ChatGPT 연결에 실패했습니다."
        else -> "ChatGPT 연결에 실패했습니다. 잠시 후 다시 시도하세요."
    }

    private companion object {
        const val TRANSCRIPTION_FINALIZATION_RETRIES = 20
        const val TRANSCRIPTION_FINALIZATION_RETRY_MS = 250L
    }
}
