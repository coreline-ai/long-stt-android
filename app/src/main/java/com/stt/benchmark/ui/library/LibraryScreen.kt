package com.stt.benchmark.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.CodexAuthViewModel
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import com.stt.benchmark.summary.SummaryUiState
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.common.ArchiveEmptyState
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.SectionLabel
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryRoute(
    viewModel: SttViewModel,
    codexAuthViewModel: CodexAuthViewModel,
    onOpenTranscription: () -> Unit,
    modifier: Modifier = Modifier,
    routeViewModel: LibraryRouteViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth by codexAuthViewModel.uiState.collectAsStateWithLifecycle()
    val summaryState by codexAuthViewModel.summaryUiState.collectAsStateWithLifecycle()
    val routeState by routeViewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.resultSessions.firstOrNull { it.sessionId == routeState.selectedSessionId }
    val deleteTarget = state.resultSessions.firstOrNull { it.sessionId == routeState.deleteSessionId }
    val selectedGroup = state.recordingGroups.firstOrNull { it.groupId == routeState.selectedGroupId }
    val deleteGroupTarget = state.recordingGroups.firstOrNull { it.groupId == routeState.deleteGroupId }
    val selectedAudio = state.audioLibrary.firstOrNull { it.path == routeState.selectedAudioPath }
    val deleteAudioTarget = state.audioLibrary.firstOrNull { it.path == routeState.deleteAudioPath }
    val standaloneResults = state.resultSessions.filter { it.recordingGroupId.isBlank() }
    val consentCandidate = routeState.summaryConsentSource?.let { source ->
        summaryCandidateFor(source, state.resultSessions, state.recordingGroups)
    }

    LaunchedEffect(routeState, state.resultSessions, state.recordingGroups, state.audioLibrary) {
        if (routeState.selectedSessionId.isNotBlank() && selected == null) routeViewModel.dismissSession()
        if (routeState.deleteSessionId.isNotBlank() && deleteTarget == null) routeViewModel.dismissSessionDeletion()
        if (routeState.selectedGroupId.isNotBlank() && selectedGroup == null) routeViewModel.dismissGroup()
        if (routeState.deleteGroupId.isNotBlank() && deleteGroupTarget == null) routeViewModel.dismissGroupDeletion()
        if (routeState.selectedAudioPath.isNotBlank() && selectedAudio == null) routeViewModel.dismissAudio()
        if (routeState.deleteAudioPath.isNotBlank() && deleteAudioTarget == null) routeViewModel.dismissAudioDeletion()
        if (routeState.summaryConsentSource != null && consentCandidate == null) routeViewModel.dismissSummaryConsent()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "보관함",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "오디오 원본과 전사 결과를 분리해 보존합니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
        }

        if (state.resultSessions.isEmpty() && state.audioLibrary.isEmpty() && state.recordingGroups.isEmpty()) {
            item {
                ArchiveEmptyState(
                    title = "아직 보관된 기록이 없습니다",
                    body = "오디오 파일을 가져와 첫 전사를 시작하면 결과가 여기에 안전하게 남습니다.",
                    action = {
                        Button(
                            onClick = onOpenTranscription,
                            modifier = Modifier.archiveTouchTarget(),
                        ) { Text("첫 전사 시작") }
                    },
                )
            }
        } else {
            if (state.recordingGroups.isNotEmpty()) {
                item { SectionLabel("녹음 그룹 ${state.recordingGroups.size}") }
                items(state.recordingGroups, key = RecordingTranscriptionGroupStore.Group::groupId) { group ->
                    RecordingGroupRow(group = group, onOpen = { routeViewModel.selectGroup(group.groupId) })
                }
                item { Spacer(Modifier.height(22.dp)) }
            }

            if (standaloneResults.isNotEmpty()) {
                item { SectionLabel("단일 전사 결과 ${standaloneResults.size}") }
                items(standaloneResults, key = { it.sessionId }) { session ->
                    TranscriptRow(
                        session = session,
                        onOpen = { routeViewModel.selectSession(session.sessionId) },
                    )
                }
                item { Spacer(Modifier.height(22.dp)) }
            }

            if (state.audioLibrary.isNotEmpty()) {
                item { SectionLabel("오디오 원본 ${state.audioLibrary.size}") }
                items(state.audioLibrary, key = MediaLibraryStore.AudioEntry::id) { audio ->
                    AudioRow(audio = audio, onOpen = { routeViewModel.selectAudio(audio.path) })
                }
            }
        }
    }

    selectedGroup?.let { group ->
        val childSessions = group.children.mapNotNull { child ->
            state.resultSessions.firstOrNull { it.sessionId == child.sttSessionId }
        }
        AlertDialog(
            onDismissRequest = routeViewModel::dismissGroup,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("녹음 그룹 전사")
                    StatusPill(group.status.archiveLabel(), tone = group.status.archiveTone())
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "원본 ${group.recordingSessionId} · 처리 ${group.children.size}개" +
                            if (group.isPartial) " · 제외 ${group.excludedSequences.joinToString()}" else " · 전체 범위",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    group.children.forEach { child ->
                        val checkpoint = childSessions.firstOrNull { it.sessionId == child.sttSessionId }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "청크 ${child.sequence + 1} · ${child.status.archiveLabel()}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                checkpoint?.toResult()?.text.orEmpty().ifBlank {
                                    child.errorMessage.ifBlank { "아직 저장된 전사 본문이 없습니다." }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    SummaryResultSection(
                        candidate = summaryCandidateFor(
                            source = SummaryRequestPolicy.Source(
                                SummarySessionStore.SourceType.RECORDING_GROUP,
                                group.groupId,
                            ),
                            sessions = state.resultSessions,
                            groups = state.recordingGroups,
                        ),
                        auth = auth,
                        summaryState = summaryState,
                        onRequest = routeViewModel::requestSummaryConsent,
                    )
                }
            },
            confirmButton = { TextButton(onClick = routeViewModel::dismissGroup) { Text("닫기") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        routeViewModel.requestGroupDeletion(group.groupId)
                    },
                    enabled = group.isTerminal &&
                        group.status != RecordingTranscriptionGroupStore.GroupStatus.INTERRUPTED,
                ) { Text("그룹 결과 삭제", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    deleteGroupTarget?.let { group ->
        AlertDialog(
            onDismissRequest = routeViewModel::dismissGroupDeletion,
            title = { Text("그룹 전사 결과를 삭제할까요?") },
            text = { Text("부모 그룹과 ${group.children.size}개 child 전사 결과만 삭제합니다. 녹음 원본 파일은 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecordingGroupResult(group.groupId)
                    routeViewModel.dismissGroupDeletion()
                }) { Text("결과만 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = routeViewModel::dismissGroupDeletion) { Text("취소") } },
        )
    }

    selectedAudio?.let { audio ->
        val linkedResults = state.resultSessions.count { it.audioPath == audio.path }
        val linkedGroups = state.recordingGroups.count { group -> group.children.any { it.mediaId == audio.id } }
        AlertDialog(
            onDismissRequest = routeViewModel::dismissAudio,
            title = { Text(audio.displayName) },
            text = {
                Text(
                    "${audio.source.archiveLabel()} · ${formatDuration(audio.durationMs)} · ${formatBytes(audio.sizeBytes)}\n" +
                        "연결된 전사 ${linkedResults}개 · 녹음 그룹 ${linkedGroups}개\n\n" +
                        "보관함에서 숨겨도 파일과 결과는 유지됩니다. 파일 삭제는 기존 결과를 남기지만 재전사 원본을 제거합니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetAudioFromLibrary(audio.path)
                    routeViewModel.dismissAudio()
                }) { Text("보관함에서 숨기기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    routeViewModel.requestAudioDeletion(audio.path)
                }) { Text("원본 파일 삭제", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    deleteAudioTarget?.let { audio ->
        val linkedResults = state.resultSessions.count { it.audioPath == audio.path }
        AlertDialog(
            onDismissRequest = routeViewModel::dismissAudioDeletion,
            title = { Text("원본 오디오 파일을 삭제할까요?") },
            text = {
                Text(
                    "이 파일을 사용하는 전사 결과 ${linkedResults}개는 자동 삭제되지 않습니다. " +
                        "하지만 원본 재생과 재전사는 불가능해지며, 녹음 checkpoint에는 누락 파일로 남을 수 있습니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAudioPermanently(audio.path)
                    routeViewModel.dismissAudioDeletion()
                }) { Text("원본만 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = routeViewModel::dismissAudioDeletion) { Text("취소") } },
        )
    }

    selected?.let { session ->
        val result = session.toResult()
        AlertDialog(
            onDismissRequest = routeViewModel::dismissSession,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(File(session.audioPath).name)
                    StatusPill(session.status.archiveLabel(), tone = session.status.archiveTone())
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${session.chunks.size}/${session.totalChunks} 구간 · ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        result.text.ifBlank { "아직 저장된 전사 본문이 없습니다." },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 18,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SummaryResultSection(
                        candidate = summaryCandidateFor(
                            source = SummaryRequestPolicy.Source(
                                SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
                                session.sessionId,
                            ),
                            sessions = state.resultSessions,
                            groups = state.recordingGroups,
                        ),
                        auth = auth,
                        summaryState = summaryState,
                        onRequest = routeViewModel::requestSummaryConsent,
                    )
                }
            },
            confirmButton = { TextButton(onClick = routeViewModel::dismissSession) { Text("닫기") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        routeViewModel.requestSessionDeletion(session.sessionId)
                    },
                    enabled = session.status !in SttViewModel.RESULT_DELETE_BLOCKED_STATUSES,
                ) { Text("결과 삭제", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = routeViewModel::dismissSessionDeletion,
            title = { Text("전사 결과를 삭제할까요?") },
            text = { Text("오디오 원본은 유지하고 이 전사 결과와 성능 기록만 삭제합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTranscriptionResult(session.sessionId)
                        routeViewModel.dismissSessionDeletion()
                    },
                    enabled = session.status !in SttViewModel.RESULT_DELETE_BLOCKED_STATUSES,
                ) { Text("결과 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = routeViewModel::dismissSessionDeletion) { Text("취소") } },
        )
    }

    consentCandidate?.let { candidate ->
        SummaryConsentDialog(
            onConfirm = {
                routeViewModel.dismissSummaryConsent()
                codexAuthViewModel.runUserApprovedSummary(candidate.source, candidate.transcript)
            },
            onDismiss = routeViewModel::dismissSummaryConsent,
        )
    }
}

internal data class SummaryCandidate(
    val source: SummaryRequestPolicy.Source,
    val transcript: String,
)

private fun summaryCandidateFor(
    source: SummaryRequestPolicy.Source,
    sessions: List<TranscriptionSessionStore.Checkpoint>,
    groups: List<RecordingTranscriptionGroupStore.Group>,
): SummaryCandidate? {
    return when (source.type) {
        SummarySessionStore.SourceType.TRANSCRIPTION_SESSION -> sessions
            .firstOrNull { it.sessionId == source.id && it.status == TranscriptionSessionStore.Status.COMPLETED }
            ?.toResult()
            ?.text
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { SummaryCandidate(source, it) }

        SummarySessionStore.SourceType.RECORDING_GROUP -> {
            val group = groups.firstOrNull {
                it.groupId == source.id && it.status == RecordingTranscriptionGroupStore.GroupStatus.COMPLETED
            } ?: return null
            val sessionsById = sessions.associateBy(TranscriptionSessionStore.Checkpoint::sessionId)
            val transcript = group.children.sortedBy { it.sequence }.mapNotNull { child ->
                sessionsById[child.sttSessionId]
                    ?.takeIf { it.status == TranscriptionSessionStore.Status.COMPLETED }
                    ?.toResult()
                    ?.text
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }.takeIf { it.size == group.children.size }?.joinToString("\n")?.trim()
            transcript?.takeIf(String::isNotBlank)?.let { SummaryCandidate(source, it) }
        }
    }
}

@Composable
internal fun SummaryResultSection(
    candidate: SummaryCandidate?,
    auth: CodexAuthUiState,
    summaryState: SummaryUiState,
    onRequest: (SummaryRequestPolicy.Source) -> Unit,
) {
    val source = candidate?.source
    val entry = source?.let { candidateSource ->
        summaryState.entries.firstOrNull { it.source == candidateSource }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("외부 요약", style = MaterialTheme.typography.titleMedium)
        Text(
            "전사는 기기에 보관됩니다. 이 결과를 선택하고 동의할 때만 Codex로 전송합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            entry != null -> {
                StatusPill("요약 완료", tone = ArchiveStatusTone.COMPLETE)
                Text(entry.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 12)
            }
            source != null && summaryState.activeSourceKey == source.key -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(summaryState.statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
            candidate == null -> Text(
                "내용이 있는 전체 완료 전사만 요약할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            auth.phase != CodexAuthPhase.AUTHENTICATED -> Text(
                "설정에서 ChatGPT 연결을 완료하면 요약을 시작할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summaryState.isRunning -> Text(
                "다른 요약 작업이 진행 중입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Button(
                onClick = { onRequest(requireNotNull(source)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .archiveTouchTarget()
                    .semantics { contentDescription = "외부 요약 시작" },
            ) { Text("외부 요약") }
        }
        if (entry == null && summaryState.statusMessage.isNotBlank() && !summaryState.isRunning) {
            Text(
                summaryState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SummaryConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("외부 요약을 시작할까요?") },
        text = {
            Text(
                "선택한 완료 전사의 본문을 Codex 외부 서비스에 한 번 전송해 요약합니다. " +
                    "전사 원문은 자동 전송되지 않으며, 취소하면 전송하지 않습니다.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("동의하고 요약") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun TranscriptRow(
    session: TranscriptionSessionStore.Checkpoint,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .archiveTouchTarget()
                .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    File(session.audioPath).name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatDate(session.updatedAtMs)} · ${session.chunks.size}/${session.totalChunks} 구간",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(session.status.archiveLabel(), tone = session.status.archiveTone())
        }
    }
}

@Composable
private fun RecordingGroupRow(
    group: RecordingTranscriptionGroupStore.Group,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().archiveTouchTarget().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (group.isPartial) "보존 구간 녹음 전사" else "전체 녹음 순차 전사",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${formatDate(group.updatedAtMs)} · ${group.completedChildren}/${group.children.size} 청크" +
                        if (group.isPartial) " · 제외 ${group.excludedSequences.size}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(group.status.archiveLabel(), tone = group.status.archiveTone())
        }
    }
}

@Composable
private fun AudioRow(
    audio: MediaLibraryStore.AudioEntry,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().archiveTouchTarget().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    audio.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(audio.source.archiveLabel())
                        if (audio.source == MediaLibraryStore.AudioSource.RECORDED && audio.sequence >= 0) {
                            append(" 청크 ${audio.sequence + 1}")
                        }
                        append(" · ${formatDuration(audio.durationMs)} · ${formatBytes(audio.sizeBytes)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun MediaLibraryStore.AudioSource.archiveLabel(): String = when (this) {
    MediaLibraryStore.AudioSource.IMPORTED -> "가져온 오디오"
    MediaLibraryStore.AudioSource.RECORDED -> "직접 녹음"
}

private fun RecordingTranscriptionGroupStore.GroupStatus.archiveLabel(): String = when (this) {
    RecordingTranscriptionGroupStore.GroupStatus.READY -> "준비"
    RecordingTranscriptionGroupStore.GroupStatus.RUNNING -> "순차 전사 중"
    RecordingTranscriptionGroupStore.GroupStatus.COMPLETED -> "전체 완료"
    RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED -> "일부 완료"
    RecordingTranscriptionGroupStore.GroupStatus.FAILED -> "실패"
    RecordingTranscriptionGroupStore.GroupStatus.CANCELLED -> "취소"
    RecordingTranscriptionGroupStore.GroupStatus.INTERRUPTED -> "재개 확인"
    RecordingTranscriptionGroupStore.GroupStatus.MODEL_REQUIRED -> "모델 필요"
}

private fun RecordingTranscriptionGroupStore.GroupStatus.archiveTone(): ArchiveStatusTone = when (this) {
    RecordingTranscriptionGroupStore.GroupStatus.COMPLETED -> ArchiveStatusTone.COMPLETE
    RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED,
    RecordingTranscriptionGroupStore.GroupStatus.MODEL_REQUIRED,
    RecordingTranscriptionGroupStore.GroupStatus.INTERRUPTED,
    -> ArchiveStatusTone.NEUTRAL
    RecordingTranscriptionGroupStore.GroupStatus.FAILED -> ArchiveStatusTone.ERROR
    RecordingTranscriptionGroupStore.GroupStatus.READY,
    RecordingTranscriptionGroupStore.GroupStatus.RUNNING,
    -> ArchiveStatusTone.ACTIVE
    RecordingTranscriptionGroupStore.GroupStatus.CANCELLED -> ArchiveStatusTone.NEUTRAL
}

private fun RecordingTranscriptionGroupStore.ChildStatus.archiveLabel(): String = when (this) {
    RecordingTranscriptionGroupStore.ChildStatus.PENDING -> "대기"
    RecordingTranscriptionGroupStore.ChildStatus.STARTING -> "시작 중"
    RecordingTranscriptionGroupStore.ChildStatus.RUNNING -> "전사 중"
    RecordingTranscriptionGroupStore.ChildStatus.COMPLETED -> "완료"
    RecordingTranscriptionGroupStore.ChildStatus.FAILED -> "실패"
    RecordingTranscriptionGroupStore.ChildStatus.CANCELLED -> "취소"
    RecordingTranscriptionGroupStore.ChildStatus.INTERRUPTED -> "중단"
}

private fun TranscriptionSessionStore.Status.archiveLabel(): String = when (this) {
    TranscriptionSessionStore.Status.PREPARING -> "준비"
    TranscriptionSessionStore.Status.RUNNING -> "전사 중"
    TranscriptionSessionStore.Status.COOLING -> "냉각"
    TranscriptionSessionStore.Status.COMPLETED -> "완료"
    TranscriptionSessionStore.Status.FAILED -> "실패"
    TranscriptionSessionStore.Status.CANCELLED -> "취소"
    TranscriptionSessionStore.Status.INTERRUPTED -> "재개 대기"
}

private fun TranscriptionSessionStore.Status.archiveTone(): ArchiveStatusTone = when (this) {
    TranscriptionSessionStore.Status.COMPLETED -> ArchiveStatusTone.COMPLETE
    TranscriptionSessionStore.Status.FAILED -> ArchiveStatusTone.ERROR
    TranscriptionSessionStore.Status.PREPARING,
    TranscriptionSessionStore.Status.RUNNING,
    TranscriptionSessionStore.Status.COOLING,
    -> ArchiveStatusTone.ACTIVE
    else -> ArchiveStatusTone.NEUTRAL
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestamp))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
