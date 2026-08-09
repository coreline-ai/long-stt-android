package com.stt.benchmark.ui.recording

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stt.benchmark.recording.RecorderController
import com.stt.benchmark.recording.RecorderPreconditions
import com.stt.benchmark.recording.RecordingPhase
import com.stt.benchmark.recording.RecordingMediaRegistrar
import com.stt.benchmark.recording.RecordingRuntime
import com.stt.benchmark.recording.RecordingRuntimeSnapshot
import com.stt.benchmark.recording.RecordingSessionStore
import com.stt.benchmark.recording.RecordingStorageEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecordingAvailability {
    READY,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    UNSUPPORTED_INPUT,
    INSUFFICIENT_STORAGE,
}

enum class PendingRecordingCommand { NONE, START, STOP }

data class RecentRecordingUi(
    val sessionId: String,
    val phase: RecordingPhase,
    val updatedAtMs: Long,
    val durationMs: Long,
    val readyChunkCount: Int,
    val quarantinedChunkCount: Int,
    val missingChunkCount: Int,
    val container: String,
    val message: String,
)

data class RecordingUiState(
    val runtime: RecordingRuntimeSnapshot = RecordingRuntimeSnapshot(),
    val availability: RecordingAvailability = RecordingAvailability.PERMISSION_REQUIRED,
    val availableBytes: Long = 0L,
    val estimatedMaxDurationMs: Long = 0L,
    val recentSessions: List<RecentRecordingUi> = emptyList(),
    val pendingCommand: PendingRecordingCommand = PendingRecordingCommand.NONE,
    val localMessage: String = "",
) {
    val displayPhase: RecordingPhase
        get() = when (pendingCommand) {
            PendingRecordingCommand.START -> RecordingPhase.PREPARING
            PendingRecordingCommand.STOP -> RecordingPhase.FINALIZING
            PendingRecordingCommand.NONE -> runtime.phase
        }

    val isRecorderActive: Boolean
        get() = displayPhase in ACTIVE_PHASES

    val canStart: Boolean
        get() = availability == RecordingAvailability.READY &&
            runtime.phase in STARTABLE_PHASES && pendingCommand == PendingRecordingCommand.NONE

    val canStop: Boolean
        get() = runtime.phase in STOPPABLE_PHASES && pendingCommand == PendingRecordingCommand.NONE

    val message: String
        get() = localMessage.ifBlank { runtime.message }

    companion object {
        val ACTIVE_PHASES = setOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.ROLLING_OVER,
            RecordingPhase.FINALIZING,
        )
        val STARTABLE_PHASES = setOf(RecordingPhase.IDLE, RecordingPhase.SAVED, RecordingPhase.FAILED)
        val STOPPABLE_PHASES = setOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.ROLLING_OVER,
        )
    }
}

internal class RecordingActionGate {
    @Synchronized
    fun request(state: RecordingUiState, command: PendingRecordingCommand): Boolean = when (command) {
        PendingRecordingCommand.START -> state.canStart
        PendingRecordingCommand.STOP -> state.canStop
        PendingRecordingCommand.NONE -> false
    }
}

class RecordingViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RecordingSessionStore(app)
    private val actionGate = RecordingActionGate()
    private var deniedState: RecordingAvailability? = null
    private var lastObservedPhase = RecordingPhase.IDLE
    private var lastObservedSessionId = ""

    private val _uiState = MutableStateFlow(initialState(app))
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    init {
        refreshEnvironmentAndSessions()
        viewModelScope.launch {
            RecordingRuntime.snapshot.collect { runtime ->
                val previousPhase = lastObservedPhase
                val previousSession = lastObservedSessionId
                lastObservedPhase = runtime.phase
                lastObservedSessionId = runtime.sessionId
                _uiState.update { current ->
                    current.copy(
                        runtime = runtime,
                        pendingCommand = clearPending(current.pendingCommand, runtime.phase),
                        localMessage = if (runtime.phase == RecordingPhase.FAILED) current.localMessage else "",
                    )
                }
                if (
                    runtime.sessionId != previousSession ||
                    runtime.phase != previousPhase && runtime.phase in TERMINAL_PHASES
                ) {
                    refreshEnvironmentAndSessions()
                }
            }
        }
    }

    fun refreshEnvironmentAndSessions() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readEnvironmentAndSessions() }
            if (snapshot.permissionGranted) deniedState = null
            _uiState.update { current ->
                current.copy(
                    availability = if (!snapshot.permissionGranted && deniedState != null) {
                        deniedState!!
                    } else {
                        snapshot.availability
                    },
                    availableBytes = snapshot.availableBytes,
                    estimatedMaxDurationMs = snapshot.estimatedMaxDurationMs,
                    recentSessions = snapshot.sessions,
                )
            }
        }
    }

    fun onPermissionResult(granted: Boolean, shouldShowRationale: Boolean) {
        deniedState = when {
            granted -> null
            shouldShowRationale -> RecordingAvailability.PERMISSION_DENIED
            else -> RecordingAvailability.PERMISSION_PERMANENTLY_DENIED
        }
        _uiState.update { current ->
            current.copy(
                availability = deniedState ?: current.availability,
                localMessage = if (granted) "" else "마이크 권한이 없어 녹음을 시작하지 않았습니다.",
            )
        }
        refreshEnvironmentAndSessions()
    }

    fun startRecording(): Boolean {
        val current = _uiState.value
        if (!actionGate.request(current, PendingRecordingCommand.START)) return false
        _uiState.update { it.copy(pendingCommand = PendingRecordingCommand.START, localMessage = "") }
        return runCatching {
            RecorderController.start(getApplication())
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        pendingCommand = PendingRecordingCommand.NONE,
                        localMessage = "녹음 서비스를 시작하지 못했습니다: ${error.javaClass.simpleName}",
                    )
                }
                false
            },
        )
    }

    fun stopRecording(): Boolean {
        val current = _uiState.value
        if (!actionGate.request(current, PendingRecordingCommand.STOP)) return false
        _uiState.update { it.copy(pendingCommand = PendingRecordingCommand.STOP, localMessage = "") }
        return runCatching {
            RecorderController.stop(getApplication())
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        pendingCommand = PendingRecordingCommand.NONE,
                        localMessage = "녹음을 정지하지 못했습니다: ${error.javaClass.simpleName}",
                    )
                }
                false
            },
        )
    }

    private fun readEnvironmentAndSessions(): EnvironmentSnapshot {
        val app = getApplication<Application>()
        val permissionGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val availableBytes = StatFs(app.filesDir.path).availableBytes.coerceAtLeast(0L)
        val preconditions = RecorderController.preconditions(app)
        val sessions = store.listAll()
        // 구버전에서 저장된 세션이나 finalization 직후 중단된 등록을 재진입 시 보완한다.
        runCatching { RecordingMediaRegistrar(app).registerAll(sessions) }
        return EnvironmentSnapshot(
            permissionGranted = permissionGranted,
            availability = mapAvailability(preconditions),
            availableBytes = availableBytes,
            estimatedMaxDurationMs = RecordingStorageEstimator.estimateMaxDurationMs(availableBytes),
            sessions = sessions.take(5).map(::toRecentUi),
        )
    }

    private data class EnvironmentSnapshot(
        val permissionGranted: Boolean,
        val availability: RecordingAvailability,
        val availableBytes: Long,
        val estimatedMaxDurationMs: Long,
        val sessions: List<RecentRecordingUi>,
    )

    companion object {
        private val TERMINAL_PHASES = setOf(
            RecordingPhase.SAVED,
            RecordingPhase.FAILED,
            RecordingPhase.RECOVERY_REQUIRED,
        )

        internal fun mapAvailability(result: RecorderPreconditions.Result): RecordingAvailability = when (result) {
            RecorderPreconditions.Result.Ready -> RecordingAvailability.READY
            RecorderPreconditions.Result.PermissionRequired -> RecordingAvailability.PERMISSION_REQUIRED
            RecorderPreconditions.Result.UnsupportedInput -> RecordingAvailability.UNSUPPORTED_INPUT
            is RecorderPreconditions.Result.InsufficientStorage -> RecordingAvailability.INSUFFICIENT_STORAGE
        }

        internal fun clearPending(
            pending: PendingRecordingCommand,
            runtimePhase: RecordingPhase,
        ): PendingRecordingCommand = when (pending) {
            PendingRecordingCommand.START -> if (runtimePhase != RecordingPhase.IDLE) {
                PendingRecordingCommand.NONE
            } else {
                pending
            }
            PendingRecordingCommand.STOP -> if (runtimePhase in setOf(
                    RecordingPhase.FINALIZING,
                    RecordingPhase.SAVED,
                    RecordingPhase.FAILED,
                    RecordingPhase.RECOVERY_REQUIRED,
                )
            ) {
                PendingRecordingCommand.NONE
            } else {
                pending
            }
            PendingRecordingCommand.NONE -> pending
        }

        internal fun toRecentUi(session: RecordingSessionStore.RecordingSession): RecentRecordingUi {
            val ready = session.readyChunks
            return RecentRecordingUi(
                sessionId = session.sessionId,
                phase = session.phase,
                updatedAtMs = session.updatedAtMs,
                durationMs = ready.sumOf { it.durationMs }.takeIf { it > 0L }
                    ?: (session.stoppedAtMs - session.startedAtMs).coerceAtLeast(0L),
                readyChunkCount = ready.size,
                quarantinedChunkCount = session.chunks.count {
                    it.status == RecordingSessionStore.ChunkStatus.QUARANTINED
                },
                missingChunkCount = session.chunks.count {
                    it.status == RecordingSessionStore.ChunkStatus.MISSING
                },
                container = ready.firstOrNull()?.container.orEmpty(),
                message = session.errorMessage.ifBlank {
                    session.chunks.firstOrNull { it.issue.isNotBlank() }?.issue.orEmpty()
                },
            )
        }

        private fun initialState(app: Application): RecordingUiState {
            val availability = runCatching {
                mapAvailability(RecorderController.preconditions(app))
            }.getOrDefault(RecordingAvailability.UNSUPPORTED_INPUT)
            val availableBytes = runCatching { StatFs(app.filesDir.path).availableBytes.coerceAtLeast(0L) }
                .getOrDefault(0L)
            return RecordingUiState(
                runtime = RecordingRuntime.snapshot.value,
                availability = availability,
                availableBytes = availableBytes,
                estimatedMaxDurationMs = RecordingStorageEstimator.estimateMaxDurationMs(availableBytes),
            )
        }
    }
}
