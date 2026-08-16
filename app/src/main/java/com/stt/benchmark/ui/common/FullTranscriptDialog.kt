package com.stt.benchmark.ui.common

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val evidenceIndex = sections.indexOfFirst { it.key == initialSectionKey }.coerceAtLeast(0)
    LaunchedEffect(initialSectionKey, sections) {
        if (sections.isNotEmpty()) listState.scrollToItem(evidenceIndex)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            DialogSystemBarAppearance()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                ViewerHeader(
                    title = title,
                    detail = detail,
                    mode = mode,
                    onDismiss = onDismiss,
                )
                if (mode == TranscriptViewerMode.CHAT_CITATION && sections.isNotEmpty()) {
                    CitationContextNavigator(
                        evidenceIndex = evidenceIndex,
                        totalSections = sections.size,
                        onPrevious = {
                            scope.launch {
                                listState.animateScrollToItem((evidenceIndex - 1).coerceAtLeast(0))
                            }
                        },
                        onNext = {
                            scope.launch {
                                listState.animateScrollToItem((evidenceIndex + 1).coerceAtMost(sections.lastIndex))
                            }
                        },
                    )
                }
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
                        if (mode == TranscriptViewerMode.CHAT_CITATION) {
                            CitationSectionCard(section = section, highlighted = highlighted)
                        } else {
                            LibraryTranscriptSection(section)
                        }
                    }
                }
                if (mode == TranscriptViewerMode.CHAT_CITATION) {
                    CitationBottomAction(onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DialogSystemBarAppearance() {
    val view = LocalView.current
    val useDarkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
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
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .archiveTouchTarget(),
            ) { Text("← 대화로 돌아가기") }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .semantics { heading() },
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
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
            TextButton(onClick = onDismiss, modifier = Modifier.archiveTouchTarget()) { Text("닫기") }
        }
    }
}

@Composable
private fun CitationContextNavigator(
    evidenceIndex: Int,
    totalSections: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onPrevious,
            enabled = evidenceIndex > 0,
            modifier = Modifier.weight(1f),
        ) { Text("이전 구간") }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(100),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                "근거 ${evidenceIndex + 1}/$totalSections",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        TextButton(
            onClick = onNext,
            enabled = evidenceIndex < totalSections - 1,
            modifier = Modifier.weight(1f),
        ) { Text("다음 구간") }
    }
}

@Composable
private fun CitationSectionCard(
    section: TranscriptViewerSection,
    highlighted: Boolean,
) {
    Surface(
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(20.dp),
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = if (highlighted) {
            Modifier.semantics { contentDescription = "검증된 근거 구간 ${section.label}" }
        } else {
            Modifier
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (highlighted) "검증된 근거" else "주변 문맥",
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                section.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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

@Composable
private fun LibraryTranscriptSection(section: TranscriptViewerSection) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

@Composable
private fun CitationBottomAction(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Some Samsung three-button navigation configurations report a consumed
                // dialog inset. Keep a deterministic physical fallback so the primary action can
                // never be covered by the system navigation surface.
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 72.dp),
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .archiveTouchTarget()
                    .semantics { contentDescription = "근거 확인을 닫고 대화로 돌아가기" },
            ) {
                Text("대화로 돌아가기")
            }
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
