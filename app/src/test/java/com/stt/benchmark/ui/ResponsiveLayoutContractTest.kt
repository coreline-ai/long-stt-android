package com.stt.benchmark.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.stt.benchmark.data.ModelDownloader
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.ui.recording.RecordingAvailability
import com.stt.benchmark.ui.recording.RecordingScreen
import com.stt.benchmark.ui.recording.RecordingUiState
import com.stt.benchmark.ui.settings.SettingsRouteUiState
import com.stt.benchmark.ui.settings.SettingsScreen
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import com.stt.benchmark.ui.transcription.TranscriptionRouteUiState
import com.stt.benchmark.ui.transcription.TranscriptionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResponsiveLayoutContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactPortraitKeepsRecordingControlsReachableAt200PercentFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SttBenchmarkTheme(darkTheme = true) {
                    RecordingScreen(
                        state = RecordingUiState(availability = RecordingAvailability.READY),
                        onStart = {},
                        onStop = {},
                        onRequestPermission = {},
                        onOpenAppSettings = {},
                        onOpenTranscription = {},
                    )
                }
            }
        }

        compose.onNodeWithText("녹음 준비").assertIsDisplayed()
        compose.onNodeWithText("오디오 파일 전사").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun compactPortraitKeepsCompletedResultActionsReachableAt200PercentFontScale() {
        val target = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_completed_1",
        )!!
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SttBenchmarkTheme(darkTheme = true) {
                    TranscriptionScreen(
                        state = SttViewModel.UiState(
                            state = SttViewModel.SttState.DONE,
                            modelLoaded = true,
                            completedResultTarget = target,
                        ),
                        routeState = TranscriptionRouteUiState(),
                        onOpenModelPicker = {},
                        onOpenAudioPicker = {},
                        onOpenAudioMenu = {},
                        onDismissAudioMenu = {},
                        onRequestAudioDeletion = {},
                        onDismissDialog = {},
                        onPickAudio = {},
                        onSelectModel = {},
                        onSelectAudio = {},
                        onClearAudio = {},
                        onForgetAudio = {},
                        onDeleteAudio = {},
                        onRun = {},
                        onCancel = {},
                        onOpenSettings = {},
                    )
                }
            }
        }

        compose.onNodeWithText("보관함에서 결과 보기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("같은 오디오 다시 전사").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun landscapeKeepsTranscriptionAndSettingsActionsReachableAt130PercentFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                SttBenchmarkTheme(darkTheme = false) {
                    TranscriptionScreen(
                        state = SttViewModel.UiState(),
                        routeState = TranscriptionRouteUiState(),
                        onOpenModelPicker = {},
                        onOpenAudioPicker = {},
                        onOpenAudioMenu = {},
                        onDismissAudioMenu = {},
                        onRequestAudioDeletion = {},
                        onDismissDialog = {},
                        onPickAudio = {},
                        onSelectModel = {},
                        onSelectAudio = {},
                        onClearAudio = {},
                        onForgetAudio = {},
                        onDeleteAudio = {},
                        onRun = {},
                        onCancel = {},
                        onOpenSettings = {},
                    )
                }
            }
        }

        compose.onNodeWithText("전사 작업").assertIsDisplayed()
        compose.onNodeWithText("설정에서 모델 설치").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("오디오 선택").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun normalPortraitKeepsSettingsReplayActionReachableAt200PercentFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SttBenchmarkTheme(darkTheme = true) {
                    SettingsScreen(
                        state = SttViewModel.UiState(),
                        auth = CodexAuthUiState(),
                        routeState = SettingsRouteUiState(),
                        availableModels = ModelDownloader.MODELS,
                        isDebug = false,
                        onAuthorize = {},
                        canAuthorize = true,
                        onCancelAuthorization = {},
                        onProbe = {},
                        onLogout = {},
                        onReplayOnboarding = {},
                        onShowModelCatalog = {},
                        onShowModelPath = {},
                        onShowPerformanceHistory = {},
                        onRequestModelDeletion = {},
                        onModelInputChanged = {},
                        onDismissDialog = {},
                        onLoadModel = {},
                        onDownloadModel = {},
                        onDeleteModel = {},
                    )
                }
            }
        }

        compose.onNodeWithText("설정").assertIsDisplayed()
        compose.onNodeWithText("소개 다시 보기").performScrollTo().assertIsDisplayed()
    }
}
