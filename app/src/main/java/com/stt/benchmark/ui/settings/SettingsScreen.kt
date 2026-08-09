package com.stt.benchmark.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.BuildConfig
import com.stt.benchmark.data.BenchmarkRecorder
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.ModelDownloader
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.CodexAuthViewModel
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.SectionLabel
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration
import java.io.File

@Composable
fun SettingsRoute(
    viewModel: SttViewModel,
    codexAuthViewModel: CodexAuthViewModel,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    routeViewModel: SettingsRouteViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth by codexAuthViewModel.uiState.collectAsStateWithLifecycle()
    val routeState by routeViewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val defaultModelPath = File(LocalContext.current.filesDir, "models/ggml-base.bin").absolutePath

    LaunchedEffect(routeState.dialog, routeState.deleteModelPath, state.installedModels) {
        if (
            routeState.dialog == SettingsDialog.DELETE_MODEL &&
            state.installedModels.none { it.path == routeState.deleteModelPath }
        ) {
            routeViewModel.dismissDialog()
        }
    }

    SettingsScreen(
        state = state,
        auth = auth,
        routeState = routeState,
        availableModels = viewModel.availableModels,
        isDebug = BuildConfig.DEBUG,
        onAuthorize = { activity?.let(codexAuthViewModel::authorize) },
        canAuthorize = activity != null,
        onCancelAuthorization = codexAuthViewModel::cancelAuthorization,
        onProbe = codexAuthViewModel::runParityProbe,
        onLogout = codexAuthViewModel::logout,
        onReplayOnboarding = onReplayOnboarding,
        onShowModelCatalog = routeViewModel::showModelCatalog,
        onShowModelPath = { routeViewModel.showModelPath(defaultModelPath) },
        onShowPerformanceHistory = routeViewModel::showPerformanceHistory,
        onRequestModelDeletion = routeViewModel::requestModelDeletion,
        onModelInputChanged = routeViewModel::setModelInputPath,
        onDismissDialog = routeViewModel::dismissDialog,
        onLoadModel = viewModel::loadModel,
        onDownloadModel = viewModel::downloadModel,
        onDeleteModel = viewModel::deleteInstalledModel,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    state: SttViewModel.UiState,
    auth: CodexAuthUiState,
    routeState: SettingsRouteUiState,
    availableModels: List<ModelDownloader.ModelInfo>,
    isDebug: Boolean,
    onAuthorize: () -> Unit,
    canAuthorize: Boolean,
    onCancelAuthorization: () -> Unit,
    onProbe: () -> Unit,
    onLogout: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onShowModelCatalog: () -> Unit,
    onShowModelPath: () -> Unit,
    onShowPerformanceHistory: () -> Unit,
    onRequestModelDeletion: (String) -> Unit,
    onModelInputChanged: (String) -> Unit,
    onDismissDialog: () -> Unit,
    onLoadModel: (String) -> Unit,
    onDownloadModel: (ModelDownloader.ModelInfo) -> Unit,
    onDeleteModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsLocked = state.isDownloading || state.state in setOf(
        SttViewModel.SttState.LOADING_MODEL,
        SttViewModel.SttState.RUNNING,
        SttViewModel.SttState.CANCELLING,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            "설정",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "모델, 저장공간과 외부 연결 경계를 관리합니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsSection("모델 관리") {
            SettingLine(
                "현재 모델",
                state.modelPath.substringAfterLast('/').ifBlank { "선택 안 됨" },
            )
            SettingLine(
                "설치 모델",
                "${state.installedModels.size}개 · ${formatBytes(state.installedModels.sumOf { it.sizeBytes })}",
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
            if (state.batchStatus.startsWith("다운로드")) {
                Text(
                    state.batchStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.errorMessage.isNotBlank()) {
                Text(
                    state.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.installedModels.forEach { model ->
                val hasSameName = state.installedModels.count { it.displayName == model.displayName } > 1
                InstalledModelLine(
                    model = model,
                    selected = model.path == state.modelPath,
                    locationLabel = if (hasSameName) modelLocationLabel(model.path) else "",
                    controlsEnabled = !controlsLocked,
                    onUse = { onLoadModel(model.path) },
                    onDelete = { onRequestModelDeletion(model.path) },
                )
            }
            Button(
                onClick = onShowModelCatalog,
                enabled = !controlsLocked,
                modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
            ) { Text("모델 다운로드") }
            OutlinedButton(
                onClick = onShowModelPath,
                enabled = !controlsLocked,
                modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
            ) { Text("고급: 앱 내부 모델 경로") }
        }

        SettingsSection("요약 계정") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ChatGPT / Codex", style = MaterialTheme.typography.titleMedium)
                StatusPill(auth.phase.label(), tone = auth.phase.tone())
            }
            Text(
                auth.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (auth.phase == CodexAuthPhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "연결 확인은 비민감 고정 문장만 사용하며 전사 원문은 자동 전송하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (auth.phase in setOf(CodexAuthPhase.AUTHORIZING, CodexAuthPhase.TESTING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when (auth.phase) {
                CodexAuthPhase.SIGNED_OUT,
                CodexAuthPhase.REAUTHENTICATION_REQUIRED,
                CodexAuthPhase.ERROR,
                -> Button(
                    onClick = onAuthorize,
                    enabled = canAuthorize,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text("ChatGPT 연결") }

                CodexAuthPhase.AUTHORIZING -> OutlinedButton(
                    onClick = onCancelAuthorization,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text("연결 취소") }

                CodexAuthPhase.AUTHENTICATED,
                CodexAuthPhase.TESTING,
                -> {
                    Button(
                        onClick = onProbe,
                        enabled = auth.phase == CodexAuthPhase.AUTHENTICATED && !controlsLocked,
                        modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                    ) { Text("연결 응답 확인") }
                    OutlinedButton(
                        onClick = onLogout,
                        enabled = auth.phase != CodexAuthPhase.TESTING,
                        modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                    ) { Text("연결 해제") }
                }
            }
            auth.probeResponse?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection("저장공간") {
            SettingLine("오디오 원본", "${state.audioLibrary.size}개 · ${formatBytes(state.audioLibrary.sumOf { it.sizeBytes })}")
            SettingLine("전사 결과", "${state.resultSessions.size}개")
            SettingLine("녹음 그룹", "${state.recordingGroups.size}개")
            Text(
                "확정 오디오의 형식과 SHA-256을 검사하며 원본·결과 삭제는 서로 연쇄되지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection("처리 경계") {
            SettingLine("음성 전사", "기기 내부 Whisper")
            SettingLine("계정 연결", "Android Keystore 보호")
            SettingLine("외부 요약", "사용자가 선택할 때만")
        }

        SettingsSection("앱 정보") {
            SettingLine("버전", BuildConfig.VERSION_NAME)
            SettingLine("엔진", "whisper.cpp · arm64")
            SettingLine("화면", "Quiet Archive / Long STT")
            OutlinedButton(
                onClick = onReplayOnboarding,
                modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
            ) {
                Text("소개 다시 보기")
            }
        }

        SettingsSection("기기 및 진단") {
            Text(
                state.deviceInfo.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (controlsLocked) "장시간 작업 실행 중" else "새 작업 시작 가능",
                style = MaterialTheme.typography.bodyMedium,
                color = if (controlsLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
            )
            if (isDebug) {
                SettingLine("Debug 성능 기록", "${state.history.size}개")
                OutlinedButton(
                    onClick = onShowPerformanceHistory,
                    enabled = state.history.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) { Text("성능 기록 보기") }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    when (routeState.dialog) {
        SettingsDialog.MODEL_CATALOG -> ModelCatalogDialog(
            models = availableModels,
            installedModels = state.installedModels,
            onChoose = { model, installed ->
                if (installed != null) onLoadModel(installed.path) else onDownloadModel(model)
                onDismissDialog()
            },
            onDismiss = onDismissDialog,
        )
        SettingsDialog.MODEL_PATH -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("앱 내부 모델 경로") },
            text = {
                OutlinedTextField(
                    value = routeState.modelInputPath,
                    onValueChange = onModelInputChanged,
                    label = { Text("절대경로") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLoadModel(routeState.modelInputPath)
                    onDismissDialog()
                }) { Text("사용") }
            },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text("취소") } },
        )
        SettingsDialog.DELETE_MODEL -> state.installedModels
            .firstOrNull { it.path == routeState.deleteModelPath }
            ?.let { model ->
                AlertDialog(
                    onDismissRequest = onDismissDialog,
                    title = { Text("모델 삭제") },
                    text = {
                        Text("${model.displayName} (${formatBytes(model.sizeBytes)})를 삭제합니다. 전사 결과와 오디오는 유지됩니다.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onDeleteModel(model.path)
                            onDismissDialog()
                        }) { Text("모델 삭제", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = onDismissDialog) { Text("취소") } },
                )
            }
        SettingsDialog.PERFORMANCE_HISTORY -> PerformanceHistoryDialog(
            history = state.history,
            onDismiss = onDismissDialog,
        )
        SettingsDialog.NONE -> Unit
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(30.dp))
    SectionLabel(title)
    Column(
        modifier = Modifier.padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun InstalledModelLine(
    model: MediaLibraryStore.ModelEntry,
    selected: Boolean,
    locationLabel: String,
    controlsEnabled: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildList {
                    add(formatBytes(model.sizeBytes))
                    if (locationLabel.isNotBlank()) add(locationLabel)
                    if (selected) add("현재 사용")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onUse,
            enabled = controlsEnabled,
            modifier = Modifier.archiveTouchTarget(),
        ) { Text(if (selected) "다시 사용" else "사용") }
        TextButton(
            onClick = onDelete,
            enabled = controlsEnabled && !selected,
            modifier = Modifier.archiveTouchTarget(),
        ) {
            Text(
                "삭제",
                color = if (controlsEnabled && !selected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ModelCatalogDialog(
    models: List<ModelDownloader.ModelInfo>,
    installedModels: List<MediaLibraryStore.ModelEntry>,
    onChoose: (ModelDownloader.ModelInfo, MediaLibraryStore.ModelEntry?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Whisper 모델 다운로드") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                models.forEach { model ->
                    val installed = installedModels.firstOrNull { it.path.substringAfterLast('/') == model.fileName }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .clickable { onChoose(model, installed) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text("${model.displayName} · ${model.sizeMb} MB", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (installed != null) "설치됨 · 선택하면 사용" else model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PerformanceHistoryDialog(
    history: List<BenchmarkRecorder.BenchmarkRecord>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug 성능 기록 (${history.size}개)") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "CSV 처리 속도와 모델 기록입니다. 전사 본문은 보관함에서 확인합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                history.asReversed().forEach { record ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(record.audioFile, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            "${record.timestamp} · ${record.modelName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "오디오 ${formatDuration(record.audioDurationMs)} · 처리 ${formatDuration(record.elapsedMs)} · " +
                                "RTF %.3f · %.2fx".format(record.rtf, record.speedMultiplier),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun CodexAuthPhase.label(): String = when (this) {
    CodexAuthPhase.AUTHENTICATED -> "연결됨"
    CodexAuthPhase.AUTHORIZING -> "연결 중"
    CodexAuthPhase.TESTING -> "확인 중"
    CodexAuthPhase.REAUTHENTICATION_REQUIRED -> "재로그인"
    CodexAuthPhase.ERROR -> "확인 필요"
    CodexAuthPhase.SIGNED_OUT -> "연결 안 됨"
}

private fun CodexAuthPhase.tone(): ArchiveStatusTone = when (this) {
    CodexAuthPhase.AUTHENTICATED -> ArchiveStatusTone.COMPLETE
    CodexAuthPhase.AUTHORIZING,
    CodexAuthPhase.TESTING,
    -> ArchiveStatusTone.ACTIVE
    CodexAuthPhase.ERROR -> ArchiveStatusTone.ERROR
    else -> ArchiveStatusTone.NEUTRAL
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun modelLocationLabel(path: String): String {
    val file = File(path)
    return if (file.parentFile?.name == "models") "models/${file.name}" else "legacy/${file.name}"
}
