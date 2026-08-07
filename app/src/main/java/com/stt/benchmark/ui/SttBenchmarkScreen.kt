package com.stt.benchmark.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.stt.benchmark.data.BenchmarkRecorder
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.ModelDownloader
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.whisper.TranscriptionResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * STT 벤치마크 메인 화면.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SttBenchmarkScreen(viewModel: SttViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showPerformanceHistory by remember { mutableStateOf(false) }
    val controlsLocked = uiState.isDownloading || uiState.state in setOf(
        SttViewModel.SttState.LOADING_MODEL,
        SttViewModel.SttState.RUNNING,
        SttViewModel.SttState.CANCELLING
    )

    // 6시간 전사 기본 흐름은 한 번에 오디오 한 개를 선택한다.
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.copyAudioFromUri(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("STT 벤치마크", fontWeight = FontWeight.Bold) },
                actions = {
                    if (uiState.history.isNotEmpty()) {
                        IconButton(onClick = { showPerformanceHistory = true }) {
                            Icon(Icons.Default.Assessment, contentDescription = "성능 기록")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. 디바이스 정보 ──
            InfoCard(
                title = "기기 정보",
                icon = Icons.Default.Memory,
                content = uiState.deviceInfo.toString()
            )

            // ── 2. 모델 선택 + 다운로드 ──
            ModelSelectionCard(
                modelLoaded = uiState.modelLoaded,
                modelPath = uiState.modelPath,
                isDownloading = uiState.isDownloading,
                downloadProgress = uiState.downloadProgress,
                downloadModelName = uiState.downloadModelName,
                availableModels = viewModel.availableModels,
                installedModels = uiState.installedModels,
                controlsEnabled = !controlsLocked,
                onDownload = { viewModel.downloadModel(it) },
                onLoadModel = { path -> viewModel.loadModel(path) },
                onDeleteModel = { path -> viewModel.deleteInstalledModel(path) }
            )

            // ── 3. 오디오 파일 선택 ──
            AudioSelectionCard(
                audioPath = uiState.audioPath,
                audioLibrary = uiState.audioLibrary,
                resultSessions = uiState.resultSessions,
                controlsEnabled = !controlsLocked,
                onPickAudio = { audioPicker.launch("*/*") },
                onClearSelection = { viewModel.clearAudioSelection() },
                onSelectLibraryAudio = { viewModel.setAudioPath(it) },
                onForgetAudio = { viewModel.forgetAudioFromLibrary(it) },
                onDeleteAudio = { viewModel.deleteAudioPermanently(it) }
            )

            // ── 4. 벤치마크 실행 ──
            RunCard(
                state = uiState.state,
                progress = uiState.progress,
                batchProgress = uiState.batchProgress,
                batchStatus = uiState.batchStatus,
                totalFiles = uiState.totalFiles,
                currentFileIndex = uiState.currentFileIndex,
                canRun = !controlsLocked && uiState.modelLoaded && (
                    uiState.audioPaths.isNotEmpty() || uiState.audioPath.isNotBlank()
                    ),
                onRun = {
                    when {
                        uiState.audioPaths.isNotEmpty() -> viewModel.runBatchBenchmark()
                        uiState.audioPath.isNotBlank() -> viewModel.runBenchmark()
                    }
                },
                onCancel = { viewModel.cancelActiveSession() }
            )

            // ── 5. 세션 JSON 기반 전사 결과 보관함 ──
            if (uiState.resultSessions.isNotEmpty()) {
                ResultLibraryCard(
                    sessions = uiState.resultSessions,
                    audioLibrary = uiState.audioLibrary,
                    onDelete = { viewModel.deleteTranscriptionResult(it) }
                )
            }

            // ── 에러 메시지 ──
            if (uiState.errorMessage.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "⚠ ${uiState.errorMessage}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    if (showPerformanceHistory) {
        PerformanceHistoryDialog(
            history = uiState.history,
            onDismiss = { showPerformanceHistory = false }
        )
    }
}

@Composable
private fun InfoCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    content,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionCard(
    modelLoaded: Boolean,
    modelPath: String,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadModelName: String,
    availableModels: List<ModelDownloader.ModelInfo>,
    installedModels: List<MediaLibraryStore.ModelEntry>,
    controlsEnabled: Boolean,
    onDownload: (ModelDownloader.ModelInfo) -> Unit,
    onLoadModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit
) {
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showInstalledDialog by remember { mutableStateOf(false) }
    var showInputDialog by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<MediaLibraryStore.ModelEntry?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Whisper 모델", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            if (modelLoaded) {
                Text(
                    "✓ 로드됨: $modelPath",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace, maxLines = 1
                )
            } else if (isDownloading) {
                Text(
                    "⬇ 다운로드 중: $downloadModelName (${(downloadProgress * 100).toInt()}%)",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "모델을 다운로드하거나 경로를 입력하세요",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showInstalledDialog = true },
                    enabled = controlsEnabled && !isDownloading
                ) { Text("모델 변경") }
                OutlinedButton(
                    onClick = { showDownloadDialog = true },
                    enabled = controlsEnabled && !isDownloading
                ) { Text("다운로드") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { showInputDialog = true },
                enabled = controlsEnabled && !isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("고급: 앱 내부 모델 경로 입력")
            }
        }
    }

    if (showInstalledDialog) {
        AlertDialog(
            onDismissRequest = { showInstalledDialog = false },
            title = { Text("설치된 모델 (${installedModels.size}개)") },
            text = {
                if (installedModels.isEmpty()) {
                    Text("설치된 모델이 없습니다. 다운로드를 먼저 진행하세요.")
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        installedModels.forEach { model ->
                            val isCurrent = model.path == modelPath
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${formatFileSize(model.sizeBytes)}${if (isCurrent) " · 현재 사용" else ""}",
                                        fontSize = 11.sp,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    onLoadModel(model.path)
                                    showInstalledDialog = false
                                }) { Text(if (isCurrent) "다시 로드" else "사용") }
                                TextButton(
                                    onClick = { modelToDelete = model },
                                    enabled = !isCurrent
                                ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInstalledDialog = false }) { Text("닫기") } }
        )
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("모델 선택") },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    availableModels.forEach { model ->
                        val installed = installedModels.firstOrNull { it.path.substringAfterLast('/') == model.fileName }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    if (installed != null) onLoadModel(installed.path) else onDownload(model)
                                    showDownloadDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${model.displayName} (${model.sizeMb}MB)",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (installed != null) "설치됨 · 선택하면 사용" else model.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("취소") }
            }
        )
    }

    if (showInputDialog) {
        var inputPath by remember { mutableStateOf("/data/user/0/com.stt.benchmark/files/models/ggml-base.bin") }
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = { Text("모델 파일 경로") },
            text = {
                OutlinedTextField(
                    value = inputPath,
                    onValueChange = { inputPath = it },
                    label = { Text("절대경로") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLoadModel(inputPath)
                    showInputDialog = false
                }) { Text("로드") }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) { Text("취소") }
            }
        )
    }

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("모델 삭제") },
            text = { Text("${model.displayName} (${formatFileSize(model.sizeBytes)}) 파일을 삭제합니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteModel(model.path)
                    modelToDelete = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { modelToDelete = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun AudioSelectionCard(
    audioPath: String,
    audioLibrary: List<MediaLibraryStore.AudioEntry>,
    resultSessions: List<TranscriptionSessionStore.Checkpoint>,
    controlsEnabled: Boolean,
    onPickAudio: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectLibraryAudio: (String) -> Unit,
    onForgetAudio: (String) -> Unit,
    onDeleteAudio: (String) -> Unit
) {
    var showLibrary by remember { mutableStateOf(false) }
    var audioToDelete by remember { mutableStateOf<MediaLibraryStore.AudioEntry?>(null) }
    var menuForPath by remember { mutableStateOf<String?>(null) }
    val selectedAudio = audioLibrary.firstOrNull { it.path == audioPath }
    val selectedFile = audioPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("선택한 오디오", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            if (audioPath.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "전사할 오디오를 선택하세요",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (audioLibrary.isEmpty()) onPickAudio() else showLibrary = true },
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("오디오 선택")
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    selectedAudio?.displayName ?: selectedFile?.name.orEmpty(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Spacer(Modifier.height(4.dp))
                val metadata = buildList {
                    val durationMs = selectedAudio?.durationMs ?: 0L
                    if (durationMs > 0L) add(formatDuration(durationMs))
                    selectedFile?.extension?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                    val bytes = selectedAudio?.sizeBytes ?: selectedFile?.takeIf { it.exists() }?.length() ?: 0L
                    if (bytes > 0L) add(formatFileSize(bytes))
                }
                Text(
                    metadata.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showLibrary = true },
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("오디오 변경") }
                    OutlinedButton(
                        onClick = onClearSelection,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("선택 해제") }
                }
            }
        }
    }

    if (showLibrary) {
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("오디오 변경") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FilledTonalButton(
                        onClick = {
                            showLibrary = false
                            onPickAudio()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("새 파일 가져오기")
                    }
                    Spacer(Modifier.height(10.dp))
                    if (audioLibrary.isEmpty()) {
                        Text(
                            "이전에 가져온 오디오가 없습니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        audioLibrary.forEach { audio ->
                            val selected = audio.path == audioPath
                            val hasSameName = audioLibrary.count { it.displayName == audio.displayName } > 1
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onSelectLibraryAudio(audio.path)
                                            showLibrary = false
                                        }
                                ) {
                                    Text(
                                        text = audio.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = buildList {
                                            if (audio.durationMs > 0L) add(formatDuration(audio.durationMs))
                                            add(java.io.File(audio.path).extension.uppercase())
                                            add(formatFileSize(audio.sizeBytes))
                                            if (selected) add("현재 선택")
                                            if (hasSameName) add("ID ${audio.id.take(8)}")
                                        }.joinToString(" · "),
                                        fontSize = 11.sp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    if (!selected) {
                                        onSelectLibraryAudio(audio.path)
                                        showLibrary = false
                                    }
                                }) { Text(if (selected) "선택됨" else "선택") }
                                Box {
                                    IconButton(onClick = { menuForPath = audio.path }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "오디오 관리")
                                    }
                                    DropdownMenu(
                                        expanded = menuForPath == audio.path,
                                        onDismissRequest = { menuForPath = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("보관함에서 숨기기") },
                                            onClick = {
                                                menuForPath = null
                                                onForgetAudio(audio.path)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("파일 완전 삭제", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                menuForPath = null
                                                showLibrary = false
                                                audioToDelete = audio
                                            }
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLibrary = false }) { Text("닫기") } }
        )
    }

    audioToDelete?.let { audio ->
        val linkedResultCount = resultSessions.count { it.audioPath == audio.path }
        AlertDialog(
            onDismissRequest = { audioToDelete = null },
            title = { Text("오디오 파일 삭제") },
            text = {
                Text(
                    "${audio.displayName} (${formatFileSize(audio.sizeBytes)})를 앱 저장소에서 삭제합니다. " +
                        "저장된 전사 결과 ${linkedResultCount}개는 유지되지만 이 오디오는 다시 전사할 수 없습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAudio(audio.path)
                    audioToDelete = null
                }) { Text("파일 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { audioToDelete = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun RunCard(
    state: SttViewModel.SttState,
    progress: Float,
    batchProgress: Float,
    batchStatus: String,
    totalFiles: Int,
    currentFileIndex: Int,
    canRun: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit
) {
    val isBatch = totalFiles > 1
    val filePct = (progress * 100).toInt().coerceIn(0, 100)
    val batchPct = (batchProgress * 100).toInt().coerceIn(0, 100)
    val statusText = when {
        batchStatus.isNotBlank() && isBatch && !batchStatus.contains("냉각") && !batchStatus.contains("완료") -> {
            "파일 ${currentFileIndex + 1}/$totalFiles 전사 중 ${filePct}%"
        }
        batchStatus.isNotBlank() -> batchStatus
        else -> "처리 중... ${filePct}%"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("벤치마크 실행", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))

            if (state == SttViewModel.SttState.RUNNING || state == SttViewModel.SttState.CANCELLING) {
                if (isBatch) {
                    Text(
                        "전체 진행 $batchPct%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { batchProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "현재 파일 $filePct%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    statusText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state == SttViewModel.SttState.RUNNING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state == SttViewModel.SttState.CANCELLING) "안전하게 중지하는 중" else "전사 중지")
                }
            } else {
                Button(
                    onClick = onRun,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (totalFiles > 1) "배치 STT 실행 (${totalFiles}개)" else "STT 실행")
                }
            }
        }
    }
}

@Composable
private fun ResultLibraryCard(
    sessions: List<TranscriptionSessionStore.Checkpoint>,
    audioLibrary: List<MediaLibraryStore.AudioEntry>,
    onDelete: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TranscriptionSessionStore.Checkpoint?>(null) }
    var deleteTarget by remember { mutableStateOf<TranscriptionSessionStore.Checkpoint?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("전사 결과", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "${sessions.size}개 · 진행 상태와 전체 텍스트",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "접기" else "보기")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                sessions.forEach { session ->
                    val result = session.toResult()
                    val audioName = audioLibrary.firstOrNull { it.path == session.audioPath }?.displayName
                        ?: session.audioPath.substringAfterLast('/')
                    val isProtected = session.status in setOf(
                        TranscriptionSessionStore.Status.PREPARING,
                        TranscriptionSessionStore.Status.RUNNING,
                        TranscriptionSessionStore.Status.COOLING,
                        TranscriptionSessionStore.Status.INTERRUPTED
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                audioName,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            Text(
                                "${session.statusLabel()} · ${result.text.length}자 · ${session.chunks.size}/${session.totalChunks} 청크",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { selected = session }) { Text("보기", fontSize = 11.sp) }
                        TextButton(onClick = { deleteTarget = session }, enabled = !isProtected) {
                            Text("삭제", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { session ->
        val result = session.toResult()
        val audioName = audioLibrary.firstOrNull { it.path == session.audioPath }?.displayName
            ?: session.audioPath.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("$audioName · ${session.statusLabel()}", fontSize = 13.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text("모델: ${session.modelPath.substringAfterLast('/')} · ${session.chunks.size}/${session.totalChunks} 청크", fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    if (result.segments.isEmpty()) {
                        Text(result.text.ifBlank { "저장된 전사 텍스트가 없습니다." }, fontSize = 13.sp)
                    } else {
                        result.segments.forEach { segment ->
                            Text(
                                "[${formatTimestamp(segment.startMs)} - ${formatTimestamp(segment.endMs)}]",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(segment.text.trim(), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("닫기") } }
        )
    }

    deleteTarget?.let { session ->
        val audioName = audioLibrary.firstOrNull { it.path == session.audioPath }?.displayName
            ?: session.audioPath.substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("전사 결과 삭제") },
            text = {
                Text(
                    "${audioName}의 저장된 전사 텍스트와 타임스탬프를 삭제합니다. " +
                        "일치하는 CSV 벤치마크 기록도 함께 삭제합니다. 원본 오디오와 모델은 삭제되지 않습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session.sessionId)
                    deleteTarget = null
                }) { Text("결과 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun ResultCard(report: String, result: TranscriptionResult?) {
    var showFullText by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(report, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

            if (result != null && result.text.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("추출된 텍스트 (일부)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.text.take(500) + if (result.text.length > 500) "..." else "",
                    fontSize = 12.sp,
                    maxLines = 10
                )

                if (result.text.length > 500) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showFullText = true }) {
                        Text("전체 텍스트 보기 (${result.text.length}자)")
                    }
                }
            }
        }
    }

    // 전체 텍스트 팝업 다이얼로그
    if (showFullText && result != null) {
        AlertDialog(
            onDismissRequest = { showFullText = false },
            title = {
                Text("추출된 전체 텍스트 (${result.text.length}자)")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 세그먼트별로 타임스탬프와 함께 표시
                    if (result.segments.isNotEmpty()) {
                        result.segments.forEach { seg ->
                            val startStr = formatTimestamp(seg.startMs)
                            val endStr = formatTimestamp(seg.endMs)
                            Text(
                                text = "[$startStr - $endStr]",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = seg.text.trim(),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    } else {
                        Text(
                            text = result.text,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullText = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

/** 밀리초를 MM:SS 형식으로 변환 */
private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = (totalSec / 60).toInt()
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1fGB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun PerformanceHistoryDialog(
    history: List<BenchmarkRecorder.BenchmarkRecord>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("성능 기록 (${history.size}개)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "CSV에 저장된 처리 속도와 모델 기록입니다. 전사 텍스트는 전사 결과에서 확인하세요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                history.asReversed().forEach { record ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(record.audioFile, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            "${record.timestamp} · ${record.modelName}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "오디오 ${formatDuration(record.audioDurationMs)} · 처리 ${formatDuration(record.elapsedMs)} · " +
                                "RTF %.3f · %.2fx".format(record.rtf, record.speedMultiplier),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

private fun TranscriptionSessionStore.Checkpoint.statusLabel(): String = when (status) {
    TranscriptionSessionStore.Status.PREPARING -> "준비 중"
    TranscriptionSessionStore.Status.RUNNING -> "전사 중"
    TranscriptionSessionStore.Status.COOLING -> "냉각 대기"
    TranscriptionSessionStore.Status.COMPLETED -> "완료"
    TranscriptionSessionStore.Status.FAILED -> "실패"
    TranscriptionSessionStore.Status.CANCELLED -> "취소됨"
    TranscriptionSessionStore.Status.INTERRUPTED -> "재개 대기"
}

@Composable
private fun HistoryCard(history: List<BenchmarkRecorder.BenchmarkRecord>, viewModel: SttViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<BenchmarkRecorder.BenchmarkRecord?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("벤치마크 CSV 기록", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "${history.size}개 • ${history.sumOf { it.charCount }}자 • RTF 평균 %.3f".format(
                            if (history.isNotEmpty()) history.sumOf { it.rtf.toDouble() } / history.size else 0.0
                        ),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "접기" else "펼치기")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                history.forEach { record ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 파일 정보
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                record.audioFile,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "RTF %.2f • ${record.charCount}자 • ${record.elapsedMs/1000}초".format(record.rtf),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 텍스트 보기 버튼
                        TextButton(
                            onClick = { selectedRecord = record },
                            enabled = record.text.isNotBlank()
                        ) { Text("텍스트", fontSize = 11.sp) }
                        // 재전사 버튼
                        TextButton(onClick = { viewModel.retranscribe(record.audioFile) }) {
                            Text("재전사", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // 전체 텍스트 팝업
    selectedRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = { Text("${record.audioFile} (${record.charCount}자)", fontSize = 13.sp) },
            text = {
                Text(
                    record.text,
                    fontSize = 13.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { selectedRecord = null }) { Text("닫기") } }
        )
    }
}
