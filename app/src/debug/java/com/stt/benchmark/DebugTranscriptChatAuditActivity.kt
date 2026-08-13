package com.stt.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.stt.benchmark.chat.TranscriptChatMode
import com.stt.benchmark.chat.TranscriptChatPhase
import com.stt.benchmark.chat.TranscriptChatSessionStore
import com.stt.benchmark.chat.TranscriptChatUiState
import com.stt.benchmark.chat.TranscriptCitation
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.ui.chat.TranscriptChatScreen
import com.stt.benchmark.ui.theme.SttBenchmarkTheme

/**
 * 네트워크와 앱 저장소를 연결하지 않고 P2 GUI만 실기기에서 확인하는 Debug 전용 화면.
 * 모든 문구와 식별자는 합성이며 외부 LLM 요청을 만들 수 없다.
 */
class DebugTranscriptChatAuditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialScenario = intent.getStringExtra(EXTRA_SCENARIO).orEmpty().uppercase().ifBlank { SCENARIO_EMPTY }
        val forceLargeText = intent.getBooleanExtra(EXTRA_LARGE_TEXT, false)
        setContent {
            val deviceDensity = LocalDensity.current
            var scenario by remember { mutableStateOf(initialScenario) }
            var mode by remember { mutableStateOf(TranscriptChatMode.QUICK) }
            var draft by remember { mutableStateOf(SYNTHETIC_QUESTION) }
            val state = syntheticState(scenario, mode, draft)
            val content: @Composable () -> Unit = {
                SttBenchmarkTheme {
                    TranscriptChatScreen(
                        state = state,
                        onBack = { finish() },
                        onConfirmConsent = { scenario = SCENARIO_INDEXING },
                        onDismissConsent = { finish() },
                        onModeChange = { mode = it },
                        onDraftChange = { draft = it },
                        onSend = { scenario = SCENARIO_ANSWERING },
                        onStop = { scenario = SCENARIO_CANCELLED },
                        onRetry = { scenario = SCENARIO_COMPLETED },
                        onNewConversation = {
                            scenario = SCENARIO_EMPTY
                            draft = SYNTHETIC_QUESTION
                        },
                        onDeleteConversation = { scenario = SCENARIO_EMPTY },
                        document = syntheticDocument(),
                    )
                }
            }
            if (forceLargeText) {
                CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, 2f)) { content() }
            } else {
                content()
            }
        }
    }

    private fun syntheticState(
        scenario: String,
        mode: TranscriptChatMode,
        draft: String,
    ): TranscriptChatUiState {
        val citation = TranscriptCitation("U0001", 0L, 61_000L, "synthetic_section_1")
        val base = TranscriptChatUiState(
            source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "synthetic_chat_audit"),
            sourceFingerprint = "a".repeat(64),
            mode = mode,
            isAuthenticated = true,
            indexReady = true,
            preciseAvailable = true,
            draftQuestion = draft,
            citationCatalog = listOf(citation),
        )
        return when (scenario) {
            SCENARIO_CONSENT -> base.copy(
                phase = TranscriptChatPhase.CONSENT_REQUIRED,
                indexReady = false,
                preciseAvailable = false,
                stageLabel = "인덱싱 동의 필요",
                statusMessage = "합성 전사 인덱싱 동의 화면입니다.",
            )
            SCENARIO_INDEXING -> base.copy(
                phase = TranscriptChatPhase.INDEXING,
                completedSteps = 2,
                totalSteps = 5,
                stageLabel = "구간 인덱싱",
                statusMessage = "합성 전사 구간 인덱스를 만들고 있습니다.",
            )
            SCENARIO_ANSWERING -> base.copy(
                phase = TranscriptChatPhase.ANSWERING,
                completedSteps = 4,
                totalSteps = 5,
                stageLabel = "답변 생성",
                statusMessage = "합성 근거로 답변을 생성하고 있습니다.",
                currentAnswer = "합성 부분 답변",
            )
            SCENARIO_CANCELLED -> base.copy(
                phase = TranscriptChatPhase.CANCELLED,
                stageLabel = "취소됨",
                statusMessage = "작업을 중지했습니다. 자동으로 다시 시작하지 않습니다.",
                canRetry = true,
            )
            SCENARIO_COMPLETED -> base.copy(
                phase = TranscriptChatPhase.COMPLETED,
                completedSteps = 5,
                totalSteps = 5,
                stageLabel = "답변 완료",
                statusMessage = "합성 답변과 검증된 근거를 확인할 수 있습니다.",
                messages = listOf(
                    TranscriptChatSessionStore.Message(
                        role = TranscriptChatSessionStore.Role.USER,
                        text = SYNTHETIC_QUESTION,
                        citationUnitIds = emptyList(),
                        timestampMs = 1L,
                    ),
                    TranscriptChatSessionStore.Message(
                        role = TranscriptChatSessionStore.Role.ASSISTANT,
                        text = "합성 답변입니다 [U0001]",
                        citationUnitIds = listOf("U0001"),
                        timestampMs = 2L,
                    ),
                ),
                currentCitations = listOf(citation),
            )
            SCENARIO_ERROR -> base.copy(
                phase = TranscriptChatPhase.ERROR,
                stageLabel = "확인 필요",
                statusMessage = "요청을 완료하지 못했습니다. 잠시 후 재시도하세요.",
                canRetry = true,
            )
            else -> base.copy(
                phase = TranscriptChatPhase.IDLE,
                stageLabel = "준비 완료",
                statusMessage = "합성 전사 근거 인덱스를 사용할 수 있습니다.",
            )
        }
    }

    private fun syntheticDocument() = TranscriptSourceDocument(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "synthetic_chat_audit"),
        updatedAtMs = 1L,
        sections = listOf(
            TranscriptSourceSection(
                key = "synthetic_section_1",
                label = "구간 1/2",
                startMs = 0L,
                endMs = 61_000L,
                text = "합성 근거 구간입니다. 실제 전사 내용이나 외부 요청을 사용하지 않습니다.",
            ),
            TranscriptSourceSection(
                key = "synthetic_section_2",
                label = "구간 2/2",
                startMs = 61_000L,
                endMs = 122_000L,
                text = "근거 주변 문맥을 확인하기 위한 비민감 합성 문장입니다.",
            ),
        ),
    )

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_LARGE_TEXT = "large_text"
        const val SCENARIO_EMPTY = "EMPTY"
        const val SCENARIO_CONSENT = "CONSENT"
        const val SCENARIO_INDEXING = "INDEXING"
        const val SCENARIO_ANSWERING = "ANSWERING"
        const val SCENARIO_CANCELLED = "CANCELLED"
        const val SCENARIO_COMPLETED = "COMPLETED"
        const val SCENARIO_ERROR = "ERROR"
        private const val SYNTHETIC_QUESTION = "핵심 결정을 알려줘"
    }
}
