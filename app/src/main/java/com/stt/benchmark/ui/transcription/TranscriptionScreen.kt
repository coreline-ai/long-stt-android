package com.stt.benchmark.ui.transcription

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.SectionLabel
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration
import java.io.File

@Composable
fun TranscriptionRoute(
    viewModel: SttViewModel,
    onOpenSettings: () -> Unit,
    onOpenCompletedResult: (CompletedResultTarget) -> Unit = {},
    modifier: Modifier = Modifier,
    routeViewModel: TranscriptionRouteViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val routeState by routeViewModel.uiState.collectAsStateWithLifecycle()
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(viewModel::copyAudioFromUri)
    }

    LaunchedEffect(routeState.dialog, routeState.deleteAudioPath, state.audioLibrary) {
        if (
            routeState.dialog == TranscriptionDialog.DELETE_AUDIO &&
            state.audioLibrary.none { it.path == routeState.deleteAudioPath }
        ) {
            routeViewModel.dismissDialog()
        }
    }

    TranscriptionScreen(
        state = state,
        routeState = routeState,
        onOpenModelPicker = routeViewModel::showModelPicker,
        onOpenAudioPicker = routeViewModel::showAudioPicker,
        onOpenAudioMenu = routeViewModel::showAudioMenu,
        onDismissAudioMenu = routeViewModel::dismissAudioMenu,
        onRequestAudioDeletion = routeViewModel::confirmAudioDeletion,
        onDismissDialog = routeViewModel::dismissDialog,
        onPickAudio = { audioPicker.launch("*/*") },
        onSelectModel = viewModel::loadModel,
        onSelectAudio = viewModel::setAudioPath,
        onClearAudio = viewModel::clearAudioSelection,
        onForgetAudio = viewModel::forgetAudioFromLibrary,
        onDeleteAudio = viewModel::deleteAudioPermanently,
        onRun = viewModel::runBenchmark,
        onCancel = viewModel::cancelActiveSession,
        onOpenSettings = onOpenSettings,
        onOpenCompletedResult = onOpenCompletedResult,
        modifier = modifier,
    )
}

@Composable
fun TranscriptionScreen(
    state: SttViewModel.UiState,
    routeState: TranscriptionRouteUiState,
    onOpenModelPicker: () -> Unit,
    onOpenAudioPicker: () -> Unit,
    onOpenAudioMenu: (String) -> Unit,
    onDismissAudioMenu: () -> Unit,
    onRequestAudioDeletion: (String) -> Unit,
    onDismissDialog: () -> Unit,
    onPickAudio: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onClearAudio: () -> Unit,
    onForgetAudio: (String) -> Unit,
    onDeleteAudio: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCompletedResult: (CompletedResultTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val controlsLocked = state.isDownloading || state.state in setOf(
        SttViewModel.SttState.LOADING_MODEL,
        SttViewModel.SttState.RUNNING,
        SttViewModel.SttState.CANCELLING,
    )
    // 제품 UI는 화면에 표시된 단일 audioPath만 실행한다. 숨은 legacy batch 목록으로
    // 버튼이 활성화되거나 다른 파일이 시작되지 않도록 시각 상태와 실행 입력을 일치시킨다.
    val canRun = !controlsLocked && state.modelLoaded && state.audioPath.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "전사 작업",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "오디오와 설치 모델을 고른 뒤 기기 안에서 구간별 전사를 시작합니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SectionLabel("전사 준비")

        ModelSelectionSection(
            state = state,
            controlsEnabled = !controlsLocked,
            onOpenPicker = onOpenModelPicker,
            onOpenSettings = onOpenSettings,
        )
        AudioSelectionSection(
            state = state,
            controlsEnabled = !controlsLocked,
            onOpenPicker = onOpenAudioPicker,
            onClear = onClearAudio,
        )
        TranscriptionRunSection(
            state = state,
            canRun = canRun,
            onRun = onRun,
            onCancel = onCancel,
            onOpenCompletedResult = onOpenCompletedResult,
        )

        if (state.errorMessage.isNotBlank()) {
            Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "⚠ ${state.errorMessage}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    when (routeState.dialog) {
        TranscriptionDialog.MODEL_PICKER -> ModelPickerDialog(
            installedModels = state.installedModels,
            selectedPath = state.modelPath,
            onSelect = {
                onSelectModel(it)
                onDismissDialog()
            },
            onOpenSettings = {
                onDismissDialog()
                onOpenSettings()
            },
            onDismiss = onDismissDialog,
        )
        TranscriptionDialog.AUDIO_PICKER -> AudioPickerDialog(
            state = state,
            routeState = routeState,
            onPickAudio = {
                onDismissDialog()
                onPickAudio()
            },
            onSelect = {
                onSelectAudio(it)
                onDismissDialog()
            },
            onOpenMenu = onOpenAudioMenu,
            onDismissMenu = onDismissAudioMenu,
            onForget = onForgetAudio,
            onDelete = onRequestAudioDeletion,
            onDismiss = onDismissDialog,
        )
        TranscriptionDialog.DELETE_AUDIO -> state.audioLibrary
            .firstOrNull { it.path == routeState.deleteAudioPath }
            ?.let { audio ->
                AudioDeleteDialog(
                    audio = audio,
                    linkedResultCount = state.resultSessions.count { it.audioPath == audio.path },
                    onDelete = {
                        onDeleteAudio(audio.path)
                        onDismissDialog()
                    },
                    onDismiss = onDismissDialog,
                )
            }
        TranscriptionDialog.NONE -> Unit
    }
}

@Composable
private fun ModelSelectionSection(
    state: SttViewModel.UiState,
    controlsEnabled: Boolean,
    onOpenPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Whisper 모델", style = MaterialTheme.typography.titleMedium)
                StatusPill(
                    text = when {
                        state.isDownloading -> "설치 중"
                        state.modelLoaded -> "선택됨"
                        else -> "필요"
                    },
                    tone = when {
                        state.isDownloading -> ArchiveStatusTone.ACTIVE
                        state.modelLoaded -> ArchiveStatusTone.COMPLETE
                        else -> ArchiveStatusTone.NEUTRAL
                    },
                )
            }
            Text(
                state.modelPath.takeIf { state.modelLoaded }?.substringAfterLast('/')
                    ?: "선택된 모델이 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.isDownloading) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.downloadModelName} ${(state.downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.installedModels.isEmpty()) {
                Button(
                    onClick = onOpenSettings,
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text("설정에서 모델 설치") }
            } else {
                OutlinedButton(
                    onClick = onOpenPicker,
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text("설치 모델 선택") }
            }
        }
    }
}

@Composable
private fun AudioSelectionSection(
    state: SttViewModel.UiState,
    controlsEnabled: Boolean,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
) {
    val audio = state.audioLibrary.firstOrNull { it.path == state.audioPath }
    val file = state.audioPath.takeIf(String::isNotBlank)?.let(::File)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("선택한 오디오", style = MaterialTheme.typography.titleMedium)
            if (state.audioPath.isBlank()) {
                Text(
                    "전사할 오디오를 선택하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenPicker,
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("오디오 선택")
                }
            } else {
                Text(
                    audio?.displayName ?: file?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildList {
                        audio?.durationMs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
                        file?.extension?.takeIf(String::isNotBlank)?.let { add(it.uppercase()) }
                        (audio?.sizeBytes ?: file?.takeIf(File::exists)?.length() ?: 0L)
                            .takeIf { it > 0L }?.let { add(formatBytes(it)) }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenPicker,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).archiveTouchTarget(),
                    ) { Text("오디오 변경") }
                    OutlinedButton(
                        onClick = onClear,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).archiveTouchTarget(),
                    ) { Text("선택 해제") }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionRunSection(
    state: SttViewModel.UiState,
    canRun: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenCompletedResult: (CompletedResultTarget) -> Unit,
) {
    val active = state.state in setOf(SttViewModel.SttState.RUNNING, SttViewModel.SttState.CANCELLING)
    val filePct = (state.progress * 100).toInt().coerceIn(0, 100)
    val isGroup = state.totalFiles > 1
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("전사 실행", style = MaterialTheme.typography.titleMedium)
                StatusPill(
                    text = if (state.state == SttViewModel.SttState.READY && !canRun) {
                        "입력 필요"
                    } else {
                        state.state.routeLabel()
                    },
                    tone = when (state.state) {
                        SttViewModel.SttState.RUNNING,
                        SttViewModel.SttState.CANCELLING,
                        SttViewModel.SttState.LOADING_MODEL,
                        -> ArchiveStatusTone.ACTIVE
                        SttViewModel.SttState.DONE -> ArchiveStatusTone.COMPLETE
                        SttViewModel.SttState.ERROR -> ArchiveStatusTone.ERROR
                        else -> ArchiveStatusTone.NEUTRAL
                    },
                )
            }
            if (active) {
                if (isGroup) {
                    Text(
                        "전체 ${(state.batchProgress * 100).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LinearProgressIndicator(
                        progress = { state.batchProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (isGroup) "현재 청크 ${state.currentFileIndex + 1}/${state.totalFiles} · $filePct%" else "$filePct%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.batchStatus.ifBlank { "기기 내부에서 처리 중입니다." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.state == SttViewModel.SttState.RUNNING,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text(if (state.state == SttViewModel.SttState.CANCELLING) "안전하게 중지하는 중" else "전사 중지") }
            } else {
                if (state.batchStatus.isNotBlank()) {
                    Text(
                        state.batchStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val completedTarget = state.completedResultTarget
                if (state.state == SttViewModel.SttState.DONE && completedTarget != null) {
                    Button(
                        onClick = { onOpenCompletedResult(completedTarget) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .semantics { contentDescription = "완료 전사 보관함에서 보기" },
                    ) {
                        Icon(Icons.Default.Inventory2, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("보관함에서 결과 보기")
                    }
                    OutlinedButton(
                        onClick = onRun,
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("같은 오디오 다시 전사")
                    }
                } else {
                    Button(
                        onClick = onRun,
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.totalFiles > 1) "순차 전사 시작 (${state.totalFiles}개)" else "전사 시작")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    installedModels: List<MediaLibraryStore.ModelEntry>,
    selectedPath: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설치 모델 선택") },
        text = {
            if (installedModels.isEmpty()) {
                Text("설치된 모델이 없습니다. 설정에서 모델을 설치하세요.")
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    installedModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .archiveTouchTarget()
                                .clickable { onSelect(model.path) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${formatBytes(model.sizeBytes)}${if (model.path == selectedPath) " · 현재 사용" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onSelect(model.path) }) {
                                Text(if (model.path == selectedPath) "다시 선택" else "사용")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (installedModels.isEmpty()) onOpenSettings else onDismiss) {
                Text(if (installedModels.isEmpty()) "설정 열기" else "닫기")
            }
        },
    )
}

@Composable
private fun AudioPickerDialog(
    state: SttViewModel.UiState,
    routeState: TranscriptionRouteUiState,
    onPickAudio: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onForget: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오디오 변경") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
            ) {
                FilledTonalButton(
                    onClick = onPickAudio,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("새 파일 가져오기")
                }
                Spacer(Modifier.height(10.dp))
                if (state.audioLibrary.isEmpty()) {
                    Text("이전에 가져온 오디오가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.audioLibrary.forEach { audio ->
                    val selected = audio.path == state.audioPath
                    Row(
                        modifier = Modifier.fillMaxWidth().archiveTouchTarget().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { onSelect(audio.path) },
                        ) {
                            Text(audio.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                buildList {
                                    if (audio.durationMs > 0L) add(formatDuration(audio.durationMs))
                                    add(formatBytes(audio.sizeBytes))
                                    if (selected) add("현재 선택")
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { onOpenMenu(audio.path) }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "${audio.displayName} 관리")
                            }
                            DropdownMenu(
                                expanded = routeState.audioMenuPath == audio.path,
                                onDismissRequest = onDismissMenu,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("보관함에서 숨기기") },
                                    onClick = {
                                        onDismissMenu()
                                        onForget(audio.path)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("파일 완전 삭제", color = MaterialTheme.colorScheme.error) },
                                    onClick = { onDelete(audio.path) },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun AudioDeleteDialog(
    audio: MediaLibraryStore.AudioEntry,
    linkedResultCount: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오디오 파일 삭제") },
        text = {
            Text(
                "${audio.displayName} (${formatBytes(audio.sizeBytes)})를 앱 저장소에서 삭제합니다. " +
                    "저장된 전사 결과 ${linkedResultCount}개는 유지되지만 이 오디오는 다시 전사할 수 없습니다.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("파일 삭제", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun SttViewModel.SttState.routeLabel(): String = when (this) {
    SttViewModel.SttState.IDLE -> "준비 필요"
    SttViewModel.SttState.LOADING_MODEL -> "모델 준비"
    SttViewModel.SttState.READY -> "실행 가능"
    SttViewModel.SttState.RUNNING -> "전사 중"
    SttViewModel.SttState.CANCELLING -> "중지 중"
    SttViewModel.SttState.DONE -> "완료"
    SttViewModel.SttState.ERROR -> "확인 필요"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
