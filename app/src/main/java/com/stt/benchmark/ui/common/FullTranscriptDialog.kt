package com.stt.benchmark.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class TranscriptViewerMode {
    LIBRARY,
    CHAT_CITATION,
}

internal data class TranscriptViewerSection(
    val key: String,
    val label: String,
    val text: String,
)

@Composable
internal fun FullTranscriptDialog(
    title: String,
    detail: String,
    sections: List<TranscriptViewerSection>,
    initialSectionKey: String = "",
    mode: TranscriptViewerMode = TranscriptViewerMode.LIBRARY,
    inProgress: Boolean = false,
    statusMessage: String = "",
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(initialSectionKey, sections) {
        val index = sections.indexOfFirst { it.key == initialSectionKey }
        if (index >= 0) listState.scrollToItem(index)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                ViewerHeader(
                    title = title,
                    detail = detail,
                    mode = mode,
                    onDismiss = onDismiss,
                )
                if (mode == TranscriptViewerMode.LIBRARY && onSave != null && onShare != null) {
                    TranscriptExportActions(
                        inProgress = inProgress,
                        statusMessage = statusMessage,
                        onSave = onSave,
                        onShare = onShare,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        top = 12.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(sections, key = TranscriptViewerSection::key) { section ->
                        val highlighted = mode == TranscriptViewerMode.CHAT_CITATION &&
                            section.key == initialSectionKey
                        Surface(
                            color = if (highlighted) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = if (highlighted) {
                                Modifier.semantics {
                                    contentDescription = "검증된 근거 구간 ${section.label}"
                                }
                            } else {
                                Modifier
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(if (highlighted) 16.dp else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (highlighted) {
                                    Text(
                                        "검증된 근거",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                SectionLabel(section.label)
                                SelectionContainer {
                                    Text(
                                        section.text.ifBlank { "인식된 본문이 없는 구간입니다." },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = FontFamily.SansSerif,
                                    )
                                }
                            }
                        }
                    }
                }
                if (mode == TranscriptViewerMode.CHAT_CITATION) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .archiveTouchTarget()
                            .semantics { contentDescription = "근거 확인을 닫고 대화로 돌아가기" },
                    ) {
                        Text("대화로 돌아가기")
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerHeader(
    title: String,
    detail: String,
    mode: TranscriptViewerMode,
    onDismiss: () -> Unit,
) {
    if (mode == TranscriptViewerMode.CHAT_CITATION) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.archiveTouchTarget(),
            ) { Text("← 대화로 돌아가기") }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.archiveTouchTarget(),
            ) { Text("닫기") }
        }
    }
}

@Composable
internal fun TranscriptExportActions(
    inProgress: Boolean,
    statusMessage: String,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "TXT 저장과 파일 공유는 전사 원문 전체를 사용자가 선택한 앱 밖 위치로 복사합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onSave,
            enabled = !inProgress,
            modifier = Modifier
                .fillMaxWidth()
                .archiveTouchTarget()
                .semantics { contentDescription = "전체 전사 TXT 저장" },
        ) {
            Icon(Icons.Outlined.SaveAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("TXT 저장")
        }
        OutlinedButton(
            onClick = onShare,
            enabled = !inProgress,
            modifier = Modifier
                .fillMaxWidth()
                .archiveTouchTarget()
                .semantics { contentDescription = "전체 전사 파일 공유" },
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("파일로 공유")
        }
        if (inProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (statusMessage.isNotBlank()) {
            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
