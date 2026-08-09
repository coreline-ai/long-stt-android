package com.stt.benchmark.summary

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

class CodexAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = CodexSummaryAuthController(application)
    private val _uiState = MutableStateFlow(CodexAuthUiState())
    val uiState: StateFlow<CodexAuthUiState> = _uiState.asStateFlow()
    private var activeJob: Job? = null

    init {
        refreshAuthenticationState()
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
            }
        }.also { job -> job.invokeOnCompletion { if (activeJob === job) activeJob = null } }
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
}
