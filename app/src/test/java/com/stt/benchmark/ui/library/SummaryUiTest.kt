package com.stt.benchmark.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import com.stt.benchmark.summary.SummaryStage
import com.stt.benchmark.summary.SummaryUiState
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.ui.common.FullTranscriptDialog
import com.stt.benchmark.ui.common.TranscriptExportActions
import com.stt.benchmark.ui.common.TranscriptViewerSection
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
        compose.onNodeWithContentDescription("완료 요약 공유").assertDoesNotExist()
    }

    @Test
    fun activeLongSummaryShowsStageStepCountAndPercent() {
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
            "stt_123",
        )
        compose.setContent {
            SttBenchmarkTheme {
                SummaryResultSection(
                    candidate = SummaryCandidate(source, "안전한 테스트 전사"),
                    auth = CodexAuthUiState(phase = CodexAuthPhase.AUTHENTICATED),
                    summaryState = SummaryUiState(
                        activeSourceKey = source.key,
                        isRunning = true,
                        stage = SummaryStage.SYNTHESIZING,
                        completedSteps = 7,
                        totalSteps = 21,
                        statusMessage = "구간 요약을 통합하고 있습니다.",
                    ),
                    onRequest = {},
                )
            }
        }

        compose.onNodeWithText("요약 통합").assertExists()
        compose.onNodeWithText("7/21 단계 · 33%").assertExists()
        compose.onNodeWithText("구간 요약을 통합하고 있습니다.").assertExists()
    }

    @Test
    fun fullTranscriptViewerExposesEverySectionAndCloseAction() {
        var dismissed = false
        var saved = false
        var shared = false
        compose.setContent {
            SttBenchmarkTheme {
                FullTranscriptDialog(
                    title = "전체 전사",
                    detail = "2/2 구간",
                    sections = listOf(
                        TranscriptViewerSection("first", "구간 1/2", "첫 번째 전체 본문"),
                        TranscriptViewerSection("second", "구간 2/2", "두 번째 전체 본문"),
                    ),
                    onSave = { saved = true },
                    onShare = { shared = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("전체 전사").assertExists()
        compose.onNodeWithText("첫 번째 전체 본문").assertExists()
        compose.onNodeWithText("두 번째 전체 본문").assertExists()
        compose.onNodeWithText("TXT 저장").performClick()
        compose.onNodeWithText("파일로 공유").performClick()
        assertTrue(saved)
        assertTrue(shared)
        compose.onNodeWithText("닫기").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun listIndicatorShowsAvailableRunningCompletedAndSourceScopedErrorStates() {
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
            "stt_123",
        )

        compose.setContent {
            SttBenchmarkTheme {
                Column {
                    SummaryListIndicator(SummaryListState.Available)
                    SummaryListIndicator(SummaryListState.Running(0.48f))
                    SummaryListIndicator(SummaryListState.Completed("저장된 요약 미리보기"))
                }
            }
        }
        compose.onNodeWithText("요약 가능").assertExists()
        compose.onNodeWithText("요약 중 · 48%").assertExists()
        compose.onNodeWithText("요약 완료").assertExists()
        compose.onNodeWithText("저장된 요약 미리보기").assertExists()

        val error = summaryListState(
            source = source,
            eligible = true,
            summaryState = SummaryUiState(
                statusSourceKey = source.key,
                stage = SummaryStage.ERROR,
            ),
        )
        assertTrue(error == SummaryListState.Error)
        val otherSource = source.copy(id = "stt_456")
        assertTrue(
            summaryListState(otherSource, eligible = true, SummaryUiState(
                statusSourceKey = source.key,
                stage = SummaryStage.ERROR,
            )) == SummaryListState.Available,
        )
        assertTrue(
            summaryListState(source, eligible = false, SummaryUiState()) == SummaryListState.Hidden,
        )
        val completed = summaryListState(
            source = source,
            eligible = false,
            summaryState = SummaryUiState(
                entries = listOf(
                    SummarySessionStore.Entry(source, "완료 요약", createdAtMs = 1L, updatedAtMs = 1L),
                ),
            ),
        )
        assertTrue(completed is SummaryListState.Completed)
    }

    @Test
    fun completedSummaryExposesExplicitShareAction() {
        var shared = ""
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.RECORDING_GROUP,
            "recording_stt_123",
        )
        compose.setContent {
            SttBenchmarkTheme {
                SummaryResultSection(
                    candidate = SummaryCandidate(source, "안전한 테스트 전사"),
                    auth = CodexAuthUiState(),
                    summaryState = SummaryUiState(
                        entries = listOf(
                            SummarySessionStore.Entry(
                                source = source,
                                summary = "공유할 최종 요약",
                                createdAtMs = 1L,
                                updatedAtMs = 1L,
                            ),
                        ),
                    ),
                    onRequest = {},
                    onShare = { shared = it },
                )
            }
        }

        compose.onNodeWithContentDescription("완료 요약 공유")
            .assertHasClickAction()
            .performClick()
        assertTrue(shared == "공유할 최종 요약")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactPortraitKeepsCompletedSummaryShareReachableAt200PercentFontScale() {
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.TRANSCRIPTION_SESSION,
            "stt_123",
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SttBenchmarkTheme {
                    SummaryResultSection(
                        candidate = SummaryCandidate(source, "synthetic transcript"),
                        auth = CodexAuthUiState(),
                        summaryState = SummaryUiState(
                            entries = listOf(
                                SummarySessionStore.Entry(
                                    source = source,
                                    summary = "synthetic completed summary",
                                    createdAtMs = 1L,
                                    updatedAtMs = 1L,
                                ),
                            ),
                        ),
                        onRequest = {},
                        onShare = {},
                    )
                }
            }
        }

        compose.onNodeWithText("요약 완료").assertIsDisplayed()
        compose.onNodeWithContentDescription("완료 요약 공유")
            .assertIsDisplayed()
    }

    @Test
    fun transcriptExportActionsSeparatePersistentSaveFromFileShare() {
        var saved = false
        var shared = false
        compose.setContent {
            SttBenchmarkTheme {
                TranscriptExportActions(
                    inProgress = false,
                    statusMessage = "",
                    onSave = { saved = true },
                    onShare = { shared = true },
                )
            }
        }

        compose.onNodeWithContentDescription("전체 전사 TXT 저장")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("전체 전사 파일 공유")
            .assertHasClickAction()
            .performClick()
        assertTrue(saved)
        assertTrue(shared)
    }

    @Test
    fun transcriptExportActionsShowProgressAndDisableDuplicateRequests() {
        compose.setContent {
            SttBenchmarkTheme {
                TranscriptExportActions(
                    inProgress = true,
                    statusMessage = "TXT 파일을 저장하고 있습니다.",
                    onSave = {},
                    onShare = {},
                )
            }
        }

        compose.onNodeWithContentDescription("전체 전사 TXT 저장").assertIsNotEnabled()
        compose.onNodeWithContentDescription("전체 전사 파일 공유").assertIsNotEnabled()
        compose.onNodeWithText("TXT 파일을 저장하고 있습니다.").assertExists()
    }

    @Test
    fun completedResultOpeningFailsClosedWhenTargetIsMissing() {
        val target = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_missing",
        )!!

        assertTrue(completedResultToOpen(target, emptyList(), emptyList()) == null)
    }

    @Test
    fun coldStartCompletedResultWaitsForFirstLibraryLoadBeforeConsumption() {
        val target = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_completed_1",
        )!!

        assertTrue(!shouldConsumeInitialCompletedResult(target, resultLibraryLoaded = false))
        assertTrue(shouldConsumeInitialCompletedResult(target, resultLibraryLoaded = true))
        assertTrue(!shouldConsumeInitialCompletedResult(null, resultLibraryLoaded = true))
    }
}
