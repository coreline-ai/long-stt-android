package com.stt.benchmark.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import com.stt.benchmark.chat.TranscriptChatMode
import com.stt.benchmark.chat.TranscriptChatPhase
import com.stt.benchmark.chat.TranscriptChatSessionStore
import com.stt.benchmark.chat.TranscriptChatUiState
import com.stt.benchmark.chat.TranscriptCitation
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun consentStateRequiresExplicitConfirmation() {
        var confirmed = false
        render(
            state = baseState().copy(phase = TranscriptChatPhase.CONSENT_REQUIRED),
            onConfirmConsent = { confirmed = true },
        )

        compose.onNodeWithText("전사 기반 AI 대화를 준비할까요?").assertIsDisplayed()
        compose.onNodeWithText("동의하고 준비").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun signedOutEmptyStateSeparatesConnectionAndConsent() {
        render(baseState().copy(isAuthenticated = false, phase = TranscriptChatPhase.ERROR))

        compose.onNodeWithText("ChatGPT 연결 필요").assertIsDisplayed()
        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(3)
        compose.onNodeWithText("저장된 대화가 없습니다").assertExists()
        compose.onNodeWithContentDescription("전사 질문 전송").assertIsNotEnabled()
    }

    @Test
    fun indexingAndAnsweringExposeProgressAndStopSemantics() {
        render(
            baseState().copy(
                phase = TranscriptChatPhase.INDEXING,
                completedSteps = 2,
                totalSteps = 5,
                stageLabel = "구간 인덱싱",
                currentAnswer = "부분 답변",
            ),
        )

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(2)
        compose.onNodeWithText("2/5 단계 · 40%").assertIsDisplayed()
        compose.onNodeWithContentDescription("답변 또는 탐색 중지")
            .assertIsEnabled()
            .assertHasClickAction()
    }

    @Test
    fun verifiedCitationOpensInlineViewerAndReturnsToSameConversation() {
        val citation = TranscriptCitation("U0001", 0L, 61_000L, "section_1")
        render(
            baseState().copy(
                phase = TranscriptChatPhase.COMPLETED,
                indexReady = true,
                preciseAvailable = true,
                mode = TranscriptChatMode.PRECISE,
                completedSteps = 5,
                totalSteps = 5,
                draftQuestion = "유지할 질문",
                messages = listOf(
                    TranscriptChatSessionStore.Message(
                        TranscriptChatSessionStore.Role.ASSISTANT,
                        "저장된 답변 [U0001]",
                        listOf("U0001"),
                        1L,
                    ),
                ),
                citationCatalog = listOf(citation),
                currentCitations = listOf(citation),
            ),
            document = sourceDocument(),
        )

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(3)
        compose.onNodeWithText("저장된 답변 [U0001]").assertExists()
        compose.onNodeWithText("5/5 단계 · 100%").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("근거 U0001 확인").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("근거 U0001 확인").onFirst()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("근거 확인").assertIsDisplayed()
        compose.onNodeWithContentDescription("검증된 근거 구간 구간 1/2").assertExists()
        compose.onNodeWithText("← 대화로 돌아가기").performClick()
        compose.onNodeWithText("근거 확인").assertDoesNotExist()
        compose.onNodeWithText("저장된 답변 [U0001]").assertExists()
        compose.onNodeWithText("유지할 질문").assertExists()
        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(2)
        compose.onNodeWithText("전체 정밀 탐색").assertExists()

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(3)
        compose.onAllNodesWithContentDescription("근거 U0001 확인").onFirst().performClick()
        compose.onNodeWithContentDescription("근거 확인을 닫고 대화로 돌아가기").performClick()
        compose.onNodeWithText("근거 확인").assertDoesNotExist()
        compose.onNodeWithText("저장된 답변 [U0001]").assertExists()
        compose.onNodeWithText("유지할 질문").assertExists()
    }

    @Test
    fun invalidCitationSectionDoesNotLeaveConversation() {
        val citation = TranscriptCitation("U0001", 0L, 61_000L, "missing_section")
        render(
            baseState().copy(
                phase = TranscriptChatPhase.COMPLETED,
                indexReady = true,
                citationCatalog = listOf(citation),
                currentCitations = listOf(citation),
            ),
            document = sourceDocument(),
        )

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(4)
        compose.onNodeWithContentDescription("근거 U0001 확인").performClick()
        compose.onNodeWithText("근거 확인").assertDoesNotExist()
        compose.onNodeWithContentDescription("근거 U0001 확인").assertExists()
    }

    @Test
    fun citationViewerSelectionSurvivesStateRestoration() {
        val citation = TranscriptCitation("U0001", 0L, 61_000L, "section_1")
        val state = baseState().copy(
            phase = TranscriptChatPhase.COMPLETED,
            indexReady = true,
            citationCatalog = listOf(citation),
            currentCitations = listOf(citation),
        )
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            SttBenchmarkTheme {
                TranscriptChatScreen(
                    state = state,
                    onBack = {}, onConfirmConsent = {}, onDismissConsent = {},
                    onModeChange = {}, onDraftChange = {}, onSend = {}, onStop = {}, onRetry = {},
                    onNewConversation = {}, onDeleteConversation = {}, document = sourceDocument(),
                )
            }
        }

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(4)
        compose.onNodeWithContentDescription("근거 U0001 확인").performClick()
        compose.onNodeWithText("근거 확인").assertIsDisplayed()
        restorationTester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("근거 확인").assertIsDisplayed()
        compose.onNodeWithContentDescription("검증된 근거 구간 구간 1/2").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun citationViewerAtTwoHundredPercentKeepsReturnActionReachable() {
        val citation = TranscriptCitation("U0001", 0L, 61_000L, "section_1")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SttBenchmarkTheme {
                    TranscriptChatScreen(
                        state = baseState().copy(
                            phase = TranscriptChatPhase.COMPLETED,
                            indexReady = true,
                            citationCatalog = listOf(citation),
                            currentCitations = listOf(citation),
                        ),
                        onBack = {}, onConfirmConsent = {}, onDismissConsent = {},
                        onModeChange = {}, onDraftChange = {}, onSend = {}, onStop = {}, onRetry = {},
                        onNewConversation = {}, onDeleteConversation = {}, document = sourceDocument(),
                    )
                }
            }
        }

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(4)
        compose.onNodeWithContentDescription("근거 U0001 확인").performClick()
        compose.onNodeWithText("← 대화로 돌아가기").assertIsDisplayed()
        compose.onNodeWithContentDescription("근거 확인을 닫고 대화로 돌아가기").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactPortraitAtTwoHundredPercentKeepsPrimaryActionsReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SttBenchmarkTheme {
                    TranscriptChatScreen(
                        state = baseState().copy(
                            phase = TranscriptChatPhase.ERROR,
                            indexReady = true,
                            canRetry = true,
                            draftQuestion = "질문",
                        ),
                        onBack = {}, onConfirmConsent = {}, onDismissConsent = {},
                        onModeChange = {}, onDraftChange = {}, onSend = {}, onStop = {},
                        onRetry = {}, onNewConversation = {}, onDeleteConversation = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("전사 질문 전송").assertIsDisplayed()
        compose.onNodeWithContentDescription("전사 채팅 재시도").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun compactLandscapeKeepsAnsweringStopActionReachable() {
        render(
            baseState().copy(
                phase = TranscriptChatPhase.ANSWERING,
                completedSteps = 4,
                totalSteps = 5,
                currentAnswer = "합성 부분 답변",
            ),
        )

        compose.onNodeWithContentDescription("답변 또는 탐색 중지").assertIsDisplayed()
    }

    @Test
    fun deletingConversationRequiresScopeConfirmation() {
        var deleted = false
        compose.setContent {
            SttBenchmarkTheme {
                TranscriptChatScreen(
                    state = baseState().copy(
                        phase = TranscriptChatPhase.COMPLETED,
                        indexReady = true,
                        messages = listOf(
                            TranscriptChatSessionStore.Message(
                                TranscriptChatSessionStore.Role.USER,
                                "합성 질문",
                                timestampMs = 1L,
                            ),
                        ),
                    ),
                    onBack = {}, onConfirmConsent = {}, onDismissConsent = {},
                    onModeChange = {}, onDraftChange = {}, onSend = {}, onStop = {}, onRetry = {},
                    onNewConversation = {}, onDeleteConversation = { deleted = true },
                )
            }
        }

        compose.onNodeWithText("대화 삭제").performClick()
        compose.onNodeWithText("원본 전사, 요약, 검색 인덱스는 유지됩니다.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("대화만 삭제").performClick()
        assertTrue(deleted)
    }

    @Test
    fun recommendedQuestionPopulatesComposerWithoutSending() {
        var draft = ""
        var sent = false
        compose.setContent {
            SttBenchmarkTheme {
                TranscriptChatScreen(
                    state = baseState().copy(indexReady = true),
                    onBack = {}, onConfirmConsent = {}, onDismissConsent = {}, onModeChange = {},
                    onDraftChange = { draft = it }, onSend = { sent = true }, onStop = {}, onRetry = {},
                    onNewConversation = {}, onDeleteConversation = {},
                )
            }
        }

        compose.onNodeWithTag("transcript_chat_scroll").performScrollToIndex(3)
        compose.onNodeWithText("핵심 결정은 무엇인가요?").performClick()

        assertEquals("핵심 결정은 무엇인가요?", draft)
        assertFalse(sent)
    }

    private fun render(
        state: TranscriptChatUiState,
        onConfirmConsent: () -> Unit = {},
        document: TranscriptSourceDocument? = null,
    ) {
        compose.setContent {
            SttBenchmarkTheme {
                TranscriptChatScreen(
                    state = state,
                    onBack = {}, onConfirmConsent = onConfirmConsent, onDismissConsent = {},
                    onModeChange = {}, onDraftChange = {}, onSend = {}, onStop = {}, onRetry = {},
                    onNewConversation = {}, onDeleteConversation = {}, document = document,
                )
            }
        }
    }

    private fun baseState() = TranscriptChatUiState(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_ui"),
        sourceFingerprint = "a".repeat(64),
        isAuthenticated = true,
        mode = TranscriptChatMode.QUICK,
    )

    private fun sourceDocument() = TranscriptSourceDocument(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_ui"),
        updatedAtMs = 1L,
        sections = listOf(
            TranscriptSourceSection("section_1", "구간 1/2", 0L, 61_000L, "첫 번째 합성 근거 본문"),
            TranscriptSourceSection("section_2", "구간 2/2", 61_000L, 122_000L, "두 번째 합성 문맥 본문"),
        ),
    )
}
