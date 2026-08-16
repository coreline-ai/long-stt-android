package com.stt.benchmark.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.stt.benchmark.data.TranscriptSourceType
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
    val citedInSavedMessages = state.messages
        .flatMap(TranscriptChatSessionStore.Message::citationUnitIds)
        .toSet()
    val standaloneCitations = state.currentCitations.filterNot { it.unitId in citedInSavedMessages }

    Column(
        modifier = modifier
            .fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("transcript_chat_scroll"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ChatHeader(onBack = onBack) }
            item { SourceStatusCard(state = state) }
            if (state.phase.isRunning() && state.totalSteps > 0) {
                item { ChatProgress(state = state) }
            }
            item { ChatModeSelector(state = state, onModeChange = onModeChange) }
            if (state.messages.isEmpty() && state.currentAnswer.isBlank()) {
                item { EmptyConversation(onDraftChange = onDraftChange) }
            }
            items(state.messages, key = { "${it.timestampMs}:${it.role}" }) { message ->
                MessageBubble(message, state.citationCatalog, openCitation)
            }
            if (state.currentAnswer.isNotBlank()) {
                item {
                    AssistantAnswerBubble(
                        answer = state.currentAnswer,
                        citations = standaloneCitations,
                        onOpenCitation = openCitation,
                    )
                }
            } else if (standaloneCitations.isNotEmpty()) {
                item { StandaloneCitations(standaloneCitations, openCitation) }
            }
        }

        ChatComposer(
            state = state,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onStop = onStop,
            onRetry = onRetry,
            onNewConversation = onNewConversation,
            onDeleteConversation = { showDeleteConfirmation = true },
        )
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
private fun ChatHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "전사 기반 AI 대화",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "완료된 전사에서 필요한 근거만 찾아 답합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onBack, modifier = Modifier.archiveTouchTarget()) { Text("닫기") }
    }
}

@Composable
private fun SourceStatusCard(state: TranscriptChatUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.source?.let { source ->
                Text(
                    "대화 대상 · ${if (source.type == TranscriptSourceType.TRANSCRIPTION_SESSION) "단일 전사" else "녹음 그룹"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = if (state.isAuthenticated) "ChatGPT 연결됨" else "ChatGPT 연결 필요",
                    modifier = Modifier.weight(1f),
                    tone = if (state.isAuthenticated) ArchiveStatusTone.COMPLETE else ArchiveStatusTone.ERROR,
                )
                StatusPill(
                    text = state.stageLabel,
                    modifier = Modifier.weight(1f),
                    tone = state.phase.tone(),
                )
            }
            Text(
                state.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatProgress(state: TranscriptChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { state.progressPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${state.completedSteps}/${state.totalSteps} 단계 · ${state.progressPercent}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatModeSelector(
    state: TranscriptChatUiState,
    onModeChange: (TranscriptChatMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.mode == TranscriptChatMode.QUICK,
            onClick = { onModeChange(TranscriptChatMode.QUICK) },
            enabled = !state.phase.isRunning(),
            label = { Text("빠른 질문") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = state.mode == TranscriptChatMode.PRECISE,
            onClick = { onModeChange(TranscriptChatMode.PRECISE) },
            enabled = state.preciseAvailable && !state.phase.isRunning(),
            label = { Text("전체 정밀 탐색") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyConversation(onDraftChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("저장된 대화가 없습니다", style = MaterialTheme.typography.titleMedium)
            Text(
                "전사 내용을 바로 확인할 수 있는 질문으로 시작해 보세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf("핵심 결정은 무엇인가요?", "후속 조치를 알려줘").forEach { suggestion ->
                OutlinedButton(
                    onClick = { onDraftChange(suggestion) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .archiveTouchTarget(),
                ) {
                    Text(suggestion)
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    state: TranscriptChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        // Keep the composer on the archive background. A tonal surface here visually merges
        // with the bottom navigation (#2A251F) and makes the existing bar look enlarged.
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = state.draftQuestion,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = state.indexReady && !state.phase.isRunning(),
                    label = { Text("전사에 질문하기") },
                    supportingText = {
                        Text("${state.draftQuestion.length}/${TranscriptChatPolicy.MAX_QUESTION_CHARS}")
                    },
                    maxLines = 3,
                )
                if (state.phase.isRunning()) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .width(64.dp)
                            .height(56.dp)
                            .archiveTouchTarget()
                            .semantics { contentDescription = "답변 또는 탐색 중지" },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = state.indexReady && state.draftQuestion.isNotBlank(),
                        modifier = Modifier
                            .width(64.dp)
                            .height(56.dp)
                            .archiveTouchTarget()
                            .semantics { contentDescription = "전사 질문 전송" },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = null)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onNewConversation, enabled = !state.phase.isRunning()) {
                        Text("새 대화")
                    }
                    TextButton(
                        onClick = onDeleteConversation,
                        enabled = state.messages.isNotEmpty() && !state.phase.isRunning(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("대화 삭제")
                    }
                }
                if (state.canRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics { contentDescription = "전사 채팅 재시도" },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("재시도")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TranscriptChatConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전사 기반 AI 대화를 준비할까요?") },
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
    val isUser = message.role == TranscriptChatSessionStore.Role.USER
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.8f else 0.9f)
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            shape = RoundedCornerShape(20.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (isUser) "나" else "전사 답변",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                message.citationUnitIds
                    .mapNotNull { id -> citationCatalog.firstOrNull { it.unitId == id } }
                    .forEach { citation -> CitationButton(citation, onOpenCitation) }
            }
        }
    }
}

@Composable
private fun AssistantAnswerBubble(
    answer: String,
    citations: List<TranscriptCitation>,
    onOpenCitation: (TranscriptCitation) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.CenterStart),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("전사 답변", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(answer, style = MaterialTheme.typography.bodyLarge)
                citations.forEach { citation -> CitationButton(citation, onOpenCitation) }
            }
        }
    }
}

@Composable
private fun StandaloneCitations(
    citations: List<TranscriptCitation>,
    onOpenCitation: (TranscriptCitation) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("검증된 근거", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            citations.forEach { citation -> CitationButton(citation, onOpenCitation) }
        }
    }
}

@Composable
private fun CitationButton(
    citation: TranscriptCitation,
    onOpenCitation: (TranscriptCitation) -> Unit,
) {
    OutlinedButton(
        onClick = { onOpenCitation(citation) },
        modifier = Modifier
            .fillMaxWidth()
            .archiveTouchTarget()
            .semantics { contentDescription = "근거 ${citation.unitId} 확인" },
    ) {
        Text("${citation.unitId} · ${formatDuration(citation.startMs)}–${formatDuration(citation.endMs)} · 근거 확인")
    }
}

private fun TranscriptChatPhase.isRunning(): Boolean =
    this == TranscriptChatPhase.INDEXING ||
        this == TranscriptChatPhase.SEARCHING ||
        this == TranscriptChatPhase.ANSWERING

private fun TranscriptChatPhase.tone(): ArchiveStatusTone = when (this) {
    TranscriptChatPhase.INDEXING,
    TranscriptChatPhase.SEARCHING,
    TranscriptChatPhase.ANSWERING,
    -> ArchiveStatusTone.ACTIVE

    TranscriptChatPhase.COMPLETED -> ArchiveStatusTone.COMPLETE
    TranscriptChatPhase.ERROR -> ArchiveStatusTone.ERROR
    else -> ArchiveStatusTone.NEUTRAL
}
