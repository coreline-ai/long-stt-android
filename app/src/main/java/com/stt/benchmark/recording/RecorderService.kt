package com.stt.benchmark.recording

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.stt.benchmark.core.DeviceWorkCoordinator
import com.stt.benchmark.core.DeviceWorkRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecorderService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: RecordingSessionStore
    private lateinit var fileManager: RecordingFileManager
    private lateinit var backendFactory: RecorderBackendFactory
    private lateinit var notifications: RecorderNotificationFactory
    private lateinit var commandActor: RecorderCommandActor
    private var machine = RecordingState()
    private var currentSession: RecordingSessionStore.RecordingSession? = null
    private var backend: RecorderBackend? = null
    private var lease: DeviceWorkCoordinator.Lease? = null
    private var rolloverJob: Job? = null
    private var routeConfirmationJob: Job? = null
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeStartId: Int = 0
    private var inputRouteEpoch: Long = 0L
    @Volatile
    private var activeInputRoute: RecordingInputRoute = RecordingInputRoute.UNKNOWN
    private var confirmedInputRoute: RecordingInputRoute = RecordingInputRoute.UNKNOWN
    @Volatile
    private var runtimeMessage: String = ""

    override fun onCreate() {
        super.onCreate()
        store = RecordingSessionStore(this)
        fileManager = RecordingFileManager(filesDir)
        backendFactory = RecorderBackendFactory(this, fileManager)
        notifications = RecorderNotificationFactory(this)
        commandActor = RecorderCommandActor(serviceScope, ::handleCommand)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    publishFailure("마이크 권한이 필요합니다.")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (!promoteToForeground(RecordingPhase.PREPARING)) {
                    publishFailure("마이크 foreground service를 시작할 수 없습니다.")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch { commandActor.submit(RecorderCommandActor.Command.Start(startId)) }
            }

            ACTION_STOP -> {
                if (currentSession == null) {
                    stopSelfResult(startId)
                } else {
                    serviceScope.launch { commandActor.submit(RecorderCommandActor.Command.Stop(startId)) }
                }
            }

            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleCommand(command: RecorderCommandActor.Command): RecorderCommandActor.Outcome =
        when (command) {
            is RecorderCommandActor.Command.Start -> handleStart(command.startId)
            is RecorderCommandActor.Command.Stop -> handleStop(command.startId)
            is RecorderCommandActor.Command.Rollover -> handleRollover(command)
            is RecorderCommandActor.Command.InputRouteObserved -> handleInputRouteObserved(command)
            is RecorderCommandActor.Command.InputRouteUnavailable -> handleInputRouteUnavailable(command)
            is RecorderCommandActor.Command.BackendFailure -> handleBackendFailure(command)
        }

    private suspend fun handleStart(startId: Int): RecorderCommandActor.Outcome {
        if (currentSession != null || machine.phase !in setOf(RecordingPhase.IDLE, RecordingPhase.FAILED, RecordingPhase.SAVED)) {
            return RecorderCommandActor.Outcome.Ignored("이미 녹음 작업이 있습니다")
        }
        if (machine.phase != RecordingPhase.IDLE) machine = RecordingState()
        when (RecorderController.preconditions(this)) {
            RecorderPreconditions.Result.Ready -> Unit
            RecorderPreconditions.Result.PermissionRequired -> return rejectStart("마이크 권한이 필요합니다.", startId)
            RecorderPreconditions.Result.UnsupportedInput -> return rejectStart("사용 가능한 마이크 입력이 없습니다.", startId)
            is RecorderPreconditions.Result.InsufficientStorage -> return rejectStart("녹음 저장공간이 부족합니다.", startId)
        }

        // 이전 service/process가 남긴 part를 먼저 정리하고 새 session을 만든다.
        withContext(Dispatchers.IO) { RecordingRecoveryCoordinator(this@RecorderService).reconcile() }
        val sessionId = store.newSessionId()
        activeStartId = startId
        val acquired = DeviceWorkRuntime.coordinator.tryAcquire(
            DeviceWorkCoordinator.Owner.RECORDING,
            sessionId,
        )
        if (acquired !is DeviceWorkCoordinator.AcquireResult.Acquired) {
            return rejectStart("다른 장시간 작업이 실행 중입니다.", startId)
        }
        lease = acquired.lease
        val now = System.currentTimeMillis()
        runtimeMessage = ""
        machine = transition(RecordingEvent.StartRequested(sessionId))
        currentSession = RecordingSessionStore.RecordingSession(
            sessionId = sessionId,
            phase = machine.phase,
            createdAtMs = now,
            updatedAtMs = now,
        ).also(store::save)

        return try {
            startChunk(0)
            RecorderCommandActor.Outcome.Accepted
        } catch (error: Throwable) {
            terminalFailure("녹음 장치를 시작하지 못했습니다.", startId)
            RecorderCommandActor.Outcome.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun handleStop(stopStartId: Int): RecorderCommandActor.Outcome {
        val session = currentSession ?: return RecorderCommandActor.Outcome.Ignored("활성 녹음 없음")
        if (machine.phase == RecordingPhase.FINALIZING) {
            return RecorderCommandActor.Outcome.Ignored("이미 마감 중")
        }
        rolloverJob?.cancel()
        tickerJob?.cancel()
        machine = transition(RecordingEvent.StopRequested)
        currentSession = session.copy(
            phase = machine.phase,
            updatedAtMs = System.currentTimeMillis(),
        ).also(store::save)
        DeviceWorkRuntime.coordinator.beginFinalization(
            lease ?: return terminalFailure("녹음 lease가 없습니다.", stopStartId),
        )
        promoteToForeground(RecordingPhase.FINALIZING)

        val finalized = withContext(NonCancellable + Dispatchers.IO) { finalizeCurrentChunk() }
        return if (finalized && currentSession?.readyChunks?.isNotEmpty() == true) {
            machine = transition(RecordingEvent.Finalized)
            val terminal = currentSession!!.copy(
                phase = machine.phase,
                stoppedAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                errorMessage = "",
            )
            currentSession = terminal
            store.save(terminal)
            // 녹음 checkpoint가 terminal 저장된 뒤에만 READY 원본을 보관함에 노출한다.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { RecordingMediaRegistrar(this@RecorderService).register(terminal) }
            }
            finishTerminal(saved = true, terminalStartId = stopStartId)
            RecorderCommandActor.Outcome.Accepted
        } else {
            terminalFailure("녹음 파일을 안전하게 확정하지 못했습니다.", stopStartId)
        }
    }

    private suspend fun handleRollover(
        command: RecorderCommandActor.Command.Rollover,
    ): RecorderCommandActor.Outcome = rolloverCurrentChunk(
        sessionId = command.sessionId,
        chunkIndex = command.chunkIndex,
    )

    private suspend fun handleInputRouteObserved(
        command: RecorderCommandActor.Command.InputRouteObserved,
    ): RecorderCommandActor.Outcome {
        val session = currentSession ?: return RecorderCommandActor.Outcome.Ignored("활성 녹음 없음")
        if (
            !RecordingInputRouteTransitionPolicy.isCurrentObservation(
                expectedSessionId = session.sessionId,
                expectedChunkIndex = session.currentChunkIndex,
                expectedRouteEpoch = inputRouteEpoch,
                observedSessionId = command.sessionId,
                observedChunkIndex = command.chunkIndex,
                observedRouteEpoch = command.routeEpoch,
            ) ||
            machine.phase !in ACTIVE_PHASES
        ) {
            return RecorderCommandActor.Outcome.Ignored("오래된 입력 경로 관찰")
        }

        activeInputRoute = command.route
        publishRuntime()
        if (machine.phase != RecordingPhase.RECORDING) {
            routeConfirmationJob?.cancel()
            routeConfirmationJob = null
            if (command.route != RecordingInputRoute.UNKNOWN) confirmedInputRoute = command.route
            return RecorderCommandActor.Outcome.Accepted
        }

        val decision = RecordingInputRouteTransitionPolicy.decide(
            confirmedRoute = confirmedInputRoute,
            observedRoute = command.route,
        )

        return when (decision) {
            RecordingInputRouteTransitionPolicy.Decision.INITIALIZED -> {
                routeConfirmationJob?.cancel()
                routeConfirmationJob = null
                confirmedInputRoute = command.route
                RecorderCommandActor.Outcome.Accepted
            }
            RecordingInputRouteTransitionPolicy.Decision.UNCHANGED -> {
                if (command.route != RecordingInputRoute.UNKNOWN) {
                    routeConfirmationJob?.cancel()
                    routeConfirmationJob = null
                }
                RecorderCommandActor.Outcome.Accepted
            }
            RecordingInputRouteTransitionPolicy.Decision.AWAITING_REPLACEMENT -> {
                scheduleRouteLossConfirmation(command, confirmedInputRoute)
                RecorderCommandActor.Outcome.Accepted
            }
            RecordingInputRouteTransitionPolicy.Decision.CHANGED -> {
                routeConfirmationJob?.cancel()
                routeConfirmationJob = null
                confirmedInputRoute = command.route
                runtimeMessage = ROUTE_SWITCHING_MESSAGE
                publishRuntime()
                rolloverCurrentChunk(
                    sessionId = command.sessionId,
                    chunkIndex = command.chunkIndex,
                    continuationMessage = ROUTE_SWITCHED_MESSAGE,
                )
            }
        }
    }

    private suspend fun handleInputRouteUnavailable(
        command: RecorderCommandActor.Command.InputRouteUnavailable,
    ): RecorderCommandActor.Outcome {
        routeConfirmationJob = null
        val session = currentSession ?: return RecorderCommandActor.Outcome.Ignored("활성 녹음 없음")
        if (
            !RecordingInputRouteTransitionPolicy.isCurrentObservation(
                expectedSessionId = session.sessionId,
                expectedChunkIndex = session.currentChunkIndex,
                expectedRouteEpoch = inputRouteEpoch,
                observedSessionId = command.sessionId,
                observedChunkIndex = command.chunkIndex,
                observedRouteEpoch = command.routeEpoch,
            ) ||
            machine.phase != RecordingPhase.RECORDING ||
            activeInputRoute != RecordingInputRoute.UNKNOWN ||
            confirmedInputRoute != command.previousRoute
        ) {
            return RecorderCommandActor.Outcome.Ignored("복구되었거나 오래된 입력 경로")
        }
        confirmedInputRoute = RecordingInputRoute.UNKNOWN
        runtimeMessage = ROUTE_SWITCHING_MESSAGE
        publishRuntime()
        return rolloverCurrentChunk(
            sessionId = command.sessionId,
            chunkIndex = command.chunkIndex,
            continuationMessage = ROUTE_SWITCHED_MESSAGE,
        )
    }

    private suspend fun rolloverCurrentChunk(
        sessionId: String,
        chunkIndex: Int,
        continuationMessage: String? = null,
    ): RecorderCommandActor.Outcome {
        val session = currentSession ?: return RecorderCommandActor.Outcome.Ignored("활성 녹음 없음")
        if (session.sessionId != sessionId || session.currentChunkIndex != chunkIndex) {
            return RecorderCommandActor.Outcome.Ignored("오래된 rollover")
        }
        if (machine.phase != RecordingPhase.RECORDING) {
            return RecorderCommandActor.Outcome.Ignored("rollover 불가 상태")
        }
        tickerJob?.cancel()
        machine = transition(RecordingEvent.RolloverRequested)
        currentSession = session.copy(
            phase = machine.phase,
            updatedAtMs = System.currentTimeMillis(),
        ).also(store::save)
        promoteToForeground(RecordingPhase.ROLLING_OVER)
        val finalized = withContext(NonCancellable + Dispatchers.IO) { finalizeCurrentChunk() }
        if (!finalized) return terminalFailure("청크를 안전하게 마감하지 못했습니다.")

        machine = transition(RecordingEvent.RolloverCompleted)
        return try {
            startChunk(machine.currentChunkIndex)
            continuationMessage?.let {
                runtimeMessage = it
                publishRuntime()
            }
            RecorderCommandActor.Outcome.Accepted
        } catch (error: Throwable) {
            terminalFailure("다음 녹음 청크를 시작하지 못했습니다.")
            RecorderCommandActor.Outcome.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun handleBackendFailure(
        command: RecorderCommandActor.Command.BackendFailure,
    ): RecorderCommandActor.Outcome {
        val session = currentSession ?: return RecorderCommandActor.Outcome.Ignored("활성 녹음 없음")
        if (session.sessionId != command.sessionId || session.currentChunkIndex != command.chunkIndex) {
            return RecorderCommandActor.Outcome.Ignored("오래된 backend completion")
        }
        return terminalFailure("녹음 backend 오류가 발생했습니다.")
    }

    private fun startChunk(chunkIndex: Int) {
        val session = requireNotNull(currentSession)
        when (RecorderController.preconditions(this)) {
            RecorderPreconditions.Result.Ready -> Unit
            else -> error("청크 시작 preflight 실패")
        }
        renewWakeLock()
        routeConfirmationJob?.cancel()
        routeConfirmationJob = null
        val routeEpoch = ++inputRouteEpoch
        activeInputRoute = RecordingInputRoute.UNKNOWN
        confirmedInputRoute = RecordingInputRoute.UNKNOWN
        val started = backendFactory.start(
            sessionId = session.sessionId,
            chunkIndex = chunkIndex,
            onFailure = { error ->
                serviceScope.launch {
                    commandActor.submit(
                        RecorderCommandActor.Command.BackendFailure(
                            sessionId = session.sessionId,
                            chunkIndex = chunkIndex,
                            errorType = error.javaClass.simpleName,
                        )
                    )
                }
            },
            onInputRoute = { route ->
                // Keep route state changes in the actor mailbox. The command contains only a
                // generic category; epoch/session/chunk checks discard late callbacks.
                serviceScope.launch {
                    commandActor.submit(
                        RecorderCommandActor.Command.InputRouteObserved(
                            sessionId = session.sessionId,
                            chunkIndex = chunkIndex,
                            routeEpoch = routeEpoch,
                            route = route,
                        )
                    )
                }
            },
        )
        require(started is RecorderBackendFactory.StartResult.Started) { "모든 녹음 backend 시작 실패" }
        backend = started.backend
        val now = System.currentTimeMillis()
        if (machine.phase == RecordingPhase.PREPARING) {
            machine = transition(RecordingEvent.RecorderStarted)
        }
        val chunk = RecordingSessionStore.RecordingChunk(
            index = chunkIndex,
            status = RecordingSessionStore.ChunkStatus.WRITING,
            partPath = started.backend.partFile.absolutePath,
            container = started.backend.mode.container,
            createdAtMs = now,
        )
        currentSession = session.copy(
            phase = RecordingPhase.RECORDING,
            currentChunkIndex = chunkIndex,
            startedAtMs = session.startedAtMs.takeIf { it > 0L } ?: now,
            updatedAtMs = now,
            chunks = session.chunks.filterNot { it.index == chunkIndex } + chunk,
        ).also(store::save)
        publishRuntime()
        promoteToForeground(RecordingPhase.RECORDING)
        scheduleRollover(session.sessionId, chunkIndex)
        startTicker()
    }

    private fun finalizeCurrentChunk(): Boolean {
        val activeBackend = backend ?: return false
        val session = currentSession ?: return false
        val chunkIndex = session.currentChunkIndex
        val existing = session.chunks.firstOrNull { it.index == chunkIndex }
        if (existing == null) {
            runCatching(activeBackend::release)
            fileManager.quarantineManagedFile(activeBackend.partFile, "checkpoint 없는 active backend")
            backend = null
            return false
        }
        val stopError = runCatching(activeBackend::stop).exceptionOrNull()
        runCatching(activeBackend::release)
        backend = null
        if (stopError != null) {
            val quarantined = fileManager.quarantineManagedFile(activeBackend.partFile, "recorder stop 실패")
            updateChunk(existing.toQuarantined(quarantined, "recorder stop 실패"))
            return false
        }
        return when (val finalized = fileManager.finalizePart(activeBackend.partFile)) {
            is RecordingFileManager.FinalizeResult.Ready -> {
                val inspected = runCatching { AudioFileInspector.inspect(finalized.value.file) }
                if (inspected.isFailure) {
                    val quarantined = fileManager.quarantineManagedFile(finalized.value.file, "audio format 검사 실패")
                    updateChunk(existing.toQuarantined(quarantined, "audio format 검사 실패"))
                    false
                } else {
                    val metadata = inspected.getOrThrow()
                    updateChunk(
                        existing.copy(
                            status = RecordingSessionStore.ChunkStatus.READY,
                            partPath = "",
                            finalPath = finalized.value.file.absolutePath,
                            quarantinePath = "",
                            container = finalized.value.container,
                            codec = metadata.codec,
                            sampleRateHz = metadata.sampleRateHz,
                            channelCount = metadata.channelCount,
                            durationMs = metadata.durationMs,
                            sizeBytes = finalized.value.sizeBytes,
                            sha256 = finalized.value.sha256,
                            finalizedAtMs = System.currentTimeMillis(),
                            issue = "",
                        )
                    )
                    true
                }
            }

            is RecordingFileManager.FinalizeResult.Quarantined -> {
                updateChunk(existing.toQuarantined(finalized.file, finalized.reason))
                false
            }
            is RecordingFileManager.FinalizeResult.RetryableFailure -> {
                val quarantined = fileManager.quarantineManagedFile(finalized.partFile, finalized.reason)
                updateChunk(existing.toQuarantined(quarantined, finalized.reason))
                false
            }
            is RecordingFileManager.FinalizeResult.Rejected -> {
                val quarantined = fileManager.quarantineManagedFile(activeBackend.partFile, finalized.reason)
                updateChunk(existing.toQuarantined(quarantined, finalized.reason))
                false
            }
        }
    }

    private fun updateChunk(chunk: RecordingSessionStore.RecordingChunk) {
        val session = requireNotNull(currentSession)
        currentSession = session.copy(
            chunks = session.chunks.filterNot { it.index == chunk.index } + chunk,
            updatedAtMs = System.currentTimeMillis(),
        ).also(store::save)
    }

    private fun RecordingSessionStore.RecordingChunk.toQuarantined(file: java.io.File?, reason: String) = copy(
        status = if (file != null) RecordingSessionStore.ChunkStatus.QUARANTINED else RecordingSessionStore.ChunkStatus.MISSING,
        partPath = "",
        finalPath = "",
        quarantinePath = file?.absolutePath.orEmpty(),
        issue = if (file != null) reason else "$reason; 격리 실패",
    )

    private fun scheduleRollover(sessionId: String, chunkIndex: Int) {
        rolloverJob?.cancel()
        rolloverJob = serviceScope.launch {
            delay(RecordingSessionStore.DEFAULT_CHUNK_DURATION_MS)
            commandActor.submit(RecorderCommandActor.Command.Rollover(sessionId, chunkIndex))
        }
    }

    private fun scheduleRouteLossConfirmation(
        command: RecorderCommandActor.Command.InputRouteObserved,
        previousRoute: RecordingInputRoute,
    ) {
        routeConfirmationJob?.cancel()
        routeConfirmationJob = serviceScope.launch {
            delay(ROUTE_LOSS_CONFIRMATION_MS)
            commandActor.submit(
                RecorderCommandActor.Command.InputRouteUnavailable(
                    sessionId = command.sessionId,
                    chunkIndex = command.chunkIndex,
                    routeEpoch = command.routeEpoch,
                    previousRoute = previousRoute,
                )
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        var lastNotificationSecond = -1L
        tickerJob = serviceScope.launch {
            while (isActive && machine.phase == RecordingPhase.RECORDING) {
                val session = currentSession ?: break
                val elapsed = (System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(0L)
                val normalizedAmplitude = (backend?.maxAmplitude()?.toFloat() ?: 0f) / Short.MAX_VALUE.toFloat()
                RecordingRuntime.publish(
                    RecordingRuntimeSnapshot(
                        phase = machine.phase,
                        sessionId = session.sessionId,
                        currentChunkIndex = session.currentChunkIndex,
                        elapsedMs = elapsed,
                        amplitude = normalizedAmplitude.coerceIn(0f, 1f),
                        inputRoute = activeInputRoute,
                        message = runtimeMessage,
                    )
                )
                val second = elapsed / 1_000L
                if (second / 5L != lastNotificationSecond / 5L) {
                    notifications.notifyForeground(RecordingPhase.RECORDING, elapsed)
                    lastNotificationSecond = second
                }
                delay(250L)
            }
        }
    }

    private fun publishRuntime() {
        val session = currentSession
        RecordingRuntime.publish(
            RecordingRuntimeSnapshot(
                phase = machine.phase,
                sessionId = session?.sessionId.orEmpty(),
                currentChunkIndex = session?.currentChunkIndex ?: 0,
                elapsedMs = session?.startedAtMs?.takeIf { it > 0L }
                    ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L,
                inputRoute = activeInputRoute,
                message = runtimeMessage,
            )
        )
    }

    private fun transition(event: RecordingEvent): RecordingState {
        val transition = RecordingStateReducer.reduce(machine, event)
        check(transition.accepted) { transition.reason }
        return transition.state
    }

    private fun rejectStart(message: String, terminalStartId: Int): RecorderCommandActor.Outcome {
        publishFailure(message)
        removeForeground()
        stopSelfResult(terminalStartId)
        return RecorderCommandActor.Outcome.Failed(message)
    }

    private suspend fun terminalFailure(
        message: String,
        terminalStartId: Int = activeStartId,
    ): RecorderCommandActor.Outcome {
        rolloverJob?.cancel()
        routeConfirmationJob?.cancel()
        routeConfirmationJob = null
        tickerJob?.cancel()
        if (backend != null && currentSession != null) {
            runCatching { withContext(NonCancellable + Dispatchers.IO) { finalizeCurrentChunk() } }
        }
        val session = currentSession
        if (session != null) {
            if (machine.phase in ACTIVE_PHASES) {
                machine = runCatching { transition(RecordingEvent.Failed(message, mayHaveRecoverableFiles = false)) }
                    .getOrElse { machine.copy(phase = RecordingPhase.FAILED, message = message) }
            }
            val failed = session.copy(
                phase = RecordingPhase.FAILED,
                stoppedAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                errorMessage = message,
            )
            currentSession = failed
            runCatching { store.save(failed) }
        }
        finishTerminal(saved = false, terminalStartId = terminalStartId)
        return RecorderCommandActor.Outcome.Failed(message)
    }

    /**
     * stopSelf() without an id can destroy a new ACTION_START delivered while a prior STOP was
     * finalizing. startId-aware stopping leaves that newer start alive and closes the FGS timeout
     * race observed during the 20× START/STOP device gate.
     */
    private fun finishTerminal(saved: Boolean, terminalStartId: Int = activeStartId) {
        val activeLease = lease
        if (activeLease != null) {
            DeviceWorkRuntime.coordinator.releaseAfterTerminal(
                activeLease,
                if (saved) DeviceWorkCoordinator.TerminalOutcome.COMPLETED else DeviceWorkCoordinator.TerminalOutcome.FAILED,
            )
        }
        lease = null
        routeConfirmationJob?.cancel()
        routeConfirmationJob = null
        releaseWakeLock()
        activeInputRoute = RecordingInputRoute.UNKNOWN
        confirmedInputRoute = RecordingInputRoute.UNKNOWN
        runtimeMessage = if (saved) "녹음이 저장되었습니다." else currentSession?.errorMessage.orEmpty()
        publishRuntime()
        currentSession = null
        removeForeground()
        notifications.notifyTerminal(saved)
        if (terminalStartId > 0) {
            stopSelfResult(terminalStartId)
        } else {
            stopSelf()
        }
    }

    private fun publishFailure(message: String) {
        machine = RecordingState(phase = RecordingPhase.FAILED, message = message)
        runtimeMessage = message
        RecordingRuntime.publish(RecordingRuntimeSnapshot(phase = RecordingPhase.FAILED, message = message))
    }

    private fun promoteToForeground(phase: RecordingPhase): Boolean = runCatching {
        ServiceCompat.startForeground(
            this,
            RecorderNotificationFactory.NOTIFICATION_ID,
            notifications.foreground(phase, RecordingRuntime.snapshot.value.elapsedMs),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }.isSuccess

    private fun removeForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun renewWakeLock() {
        releaseWakeLock()
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        rolloverJob?.cancel()
        routeConfirmationJob?.cancel()
        tickerJob?.cancel()
        val abandoned = currentSession
        if (abandoned != null && abandoned.phase in ACTIVE_PHASES) {
            runCatching { backend?.release() }
            val recovery = abandoned.copy(
                phase = RecordingPhase.RECOVERY_REQUIRED,
                errorMessage = "service 종료 뒤 파일 검사가 필요합니다.",
                updatedAtMs = System.currentTimeMillis(),
            )
            runCatching { store.save(recovery) }
            lease?.let {
                DeviceWorkRuntime.coordinator.releaseAfterTerminal(it, DeviceWorkCoordinator.TerminalOutcome.FAILED)
            }
        }
        backend = null
        currentSession = null
        lease = null
        activeInputRoute = RecordingInputRoute.UNKNOWN
        confirmedInputRoute = RecordingInputRoute.UNKNOWN
        releaseWakeLock()
        commandActor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.stt.benchmark.recording.action.START"
        const val ACTION_STOP = "com.stt.benchmark.recording.action.STOP"
        private const val WAKE_LOCK_TAG = "LongStt:Recorder"
        private const val ROUTE_LOSS_CONFIRMATION_MS = 1_500L
        private const val ROUTE_SWITCHING_MESSAGE =
            "입력 장치 변경을 감지해 현재 파일을 안전하게 저장하는 중입니다."
        private const val ROUTE_SWITCHED_MESSAGE =
            "입력 장치 변경 후 새 파일에서 녹음을 이어가고 있습니다."
        private const val WAKE_LOCK_TIMEOUT_MS = RecordingSessionStore.DEFAULT_CHUNK_DURATION_MS + 5L * 60L * 1_000L
        private val ACTIVE_PHASES = setOf(
            RecordingPhase.PREPARING,
            RecordingPhase.RECORDING,
            RecordingPhase.ROLLING_OVER,
            RecordingPhase.FINALIZING,
        )
    }
}
