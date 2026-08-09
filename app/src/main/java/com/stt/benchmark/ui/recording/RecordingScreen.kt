package com.stt.benchmark.ui.recording

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stt.benchmark.recording.RecordingPhase
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.CopperThreadArtwork
import com.stt.benchmark.ui.common.RecordControl
import com.stt.benchmark.ui.common.SectionLabel
import com.stt.benchmark.ui.common.SoundThread
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration
import com.stt.benchmark.ui.theme.ArchiveInk
import com.stt.benchmark.ui.theme.ArchivePaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingRoute(
    viewModel: RecordingViewModel,
    onOpenTranscription: () -> Unit,
    modifier: Modifier = Modifier,
    onTranscribeRecording: (String, Boolean) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val reducedMotion = rememberReducedMotion()
    var startAfterNotificationPermission by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val showRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } ?: false
        viewModel.onPermissionResult(granted, showRationale)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (startAfterNotificationPermission) {
            startAfterNotificationPermission = false
            // Recording remains available after an explicit denial; Android still exposes an
            // active FGS in Task Manager. When granted, the product notification also exposes
            // the user-facing "녹음 정지" action.
            viewModel.startRecording()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshEnvironmentAndSessions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RecordingScreen(
        state = state,
        onStart = {
            val needsNotificationPrompt = shouldRequestRecordingNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                notificationsGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            )
            if (needsNotificationPrompt) {
                startAfterNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startRecording()
            }
        },
        onStop = { viewModel.stopRecording() },
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        },
        onOpenTranscription = onOpenTranscription,
        onTranscribeRecording = onTranscribeRecording,
        reducedMotion = reducedMotion,
        modifier = modifier,
    )
}

internal fun shouldRequestRecordingNotificationPermission(
    sdkInt: Int,
    notificationsGranted: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenTranscription: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    onTranscribeRecording: (String, Boolean) -> Unit = { _, _ -> },
) {
    val presentation = recordingPresentation(state)
    val elapsed = state.runtime.elapsedMs
    val controlEnabled = state.canStart || state.canStop
    val controlAction = if (state.canStop) "녹음 정지" else "녹음 시작"
    var partialTarget by remember { mutableStateOf<RecentRecordingUi?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(ArchiveInk)
                .semantics {
                    stateDescription = presentation.label
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            CopperThreadArtwork(
                archiveMode = state.displayPhase == RecordingPhase.SAVED,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                ArchiveInk.copy(alpha = 0.04f),
                                ArchiveInk.copy(alpha = 0.30f),
                                ArchiveInk.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                StatusPill(presentation.label, tone = presentation.tone)
                Spacer(Modifier.height(14.dp))
                Text(
                    formatTimer(elapsed),
                    style = MaterialTheme.typography.displayLarge,
                    color = ArchivePaper,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = "녹음 시간 ${formatDuration(elapsed)}"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    presentation.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArchivePaper.copy(alpha = 0.72f),
                    maxLines = 2,
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SoundThread(
                amplitude = state.runtime.amplitude,
                active = state.displayPhase == RecordingPhase.RECORDING,
                reducedMotion = reducedMotion,
            )
            if (state.isRecorderActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "입력: ${state.runtime.inputRoute.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = "현재 녹음 입력 ${state.runtime.inputRoute.label}"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            RecordControl(
                active = state.isRecorderActive,
                enabled = controlEnabled,
                onClick = { if (state.canStop) onStop() else onStart() },
                disabledReason = presentation.detail,
                actionLabel = controlAction,
                stateLabel = presentation.label,
                reducedMotion = reducedMotion,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                controlCaption(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PermissionAction(
                availability = state.availability,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
            )
            if (state.message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = if (state.displayPhase == RecordingPhase.FAILED) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(34.dp))
            SectionLabel("안전 녹음 여유")
            Spacer(Modifier.height(12.dp))
            StorageSummary(state)

            Spacer(Modifier.height(34.dp))
            SectionLabel("최근 녹음")
            Spacer(Modifier.height(12.dp))
            if (state.recentSessions.isEmpty()) {
                EmptyRecentRecording(onOpenTranscription)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.recentSessions.forEach { session ->
                        RecentRecordingCard(
                            session = session,
                            actionsEnabled = !state.isRecorderActive,
                            onTranscribe = {
                                if (session.quarantinedChunkCount > 0 || session.missingChunkCount > 0 ||
                                    session.phase != RecordingPhase.SAVED
                                ) {
                                    partialTarget = session
                                } else {
                                    onTranscribeRecording(session.sessionId, false)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onOpenTranscription,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) {
                    Text("기존 오디오 파일 전사하기")
                }
            }
            Spacer(Modifier.height(34.dp))
        }
    }

    partialTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { partialTarget = null },
            title = { Text("보존된 구간만 전사할까요?") },
            text = {
                Text(
                    "READY 청크 ${session.readyChunkCount}개만 sequence 순서로 처리합니다. " +
                        "격리 ${session.quarantinedChunkCount}개와 누락 ${session.missingChunkCount}개는 제외되며, " +
                        "결과는 일부 완료로 표시됩니다.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        partialTarget = null
                        onTranscribeRecording(session.sessionId, true)
                    },
                ) { Text("보존 구간 전사") }
            },
            dismissButton = {
                TextButton(onClick = { partialTarget = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun PermissionAction(
    availability: RecordingAvailability,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when (availability) {
        RecordingAvailability.PERMISSION_REQUIRED,
        RecordingAvailability.PERMISSION_DENIED,
        -> {
            Spacer(Modifier.height(14.dp))
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (availability == RecordingAvailability.PERMISSION_DENIED) "마이크 권한 다시 요청" else "마이크 권한 허용")
            }
        }
        RecordingAvailability.PERMISSION_PERMANENTLY_DENIED -> {
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("앱 설정에서 마이크 권한 열기")
            }
        }
        else -> Unit
    }
}

@Composable
private fun StorageSummary(state: RecordingUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("사용 가능한 공간", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatBytes(state.availableBytes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("보수적 녹음 시간", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatEstimatedDuration(state.estimatedMaxDurationMs),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                "AAC 준비 실패 뒤 48kHz mono WAV로 전환하는 최악 조건과 64MB 안전 여유를 반영합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyRecentRecording(onOpenTranscription: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("아직 직접 녹음이 없습니다", style = MaterialTheme.typography.titleMedium)
            Text(
                "위 버튼으로 첫 녹음을 시작하거나 기존 오디오를 전사할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onOpenTranscription,
                modifier = Modifier.archiveTouchTarget(),
            ) { Text("오디오 파일 전사") }
        }
    }
}

@Composable
private fun RecentRecordingCard(
    session: RecentRecordingUi,
    actionsEnabled: Boolean,
    onTranscribe: () -> Unit,
) {
    val hasIssue = session.quarantinedChunkCount > 0 || session.missingChunkCount > 0 ||
        session.phase in setOf(RecordingPhase.FAILED, RecordingPhase.RECOVERY_REQUIRED)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = when {
                        hasIssue -> Icons.Outlined.ErrorOutline
                        session.phase == RecordingPhase.SAVED -> Icons.Outlined.CheckCircle
                        else -> Icons.Outlined.HourglassTop
                    },
                    contentDescription = null,
                    tint = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    formatRecordingDate(session.updatedAtMs),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatDuration(session.durationMs))
                        append(" · 확정 ${session.readyChunkCount}개")
                        if (session.container.isNotBlank()) append(" · ${session.container.uppercase()}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasIssue) {
                    Text(
                        "격리 ${session.quarantinedChunkCount}개 · 누락 ${session.missingChunkCount}개",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (session.message.isNotBlank()) {
                        Text(
                            session.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
                StatusPill(
                    text = session.phase.shortLabel(),
                    tone = when {
                        hasIssue -> ArchiveStatusTone.ERROR
                        session.phase == RecordingPhase.SAVED -> ArchiveStatusTone.COMPLETE
                        session.phase in RecordingUiState.ACTIVE_PHASES -> ArchiveStatusTone.ACTIVE
                        else -> ArchiveStatusTone.NEUTRAL
                    },
                )
            }
            if (session.readyChunkCount > 0 && session.phase !in RecordingUiState.ACTIVE_PHASES) {
                OutlinedButton(
                    onClick = onTranscribe,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                ) {
                    Text(if (hasIssue) "보존 구간 순차 전사" else "녹음 전체 순차 전사")
                }
            }
        }
    }
}

internal data class RecordingPresentation(
    val label: String,
    val detail: String,
    val tone: ArchiveStatusTone,
)

internal fun recordingPresentation(state: RecordingUiState): RecordingPresentation = when (state.displayPhase) {
    RecordingPhase.PREPARING -> RecordingPresentation("녹음 준비", "마이크와 안전 저장공간을 확인하고 있습니다.", ArchiveStatusTone.ACTIVE)
    RecordingPhase.RECORDING -> RecordingPresentation("녹음 중", "화면을 이동해도 백그라운드에서 계속 기록합니다.", ArchiveStatusTone.ACTIVE)
    RecordingPhase.ROLLING_OVER -> RecordingPresentation("청크 저장", "현재 청크를 확정하고 다음 기록을 준비합니다.", ArchiveStatusTone.ACTIVE)
    RecordingPhase.FINALIZING -> RecordingPresentation("안전 저장 중", "오디오 형식과 해시를 검사하고 있습니다.", ArchiveStatusTone.ACTIVE)
    RecordingPhase.SAVED -> RecordingPresentation("저장 완료", "확정된 녹음은 최근 기록에서 확인할 수 있습니다.", ArchiveStatusTone.COMPLETE)
    RecordingPhase.FAILED -> RecordingPresentation("확인 필요", "확정하지 못한 파일은 정상 녹음과 분리해 보존합니다.", ArchiveStatusTone.ERROR)
    RecordingPhase.RECOVERY_REQUIRED -> RecordingPresentation("복구 확인", "중단된 파일을 검사하고 정상 기록과 분리합니다.", ArchiveStatusTone.ERROR)
    RecordingPhase.IDLE -> when (state.availability) {
        RecordingAvailability.READY -> RecordingPresentation("녹음 준비", "버튼을 누르면 기기 안에서 안전하게 녹음을 시작합니다.", ArchiveStatusTone.NEUTRAL)
        RecordingAvailability.PERMISSION_REQUIRED -> RecordingPresentation("마이크 권한 필요", "권한을 허용하기 전에는 녹음을 시작하지 않습니다.", ArchiveStatusTone.NEUTRAL)
        RecordingAvailability.PERMISSION_DENIED -> RecordingPresentation("권한 다시 확인", "마이크 권한을 다시 요청하거나 기존 오디오를 전사하세요.", ArchiveStatusTone.ERROR)
        RecordingAvailability.PERMISSION_PERMANENTLY_DENIED -> RecordingPresentation("권한 설정 필요", "앱 설정에서 마이크 권한을 허용해 주세요.", ArchiveStatusTone.ERROR)
        RecordingAvailability.UNSUPPORTED_INPUT -> RecordingPresentation("마이크 확인 필요", "사용 가능한 물리적 오디오 입력을 찾지 못했습니다.", ArchiveStatusTone.ERROR)
        RecordingAvailability.INSUFFICIENT_STORAGE -> RecordingPresentation("저장공간 부족", "20분 안전 청크를 저장할 공간을 먼저 확보해 주세요.", ArchiveStatusTone.ERROR)
    }
}

private fun controlCaption(state: RecordingUiState): String = when {
    state.canStop -> "누르면 현재 녹음을 안전하게 마감합니다."
    state.canStart -> "기본 20분 단위로 나누어 기기 내부에 저장합니다."
    state.availability == RecordingAvailability.PERMISSION_REQUIRED -> "녹음은 권한 허용 뒤에만 시작됩니다."
    state.availability == RecordingAvailability.PERMISSION_DENIED -> "권한을 다시 요청해 녹음을 활성화할 수 있습니다."
    state.availability == RecordingAvailability.PERMISSION_PERMANENTLY_DENIED -> "Android 앱 설정에서 마이크 권한을 변경하세요."
    else -> recordingPresentation(state).detail
}

private fun RecordingPhase.shortLabel(): String = when (this) {
    RecordingPhase.IDLE -> "대기"
    RecordingPhase.PREPARING -> "준비"
    RecordingPhase.RECORDING -> "녹음 중"
    RecordingPhase.ROLLING_OVER -> "저장 중"
    RecordingPhase.FINALIZING -> "마감 중"
    RecordingPhase.SAVED -> "저장됨"
    RecordingPhase.FAILED -> "실패"
    RecordingPhase.RECOVERY_REQUIRED -> "복구 확인"
}

private fun formatTimer(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(totalSeconds / 3_600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
}

private fun formatEstimatedDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "20분 미만"
    val hours = milliseconds / 3_600_000L
    val minutes = (milliseconds % 3_600_000L) / 60_000L
    return when {
        hours >= 100L -> "99시간+"
        hours > 0L -> "약 ${hours}시간 ${minutes}분"
        else -> "약 ${minutes.coerceAtLeast(1L)}분"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRecordingDate(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestamp))

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalView.current.context
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
