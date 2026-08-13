package com.stt.benchmark.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stt.benchmark.chat.TranscriptChatMode
import com.stt.benchmark.chat.TranscriptChatPhase
import com.stt.benchmark.chat.TranscriptChatPolicy
import com.stt.benchmark.chat.TranscriptChatSessionStore
import com.stt.benchmark.chat.TranscriptChatUiState
import com.stt.benchmark.chat.TranscriptChatViewModel
import com.stt.benchmark.chat.TranscriptCitation
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.ui.common.ArchiveStatusTone
import com.stt.benchmark.ui.common.FullTranscriptDialog
import com.stt.benchmark.ui.common.StatusPill
import com.stt.benchmark.ui.common.TranscriptViewerMode
import com.stt.benchmark.ui.common.TranscriptViewerSection
import com.stt.benchmark.ui.common.archiveTouchTarget
import com.stt.benchmark.ui.common.formatDuration

@Composable
fun TranscriptChatRoute(
    document: TranscriptSourceDocument?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranscriptChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(document?.source, document?.updatedAtMs) { viewModel.openSource(document) }
    TranscriptChatScreen(
        state = state,
        onBack = onBack,
        onConfirmConsent = viewModel::confirmIndexingConsent,
        onDismissConsent = onBack,
        onModeChange = viewModel::selectMode,
        onDraftChange = viewModel::updateDraft,
        onSend = { viewModel.ask() },
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
        onNewConversation = viewModel::newConversation,
        onDeleteConversation = viewModel::deleteConversation,
        document = document,
        modifier = modifier,
    )
}

@Composable
internal fun TranscriptChatScreen(
    state: TranscriptChatUiState,
    onBack: () -> Unit,
    onConfirmConsent: () -> Unit,
    onDismissConsent: () -> Unit,
    onModeChange: (TranscriptChatMode) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
    document: TranscriptSourceDocument? = null,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectedCitationUnitId by rememberSaveable { mutableStateOf("") }
    val selectedCitation = state.citationCatalog.firstOrNull {
        it.unitId == selectedCitationUnitId &&
            document?.sections?.any { section -> section.key == it.sourceSectionKey } == true
    }
    val openCitation: (TranscriptCitation) -> Unit = { citation ->
        if (document?.sections?.any { it.key == citation.sourceSectionKey } == true) {
            selectedCitationUnitId = citation.unitId
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("transcript_chat_scroll"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "전사와 대화",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "원문은 기존 전사에서 필요한 구간만 읽습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onBack, modifier = Modifier.archiveTouchTarget()) { Text("닫기") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.source?.let { source ->
                        Text(
                            "대화 대상 · ${if (source.type == com.stt.benchmark.data.TranscriptSourceType.TRANSCRIPTION_SESSION) "단일 전사" else "녹음 그룹"}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    StatusPill(
                        if (state.isAuthenticated) "ChatGPT 연결됨" else "ChatGPT 연결 필요",
                        tone = if (state.isAuthenticated) ArchiveStatusTone.COMPLETE else ArchiveStatusTone.ERROR,
                    )
                    StatusPill(state.stageLabel, tone = state.phase.tone())
                }
            }
            if (state.totalSteps > 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.completedSteps}/${state.totalSteps} 단계 · ${state.progressPercent}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            item { Text(state.statusMessage, style = MaterialTheme.typography.bodySmall) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.mode == TranscriptChatMode.QUICK,
                        onClick = { onModeChange(TranscriptChatMode.QUICK) },
                        enabled = !state.phase.isRunning(),
                        label = { Text("빠른 질문") },
                    )
                    FilterChip(
                        selected = state.mode == TranscriptChatMode.PRECISE,
                        onClick = { onModeChange(TranscriptChatMode.PRECISE) },
                        enabled = state.preciseAvailable && !state.phase.isRunning(),
                        label = { Text("전체 정밀 탐색") },
                    )
                }
            }
            if (state.messages.isEmpty() && state.currentAnswer.isBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Chat, contentDescription = null)
                            Text("저장된 대화가 없습니다", style = MaterialTheme.typography.titleMedium)
                            Text("추천 질문")
                            listOf("핵심 결정은 무엇인가요?", "후속 조치를 알려줘").forEach { suggestion ->
                                OutlinedButton(
                                    onClick = { onDraftChange(suggestion) },
                                    modifier = Modifier.fillMaxWidth().archiveTouchTarget(),
                                ) {
                                    Text(suggestion)
                                }
                            }
                        }
                    }
                }
            }
            items(state.messages) { message ->
                MessageBubble(message, state.citationCatalog, openCitation)
            }
            if (state.currentAnswer.isNotBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(state.currentAnswer, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            if (state.currentCitations.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("검증된 근거", style = MaterialTheme.typography.labelLarge)
                        state.currentCitations.forEach { citation ->
                            OutlinedButton(
                                onClick = { openCitation(citation) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .archiveTouchTarget()
                                    .semantics { contentDescription = "근거 ${citation.unitId} 확인" },
                            ) {
                                Text("${citation.unitId} · ${formatDuration(citation.startMs)}–${formatDuration(citation.endMs)}")
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.draftQuestion,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.indexReady && !state.phase.isRunning(),
                    label = { Text("전사에 질문하기") },
                    supportingText = { Text("${state.draftQuestion.length}/${TranscriptChatPolicy.MAX_QUESTION_CHARS}") },
                    maxLines = 4,
                )
            }
            item {
                if (state.phase.isRunning()) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .semantics { contentDescription = "답변 또는 탐색 중지" },
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("중지")
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = state.indexReady && state.draftQuestion.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .semantics { contentDescription = "전사 질문 전송" },
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.mode == TranscriptChatMode.PRECISE) "전체 탐색 시작" else "질문 보내기")
                    }
                }
            }
            if (state.canRetry) {
                item {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .archiveTouchTarget()
                            .semantics { contentDescription = "전사 채팅 재시도" },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("재시도")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onNewConversation, enabled = !state.phase.isRunning()) { Text("새 대화") }
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = state.messages.isNotEmpty() && !state.phase.isRunning(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Text("대화 삭제")
                    }
                }
            }
        }
    }

    if (state.phase == TranscriptChatPhase.CONSENT_REQUIRED) {
        TranscriptChatConsentDialog(onConfirm = onConfirmConsent, onDismiss = onDismissConsent)
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("대화 기록을 삭제할까요?") },
            text = { Text("완료된 질문과 답변만 삭제합니다. 원본 전사, 요약, 검색 인덱스는 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDeleteConversation()
                }) { Text("대화만 삭제") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("취소") } },
        )
    }
    selectedCitation?.let { citation ->
        FullTranscriptDialog(
            title = "근거 확인",
            detail = "${citation.unitId} · ${formatDuration(citation.startMs)}–${formatDuration(citation.endMs)} · 답변에서 참조한 전사 구간",
            sections = document?.sections.orEmpty().map { section ->
                TranscriptViewerSection(section.key, section.label, section.text)
            },
            initialSectionKey = citation.sourceSectionKey,
            mode = TranscriptViewerMode.CHAT_CITATION,
            onDismiss = { selectedCitationUnitId = "" },
        )
    }
}

@Composable
internal fun TranscriptChatConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전사와 대화를 준비할까요?") },
        text = {
            Text(
                "검색용 인덱스를 만들기 위해 완료 전사를 약 10,000자 구간으로 나눠 연결된 외부 LLM에 전송합니다. " +
                    "원문은 인덱스 파일에 저장하지 않으며, 동의하기 전에는 전송하지 않습니다.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("동의하고 준비") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun MessageBubble(
    message: TranscriptChatSessionStore.Message,
    citationCatalog: List<TranscriptCitation>,
    onOpenCitation: (TranscriptCitation) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (message.role == TranscriptChatSessionStore.Role.USER) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (message.role == TranscriptChatSessionStore.Role.USER) "나" else "전사 답변", style = MaterialTheme.typography.labelLarge)
            Text(message.text)
            message.citationUnitIds.mapNotNull { id -> citationCatalog.firstOrNull { it.unitId == id } }
                .forEach { citation ->
                    TextButton(
                        onClick = { onOpenCitation(citation) },
                        modifier = Modifier.semantics {
                            contentDescription = "근거 ${citation.unitId} 확인"
                        },
                    ) {
                        Text("${citation.unitId} · ${formatDuration(citation.startMs)}–${formatDuration(citation.endMs)}")
                    }
                }
        }
    }
}

private fun TranscriptChatPhase.isRunning(): Boolean =
    this == TranscriptChatPhase.INDEXING || this == TranscriptChatPhase.SEARCHING || this == TranscriptChatPhase.ANSWERING

private fun TranscriptChatPhase.tone(): ArchiveStatusTone = when (this) {
    TranscriptChatPhase.INDEXING, TranscriptChatPhase.SEARCHING, TranscriptChatPhase.ANSWERING -> ArchiveStatusTone.ACTIVE
    TranscriptChatPhase.COMPLETED -> ArchiveStatusTone.COMPLETE
    TranscriptChatPhase.ERROR -> ArchiveStatusTone.ERROR
    else -> ArchiveStatusTone.NEUTRAL
}
