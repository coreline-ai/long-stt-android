package com.stt.benchmark.ui.transcription

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingModelRoutesToSettingsAndKeepsRunDisabled() {
        var settingsOpened = false
        render(
            state = SttViewModel.UiState(),
            onOpenSettings = { settingsOpened = true },
        )

        compose.onNodeWithText("설정에서 모델 설치").assertIsDisplayed().performClick()
        compose.onNodeWithText("전사 시작").assertIsNotEnabled()
        assertTrue(settingsOpened)
    }

    @Test
    fun readyInputsEnableSingleFileTranscription() {
        render(
            state = SttViewModel.UiState(
                state = SttViewModel.SttState.READY,
                modelLoaded = true,
                modelPath = "/managed/models/ggml-base.bin",
                installedModels = listOf(model()),
                audioPath = "/managed/audio.m4a",
                audioPaths = listOf("/managed/audio.m4a"),
                audioLibrary = listOf(audio()),
                totalFiles = 1,
            ),
        )

        compose.onNodeWithText("ggml-base.bin").assertIsDisplayed()
        compose.onNodeWithText("audio.m4a").assertIsDisplayed()
        compose.onNodeWithText("전사 시작").assertIsEnabled()
    }

    @Test
    fun hiddenLegacyBatchPathCannotEnableAVisuallyEmptySelection() {
        render(
            state = SttViewModel.UiState(
                state = SttViewModel.SttState.READY,
                modelLoaded = true,
                modelPath = "/managed/models/ggml-base.bin",
                installedModels = listOf(model()),
                audioPath = "",
                audioPaths = listOf("/managed/stale.m4a"),
                totalFiles = 1,
            ),
        )

        compose.onNodeWithText("전사할 오디오를 선택하세요.").assertIsDisplayed()
        compose.onNodeWithText("입력 필요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("전사 시작").assertIsNotEnabled()
    }

    @Test
    fun runningStateShowsProgressAndSafeStop() {
        render(
            state = SttViewModel.UiState(
                state = SttViewModel.SttState.RUNNING,
                modelLoaded = true,
                modelPath = "/managed/models/ggml-base.bin",
                installedModels = listOf(model()),
                audioPath = "/managed/audio.m4a",
                audioPaths = listOf("/managed/audio.m4a"),
                audioLibrary = listOf(audio()),
                totalFiles = 1,
                progress = 0.42f,
                batchStatus = "청크 2/4 처리 중",
            ),
        )

        compose.onNodeWithText("전사 중").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("청크 2/4 처리 중").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("전사 중지").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("보관함에서 결과 보기").assertDoesNotExist()
    }

    @Test
    fun completedStateOpensExactResultFromPersistentPrimaryAction() {
        val target = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_completed_1",
        )!!
        var opened: CompletedResultTarget? = null
        render(
            state = SttViewModel.UiState(
                state = SttViewModel.SttState.DONE,
                modelLoaded = true,
                modelPath = "/managed/models/ggml-base.bin",
                installedModels = listOf(model()),
                audioPath = "/managed/audio.m4a",
                audioPaths = listOf("/managed/audio.m4a"),
                audioLibrary = listOf(audio()),
                totalFiles = 1,
                progress = 1f,
                batchStatus = "전사 완료",
                completedResultTarget = target,
            ),
            onOpenCompletedResult = { opened = it },
        )

        compose.onNodeWithContentDescription("완료 전사 보관함에서 보기")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertTrue(opened == target)
        compose.onNodeWithText("같은 오디오 다시 전사").performScrollTo().assertIsEnabled()
    }

    @Test
    fun doneWithoutVerifiedTargetDoesNotExposeBrokenResultAction() {
        render(state = SttViewModel.UiState(state = SttViewModel.SttState.DONE))

        compose.onNodeWithText("보관함에서 결과 보기").assertDoesNotExist()
    }

    private fun render(
        state: SttViewModel.UiState,
        onOpenSettings: () -> Unit = {},
        onOpenCompletedResult: (CompletedResultTarget) -> Unit = {},
    ) {
        compose.setContent {
            SttBenchmarkTheme(darkTheme = false) {
                TranscriptionScreen(
                    state = state,
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
                    onOpenSettings = onOpenSettings,
                    onOpenCompletedResult = onOpenCompletedResult,
                )
            }
        }
    }

    private fun model() = MediaLibraryStore.ModelEntry(
        path = "/managed/models/ggml-base.bin",
        displayName = "base",
        sizeBytes = 74L * 1024 * 1024,
        installedAtMs = 1L,
    )

    private fun audio() = MediaLibraryStore.AudioEntry(
        id = "audio-1",
        path = "/managed/audio.m4a",
        displayName = "audio.m4a",
        sizeBytes = 1024L,
        durationMs = 10_000L,
        importedAtMs = 1L,
    )
}
