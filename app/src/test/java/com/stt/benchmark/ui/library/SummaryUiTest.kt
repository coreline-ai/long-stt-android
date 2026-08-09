package com.stt.benchmark.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import com.stt.benchmark.summary.SummaryUiState
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun consentDialogRequiresExplicitConfirmAction() {
        var confirmed = false
        compose.setContent {
            SttBenchmarkTheme {
                SummaryConsentDialog(onConfirm = { confirmed = true }, onDismiss = {})
            }
        }

        compose.onNodeWithText("외부 요약을 시작할까요?").assertExists()
        compose.onNodeWithText("동의하고 요약").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun signedOutStateDoesNotExposeSummaryStartButton() {
        compose.setContent {
            SttBenchmarkTheme {
                SummaryResultSection(
                    candidate = SummaryCandidate(
                        source = SummaryRequestPolicy.Source(
                            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
                            "stt_123",
                        ),
                        transcript = "안전한 테스트 전사",
                    ),
                    auth = CodexAuthUiState(),
                    summaryState = SummaryUiState(),
                    onRequest = {},
                )
            }
        }

        compose.onNodeWithText("설정에서 ChatGPT 연결을 완료하면 요약을 시작할 수 있습니다.").assertExists()
    }

    @Test
    fun authenticatedStateRequiresExplicitSummaryStartAction() {
        var requested = false
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
            "stt_123",
        )
        compose.setContent {
            SttBenchmarkTheme {
                SummaryResultSection(
                    candidate = SummaryCandidate(source, "안전한 테스트 전사"),
                    auth = CodexAuthUiState(phase = CodexAuthPhase.AUTHENTICATED),
                    summaryState = SummaryUiState(),
                    onRequest = { requested = it == source },
                )
            }
        }

        compose.onNodeWithContentDescription("외부 요약 시작")
            .assertHasClickAction()
            .performClick()
        assertTrue(requested)
    }
}
