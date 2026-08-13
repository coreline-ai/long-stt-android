package com.stt.benchmark.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import com.stt.benchmark.core.AppLog
import androidx.core.app.NotificationCompat
import com.stt.benchmark.R
import com.stt.benchmark.core.DeviceWorkCoordinator
import com.stt.benchmark.core.DeviceWorkRuntime
import com.stt.benchmark.data.BenchmarkRecorder
import com.stt.benchmark.data.CompletedResultTargetPolicy
import com.stt.benchmark.data.CompletedResultTargetStore
import com.stt.benchmark.data.TerminalCheckpointPersistence
import com.stt.benchmark.data.TranscriptionLifecyclePolicy
import com.stt.benchmark.data.TranscriptionPlan
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.recording.RecordingTranscriptionCoordinator
import com.stt.benchmark.whisper.AudioDecoder
import com.stt.benchmark.whisper.TranscriptSegment
import com.stt.benchmark.whisper.TranscriptionResult
import com.stt.benchmark.whisper.WhisperCppEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 화면 수명과 분리된 장시간 전사 실행기.
 *
 * 청크가 완료될 때마다 transcript와 coverage를 checkpoint에 원자 저장한다. 따라서
 * 프로세스가 그 사이에 종료되면 마지막으로 저장된 청크 다음부터만 다시 실행한다.
 */
class TranscriptionService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val store by lazy { TranscriptionSessionStore(this) }
    private val recorder by lazy { BenchmarkRecorder(this) }
    private val engine by lazy { WhisperCppEngine(this) }
    private val recordingCoordinator by lazy { RecordingTranscriptionCoordinator(this) }
    private val completedResultTargetStore by lazy { CompletedResultTargetStore(this) }
    private val completionNotifier by lazy { TranscriptionCompletionNotifier(this) }

    @Volatile private var activeJob: Job? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var workLease: DeviceWorkCoordinator.Lease? = null
    /** Claimed before a terminal child status broadcast, then run without leaving this FGS. */
    private var pendingGroupLaunch: RecordingTranscriptionCoordinator.ChildLaunchRequest? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> requestCancellation(intent, startId)
            ACTION_QUERY -> queryOrReconcile(startId)
            ACTION_RESUME -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val checkpoint = sessionId?.let { store.load(it) }
                if (checkpoint == null) {
                    publishTransientFailure("재개할 전사 세션을 찾을 수 없습니다")
                } else {
                    launchSession(checkpoint)
                }
            }
            ACTION_START -> {
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
                val recordingSessionId = intent.getStringExtra(EXTRA_RECORDING_SESSION_ID).orEmpty()
                val recordingGroupId = intent.getStringExtra(EXTRA_RECORDING_GROUP_ID).orEmpty()
                val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID).orEmpty()
                val recordingSequence = intent.getIntExtra(EXTRA_RECORDING_SEQUENCE, -1)
                if (modelPath.isNullOrBlank() || audioPath.isNullOrBlank()) {
                    publishTransientFailure("모델 또는 오디오 경로가 없습니다")
                } else {
                    val resumable = if (recordingGroupId.isNotBlank() && mediaId.isNotBlank()) {
                        store.latestIncompleteForGroup(recordingGroupId, mediaId)
                    } else {
                        store.latestIncompleteFor(modelPath, audioPath)
                    }
                    if (resumable != null) {
                        launchSession(resumable)
                    } else {
                        launchNewSession(
                            modelPath = modelPath,
                            audioPath = audioPath,
                            note = note,
                            recordingSessionId = recordingSessionId,
                            recordingGroupId = recordingGroupId,
                            mediaId = mediaId,
                            recordingSequence = recordingSequence,
                        )
                    }
                }
            }
            null -> reconcileStickyRestart(startId)
        }
        // process death 뒤에는 startup reconciliation을 거쳐 사용자가 다시 실행을 선택한다.
        return START_NOT_STICKY
    }

    private fun queryOrReconcile(startId: Int) {
        if (activeJob?.isActive == true) {
            publishActiveSnapshot("현재 전사 상태")
        } else {
            reconcileStickyRestart(startId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // process-local lease를 놓기 전에 최소한 재개 가능한 terminal checkpoint를 동기 보존한다.
        activeSessionId?.let(store::load)?.takeIf {
            TranscriptionLifecyclePolicy.needsStartupReconciliation(it.status)
        }?.let { checkpoint ->
            runCatching {
                store.save(
                    TranscriptionLifecyclePolicy.reconcileAfterProcessDeath(
                        checkpoint = checkpoint,
                        nowMs = System.currentTimeMillis(),
                        message = "전사 Service 종료로 재개 대기 상태로 전환됨",
                    )
                )
            }
        }
        if (activeJob?.isActive == true) {
            activeJob?.cancel(CancellationException("Service가 종료됨"))
        }
        serviceScope.launch(NonCancellable + Dispatchers.IO) { engine.release() }
        workLease?.let { lease ->
            DeviceWorkRuntime.coordinator.releaseAfterTerminal(lease, DeviceWorkCoordinator.TerminalOutcome.FAILED)
        }
        workLease = null
        serviceJob.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun requestCancellation(intent: Intent, startId: Int) {
        cancelRequested = true
        val running = activeJob
        if (running?.isActive == true) {
            running.cancel(CancellationException("사용자가 foreground 전사를 중지함"))
            return
        }

        startForegroundNow("중단 상태 정리 중")
        val requestedSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        serviceScope.launch {
            try {
                val checkpoint = withContext(Dispatchers.IO) {
                    requestedSessionId?.let(store::load) ?: store.latestIncomplete()
                }
                if (checkpoint != null && TranscriptionLifecyclePolicy.canResume(checkpoint.status)) {
                    persistTerminal(
                        checkpoint,
                        TranscriptionSessionStore.Status.CANCELLED,
                        "사용자가 전사를 취소함"
                    )
                } else {
                    publishStatus("", TranscriptionSessionStore.Status.CANCELLED, 0f, 0, 0, "실행 중인 전사가 없습니다")
                }
            } catch (error: Throwable) {
                AppLog.e(TAG, "미완료 세션 취소 상태 저장 실패", error)
                publishStatus("", TranscriptionSessionStore.Status.FAILED, 0f, 0, 0, "중단 상태를 저장하지 못했습니다")
            } finally {
                finishRun(startId)
            }
        }
    }

    /** START_STICKY 잔여 호출은 자동 재개하지 않고 RUNNING 계열만 INTERRUPTED로 정리한다. */
    private fun reconcileStickyRestart(startId: Int) {
        startForegroundNow("중단된 전사 상태 확인 중")
        serviceScope.launch {
            try {
                val reconciled = withContext(Dispatchers.IO) { store.reconcileAfterProcessDeath() }
                val latest = reconciled.maxByOrNull { it.updatedAtMs }
                if (latest != null) {
                    publishStatus(
                        latest.sessionId,
                        latest.status,
                        latest.progress,
                        latest.currentChunk,
                        latest.totalChunks,
                        "중단된 전사를 재개 대기 상태로 전환했습니다"
                    )
                } else {
                    publishStatus("", TranscriptionSessionStore.Status.INTERRUPTED, 0f, 0, 0, "자동 재개할 작업이 없습니다")
                }
            } catch (error: Throwable) {
                AppLog.e(TAG, "중단 상태 조정 실패", error)
                publishStatus("", TranscriptionSessionStore.Status.FAILED, 0f, 0, 0, "중단 상태를 확인하지 못했습니다")
            } finally {
                finishRun(startId)
            }
        }
    }

    private fun launchNewSession(
        modelPath: String,
        audioPath: String,
        note: String,
        recordingSessionId: String,
        recordingGroupId: String,
        mediaId: String,
        recordingSequence: Int,
    ) {
        if (activeJob?.isActive == true) {
            publishActiveSnapshot("이미 전사 중입니다")
            return
        }
        clearPreviousCompletedResult()
        startForegroundNow("전사 준비 중")
        cancelRequested = false
        activeJob = serviceScope.launch {
            var checkpoint: TranscriptionSessionStore.Checkpoint? = null
            try {
                checkpoint = createCheckpoint(
                    modelPath = modelPath,
                    audioPath = audioPath,
                    note = note,
                    recordingSessionId = recordingSessionId,
                    recordingGroupId = recordingGroupId,
                    mediaId = mediaId,
                    recordingSequence = recordingSequence,
                )
                activeSessionId = checkpoint.sessionId
                if (!acquireWorkLease(checkpoint)) {
                    persistTerminal(checkpoint, TranscriptionSessionStore.Status.FAILED, "다른 장시간 작업이 실행 중입니다")
                    return@launch
                }
                runCheckpoint(checkpoint)
            } catch (cancelled: CancellationException) {
                checkpoint?.let {
                    persistTerminal(
                        it,
                        TranscriptionLifecyclePolicy.terminalStatusForCancellation(cancelRequested),
                        if (cancelRequested) "사용자가 전사를 취소함" else "전사 서비스가 중단됨"
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                AppLog.e(TAG, "세션 시작 실패", error)
                checkpoint?.let {
                    persistTerminal(it, TranscriptionSessionStore.Status.FAILED, "전사를 시작하지 못했습니다.")
                } ?: publishTransientFailure("전사를 시작하지 못했습니다.")
            } finally {
                finishRun(startId = null)
            }
        }
    }

    private fun launchSession(checkpoint: TranscriptionSessionStore.Checkpoint) {
        if (activeJob?.isActive == true) {
            publishActiveSnapshot("이미 전사 중입니다")
            return
        }
        if (!TranscriptionLifecyclePolicy.canResume(checkpoint.status)) {
            publishTransientFailure("${checkpoint.status.name} 상태의 전사는 재개할 수 없습니다")
            return
        }
        clearPreviousCompletedResult()
        startForegroundNow("전사 재개 준비 중")
        cancelRequested = false
        activeSessionId = checkpoint.sessionId
        activeJob = serviceScope.launch {
            try {
                if (!acquireWorkLease(checkpoint)) {
                    persistTerminal(checkpoint, TranscriptionSessionStore.Status.FAILED, "다른 장시간 작업이 실행 중입니다")
                    return@launch
                }
                runCheckpoint(checkpoint)
            } catch (cancelled: CancellationException) {
                persistTerminal(
                    checkpoint,
                    TranscriptionLifecyclePolicy.terminalStatusForCancellation(cancelRequested),
                    if (cancelRequested) "사용자가 전사를 취소함" else "전사 서비스가 중단됨"
                )
                throw cancelled
            } catch (error: Throwable) {
                AppLog.e(TAG, "세션 재개 실패", error)
                persistTerminal(checkpoint, TranscriptionSessionStore.Status.FAILED, "전사를 재개하지 못했습니다.")
            } finally {
                finishRun(startId = null)
            }
        }
    }

    private suspend fun createCheckpoint(
        modelPath: String,
        audioPath: String,
        note: String,
        recordingSessionId: String,
        recordingGroupId: String,
        mediaId: String,
        recordingSequence: Int,
    ): TranscriptionSessionStore.Checkpoint {
        require(isManagedReadableFile(modelPath)) { "앱 내부 모델 파일을 찾을 수 없습니다" }
        require(isManagedReadableFile(audioPath)) { "앱 내부 오디오 파일을 찾을 수 없습니다" }
        ensureFreeSpace()
        val durationMs = withContext(Dispatchers.IO) { AudioDecoder.durationMs(audioPath) }
            ?: throw IllegalStateException("오디오 길이를 확인할 수 없습니다")
        val totalChunks = TranscriptionPlan.create(durationMs).totalChunks
        val now = System.currentTimeMillis()
        return TranscriptionSessionStore.Checkpoint(
            sessionId = store.newSessionId(),
            status = TranscriptionSessionStore.Status.PREPARING,
            modelPath = modelPath,
            audioPath = audioPath,
            note = note.take(MAX_NOTE_LENGTH),
            durationMs = durationMs,
            totalChunks = totalChunks,
            currentChunk = 0,
            createdAtMs = now,
            updatedAtMs = now,
            recordingSessionId = recordingSessionId,
            recordingGroupId = recordingGroupId,
            mediaId = mediaId,
            recordingSequence = recordingSequence,
        ).also { persistAndPublish(it, "전사 세션 생성") }
    }

    private fun acquireWorkLease(checkpoint: TranscriptionSessionStore.Checkpoint): Boolean {
        val acquired = DeviceWorkRuntime.coordinator.tryAcquire(
            DeviceWorkCoordinator.Owner.TRANSCRIPTION,
            checkpoint.sessionId,
        )
        if (acquired !is DeviceWorkCoordinator.AcquireResult.Acquired) return false
        workLease = acquired.lease
        return true
    }

    private suspend fun runCheckpoint(initial: TranscriptionSessionStore.Checkpoint) {
        require(isManagedReadableFile(initial.modelPath)) { "모델 파일이 없어 재개할 수 없습니다" }
        require(isManagedReadableFile(initial.audioPath)) { "오디오 파일이 없어 재개할 수 없습니다" }
        ensureFreeSpace()
        acquireWakeLock()

        var checkpoint = initial.copy(
            status = TranscriptionSessionStore.Status.RUNNING,
            errorMessage = "",
            updatedAtMs = System.currentTimeMillis()
        )
        persistAndPublish(checkpoint, "모델 로드 중")

        if (!withContext(Dispatchers.IO) { engine.loadModel(checkpoint.modelPath) }) {
            throw IllegalStateException("모델 로드 실패")
        }

        var usedContext = false
        val completed = checkpoint.chunks.associateBy { it.index }.toMutableMap()
        val plan = TranscriptionPlan.create(checkpoint.durationMs)
        require(plan.totalChunks == checkpoint.totalChunks) {
            "저장된 청크 수가 현재 계획과 다릅니다: ${checkpoint.totalChunks}/${plan.totalChunks}"
        }
        for (plannedChunk in plan.chunks) {
            currentCoroutineContext().ensureActive()
            renewWakeLock()
            val chunkIndex = plannedChunk.index
            if (completed.containsKey(chunkIndex)) continue

            val primaryStartMs = plannedChunk.primaryStartMs
            val primaryEndMs = plannedChunk.primaryEndMs
            checkpoint = checkpoint.copy(
                status = TranscriptionSessionStore.Status.RUNNING,
                currentChunk = chunkIndex,
                updatedAtMs = System.currentTimeMillis()
            )
            persistAndPublish(checkpoint, "청크 $chunkIndex/${checkpoint.totalChunks} 디코드 중")

            val transcribed = transcribeChunk(
                checkpoint = checkpoint,
                chunkIndex = chunkIndex,
                primaryStartMs = primaryStartMs,
                primaryEndMs = primaryEndMs,
                decodeStartMs = plannedChunk.decodeStartMs,
                decodeEndMs = plannedChunk.decodeEndMs,
                reloadModelBeforeAttempt = usedContext
            )
            usedContext = true
            val decoded = transcribed.window
            val result = transcribed.result
            val primarySegments = result.segments.filter { segmentBelongsToPrimary(it, primaryStartMs, primaryEndMs) }
            val completedChunk = TranscriptionSessionStore.CompletedChunk(
                index = chunkIndex,
                primaryStartMs = primaryStartMs,
                primaryEndMs = primaryEndMs,
                decodedStartMs = maxOf(primaryStartMs, decoded.decodedStartMs),
                decodedEndMs = minOf(primaryEndMs, decoded.decodedEndMs),
                decodedSamples = decoded.pcm.size,
                retryCount = transcribed.retryCount,
                elapsedMs = result.elapsedMs,
                text = primarySegments.joinToString(" ") { it.text.trim() }.trim(),
                segments = primarySegments
            )
            completed[chunkIndex] = completedChunk
            checkpoint = checkpoint.copy(
                status = TranscriptionSessionStore.Status.RUNNING,
                currentChunk = chunkIndex,
                chunks = completed.values.sortedBy { it.index },
                updatedAtMs = System.currentTimeMillis()
            )
            persistAndPublish(checkpoint, "청크 $chunkIndex/${checkpoint.totalChunks} 완료")

            if (chunkIndex < checkpoint.totalChunks) {
                checkpoint = checkpoint.copy(
                    status = TranscriptionSessionStore.Status.COOLING,
                    updatedAtMs = System.currentTimeMillis()
                )
                persistAndPublish(checkpoint, "냉각 대기 ${cooldownFor(chunkIndex) / 1000}초")
                delay(cooldownFor(chunkIndex))
            }
        }

        verifyCoverage(checkpoint)
        checkpoint = checkpoint.copy(
            status = TranscriptionSessionStore.Status.COMPLETED,
            currentChunk = checkpoint.totalChunks,
            errorMessage = "",
            updatedAtMs = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            val result = checkpoint.toResult(
                modelSize = "${File(checkpoint.modelPath).length() / 1024 / 1024}MB",
                engineName = engine.engineName
            )
            recorder.appendResult(
                result = result,
                audioFile = checkpoint.audioPath,
                modelName = File(checkpoint.modelPath).name,
                note = checkpoint.note,
                sessionId = checkpoint.sessionId
            )
        }
        persistAndPublish(checkpoint, "전사 완료")
    }

    private suspend fun persistTerminal(
        initial: TranscriptionSessionStore.Checkpoint,
        status: TranscriptionSessionStore.Status,
        error: String
    ) {
        // 부모 Job이 이미 cancel된 catch 블록에서도 terminal checkpoint와 UI 상태를 반드시 남긴다.
        TerminalCheckpointPersistence.persist(
            initial = initial,
            status = status,
            errorMessage = error,
            loadLatest = { store.load(initial.sessionId) },
            save = store::save,
            // Group ownership advances before the status broadcast reaches the background UI.
            // Otherwise a ViewModel attempts to start the next FGS after this service exits.
            afterSave = { terminal -> prepareRecordingGroupHandoff(terminal, error) },
            publish = { checkpoint ->
                publishStatus(
                    checkpoint.sessionId,
                    checkpoint.status,
                    checkpoint.progress,
                    checkpoint.currentChunk,
                    checkpoint.totalChunks,
                    error
                )
            }
        )
    }

    private suspend fun persistAndPublish(
        checkpoint: TranscriptionSessionStore.Checkpoint,
        detail: String
    ) {
        withContext(Dispatchers.IO) { store.save(checkpoint) }
        val completedTarget = if (checkpoint.status in TERMINAL_STATUSES) {
            prepareRecordingGroupHandoff(checkpoint, detail)
        } else null
        if (completedTarget != null) {
            withContext(Dispatchers.IO) { completedResultTargetStore.save(completedTarget) }
        }
        publishStatus(
            checkpoint.sessionId,
            checkpoint.status,
            checkpoint.progress,
            checkpoint.currentChunk,
            checkpoint.totalChunks,
            detail.ifBlank { checkpoint.errorMessage }
        )
        if (completedTarget != null) completionNotifier.post(completedTarget)
    }

    /**
     * Claim the following recording child while this service is still foreground.  The next
     * actual run is started by [finishRun] after the current engine/lease has been released.
     * This prevents Android 14+ background FGS-start restrictions from breaking child 2+.
     */
    private fun prepareRecordingGroupHandoff(
        checkpoint: TranscriptionSessionStore.Checkpoint,
        detail: String,
    ): CompletedResultTargetStore.Target? {
        if (checkpoint.recordingGroupId.isBlank() || checkpoint.mediaId.isBlank()) {
            return CompletedResultTargetPolicy.fromStandaloneSession(checkpoint)
        }
        val result = recordingCoordinator.onChildEvent(
            RecordingTranscriptionCoordinator.ChildEvent(
                groupId = checkpoint.recordingGroupId,
                mediaId = checkpoint.mediaId,
                sttSessionId = checkpoint.sessionId,
                status = checkpoint.status,
                detail = detail.ifBlank { checkpoint.errorMessage },
            ),
        )
        if (result is RecordingTranscriptionCoordinator.EventResult.Updated && result.launchNext) {
            pendingGroupLaunch = recordingCoordinator.prepareCurrentLaunch(result.group.groupId)?.request
        }
        return (result as? RecordingTranscriptionCoordinator.EventResult.Updated)?.group
            ?.let(CompletedResultTargetPolicy::fromRecordingGroup)
    }

    private fun launchPreparedGroupChild(
        request: RecordingTranscriptionCoordinator.ChildLaunchRequest,
    ) {
        launchNewSession(
            modelPath = request.modelPath,
            audioPath = request.audioPath,
            note = request.note,
            recordingSessionId = request.recordingSessionId,
            recordingGroupId = request.groupId,
            mediaId = request.mediaId,
            recordingSequence = request.sequence,
        )
    }

    private data class TranscribedWindow(
        val window: AudioDecoder.DecodedAudioWindow,
        val result: TranscriptionResult,
        val retryCount: Int
    )

    /** 빈 PCM·MediaCodec·native 실패는 context를 새로 로드해 한 번만 재시도한다. */
    private suspend fun transcribeChunk(
        checkpoint: TranscriptionSessionStore.Checkpoint,
        chunkIndex: Int,
        primaryStartMs: Long,
        primaryEndMs: Long,
        decodeStartMs: Long,
        decodeEndMs: Long,
        reloadModelBeforeAttempt: Boolean
    ): TranscribedWindow {
        var lastFailure: Throwable? = null
        repeat(MAX_CHUNK_ATTEMPTS) { attempt ->
            try {
                if (reloadModelBeforeAttempt || attempt > 0) {
                    if (!withContext(Dispatchers.IO) { engine.loadModel(checkpoint.modelPath) }) {
                        throw IllegalStateException("청크 $chunkIndex 모델 재로드 실패")
                    }
                }
                if (attempt > 0) {
                    persistAndPublish(
                        checkpoint,
                        "청크 $chunkIndex/${checkpoint.totalChunks} 재시도 ${attempt + 1}/$MAX_CHUNK_ATTEMPTS"
                    )
                } else {
                    persistAndPublish(checkpoint, "청크 $chunkIndex/${checkpoint.totalChunks} 디코드/전사 중")
                }

                val decoded = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeWindowWithMetadata(
                        checkpoint.audioPath,
                        decodeStartMs,
                        decodeEndMs - decodeStartMs
                    )
                }
                currentCoroutineContext().ensureActive()
                require(!decoded.isEmpty) { "청크 $chunkIndex PCM 디코드 실패" }
                require(
                    decoded.decodedStartMs <= primaryStartMs + COVERAGE_TOLERANCE_MS &&
                        decoded.decodedEndMs >= primaryEndMs - COVERAGE_TOLERANCE_MS
                ) {
                    "청크 $chunkIndex PCM coverage 부족: primary=$primaryStartMs~$primaryEndMs, " +
                        "decoded=${decoded.decodedStartMs}~${decoded.decodedEndMs}"
                }

                val result = withContext(Dispatchers.Default) {
                    engine.transcribePcm(decoded.pcm, decoded.decodedStartMs)
                }
                return TranscribedWindow(decoded, result, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                AppLog.w(TAG, "청크 $chunkIndex 시도 ${attempt + 1} 실패", failure)
            }
        }
        throw IllegalStateException(
            "청크 $chunkIndex 재시도 후 실패",
            lastFailure
        )
    }

    private fun verifyCoverage(checkpoint: TranscriptionSessionStore.Checkpoint) {
        require(checkpoint.chunks.size == checkpoint.totalChunks) {
            "완료 청크 누락: ${checkpoint.chunks.size}/${checkpoint.totalChunks}"
        }
        var cursor = 0L
        checkpoint.chunks.sortedBy { it.index }.forEach { chunk ->
            require(chunk.decodedStartMs <= cursor + COVERAGE_TOLERANCE_MS &&
                chunk.decodedEndMs >= chunk.primaryEndMs - COVERAGE_TOLERANCE_MS) {
                "coverage 불연속: cursor=$cursor, chunk=${chunk.decodedStartMs}~${chunk.decodedEndMs}"
            }
            cursor = maxOf(cursor, chunk.decodedEndMs)
        }
        require(cursor >= checkpoint.durationMs - COVERAGE_TOLERANCE_MS) {
            "coverage 미완료: $cursor/${checkpoint.durationMs}"
        }
    }

    private fun segmentBelongsToPrimary(
        segment: TranscriptSegment,
        primaryStartMs: Long,
        primaryEndMs: Long
    ): Boolean {
        val midpoint = (segment.startMs + segment.endMs) / 2L
        return midpoint >= primaryStartMs && midpoint < primaryEndMs
    }

    private fun cooldownFor(chunkIndex: Int): Long =
        if (chunkIndex % LONG_REST_EVERY == 0) LONG_COOLDOWN_MS else SHORT_COOLDOWN_MS

    private fun isManagedReadableFile(path: String): Boolean = try {
        val root = filesDir.canonicalFile
        val candidate = File(path).canonicalFile
        candidate.isFile && candidate.canRead() && candidate.path.startsWith(root.path + File.separator)
    } catch (_: Exception) {
        false
    }

    private fun ensureFreeSpace() {
        val available = StatFs(filesDir.absolutePath).availableBytes
        require(available >= MIN_FREE_SPACE_BYTES) {
            "저장 공간 부족: ${available / 1024 / 1024}MB (최소 ${MIN_FREE_SPACE_BYTES / 1024 / 1024}MB 필요)"
        }
    }

    private fun startForegroundNow(detail: String, progress: Float? = null) {
        val notification = buildNotification(detail, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publishStatus(
        sessionId: String,
        status: TranscriptionSessionStore.Status,
        progress: Float,
        currentChunk: Int,
        totalChunks: Int,
        detail: String
    ) {
        val statusLine = if (totalChunks > 0) {
            "$detail · 청크 $currentChunk/$totalChunks · coverage ${(progress * 100).toInt()}%"
        } else {
            detail
        }
        startForegroundNow(statusLine, progress)
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_STATUS, status.name)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_CURRENT_CHUNK, currentChunk)
            putExtra(EXTRA_TOTAL_CHUNKS, totalChunks)
            putExtra(EXTRA_DETAIL, detail)
            store.load(sessionId)?.let { checkpoint ->
                putExtra(EXTRA_RECORDING_SESSION_ID, checkpoint.recordingSessionId)
                putExtra(EXTRA_RECORDING_GROUP_ID, checkpoint.recordingGroupId)
                putExtra(EXTRA_MEDIA_ID, checkpoint.mediaId)
                putExtra(EXTRA_RECORDING_SEQUENCE, checkpoint.recordingSequence)
            }
        })
    }

    private fun publishTransientFailure(message: String) {
        startForegroundNow(message)
        publishStatus("", TranscriptionSessionStore.Status.FAILED, 0f, 0, 0, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(detail: String, progress: Float?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(getString(R.string.stt_notification_title))
        .setContentText(detail)
        .setOnlyAlertOnce(true)
        .setOngoing(activeJob?.isActive == true)
        .setProgress(
            100,
            ((progress ?: 0f).coerceIn(0f, 1f) * 100).toInt(),
            progress == null
        )
        .build()

    private fun clearPreviousCompletedResult() {
        completedResultTargetStore.clear()
        completionNotifier.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.stt_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stt-transcription")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    /** 각 10분 청크 시작 전에 bounded wake lock의 만료 시간을 갱신한다. */
    private fun renewWakeLock() {
        releaseWakeLock()
        acquireWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun publishActiveSnapshot(detail: String) {
        val checkpoint = activeSessionId?.let(store::load)
        if (checkpoint == null) {
            publishStatus(activeSessionId.orEmpty(), TranscriptionSessionStore.Status.RUNNING, 0f, 0, 0, detail)
            return
        }
        publishStatus(
            checkpoint.sessionId,
            checkpoint.status,
            checkpoint.progress,
            checkpoint.currentChunk,
            checkpoint.totalChunks,
            detail
        )
    }

    private suspend fun finishRun(startId: Int?) = withContext(NonCancellable + Dispatchers.IO) {
        // 취소된 문맥으로 돌아가는 순간 다시 CancellationException이 발생할 수 있으므로
        // release뿐 아니라 service terminal cleanup 전체를 NonCancellable 안에서 끝낸다.
        engine.release()
        releaseWakeLock()
        workLease?.let { lease ->
            val status = activeSessionId?.let(store::load)?.status
            val outcome = when (status) {
                TranscriptionSessionStore.Status.COMPLETED -> DeviceWorkCoordinator.TerminalOutcome.COMPLETED
                TranscriptionSessionStore.Status.CANCELLED -> DeviceWorkCoordinator.TerminalOutcome.CANCELLED
                else -> DeviceWorkCoordinator.TerminalOutcome.FAILED
            }
            DeviceWorkRuntime.coordinator.beginFinalization(lease)
            DeviceWorkRuntime.coordinator.releaseAfterTerminal(lease, outcome)
        }
        workLease = null
        activeJob = null
        activeSessionId = null
        val next = pendingGroupLaunch
        pendingGroupLaunch = null
        if (next != null) {
            // Remain foreground for the complete group chain; do not hand off FGS startup to a
            // background BroadcastReceiver/ViewModel after the current child terminal event.
            launchPreparedGroupChild(next)
            return@withContext
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    companion object {
        const val ACTION_START = "com.stt.benchmark.action.START_TRANSCRIPTION"
        const val ACTION_RESUME = "com.stt.benchmark.action.RESUME_TRANSCRIPTION"
        const val ACTION_CANCEL = "com.stt.benchmark.action.CANCEL_TRANSCRIPTION"
        const val ACTION_QUERY = "com.stt.benchmark.action.QUERY_TRANSCRIPTION"
        const val ACTION_STATUS = "com.stt.benchmark.action.TRANSCRIPTION_STATUS"

        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_NOTE = "note"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_CURRENT_CHUNK = "current_chunk"
        const val EXTRA_TOTAL_CHUNKS = "total_chunks"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_RECORDING_SESSION_ID = "recording_session_id"
        const val EXTRA_RECORDING_GROUP_ID = "recording_group_id"
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_RECORDING_SEQUENCE = "recording_sequence"

        private const val TAG = "TranscriptionService"
        /** Stable and intentionally separate from RecorderNotificationFactory's recording identity. */
        internal const val CHANNEL_ID = "long_transcription"
        internal const val NOTIFICATION_ID = 6_001
        internal const val COMPLETION_NOTIFICATION_ID = 6_002
        private const val COVERAGE_TOLERANCE_MS = 50L
        private const val SHORT_COOLDOWN_MS = 10_000L
        private const val LONG_COOLDOWN_MS = 30_000L
        private const val LONG_REST_EVERY = 5
        private const val MIN_FREE_SPACE_BYTES = 256L * 1024L * 1024L
        private const val MAX_NOTE_LENGTH = 200
        private const val MAX_CHUNK_ATTEMPTS = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private val TERMINAL_STATUSES = setOf(
            TranscriptionSessionStore.Status.COMPLETED,
            TranscriptionSessionStore.Status.FAILED,
            TranscriptionSessionStore.Status.CANCELLED,
            TranscriptionSessionStore.Status.INTERRUPTED,
        )
    }
}
