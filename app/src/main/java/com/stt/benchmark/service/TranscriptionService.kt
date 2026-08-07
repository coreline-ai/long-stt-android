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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stt.benchmark.R
import com.stt.benchmark.data.BenchmarkRecorder
import com.stt.benchmark.data.TranscriptionSessionStore
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { TranscriptionSessionStore(this) }
    private val recorder by lazy { BenchmarkRecorder(this) }
    private val engine by lazy { WhisperCppEngine(this) }

    private var activeJob: Job? = null
    private var activeSessionId: String? = null
    private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRequested = true
                activeJob?.cancel(CancellationException("사용자가 foreground 전사를 중지함"))
            }
            ACTION_RESUME -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                sessionId?.let { store.load(it) }?.let { launchSession(it) }
            }
            ACTION_START -> {
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
                if (modelPath.isNullOrBlank() || audioPath.isNullOrBlank()) {
                    publishTransientFailure("모델 또는 오디오 경로가 없습니다")
                } else {
                    val resumable = store.latestIncompleteFor(modelPath, audioPath)
                    if (resumable != null) launchSession(resumable) else launchNewSession(modelPath, audioPath, note)
                }
            }
            null -> store.latestIncomplete()?.let { launchSession(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeJob?.isActive == true) {
            activeJob?.cancel(CancellationException("Service가 종료됨"))
        }
        serviceScope.launch(NonCancellable + Dispatchers.IO) { engine.release() }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun launchNewSession(modelPath: String, audioPath: String, note: String) {
        if (activeJob?.isActive == true) {
            publishStatus(activeSessionId.orEmpty(), TranscriptionSessionStore.Status.RUNNING, 0f, 0, 0, "이미 전사 중입니다")
            return
        }
        startForegroundNow("전사 준비 중")
        cancelRequested = false
        activeJob = serviceScope.launch {
            var checkpoint: TranscriptionSessionStore.Checkpoint? = null
            try {
                checkpoint = createCheckpoint(modelPath, audioPath, note)
                activeSessionId = checkpoint.sessionId
                runCheckpoint(checkpoint)
            } catch (cancelled: CancellationException) {
                checkpoint?.let { persistTerminal(it, if (cancelRequested) TranscriptionSessionStore.Status.CANCELLED else TranscriptionSessionStore.Status.INTERRUPTED, "전사 중단") }
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "세션 시작 실패", error)
                checkpoint?.let { persistTerminal(it, TranscriptionSessionStore.Status.FAILED, error.message ?: "세션 시작 실패") }
                    ?: publishTransientFailure(error.message ?: "세션 시작 실패")
            } finally {
                finishIfIdle(startId = null)
            }
        }
    }

    private fun launchSession(checkpoint: TranscriptionSessionStore.Checkpoint) {
        if (activeJob?.isActive == true) return
        startForegroundNow("전사 재개 준비 중")
        cancelRequested = false
        activeSessionId = checkpoint.sessionId
        activeJob = serviceScope.launch {
            try {
                runCheckpoint(checkpoint)
            } catch (cancelled: CancellationException) {
                persistTerminal(
                    checkpoint,
                    if (cancelRequested) TranscriptionSessionStore.Status.CANCELLED else TranscriptionSessionStore.Status.INTERRUPTED,
                    "전사 중단"
                )
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "세션 재개 실패", error)
                persistTerminal(checkpoint, TranscriptionSessionStore.Status.FAILED, error.message ?: "세션 재개 실패")
            } finally {
                finishIfIdle(startId = null)
            }
        }
    }

    private suspend fun createCheckpoint(
        modelPath: String,
        audioPath: String,
        note: String
    ): TranscriptionSessionStore.Checkpoint {
        require(isManagedReadableFile(modelPath)) { "앱 내부 모델 파일을 찾을 수 없습니다" }
        require(isManagedReadableFile(audioPath)) { "앱 내부 오디오 파일을 찾을 수 없습니다" }
        ensureFreeSpace()
        val durationMs = withContext(Dispatchers.IO) { AudioDecoder.durationMs(audioPath) }
            ?: throw IllegalStateException("오디오 길이를 확인할 수 없습니다")
        val totalChunks = ((durationMs + CHUNK_DURATION_MS - 1L) / CHUNK_DURATION_MS).toInt().coerceAtLeast(1)
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
            updatedAtMs = now
        ).also { persistAndPublish(it, "전사 세션 생성") }
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
        for (chunkIndex in 1..checkpoint.totalChunks) {
            currentCoroutineContext().ensureActive()
            if (completed.containsKey(chunkIndex)) continue

            val primaryStartMs = (chunkIndex - 1L) * CHUNK_DURATION_MS
            val primaryEndMs = minOf(checkpoint.durationMs, primaryStartMs + CHUNK_DURATION_MS)
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
            recorder.appendResult(result, checkpoint.audioPath, File(checkpoint.modelPath).name, checkpoint.note)
        }
        persistAndPublish(checkpoint, "전사 완료")
    }

    private suspend fun persistTerminal(
        initial: TranscriptionSessionStore.Checkpoint,
        status: TranscriptionSessionStore.Status,
        error: String
    ) {
        val latest = store.load(initial.sessionId) ?: initial
        persistAndPublish(
            latest.copy(status = status, errorMessage = error, updatedAtMs = System.currentTimeMillis()),
            error
        )
    }

    private suspend fun persistAndPublish(
        checkpoint: TranscriptionSessionStore.Checkpoint,
        detail: String
    ) {
        withContext(Dispatchers.IO) { store.save(checkpoint) }
        publishStatus(
            checkpoint.sessionId,
            checkpoint.status,
            checkpoint.progress,
            checkpoint.currentChunk,
            checkpoint.totalChunks,
            detail.ifBlank { checkpoint.errorMessage }
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

                val decodeStartMs = (primaryStartMs - CHUNK_OVERLAP_MS).coerceAtLeast(0L)
                val decodeEndMs = (primaryEndMs + CHUNK_OVERLAP_MS).coerceAtMost(checkpoint.durationMs)
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
                Log.w(TAG, "청크 $chunkIndex 시도 ${attempt + 1} 실패", failure)
            }
        }
        throw IllegalStateException(
            "청크 $chunkIndex 재시도 후 실패: ${lastFailure?.message ?: "알 수 없는 오류"}",
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
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun finishIfIdle(startId: Int?) {
        serviceScope.launch(NonCancellable + Dispatchers.IO) { engine.release() }
        releaseWakeLock()
        activeJob = null
        activeSessionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    companion object {
        const val ACTION_START = "com.stt.benchmark.action.START_TRANSCRIPTION"
        const val ACTION_RESUME = "com.stt.benchmark.action.RESUME_TRANSCRIPTION"
        const val ACTION_CANCEL = "com.stt.benchmark.action.CANCEL_TRANSCRIPTION"
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

        private const val TAG = "TranscriptionService"
        private const val CHANNEL_ID = "long_transcription"
        private const val NOTIFICATION_ID = 6_001
        private const val CHUNK_DURATION_MS = 10 * 60 * 1000L
        private const val CHUNK_OVERLAP_MS = 1_000L
        private const val COVERAGE_TOLERANCE_MS = 50L
        private const val SHORT_COOLDOWN_MS = 10_000L
        private const val LONG_COOLDOWN_MS = 30_000L
        private const val LONG_REST_EVERY = 5
        private const val MIN_FREE_SPACE_BYTES = 256L * 1024L * 1024L
        private const val MAX_NOTE_LENGTH = 200
        private const val MAX_CHUNK_ATTEMPTS = 2
    }
}
