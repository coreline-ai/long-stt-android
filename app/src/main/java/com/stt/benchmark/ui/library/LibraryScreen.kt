package com.stt.benchmark.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceReader
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.export.TranscriptDocumentSaver
import com.stt.benchmark.export.TranscriptDocumentLauncher
import com.stt.benchmark.export.TranscriptExportWriter
import com.stt.benchmark.export.TranscriptFileShareFactory
import com.stt.benchmark.drive.DriveArtifact
import com.stt.benchmark.drive.DriveConnectionPhase
import com.stt.benchmark.drive.DriveUploadJob
import com.stt.benchmark.drive.DriveUploadStatus
import com.stt.benchmark.drive.GoogleDriveUiState
import com.stt.benchmark.drive.GoogleDriveViewModel
import com.stt.benchmark.drive.toSummarySourceType
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.CodexAuthViewModel
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import com.stt.benchmark.summary.SummaryShareLauncher
import com.stt.benchmark.summary.SummaryStage
import com.stt.benchmark.summary.SummaryUiState
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.common.ArchiveEmptyState
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.FullTranscriptDialog
import com.stt.benchmark.ui.common.SectionLabel
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.TranscriptExportActions
import com.stt.benchmark.ui.common.TranscriptViewerSection
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryRoute(
    viewModel: SttViewModel,
    codexAuthViewModel: CodexAuthViewModel,
    googleDriveViewModel: GoogleDriveViewModel,
    onOpenTranscription: () -> Unit,
    onOpenTranscriptChat: (TranscriptSourceRef) -> Unit = {},
    modifier: Modifier = Modifier,
    routeViewModel: LibraryRouteViewModel = viewModel(),
    initialCompletedResult: CompletedResultTarget? = null,
    initialTranscriptSectionKey: String = "",
    onInitialCompletedResultHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val documentSaver = remember(context) { TranscriptDocumentSaver(context.applicationContext) }
    val fileShareFactory = remember(context) { TranscriptFileShareFactory(context.applicationContext) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth by codexAuthViewModel.uiState.collectAsStateWithLifecycle()
    val summaryState by codexAuthViewModel.summaryUiState.collectAsStateWithLifecycle()
    val driveState by googleDriveViewModel.uiState.collectAsStateWithLifecycle()
    val routeState by routeViewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.resultSessions.firstOrNull { it.sessionId == routeState.selectedSessionId }
    val fullTranscriptSession = state.resultSessions.firstOrNull {
        it.sessionId == routeState.fullTranscriptSessionId
    }
    val deleteTarget = state.resultSessions.firstOrNull { it.sessionId == routeState.deleteSessionId }
    val selectedGroup = state.recordingGroups.firstOrNull { it.groupId == routeState.selectedGroupId }
    val fullTranscriptGroup = state.recordingGroups.firstOrNull {
        it.groupId == routeState.fullTranscriptGroupId
    }
    val deleteGroupTarget = state.recordingGroups.firstOrNull { it.groupId == routeState.deleteGroupId }
    val selectedAudio = state.audioLibrary.firstOrNull { it.path == routeState.selectedAudioPath }
    val deleteAudioTarget = state.audioLibrary.firstOrNull { it.path == routeState.deleteAudioPath }
    val standaloneResults = state.resultSessions.filter { it.recordingGroupId.isBlank() }
    val resultSessionsById = state.resultSessions.associateBy(TranscriptionSessionStore.Checkpoint::sessionId)
    val consentCandidate = routeState.summaryConsentSource?.let { source ->
        summaryCandidateFor(source, state.resultSessions, state.recordingGroups)
    }
    val driveUploadCandidate = routeState.pendingDriveUploadSource?.let { source ->
        TranscriptSourceReader.resolve(source, state.resultSessions, state.recordingGroups)
    }
    val selectedSessionDocument = selected?.let(TranscriptSourceReader::fromCompletedSession)
    val selectedGroupDocument = selectedGroup?.let {
        TranscriptSourceReader.fromCompletedGroup(it, state.resultSessions)
    }
    val fullSessionDocument = fullTranscriptSession?.let(TranscriptSourceReader::fromCompletedSession)
    val fullGroupDocument = fullTranscriptGroup?.let {
        TranscriptSourceReader.fromCompletedGroup(it, state.resultSessions)
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(TranscriptFileShareFactory.MIME_TYPE),
    ) { uri ->
        val pendingSource = routeViewModel.uiState.value.pendingExportSource
        when {
            uri == null -> routeViewModel.finishTranscriptExport("TXT 저장을 취소했습니다.")
            pendingSource == null -> routeViewModel.finishTranscriptExport("저장할 전사를 다시 선택하세요.")
            else -> {
                val currentState = viewModel.uiState.value
                val document = TranscriptSourceReader.resolve(
                    pendingSource,
                    currentState.resultSessions,
                    currentState.recordingGroups,
                )
                if (document == null) {
                    routeViewModel.finishTranscriptExport("저장할 완료 전사를 찾을 수 없습니다.")
                } else {
                    routeViewModel.beginTranscriptExport("TXT 파일을 저장하고 있습니다.")
                    coroutineScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching { documentSaver.save(uri, document) }
                        }
                        routeViewModel.finishTranscriptExport(
                            if (saved.isSuccess) {
                                "전체 전사를 TXT 파일로 저장했습니다."
                            } else {
                                "TXT 저장에 실패했습니다. 저장 위치와 공간을 확인하세요."
                            },
                        )
                    }
                }
            }
        }
    }

    val requestDocumentSave: (TranscriptSourceDocument) -> Unit = { document ->
        if (!routeViewModel.uiState.value.exportInProgress) {
            routeViewModel.requestTranscriptSave(document.source)
            when (
                TranscriptDocumentLauncher.launch {
                    saveDocumentLauncher.launch(TranscriptExportWriter.defaultFileName(document.updatedAtMs))
                }
            ) {
                TranscriptDocumentLauncher.Result.STARTED -> Unit
                TranscriptDocumentLauncher.Result.NO_HANDLER -> {
                    routeViewModel.finishTranscriptExport("TXT 저장 위치를 선택할 앱을 찾을 수 없습니다.")
                }
                TranscriptDocumentLauncher.Result.BLOCKED -> {
                    routeViewModel.finishTranscriptExport("TXT 저장 위치 선택 화면을 열 수 없습니다.")
                }
            }
        }
    }
    val requestFileShare: (TranscriptSourceDocument) -> Unit = { document ->
        if (!routeViewModel.uiState.value.exportInProgress) {
            routeViewModel.beginTranscriptExport("공유할 TXT 파일을 준비하고 있습니다.")
            coroutineScope.launch {
                val prepared = withContext(Dispatchers.IO) {
                    runCatching { fileShareFactory.createChooser(document) }
                }
                prepared.fold(
                    onSuccess = { chooser ->
                        try {
                            context.startActivity(chooser)
                            routeViewModel.finishTranscriptExport("공유할 앱을 선택하세요.")
                        } catch (_: ActivityNotFoundException) {
                            routeViewModel.finishTranscriptExport("전체 전사 파일을 공유할 앱을 찾을 수 없습니다.")
                        } catch (_: SecurityException) {
                            routeViewModel.finishTranscriptExport("전체 전사 파일 공유 화면을 열 수 없습니다.")
                        }
                    },
                    onFailure = {
                        routeViewModel.finishTranscriptExport("공유 파일을 만들지 못했습니다. 저장 공간을 확인하세요.")
                    },
                )
            }
        }
    }

    LaunchedEffect(routeState, state.resultSessions, state.recordingGroups, state.audioLibrary) {
        if (routeState.selectedSessionId.isNotBlank() && selected == null) routeViewModel.dismissSession()
        if (routeState.fullTranscriptSessionId.isNotBlank() && fullTranscriptSession == null) {
            routeViewModel.dismissFullTranscript()
        }
        if (routeState.deleteSessionId.isNotBlank() && deleteTarget == null) routeViewModel.dismissSessionDeletion()
        if (routeState.selectedGroupId.isNotBlank() && selectedGroup == null) routeViewModel.dismissGroup()
        if (routeState.fullTranscriptGroupId.isNotBlank() && fullTranscriptGroup == null) {
            routeViewModel.dismissFullTranscript()
        }
        if (routeState.deleteGroupId.isNotBlank() && deleteGroupTarget == null) routeViewModel.dismissGroupDeletion()
        if (routeState.selectedAudioPath.isNotBlank() && selectedAudio == null) routeViewModel.dismissAudio()
        if (routeState.deleteAudioPath.isNotBlank() && deleteAudioTarget == null) routeViewModel.dismissAudioDeletion()
        if (routeState.summaryConsentSource != null && consentCandidate == null) routeViewModel.dismissSummaryConsent()
        routeState.pendingExportSource?.let { pendingSource ->
            val pendingDocument = TranscriptSourceReader.resolve(
                pendingSource,
                state.resultSessions,
                state.recordingGroups,
            )
            if (pendingDocument == null) {
                routeViewModel.finishTranscriptExport("저장할 완료 전사를 찾을 수 없습니다.")
            }
        }
        if (routeState.pendingDriveUploadSource != null && driveUploadCandidate == null) {
            routeViewModel.dismissDriveUpload()
        }
    }

    LaunchedEffect(initialCompletedResult, initialTranscriptSectionKey, state.resultLibraryLoaded) {
        if (shouldConsumeInitialCompletedResult(initialCompletedResult, state.resultLibraryLoaded)) {
            initialCompletedResult?.let {
                completedResultToOpen(it, state.resultSessions, state.recordingGroups)?.let { target ->
                    if (initialTranscriptSectionKey.isBlank()) {
                        routeViewModel.openCompletedResult(target)
                    } else {
                        routeViewModel.openTranscriptCitation(target, initialTranscriptSectionKey)
                    }
                }
                onInitialCompletedResultHandled()
            }
        }
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
                    RecordingGroupRow(
                        group = group,
                        summaryState = summaryState,
                        drive = driveState,
                        summaryEligible = auth.phase == CodexAuthPhase.AUTHENTICATED &&
                            group.status == RecordingTranscriptionGroupStore.GroupStatus.COMPLETED &&
                            group.children.all { child ->
                                resultSessionsById[child.sttSessionId]?.let { session ->
                                    session.status == TranscriptionSessionStore.Status.COMPLETED &&
                                        session.chunks.any { it.text.isNotBlank() }
                                } == true
                            },
                        onOpen = { routeViewModel.selectGroup(group.groupId) },
                    )
                }
                item { Spacer(Modifier.height(22.dp)) }
            }

            if (standaloneResults.isNotEmpty()) {
                item { SectionLabel("단일 전사 결과 ${standaloneResults.size}") }
                items(standaloneResults, key = { it.sessionId }) { session ->
                    TranscriptRow(
                        session = session,
                        summaryState = summaryState,
                        drive = driveState,
                        summaryEligible = auth.phase == CodexAuthPhase.AUTHENTICATED &&
                            session.status == TranscriptionSessionStore.Status.COMPLETED &&
                            session.chunks.any { it.text.isNotBlank() },
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
                        onShare = { summary -> shareSummary(context, summary) },
                    )
                    if (childSessions.any { session -> session.chunks.any { it.text.isNotBlank() } }) {
                        OutlinedButton(
                            onClick = { routeViewModel.showFullGroupTranscript(group.groupId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .archiveTouchTarget()
                                .semantics { contentDescription = "그룹 전사 전체 보기" },
                        ) { Text("그룹 전사 전체 보기") }
                    }
                    selectedGroupDocument?.let { document ->
                        OutlinedButton(
                            onClick = {
                                routeViewModel.dismissGroup()
                                onOpenTranscriptChat(document.source)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .archiveTouchTarget()
                                .semantics { contentDescription = "그룹 전사를 AI에게 질문" },
                        ) {
                            Icon(Icons.Outlined.Chat, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("AI에게 전사 질문")
                        }
                        TranscriptExportActions(
                            inProgress = routeState.exportInProgress,
                            statusMessage = routeState.exportStatusMessage,
                            onSave = { requestDocumentSave(document) },
                            onShare = { requestFileShare(document) },
                        )
                        DriveUploadActions(
                            drive = driveState,
                            source = document.source,
                            onUpload = { routeViewModel.requestDriveUpload(document.source) },
                            onRetry = googleDriveViewModel::retry,
                        )
                    }
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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "${session.chunks.size}/${session.totalChunks} 구간 · ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        onShare = { summary -> shareSummary(context, summary) },
                    )
                    if (session.chunks.any { it.text.isNotBlank() }) {
                        OutlinedButton(
                            onClick = { routeViewModel.showFullSessionTranscript(session.sessionId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .archiveTouchTarget()
                                .semantics { contentDescription = "전사 전체 보기" },
                        ) { Text("전사 전체 보기") }
                    }
                    selectedSessionDocument?.let { document ->
                        OutlinedButton(
                            onClick = {
                                routeViewModel.dismissSession()
                                onOpenTranscriptChat(document.source)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .archiveTouchTarget()
                                .semantics { contentDescription = "전사를 AI에게 질문" },
                        ) {
                            Icon(Icons.Outlined.Chat, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("AI에게 전사 질문")
                        }
                        TranscriptExportActions(
                            inProgress = routeState.exportInProgress,
                            statusMessage = routeState.exportStatusMessage,
                            onSave = { requestDocumentSave(document) },
                            onShare = { requestFileShare(document) },
                        )
                        DriveUploadActions(
                            drive = driveState,
                            source = document.source,
                            onUpload = { routeViewModel.requestDriveUpload(document.source) },
                            onRetry = googleDriveViewModel::retry,
                        )
                    }
                    SectionLabel("전사 미리보기")
                    Text(
                        result.text.ifBlank { "아직 저장된 전사 본문이 없습니다." },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
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

    driveUploadCandidate?.let { document ->
        val hasSummary = summaryState.entries.any { entry ->
            entry.source.type == document.source.type.toSummarySourceType() &&
                entry.source.id == document.source.id
        }
        DriveUploadSelectionDialog(
            hasSummary = hasSummary,
            onConfirm = { artifacts ->
                googleDriveViewModel.enqueueManual(document.source, artifacts)
                routeViewModel.dismissDriveUpload()
            },
            onDismiss = routeViewModel::dismissDriveUpload,
        )
    }

    fullTranscriptSession?.let { session ->
        FullTranscriptDialog(
            title = "전체 전사",
            detail = "${session.chunks.size}/${session.totalChunks} 구간 · ${formatDuration(session.durationMs)}",
            sections = fullSessionDocument?.sections.orEmpty().map { section ->
                TranscriptViewerSection(section.key, section.label, section.text)
            },
            initialSectionKey = routeState.fullTranscriptInitialSectionKey,
            inProgress = routeState.exportInProgress,
            statusMessage = routeState.exportStatusMessage,
            onSave = fullSessionDocument?.let { document -> { requestDocumentSave(document) } },
            onShare = fullSessionDocument?.let { document -> { requestFileShare(document) } },
            onDismiss = routeViewModel::dismissFullTranscript,
        )
    }

    fullTranscriptGroup?.let { group ->
        val sections = fullGroupDocument?.sections.orEmpty().map { section ->
            TranscriptViewerSection(section.key, section.label, section.text)
        }
        FullTranscriptDialog(
            title = "그룹 전체 전사",
            detail = "녹음 ${group.children.size}개 · 전사 구간 ${sections.size}개",
            sections = sections,
            initialSectionKey = routeState.fullTranscriptInitialSectionKey,
            inProgress = routeState.exportInProgress,
            statusMessage = routeState.exportStatusMessage,
            onSave = fullGroupDocument?.let { document -> { requestDocumentSave(document) } },
            onShare = fullGroupDocument?.let { document -> { requestFileShare(document) } },
            onDismiss = routeViewModel::dismissFullTranscript,
        )
    }
}

@Composable
private fun DriveUploadActions(
    drive: GoogleDriveUiState,
    source: TranscriptSourceRef,
    onUpload: () -> Unit,
    onRetry: (String) -> Unit,
) {
    val job = drive.latestJob(source)
    val uploading = job?.status in setOf(
        DriveUploadStatus.QUEUED,
        DriveUploadStatus.PREPARING,
        DriveUploadStatus.UPLOADING,
        DriveUploadStatus.RETRY_WAIT,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onUpload,
        enabled = !uploading,
        modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
    ) { Text(if (job?.status == DriveUploadStatus.COMPLETED) "Google Drive 저장됨" else "Google Drive에 업로드") }
    when {
        drive.connectionPhase == DriveConnectionPhase.REAUTH_REQUIRED -> Text(
            "Google Drive 재연결이 필요합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        job == null -> Unit
        uploading -> {
            LinearProgressIndicator(
                progress = { job.progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                job.status.uploadLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        job.status == DriveUploadStatus.PARTIAL_COMPLETED -> Text(
            "전사 또는 요약 일부가 Google Drive에 저장되었습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        job.status == DriveUploadStatus.COMPLETED -> Text(
            "선택한 파일을 Google Drive에 저장했습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        job.status in setOf(DriveUploadStatus.FAILED, DriveUploadStatus.AUTH_REQUIRED) -> {
            Text(
                if (job.status == DriveUploadStatus.AUTH_REQUIRED) {
                    "Google Drive 재연결 후 다시 시도하세요."
                } else {
                    "Google Drive 업로드를 완료하지 못했습니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { onRetry(job.jobId) },
                modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
            ) { Text("Google Drive 업로드 다시 시도") }
        }

        else -> Unit
    }
}

/** 보관함에서 전사·요약의 Drive 저장 결과를 한눈에 구분하는 상태 행. */
@Composable
private fun DriveUploadListIndicator(
    job: DriveUploadJob?,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (job?.status) {
        null -> return
        DriveUploadStatus.QUEUED,
        DriveUploadStatus.PREPARING,
        DriveUploadStatus.UPLOADING,
        DriveUploadStatus.RETRY_WAIT,
        -> job.status.uploadLabel() to MaterialTheme.colorScheme.onSurfaceVariant

        DriveUploadStatus.PARTIAL_COMPLETED -> "Google Drive 일부 저장됨" to MaterialTheme.colorScheme.onSurfaceVariant
        DriveUploadStatus.COMPLETED -> "Google Drive 저장됨" to MaterialTheme.colorScheme.primary
        DriveUploadStatus.AUTH_REQUIRED -> "Google Drive 재연결 필요" to MaterialTheme.colorScheme.error
        DriveUploadStatus.FAILED -> "Google Drive 업로드 실패" to MaterialTheme.colorScheme.error
        DriveUploadStatus.CANCELLED -> "Google Drive 업로드 취소됨" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun DriveUploadSelectionDialog(
    hasSummary: Boolean,
    onConfirm: (Set<DriveArtifact>) -> Unit,
    onDismiss: () -> Unit,
) {
    var includeTranscript by remember { mutableStateOf(true) }
    var includeSummary by remember(hasSummary) { mutableStateOf(hasSummary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google Drive에 업로드") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("선택한 파일을 개인 Google Drive의 Long STT 폴더에 저장합니다.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeTranscript, onCheckedChange = { includeTranscript = it })
                    Text("전체 전사 TXT")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeSummary,
                        enabled = hasSummary,
                        onCheckedChange = { includeSummary = it },
                    )
                    Text(if (hasSummary) "완료된 요약 TXT" else "완료된 요약이 없습니다.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(buildSet {
                        if (includeTranscript) add(DriveArtifact.TRANSCRIPT)
                        if (includeSummary && hasSummary) add(DriveArtifact.SUMMARY)
                    })
                },
                enabled = includeTranscript || (includeSummary && hasSummary),
            ) { Text("업로드") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun DriveUploadStatus.uploadLabel(): String = when (this) {
    DriveUploadStatus.QUEUED -> "Google Drive 업로드 대기 중"
    DriveUploadStatus.PREPARING -> "Google Drive 파일을 준비하고 있습니다."
    DriveUploadStatus.UPLOADING -> "Google Drive에 업로드하고 있습니다."
    DriveUploadStatus.RETRY_WAIT -> "네트워크 재시도를 기다리고 있습니다."
    else -> ""
}

internal fun completedResultToOpen(
    target: CompletedResultTarget?,
    sessions: List<TranscriptionSessionStore.Checkpoint>,
    groups: List<RecordingTranscriptionGroupStore.Group>,
): CompletedResultTarget? = target?.takeIf { it.isAvailable(sessions, groups) }

internal fun shouldConsumeInitialCompletedResult(
    target: CompletedResultTarget?,
    resultLibraryLoaded: Boolean,
): Boolean = target != null && resultLibraryLoaded

internal data class SummaryCandidate(
    val source: SummaryRequestPolicy.Source,
    val transcript: String,
)

private fun summaryCandidateFor(
    source: SummaryRequestPolicy.Source,
    sessions: List<TranscriptionSessionStore.Checkpoint>,
    groups: List<RecordingTranscriptionGroupStore.Group>,
): SummaryCandidate? {
    val transcriptSource = TranscriptSourceRef(
        type = when (source.type) {
            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION -> TranscriptSourceType.TRANSCRIPTION_SESSION
            SummarySessionStore.SourceType.RECORDING_GROUP -> TranscriptSourceType.RECORDING_GROUP
        },
        id = source.id,
    )
    return TranscriptSourceReader.resolve(transcriptSource, sessions, groups)
        ?.joinedText()
        ?.takeIf(String::isNotBlank)
        ?.let { SummaryCandidate(source, it) }
}

@Composable
internal fun SummaryResultSection(
    candidate: SummaryCandidate?,
    auth: CodexAuthUiState,
    summaryState: SummaryUiState,
    onRequest: (SummaryRequestPolicy.Source) -> Unit,
    onShare: (String) -> Unit = {},
) {
    val source = candidate?.source
    val entry = source?.let { candidateSource ->
        summaryState.entries.firstOrNull { it.source == candidateSource }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("외부 요약", style = MaterialTheme.typography.titleMedium)
            Text(
                "명시적으로 동의한 결과만 전송합니다. 긴 전사는 여러 구간으로 나눠 순차 요약합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                entry != null -> {
                    StatusPill("요약 완료", tone = ArchiveStatusTone.COMPLETE)
                    OutlinedButton(
                        onClick = { onShare(entry.summary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .semantics { contentDescription = "완료 요약 공유" },
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("요약 공유")
                    }
                    Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                }
                source != null && summaryState.activeSourceKey == source.key -> {
                    StatusPill(summaryState.stage.visibleLabel(), tone = ArchiveStatusTone.ACTIVE)
                    LinearProgressIndicator(
                        progress = { summaryState.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${summaryState.completedSteps}/${summaryState.totalSteps} 단계 · " +
                            "${(summaryState.progressFraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                ) { Text("외부 요약 시작") }
            }
            if (
                entry == null &&
                summaryState.statusMessage.isNotBlank() &&
                !summaryState.isRunning &&
                source?.key == summaryState.statusSourceKey
            ) {
                Text(
                    summaryState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summaryState.stage == SummaryStage.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

internal sealed interface SummaryListState {
    data object Hidden : SummaryListState
    data object Available : SummaryListState
    data class Running(val progress: Float) : SummaryListState
    data class Completed(val preview: String) : SummaryListState
    data object Error : SummaryListState
}

internal fun summaryListState(
    source: SummaryRequestPolicy.Source,
    eligible: Boolean,
    summaryState: SummaryUiState,
): SummaryListState {
    val entry = summaryState.entries.firstOrNull { it.source == source }
    return when {
        entry != null -> SummaryListState.Completed(entry.summary)
        summaryState.activeSourceKey == source.key && summaryState.isRunning ->
            SummaryListState.Running(summaryState.progressFraction)
        summaryState.statusSourceKey == source.key && summaryState.stage == SummaryStage.ERROR ->
            SummaryListState.Error
        eligible -> SummaryListState.Available
        else -> SummaryListState.Hidden
    }
}

@Composable
internal fun SummaryListIndicator(
    state: SummaryListState,
    modifier: Modifier = Modifier,
) {
    if (state == SummaryListState.Hidden) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            SummaryListState.Hidden -> Unit
            SummaryListState.Available -> StatusPill("요약 가능")
            SummaryListState.Error -> StatusPill("요약 확인 필요", tone = ArchiveStatusTone.ERROR)
            is SummaryListState.Running -> {
                val percent = (state.progress * 100).roundToInt()
                StatusPill("요약 중 · $percent%", tone = ArchiveStatusTone.ACTIVE)
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SummaryListState.Completed -> {
                StatusPill("요약 완료", tone = ArchiveStatusTone.COMPLETE)
                Text(
                    state.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun SummaryStage.visibleLabel(): String = when (this) {
    SummaryStage.PREPARING -> "요약 준비"
    SummaryStage.SUMMARIZING -> "구간 요약"
    SummaryStage.SYNTHESIZING -> "요약 통합"
    SummaryStage.SAVING -> "저장 중"
    SummaryStage.ERROR -> "확인 필요"
    SummaryStage.IDLE -> "대기"
}

private fun shareSummary(context: Context, summary: String) {
    when (SummaryShareLauncher.launch(context, summary)) {
        SummaryShareLauncher.Result.STARTED -> Unit
        SummaryShareLauncher.Result.NO_HANDLER -> {
            Toast.makeText(context, "요약을 공유할 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
        SummaryShareLauncher.Result.BLOCKED -> {
            Toast.makeText(context, "요약 공유 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
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
                "선택한 완료 전사의 본문을 Codex 외부 서비스에 전송해 요약합니다. " +
                    "긴 전사는 제한된 크기의 여러 구간으로 나눠 순차 전송한 뒤 통합합니다. " +
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
    summaryState: SummaryUiState,
    drive: GoogleDriveUiState,
    summaryEligible: Boolean,
    onOpen: () -> Unit,
) {
    val source = SummaryRequestPolicy.Source(
        SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
        session.sessionId,
    )
    val listState = summaryListState(
        source = source,
        eligible = summaryEligible,
        summaryState = summaryState,
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .archiveTouchTarget()
                .clickable(onClick = onOpen),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            SummaryListIndicator(state = listState, modifier = Modifier.padding(start = 36.dp, end = 8.dp))
            DriveUploadListIndicator(
                job = drive.latestJob(
                    TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, session.sessionId),
                ),
                modifier = Modifier.padding(start = 36.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun RecordingGroupRow(
    group: RecordingTranscriptionGroupStore.Group,
    summaryState: SummaryUiState,
    drive: GoogleDriveUiState,
    summaryEligible: Boolean,
    onOpen: () -> Unit,
) {
    val source = SummaryRequestPolicy.Source(
        SummarySessionStore.SourceType.RECORDING_GROUP,
        group.groupId,
    )
    val listState = summaryListState(source, summaryEligible, summaryState)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().archiveTouchTarget().clickable(onClick = onOpen),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            SummaryListIndicator(state = listState, modifier = Modifier.padding(start = 36.dp, end = 8.dp))
            DriveUploadListIndicator(
                job = drive.latestJob(
                    TranscriptSourceRef(TranscriptSourceType.RECORDING_GROUP, group.groupId),
                ),
                modifier = Modifier.padding(start = 36.dp, end = 8.dp),
            )
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
