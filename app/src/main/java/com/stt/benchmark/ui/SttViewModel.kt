package com.stt.benchmark.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stt.benchmark.data.BenchmarkRecorder
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.ModelDownloader
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.service.TranscriptionService
import com.stt.benchmark.whisper.AudioDecoder
import com.stt.benchmark.whisper.ChunkCoverage
import com.stt.benchmark.whisper.TranscriptSegment
import com.stt.benchmark.whisper.TranscriptionResult
import com.stt.benchmark.whisper.WhisperCppEngine
import com.stt.benchmark.whisper.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SttViewModel(app: Application) : AndroidViewModel(app) {

    private val whisperEngine: WhisperEngine = WhisperCppEngine(app)
    private val recorder = BenchmarkRecorder(app)
    private val modelDownloader = ModelDownloader(app)
    private val sessionStore = TranscriptionSessionStore(app)
    private val mediaLibraryStore = MediaLibraryStore(app)

    /** 사용 가능한 모델 목록 */
    val availableModels: List<ModelDownloader.ModelInfo> get() = ModelDownloader.MODELS

    enum class SttState { IDLE, LOADING_MODEL, READY, RUNNING, CANCELLING, DONE, ERROR }

    private data class MediaSnapshot(
        val audios: List<MediaLibraryStore.AudioEntry>,
        val models: List<MediaLibraryStore.ModelEntry>,
        val selectedAudioPath: String,
        val selectedModelPath: String,
        val hasIncompleteSession: Boolean
    )

    data class UiState(
        val state: SttState = SttState.IDLE,
        val modelLoaded: Boolean = false,
        val modelPath: String = "",
        val audioPath: String = "",
        val progress: Float = 0f,
        val result: TranscriptionResult? = null,
        val lastReport: String = "",
        val errorMessage: String = "",
        val deviceInfo: BenchmarkRecorder.DeviceInfo = BenchmarkRecorder.DeviceInfo(),
        val history: List<BenchmarkRecorder.BenchmarkRecord> = emptyList(),
        /** checkpoint 원본을 기준으로 한 전사 결과 보관함 */
        val resultSessions: List<TranscriptionSessionStore.Checkpoint> = emptyList(),
        /** 앱 내부로 가져온 오디오의 영구 목록 */
        val audioLibrary: List<MediaLibraryStore.AudioEntry> = emptyList(),
        /** 새 models/ 폴더와 이전 루트 모델을 함께 보여주는 설치 목록 */
        val installedModels: List<MediaLibraryStore.ModelEntry> = emptyList(),
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadModelName: String = "",
        // 다중 파일 배치 전사
        val audioPaths: List<String> = emptyList(),
        val currentFileIndex: Int = 0,
        val totalFiles: Int = 0,
        val batchStatus: String = ""
    ) {
        /** 전체 진행률 (완료된 파일 + 현재 파일 부분 진행률) */
        val batchProgress: Float
            get() = if (totalFiles > 0) {
                (currentFileIndex.toFloat() + progress) / totalFiles.toFloat()
            } else 0f
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** native context의 생성·전사·해제를 한 세션으로 직렬화한다. */
    private val sessionMutex = Mutex()
    private var activeSession: Job? = null

    @Volatile
    private var releaseWhenIdle = false

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TranscriptionService.ACTION_STATUS) return
            onServiceStatus(
                sessionId = intent.getStringExtra(TranscriptionService.EXTRA_SESSION_ID).orEmpty(),
                statusName = intent.getStringExtra(TranscriptionService.EXTRA_STATUS).orEmpty(),
                progress = intent.getFloatExtra(TranscriptionService.EXTRA_PROGRESS, 0f),
                currentChunk = intent.getIntExtra(TranscriptionService.EXTRA_CURRENT_CHUNK, 0),
                totalChunks = intent.getIntExtra(TranscriptionService.EXTRA_TOTAL_CHUNKS, 0),
                detail = intent.getStringExtra(TranscriptionService.EXTRA_DETAIL).orEmpty()
            )
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            serviceStatusReceiver,
            IntentFilter(TranscriptionService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loadHistory()
        loadResultLibrary()
        loadMediaLibrary()
        resumeIncompleteServiceSession()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { recorder.loadAll() }
            _uiState.update { it.copy(history = records) }
        }
    }

    private fun loadResultLibrary() {
        viewModelScope.launch {
            val sessions = withContext(Dispatchers.IO) { sessionStore.listAll() }
            _uiState.update { it.copy(resultSessions = sessions) }
        }
    }

    private fun loadMediaLibrary() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val enrichedAudios = mediaLibraryStore.listAudios().map { audio ->
                    if (audio.durationMs > 0L) {
                        audio
                    } else {
                        val durationMs = AudioDecoder.durationMs(audio.path) ?: 0L
                        if (durationMs > 0L) {
                            mediaLibraryStore.registerAudio(File(audio.path), audio.displayName, durationMs)
                        } else {
                            audio
                        }
                    }
                }
                val installedModels = mediaLibraryStore.listInstalledModels()
                val storedModel = mediaLibraryStore.selectedModelPath()
                val recentModel = sessionStore.listAll()
                    .firstOrNull { File(it.modelPath).isFile }
                    ?.modelPath
                    .orEmpty()
                val selectedModel = storedModel.ifBlank {
                    recentModel.ifBlank { installedModels.singleOrNull()?.path.orEmpty() }
                }
                if (storedModel.isBlank() && selectedModel.isNotBlank()) {
                    mediaLibraryStore.selectModel(selectedModel)
                }
                MediaSnapshot(
                    audios = enrichedAudios,
                    models = installedModels,
                    selectedAudioPath = mediaLibraryStore.selectedAudioPath(),
                    selectedModelPath = selectedModel,
                    hasIncompleteSession = sessionStore.latestIncomplete() != null
                )
            }
            _uiState.update { current ->
                val restoreAudio = snapshot.selectedAudioPath.takeIf { current.audioPaths.isEmpty() }
                current.copy(
                    audioLibrary = snapshot.audios,
                    installedModels = snapshot.models,
                    audioPaths = restoreAudio?.let(::listOf) ?: current.audioPaths,
                    audioPath = restoreAudio ?: current.audioPath,
                    totalFiles = if (restoreAudio != null) 1 else current.totalFiles
                )
            }
            val current = _uiState.value
            if (
                !snapshot.hasIncompleteSession &&
                snapshot.selectedModelPath.isNotBlank() &&
                !current.modelLoaded &&
                current.modelPath.isBlank() &&
                current.state == SttState.IDLE
            ) {
                loadModel(snapshot.selectedModelPath)
            }
        }
    }

    private fun resumeIncompleteServiceSession() {
        viewModelScope.launch {
            val checkpoint = withContext(Dispatchers.IO) { sessionStore.latestIncomplete() } ?: return@launch
            _uiState.update {
                it.copy(
                    state = SttState.RUNNING,
                    modelLoaded = true,
                    modelPath = checkpoint.modelPath,
                    audioPath = checkpoint.audioPath,
                    progress = checkpoint.progress,
                    totalFiles = 1,
                    currentFileIndex = 0,
                    batchStatus = "중단된 전사를 재개하는 중..."
                )
            }
            ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication(), TranscriptionService::class.java).apply {
                    action = TranscriptionService.ACTION_RESUME
                    putExtra(TranscriptionService.EXTRA_SESSION_ID, checkpoint.sessionId)
                }
            )
        }
    }

    private fun onServiceStatus(
        sessionId: String,
        statusName: String,
        progress: Float,
        currentChunk: Int,
        totalChunks: Int,
        detail: String
    ) {
        val status = runCatching { TranscriptionSessionStore.Status.valueOf(statusName) }.getOrNull() ?: return
        when (status) {
            TranscriptionSessionStore.Status.PREPARING,
            TranscriptionSessionStore.Status.RUNNING,
            TranscriptionSessionStore.Status.COOLING -> _uiState.update {
                it.copy(
                    state = SttState.RUNNING,
                    progress = progress.coerceIn(0f, 1f),
                    totalFiles = 1,
                    currentFileIndex = 0,
                    batchStatus = detail.ifBlank { "청크 $currentChunk/$totalChunks 처리 중" },
                    errorMessage = ""
                )
            }
            TranscriptionSessionStore.Status.COMPLETED -> viewModelScope.launch {
                val checkpoint = withContext(Dispatchers.IO) { sessionStore.load(sessionId) }
                val records = withContext(Dispatchers.IO) { recorder.loadAll() }
                val record = checkpoint?.let { completed ->
                    records.firstOrNull {
                        it.audioFile == File(completed.audioPath).name && it.note == completed.note
                    }
                }
                _uiState.update {
                    it.copy(
                        state = SttState.DONE,
                        progress = 1f,
                        result = checkpoint?.toResult(
                            modelSize = checkpoint.modelPath.let { path -> "${File(path).length() / 1024 / 1024}MB" }
                        ),
                        lastReport = record?.let(recorder::formatReport).orEmpty(),
                        batchStatus = detail.ifBlank { "전사 완료" },
                        errorMessage = ""
                    )
                }
                _uiState.update { it.copy(history = records) }
                loadResultLibrary()
            }
            TranscriptionSessionStore.Status.CANCELLED,
            TranscriptionSessionStore.Status.INTERRUPTED -> {
                _uiState.update {
                    it.copy(
                        state = if (it.modelLoaded) SttState.READY else SttState.IDLE,
                        batchStatus = detail.ifBlank { "전사 중단" },
                        errorMessage = ""
                    )
                }
                loadResultLibrary()
            }
            TranscriptionSessionStore.Status.FAILED -> {
                _uiState.update {
                    it.copy(state = SttState.ERROR, batchStatus = "", errorMessage = detail.ifBlank { "전사 실패" })
                }
                loadResultLibrary()
            }
        }
    }

    private fun startForegroundTranscription(modelPath: String, audioPath: String, note: String) {
        if (!isManagedReadableFile(modelPath) || !isManagedReadableFile(audioPath)) {
            _uiState.update {
                it.copy(state = SttState.ERROR, errorMessage = "전사는 앱 내부에 stage된 모델·오디오 파일만 사용할 수 있습니다")
            }
            return
        }
        _uiState.update {
            it.copy(
                state = SttState.RUNNING,
                progress = 0f,
                totalFiles = 1,
                currentFileIndex = 0,
                batchStatus = "Foreground 전사 서비스 시작 중...",
                errorMessage = ""
            )
        }
        viewModelScope.launch {
            // UI에서 미리 로드한 context가 남아 있으면 서비스의 모델 로드 전에 해제한다.
            withContext(Dispatchers.IO) { whisperEngine.release() }
            ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication(), TranscriptionService::class.java).apply {
                    action = TranscriptionService.ACTION_START
                    putExtra(TranscriptionService.EXTRA_MODEL_PATH, modelPath)
                    putExtra(TranscriptionService.EXTRA_AUDIO_PATH, audioPath)
                    putExtra(TranscriptionService.EXTRA_NOTE, note.take(MAX_AUTOMATION_NOTE_LENGTH))
                }
            )
        }
    }

    private fun isSessionBusy(): Boolean = activeSession?.isActive == true ||
        _uiState.value.state in setOf(
            SttState.LOADING_MODEL,
            SttState.RUNNING,
            SttState.CANCELLING
        )

    private fun rejectIfSessionBusy(action: String): Boolean {
        if (!isSessionBusy()) return false
        _uiState.update { it.copy(errorMessage = "실행 중에는 $action 할 수 없습니다") }
        return true
    }

    private fun launchExclusiveSession(name: String, block: suspend () -> Unit) {
        if (rejectIfSessionBusy(name)) return

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                sessionMutex.withLock { block() }
            } catch (cancelled: CancellationException) {
                Log.i("SttViewModel", "세션 취소: $name")
                _uiState.update {
                    it.copy(
                        state = if (it.modelLoaded) SttState.READY else SttState.IDLE,
                        batchStatus = "전사 취소됨",
                        errorMessage = ""
                    )
                }
                throw cancelled
            } finally {
                if (releaseWhenIdle) {
                    withContext(NonCancellable + Dispatchers.IO) { whisperEngine.release() }
                }
                if (activeSession === currentCoroutineContext()[Job]) {
                    activeSession = null
                }
            }
        }
        activeSession = job
        job.start()
    }

    /** 현재 청크가 끝나는 즉시 세션을 중지한다. 진행 중인 native 호출은 안전하게 반환된 뒤 종료된다. */
    fun cancelActiveSession() {
        val job = activeSession
        if (job == null || !job.isActive) {
            getApplication<Application>().startService(
                Intent(getApplication(), TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_CANCEL)
            )
            _uiState.update {
                it.copy(state = SttState.CANCELLING, batchStatus = "현재 작업을 안전하게 중지하는 중...", errorMessage = "")
            }
            return
        }
        _uiState.update {
            it.copy(
                state = SttState.CANCELLING,
                batchStatus = "현재 작업을 안전하게 중지하는 중...",
                errorMessage = ""
            )
        }
        job.cancel(CancellationException("사용자가 전사 세션을 취소함"))
    }

    fun loadModel(path: String) {
        if (path.isBlank()) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "모델 경로가 비어 있습니다") }
            return
        }
        launchExclusiveSession("모델을 변경") {
            _uiState.update { it.copy(state = SttState.LOADING_MODEL) }
            val ok = withContext(Dispatchers.IO) { whisperEngine.loadModel(path) }
            if (ok && isManagedReadableFile(path)) {
                withContext(Dispatchers.IO) { mediaLibraryStore.selectModel(path) }
            }
            _uiState.update {
                it.copy(
                    state = if (ok) SttState.READY else SttState.ERROR,
                    modelLoaded = ok,
                    modelPath = path,
                    errorMessage = if (!ok) "모델 로드 실패: $path" else ""
                )
            }
            if (ok) loadMediaLibrary()
        }
    }

    fun setAudioPath(path: String) {
        if (rejectIfSessionBusy("오디오를 변경")) return
        if (!isManagedReadableFile(path)) {
            _uiState.update { it.copy(errorMessage = "앱 내부 오디오 파일을 찾을 수 없습니다") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { mediaLibraryStore.selectAudio(path) }
            _uiState.update {
                it.copy(audioPath = path, audioPaths = listOf(path), totalFiles = 1, currentFileIndex = 0)
            }
            loadMediaLibrary()
        }
    }

    /** 자동 실행은 앱 내부에 stage된 읽기 가능한 파일만 허용한다. */
    private fun isManagedReadableFile(path: String): Boolean = try {
        val root = getApplication<Application>().filesDir.canonicalFile
        val candidate = File(path).canonicalFile
        candidate.isFile && candidate.canRead() &&
            candidate.path.startsWith(root.path + File.separator)
    } catch (_: Exception) {
        false
    }

    /**
     * SAF URI로 선택한 오디오 파일을 앱 내부로 복사.
     * 다운로드 폴더 등 외부 저장소에서 선택한 파일을 whisper.cpp가 읽을 수 있게 함.
     */
    fun copyAudioFromUri(uri: android.net.Uri) {
        launchExclusiveSession("오디오 파일을 가져오기") {
            _uiState.update { it.copy(state = SttState.LOADING_MODEL, errorMessage = "") }
            val copied = withContext(Dispatchers.IO) { copyAudioUri(uri) }
            if (copied == null) {
                _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "파일 복사 실패") }
                return@launchExclusiveSession
            }
            withContext(Dispatchers.IO) {
                val durationMs = AudioDecoder.durationMs(copied.path) ?: 0L
                mediaLibraryStore.registerAudio(File(copied.path), copied.displayName, durationMs)
                mediaLibraryStore.selectAudio(copied.path)
            }
            _uiState.update { current ->
                current.copy(
                    audioPath = copied.path,
                    audioPaths = listOf(copied.path),
                    totalFiles = 1,
                    currentFileIndex = 0,
                    batchStatus = "",
                    state = if (current.modelLoaded) SttState.READY else SttState.IDLE,
                    errorMessage = ""
                )
            }
            loadMediaLibrary()
        }
    }

    /** URI에서 파일명 추출 */
    private fun queryFileName(uri: android.net.Uri, resolver: android.content.ContentResolver): String {
        var name = "audio_${System.currentTimeMillis()}.wav"
        val cursor = resolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                it.getString(nameIndex)?.let { n -> name = n }
            }
        }
        return name
    }

    private data class CopiedAudio(val path: String, val displayName: String)

    /** 동일 표시명·복사 중 종료가 기존 장시간 원본을 덮어쓰지 않도록 UUID+part를 사용한다. */
    private fun copyAudioUri(uri: Uri): CopiedAudio? {
        var tempFile: File? = null
        return try {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val sourceName = queryFileName(uri, resolver)
            val extension = sourceName.substringAfterLast('.', "").lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "audio"
            val destFile = File(app.filesDir, "audio_${UUID.randomUUID()}.$extension")
            val pendingFile = File(app.filesDir, ".${destFile.name}.part")
            tempFile = pendingFile
            resolver.openInputStream(uri)?.use { input ->
                pendingFile.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("파일 열기 실패")
            if (pendingFile.length() <= 0L || !pendingFile.renameTo(destFile)) {
                throw IllegalStateException("오디오 임시 파일을 완료 파일로 변경하지 못했습니다")
            }
            Log.i("SttViewModel", "복사 완료: ${destFile.name} (${destFile.length() / 1024 / 1024}MB)")
            CopiedAudio(destFile.absolutePath, sourceName)
        } catch (error: Exception) {
            tempFile?.delete()
            Log.e("SttViewModel", "복사 실패: $uri", error)
            null
        }
    }

    /**
     * 다중 URI → 앱 내부로 복사 → audioPaths 설정.
     * 이미 선택된 목록이 있으면 뒤에 병합(중복 경로 제외).
     */
    fun copyAudioFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchExclusiveSession("오디오 목록을 변경") {
            _uiState.update { it.copy(state = SttState.LOADING_MODEL, errorMessage = "") }
            val existing = _uiState.value.audioPaths.toMutableList()
            val added = mutableListOf<String>()

            uris.forEach { uri ->
                val copied = withContext(Dispatchers.IO) {
                    copyAudioUri(uri)
                }
                if (copied != null && copied.path !in existing && copied.path !in added) {
                    withContext(Dispatchers.IO) {
                        val durationMs = AudioDecoder.durationMs(copied.path) ?: 0L
                        mediaLibraryStore.registerAudio(File(copied.path), copied.displayName, durationMs)
                    }
                    added.add(copied.path)
                }
            }

            val merged = existing + added
            if (merged.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        audioPaths = merged,
                        audioPath = merged.first(),
                        totalFiles = merged.size,
                        currentFileIndex = 0,
                        batchStatus = "",
                        state = SttState.READY,
                        errorMessage = if (added.isEmpty() && existing.isNotEmpty()) {
                            "새 파일이 추가되지 않았습니다"
                        } else {
                            ""
                        }
                    )
                }
                withContext(Dispatchers.IO) { mediaLibraryStore.selectAudio(merged.first()) }
                loadMediaLibrary()
            } else {
                _uiState.update {
                    it.copy(state = SttState.ERROR, errorMessage = "파일 복사 실패")
                }
            }
        }
    }

    /**
     * 다중 파일 배치 전사.
     * 각 파일은 transcribeSmart(10분 청크 분할 포함)로 처리하고,
     * 파일 간 10초 대기(발열 대응) 후 CSV에 개별 저장.
     */
    fun runBatchBenchmark() {
        val current = _uiState.value
        if (rejectIfSessionBusy("새 전사를 시작")) return
        if (!current.modelLoaded) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "모델을 먼저 로드하세요") }
            return
        }
        if (current.audioPaths.isEmpty()) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "오디오 파일을 선택하세요") }
            return
        }

        // SAF 선택은 단일 파일도 audioPaths에 넣는다. 6시간급 단일 파일은 반드시
        // 화면과 분리된 Foreground Service 경로로 실행한다.
        if (current.audioPaths.size == 1) {
            startForegroundTranscription(current.modelPath, current.audioPaths.first(), "")
            return
        }

        launchExclusiveSession("새 전사를 시작") {
            val files = current.audioPaths
            val modelPath = current.modelPath
            var lastSuccess: TranscriptionResult? = null
            var lastReport = ""
            var successCount = 0
            var failCount = 0

            _uiState.update {
                it.copy(
                    state = SttState.RUNNING,
                    progress = 0f,
                    totalFiles = files.size,
                    currentFileIndex = 0,
                    errorMessage = "",
                    lastReport = "",
                    result = null,
                    batchStatus = "파일 1/${files.size} 전사 중..."
                )
            }

            files.forEachIndexed { index, audioPath ->
                _uiState.update {
                    it.copy(
                        currentFileIndex = index,
                        audioPath = audioPath,
                        progress = 0.05f,
                        batchStatus = "파일 ${index + 1}/${files.size} 전사 중..."
                    )
                }

                Log.i(
                    "SttViewModel",
                    "═══ 파일 ${index + 1}/${files.size}: ${File(audioPath).name} ═══"
                )

                val result = try {
                    Result.success(withContext(Dispatchers.Default) {
                        transcribeSmart(audioPath, modelPath)
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                result
                    .onSuccess { res ->
                        successCount++
                        val modelName = modelPath.substringAfterLast("/")
                        val record = withContext(Dispatchers.IO) {
                            recorder.appendResult(
                                result = res,
                                audioFile = audioPath,
                                modelName = modelName,
                                note = "배치${index + 1}/${files.size}"
                            )
                        }
                        lastSuccess = res
                        lastReport = recorder.formatReport(record)
                        Log.i(
                            "SttViewModel",
                            "파일 ${index + 1} 완료: ${res.text.length}자, RTF=${res.rtf}"
                        )
                        _uiState.update {
                            it.copy(
                                progress = 1f,
                                result = res,
                                lastReport = lastReport
                            )
                        }
                    }
                    .onFailure { e ->
                        failCount++
                        Log.e("SttViewModel", "파일 ${index + 1} 실패: ${e.message}", e)
                        _uiState.update {
                            it.copy(
                                progress = 1f,
                                errorMessage = "파일 ${index + 1} 실패: ${e.message}"
                            )
                        }
                    }

                // 파일 간 발열 대기 (마지막 파일 제외)
                if (index < files.size - 1) {
                    _uiState.update { it.copy(batchStatus = "냉각 대기 중... (10초)") }
                    delay(10_000)
                }
            }

            val summary = buildString {
                append("전체 완료 (${successCount}성공")
                if (failCount > 0) append("/${failCount}실패")
                append(", ${files.size}개 파일)")
            }
            _uiState.update {
                it.copy(
                    state = if (successCount > 0) SttState.DONE else SttState.ERROR,
                    progress = 1f,
                    currentFileIndex = (files.size - 1).coerceAtLeast(0),
                    batchStatus = summary,
                    result = lastSuccess,
                    lastReport = lastReport.ifBlank { it.lastReport },
                    errorMessage = if (successCount == 0) {
                        "배치 전사 실패 (모든 파일)"
                    } else {
                        ""
                    }
                )
            }
        }
    }

    /** 파일 1개 제거 */
    fun removeAudioFile(index: Int) {
        if (rejectIfSessionBusy("오디오 목록을 변경")) return
        val current = _uiState.value
        val newPaths = current.audioPaths.toMutableList()
        if (index in newPaths.indices) {
            newPaths.removeAt(index)
            _uiState.update {
                it.copy(
                    audioPaths = newPaths,
                    audioPath = newPaths.firstOrNull() ?: "",
                    totalFiles = newPaths.size,
                    currentFileIndex = 0,
                    batchStatus = ""
                )
            }
        }
    }

    fun clearAudioSelection() {
        if (rejectIfSessionBusy("오디오 선택을 해제")) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { mediaLibraryStore.clearSelectedAudio() }
            _uiState.update {
                it.copy(
                    audioPath = "",
                    audioPaths = emptyList(),
                    totalFiles = 0,
                    currentFileIndex = 0,
                    batchStatus = "",
                    errorMessage = ""
                )
            }
        }
    }

    /** checkpoint JSON과 함께 보관된 전사 결과만 삭제한다. 원본 오디오는 유지한다. */
    fun deleteTranscriptionResult(sessionId: String) {
        viewModelScope.launch {
            val checkpoint = withContext(Dispatchers.IO) { sessionStore.load(sessionId) }
            if (checkpoint == null) {
                loadResultLibrary()
                return@launch
            }
            if (checkpoint.status in RESULT_DELETE_BLOCKED_STATUSES) {
                _uiState.update { it.copy(errorMessage = "진행 중이거나 재개 가능한 전사 결과는 삭제할 수 없습니다") }
                return@launch
            }
            val sessionResult = checkpoint.toResult()
            val deleted = withContext(Dispatchers.IO) {
                val checkpointDeleted = sessionStore.delete(sessionId)
                if (checkpointDeleted && checkpoint.status == TranscriptionSessionStore.Status.COMPLETED) {
                    recorder.deleteMatchingResult(
                        audioFile = checkpoint.audioPath,
                        modelName = File(checkpoint.modelPath).name,
                        note = checkpoint.note,
                        text = sessionResult.text
                    )
                }
                checkpointDeleted
            }
            if (!deleted) {
                _uiState.update { it.copy(errorMessage = "전사 결과를 삭제하지 못했습니다") }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    resultSessions = current.resultSessions.filterNot { it.sessionId == sessionId },
                    result = current.result.takeUnless { sessionResult.text == it?.text },
                    lastReport = if (current.result?.text == sessionResult.text) "" else current.lastReport,
                    errorMessage = ""
                )
            }
            loadHistory()
        }
    }

    /** 보관함 항목만 제거한다. 파일과 기존 전사 결과는 보존한다. */
    fun forgetAudioFromLibrary(path: String) {
        if (rejectIfSessionBusy("오디오 보관함을 변경")) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { mediaLibraryStore.forgetAudio(path) }
            _uiState.update { current ->
                val shouldClear = current.audioPath == path
                current.copy(
                    audioLibrary = current.audioLibrary.filterNot { it.path == path },
                    audioPaths = current.audioPaths.filterNot { it == path },
                    audioPath = if (shouldClear) current.audioPaths.firstOrNull { it != path }.orEmpty() else current.audioPath,
                    totalFiles = current.audioPaths.count { it != path }
                )
            }
        }
    }

    /** 실제 앱 내부 오디오 파일을 삭제한다. 재개 대상 세션의 입력 파일은 보호한다. */
    fun deleteAudioPermanently(path: String) {
        launchExclusiveSession("오디오 파일을 삭제") {
            val hasIncomplete = withContext(Dispatchers.IO) { sessionStore.hasIncompleteForAudio(path) }
            if (hasIncomplete) {
                _uiState.update { it.copy(errorMessage = "재개 가능한 전사에서 사용하는 오디오는 삭제할 수 없습니다") }
                return@launchExclusiveSession
            }
            val deleted = withContext(Dispatchers.IO) { mediaLibraryStore.deleteAudioFile(path) }
            if (!deleted) {
                _uiState.update { it.copy(errorMessage = "오디오 파일을 삭제하지 못했습니다") }
                return@launchExclusiveSession
            }
            _uiState.update { current ->
                val remainingPaths = current.audioPaths.filterNot { it == path }
                current.copy(
                    audioLibrary = current.audioLibrary.filterNot { it.path == path },
                    audioPaths = remainingPaths,
                    audioPath = if (current.audioPath == path) remainingPaths.firstOrNull().orEmpty() else current.audioPath,
                    totalFiles = remainingPaths.size,
                    currentFileIndex = 0,
                    batchStatus = "",
                    errorMessage = ""
                )
            }
        }
    }

    fun deleteInstalledModel(path: String) {
        val current = _uiState.value
        if (current.modelPath == path) {
            _uiState.update { it.copy(errorMessage = "현재 사용 중인 모델은 다른 모델로 변경한 뒤 삭제하세요") }
            return
        }
        launchExclusiveSession("모델 파일을 삭제") {
            val isResumeModel = withContext(Dispatchers.IO) {
                sessionStore.listAll().any {
                    it.modelPath == path && it.status in RESULT_DELETE_BLOCKED_STATUSES
                }
            }
            if (isResumeModel) {
                _uiState.update { it.copy(errorMessage = "재개 가능한 전사에서 사용하는 모델은 삭제할 수 없습니다") }
                return@launchExclusiveSession
            }
            val deleted = withContext(Dispatchers.IO) { mediaLibraryStore.deleteModelFile(path) }
            if (!deleted) {
                _uiState.update { it.copy(errorMessage = "모델 파일을 삭제하지 못했습니다") }
                return@launchExclusiveSession
            }
            loadMediaLibrary()
            _uiState.update { it.copy(errorMessage = "") }
        }
    }

    fun runBenchmark() = runBenchmarkInternal("")

    /**
     * 모델 로드 → 오디오 설정 → 전사를 하나의 코루틴에서 순차 보장.
     * broadcast/자동실행에서 사용.
     */
    fun loadAndTranscribe(modelPath: String, audioPath: String, note: String) {
        if (!isManagedReadableFile(modelPath) || !isManagedReadableFile(audioPath)) {
            _uiState.update {
                it.copy(
                    state = SttState.ERROR,
                    errorMessage = "자동 전사는 앱 내부에 stage된 모델·오디오 파일만 사용할 수 있습니다"
                )
            }
            return
        }
        if (rejectIfSessionBusy("새 전사를 시작")) return
        _uiState.update { it.copy(modelLoaded = true, modelPath = modelPath, audioPath = audioPath, state = SttState.READY) }
        startForegroundTranscription(modelPath, audioPath, note)
    }

    /** 모델 다운로드는 설치만 한다. 실제 전사 모델 전환은 사용자가 설치 목록에서 명시적으로 선택한다. */
    fun downloadModel(model: ModelDownloader.ModelInfo) {
        launchExclusiveSession("모델을 다운로드") {
            _uiState.update {
                it.copy(isDownloading = true, downloadProgress = 0f, downloadModelName = model.displayName,
                    errorMessage = "")
            }
            val file = modelDownloader.download(model) { prog ->
                _uiState.update { it.copy(downloadProgress = prog) }
            }
            if (file == null) {
                _uiState.update {
                    it.copy(isDownloading = false, state = SttState.ERROR,
                        errorMessage = "다운로드 실패: ${model.displayName}")
                }
                return@launchExclusiveSession
            }
            loadMediaLibrary()
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    state = if (it.modelLoaded) SttState.READY else SttState.IDLE,
                    batchStatus = "다운로드 완료: ${file.name}. 모델 변경에서 '사용'을 선택하세요",
                    errorMessage = ""
                )
            }
        }
    }

    /**
     * 히스토리에서 특정 오디오 재전사.
     * 모델이 로드되어 있지 않으면 현재/기본 모델 경로로 자동 로드 후 전사.
     */
    fun retranscribe(audioFile: String) {
        val baseDir = getApplication<Application>().filesDir.absolutePath
        val fullPath = if (audioFile.startsWith("/")) audioFile else "$baseDir/$audioFile"
        val current = _uiState.value
        if (!isManagedReadableFile(fullPath)) {
            _uiState.update {
                it.copy(state = SttState.ERROR, errorMessage = "재전사할 앱 내부 오디오 파일을 찾을 수 없습니다")
            }
            return
        }
        if (rejectIfSessionBusy("재전사를 시작")) return
        val modelPath = current.modelPath.ifBlank {
            mediaLibraryStore.selectedModelPath().ifBlank {
                ModelDownloader.MODELS.firstOrNull { it.displayName == "base" }
                    ?.installedFile(getApplication<Application>().filesDir)
                    ?.absolutePath
                    .orEmpty()
            }
        }
        if (!isManagedReadableFile(modelPath)) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "재전사에 사용할 설치 모델을 먼저 선택하세요") }
            return
        }
        _uiState.update {
            it.copy(modelLoaded = true, modelPath = modelPath, audioPath = fullPath, state = SttState.READY)
        }
        startForegroundTranscription(modelPath, fullPath, "재전사_$audioFile")
    }

    private fun runBenchmarkInternal(note: String) {
        val current = _uiState.value
        if (rejectIfSessionBusy("새 전사를 시작")) return
        if (!current.modelLoaded) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "모델을 먼저 로드하세요") }
            return
        }
        if (current.audioPath.isBlank()) {
            _uiState.update { it.copy(state = SttState.ERROR, errorMessage = "오디오 파일을 선택하세요") }
            return
        }

        startForegroundTranscription(current.modelPath, current.audioPath, note)
    }

    private suspend fun executeBenchmark(current: UiState, note: String) {
        _uiState.update { it.copy(state = SttState.RUNNING, progress = 0f, errorMessage = "") }

        try {
            val res = withContext(Dispatchers.Default) {
                transcribeSmart(current.audioPath, current.modelPath)
            }
            val modelName = current.modelPath.substringAfterLast("/")
            val record = withContext(Dispatchers.IO) {
                recorder.appendResult(
                    result = res,
                    audioFile = current.audioPath,
                    modelName = modelName,
                    note = note
                )
            }
            val report = recorder.formatReport(record)
            _uiState.update {
                it.copy(state = SttState.DONE, progress = 1f, result = res, lastReport = report)
            }
            loadHistory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("SttViewModel", "전사 실패", error)
            _uiState.update {
                it.copy(state = SttState.ERROR, errorMessage = "전사 실패: ${error.message}")
            }
        }
    }

    /**
     * 오디오 길이에 따라 단일/시간-청크 전사 자동 선택.
     * - 약 10분 이하: 단일 전사 (MediaCodec 전체 디코드 — wav/m4a/mp3)
     * - 10분 초과: 10분 창 분할
     *     - WAV → readWavRange
     *     - M4A/MP3 등 → MediaCodec 구간 디코드 (decodeWindow)
     */
    private suspend fun transcribeSmart(audioPath: String, modelPath: String): TranscriptionResult {
        val modelFile = File(modelPath)
        val modelSizeLabel = if (modelFile.exists()) {
            "${modelFile.length() / 1024 / 1024}MB"
        } else {
            modelFile.name
        }

        val durationMs = withContext(Dispatchers.IO) {
            AudioDecoder.durationMs(audioPath)
        } ?: throw IllegalStateException(
            "오디오 길이를 확인할 수 없습니다. 장시간 파일은 전체 디코딩으로 실행하지 않습니다"
        )

        // 약 10분 이하 → 단일 전사. duration 조회 실패는 위에서 명시적으로 중단한다.
        if (durationMs <= CHUNK_DURATION_MS + 1000L) {
            Log.i("SttViewModel", "단일 전사 (duration=${durationMs}ms) path=$audioPath")
            _uiState.update { it.copy(progress = 0.1f) }
            val result = whisperEngine.transcribe(audioPath)
            return result.copy(
                audioDurationMs = durationMs,
                chunkCoverage = listOf(
                    ChunkCoverage(
                        chunkIndex = 1,
                        primaryStartMs = 0L,
                        primaryEndMs = durationMs,
                        decodedStartMs = 0L,
                        decodedEndMs = result.audioDurationMs,
                        decodedSamples = (result.audioDurationMs * 16L).toInt()
                    )
                )
            )
        }

        // === 시간 청크 배치 (WAV / M4A / MP3) ===
        val totalChunks = ((durationMs + CHUNK_DURATION_MS - 1) / CHUNK_DURATION_MS).toInt()
        val formatHint = when {
            AudioDecoder.isPcmWavFile(audioPath) -> "wav"
            audioPath.endsWith(".m4a", true) || audioPath.endsWith(".mp4", true) -> "m4a"
            audioPath.endsWith(".mp3", true) -> "mp3"
            else -> "compressed"
        }
        Log.i(
            "SttViewModel",
            "시간 청크 전사 시작: ${durationMs}ms → ${totalChunks}개, format=$formatHint"
        )

        val allSegments = mutableListOf<TranscriptSegment>()
        val textBuilder = StringBuilder()
        val coverage = mutableListOf<ChunkCoverage>()
        var chunkIndex = 0
        var totalElapsedMs = 0L
        var pos = 0L

        while (pos < durationMs) {
            currentCoroutineContext().ensureActive()
            chunkIndex++
            val chunkDuration = minOf(CHUNK_DURATION_MS, durationMs - pos)
            val completedChunks = chunkIndex - 1
            val progress = 0.1f + 0.85f * (completedChunks.toFloat() / totalChunks.toFloat())
            _uiState.update {
                it.copy(
                    progress = progress.coerceIn(0f, 0.95f),
                    batchStatus = if (it.totalFiles > 1) {
                        it.batchStatus
                    } else {
                        "청크 $chunkIndex/$totalChunks 전사 중..."
                    }
                )
            }
            Log.i(
                "SttViewModel",
                "청크 $chunkIndex/$totalChunks (${pos}ms~${pos + chunkDuration}ms) [$formatHint]"
            )

            if (chunkIndex > 1) {
                Log.i("SttViewModel", "모델 재로드...")
                val reloaded = whisperEngine.loadModel(modelPath)
                if (!reloaded) {
                    throw RuntimeException("청크 $chunkIndex 모델 재로드 실패: $modelPath")
                }
            }

            val primaryEndMs = pos + chunkDuration
            val decodeStartMs = (pos - CHUNK_OVERLAP_MS).coerceAtLeast(0L)
            val decodeEndMs = (primaryEndMs + CHUNK_OVERLAP_MS).coerceAtMost(durationMs)
            val decodedWindow = withContext(Dispatchers.IO) {
                AudioDecoder.decodeWindowWithMetadata(
                    audioPath,
                    decodeStartMs,
                    decodeEndMs - decodeStartMs
                )
            }
            currentCoroutineContext().ensureActive()
            if (decodedWindow.isEmpty) {
                throw IllegalStateException(
                    "청크 $chunkIndex 디코드 실패 (primary=${pos}ms~${primaryEndMs}ms)"
                )
            }
            if (decodedWindow.decodedStartMs > pos + COVERAGE_TOLERANCE_MS ||
                decodedWindow.decodedEndMs < primaryEndMs - COVERAGE_TOLERANCE_MS) {
                throw IllegalStateException(
                    "청크 $chunkIndex PCM coverage 부족: primary=${pos}~$primaryEndMs, " +
                        "decoded=${decodedWindow.decodedStartMs}~${decodedWindow.decodedEndMs}"
                )
            }
            coverage += ChunkCoverage(
                chunkIndex = chunkIndex,
                primaryStartMs = pos,
                primaryEndMs = primaryEndMs,
                decodedStartMs = maxOf(pos, decodedWindow.decodedStartMs),
                decodedEndMs = minOf(primaryEndMs, decodedWindow.decodedEndMs),
                decodedSamples = decodedWindow.pcm.size
            )

            val chunkResult = whisperEngine.transcribePcm(
                decodedWindow.pcm,
                decodedWindow.decodedStartMs
            )
            val primarySegments = chunkResult.segments.filter { segment ->
                val midpoint = (segment.startMs + segment.endMs) / 2L
                midpoint >= pos && midpoint < primaryEndMs
            }
            allSegments.addAll(primarySegments)
            primarySegments.joinToString(" ") { it.text.trim() }
                .takeIf { it.isNotBlank() }
                ?.let { textBuilder.append(it).append(' ') }
            totalElapsedMs += chunkResult.elapsedMs

            Log.i(
                "SttViewModel",
                "청크 $chunkIndex 완료: primarySegs=${primarySegments.size}/${chunkResult.segments.size}, " +
                    "${chunkResult.elapsedMs}ms, pcm=${decodedWindow.pcm.size}, " +
                    "coverage=${decodedWindow.decodedStartMs}~${decodedWindow.decodedEndMs}"
            )
            pos += chunkDuration

            // 발열 대응: 마지막 청크가 아니면 냉각 대기
            // - 매 청크 후 짧은 대기
            // - N청크마다 긴 휴식 (쓰로틀 완화)
            if (pos < durationMs) {
                val longRest = chunkIndex % CHUNK_LONG_REST_EVERY == 0
                val coolMs = if (longRest) CHUNK_LONG_REST_MS else CHUNK_COOLDOWN_MS
                val coolSec = (coolMs / 1000).toInt()
                val status = if (longRest) {
                    "냉각 대기 중... (${coolSec}초, ${CHUNK_LONG_REST_EVERY}청크 주기)"
                } else {
                    "냉각 대기 중... (${coolSec}초)"
                }
                Log.i("SttViewModel", "청크 간 $status")
                _uiState.update { state ->
                    state.copy(
                        batchStatus = if (state.totalFiles > 1) {
                            // 다중 파일 배치 중이면 파일 상태 유지 + 냉각 표시
                            "파일 ${state.currentFileIndex + 1}/${state.totalFiles} · $status"
                        } else {
                            status
                        }
                    )
                }
                delay(coolMs)
            }
        }

        verifyCoverage(coverage, durationMs)

        Log.i(
            "SttViewModel",
            "시간 청크 전사 완료: segs=${allSegments.size}, chars=${textBuilder.length}, " +
                "elapsed=${totalElapsedMs}ms, coverage=${coverage.size}/$totalChunks"
        )

        return TranscriptionResult(
            text = textBuilder.toString().trim(),
            segments = allSegments,
            elapsedMs = totalElapsedMs,
            audioDurationMs = durationMs,
            modelSize = modelSizeLabel,
            engineName = whisperEngine.engineName,
            chunkCoverage = coverage
        )
    }

    private fun verifyCoverage(coverage: List<ChunkCoverage>, durationMs: Long) {
        if (coverage.isEmpty()) {
            throw IllegalStateException("PCM coverage가 비어 있습니다")
        }
        var cursorMs = 0L
        coverage.sortedBy { it.primaryStartMs }.forEach { chunk ->
            if (chunk.decodedStartMs > cursorMs + COVERAGE_TOLERANCE_MS ||
                chunk.decodedEndMs < chunk.primaryEndMs - COVERAGE_TOLERANCE_MS) {
                throw IllegalStateException(
                    "PCM coverage 불연속: cursor=$cursorMs, " +
                        "chunk=${chunk.decodedStartMs}~${chunk.decodedEndMs}, " +
                        "primary=${chunk.primaryStartMs}~${chunk.primaryEndMs}"
                )
            }
            cursorMs = maxOf(cursorMs, chunk.decodedEndMs)
        }
        if (cursorMs < durationMs - COVERAGE_TOLERANCE_MS) {
            throw IllegalStateException("PCM coverage 미완료: $cursorMs/$durationMs ms")
        }
    }

    companion object {
        private const val MAX_AUTOMATION_NOTE_LENGTH = 200
        private const val CHUNK_DURATION_MS = 10 * 60 * 1000L  // 10분
        /** 경계 인식 손실을 줄이기 위한 좌우 오디오 문맥. */
        private const val CHUNK_OVERLAP_MS = 1_000L
        private const val COVERAGE_TOLERANCE_MS = 50L
        /** 시간 청크 사이 기본 냉각 (다중 파일 간 대기와 동일 계열) */
        private const val CHUNK_COOLDOWN_MS = 10_000L
        /** N청크마다 추가 긴 휴식 */
        private const val CHUNK_LONG_REST_EVERY = 5
        private const val CHUNK_LONG_REST_MS = 30_000L
        val RESULT_DELETE_BLOCKED_STATUSES = setOf(
            TranscriptionSessionStore.Status.PREPARING,
            TranscriptionSessionStore.Status.RUNNING,
            TranscriptionSessionStore.Status.COOLING,
            TranscriptionSessionStore.Status.INTERRUPTED
        )
    }

    override fun onCleared() {
        releaseWhenIdle = true
        try {
            getApplication<Application>().unregisterReceiver(serviceStatusReceiver)
        } catch (_: Exception) {
            // 이미 해제된 receiver
        }
        val session = activeSession
        if (session?.isActive == true) {
            session.cancel(CancellationException("ViewModel이 해제됨"))
        } else {
            whisperEngine.release()
        }
        super.onCleared()
    }
}
