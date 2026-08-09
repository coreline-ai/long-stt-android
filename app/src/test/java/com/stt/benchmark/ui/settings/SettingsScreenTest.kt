package com.stt.benchmark.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.ModelDownloader
import com.stt.benchmark.summary.CodexAuthUiState
import com.stt.benchmark.summary.CodexAuthPhase
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun releaseSettingsKeepAccountAndModelManagementButHideDebugHistory() {
        render(isDebug = false)

        compose.onNodeWithText("모델 관리").assertIsDisplayed()
        compose.onNodeWithText("ChatGPT 연결").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("소개 다시 보기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("성능 기록 보기").assertDoesNotExist()
    }

    @Test
    fun selectedModelCannotBeDeletedFromSettings() {
        render(
            isDebug = true,
            state = SttViewModel.UiState(
                state = SttViewModel.SttState.READY,
                modelLoaded = true,
                modelPath = MODEL_PATH,
                installedModels = listOf(
                    MediaLibraryStore.ModelEntry(
                        path = MODEL_PATH,
                        displayName = "base",
                        sizeBytes = 74L * 1024 * 1024,
                        installedAtMs = 1L,
                    )
                ),
            ),
        )

        compose.onNodeWithText("현재 사용", substring = true).assertIsDisplayed()
        compose.onNodeWithText("삭제").assertIsNotEnabled()
        compose.onNodeWithText("성능 기록 보기").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun authenticatedAccountExposesProbeAndLogoutOnlyInSettings() {
        render(
            isDebug = false,
            auth = CodexAuthUiState(
                phase = CodexAuthPhase.AUTHENTICATED,
                statusMessage = "ChatGPT 계정이 연결되어 있습니다.",
            ),
        )

        compose.onNodeWithText("연결 응답 확인").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("연결 해제").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ChatGPT 연결").assertDoesNotExist()
    }

    @Test
    fun modelCatalogDispatchesDownloadForMissingModel() {
        var requested: ModelDownloader.ModelInfo? = null
        render(
            isDebug = false,
            routeState = SettingsRouteUiState(dialog = SettingsDialog.MODEL_CATALOG),
            onDownloadModel = { requested = it },
        )

        compose.onNodeWithText("tiny · 39 MB").performClick()
        assertEquals("ggml-tiny.bin", requested?.fileName)
    }

    @Test
    fun replayOnboardingIsAnExplicitSettingsAction() {
        var replayRequested = false
        render(
            isDebug = false,
            onReplayOnboarding = { replayRequested = true },
        )

        compose.onNodeWithText("소개 다시 보기").performScrollTo().performClick()
        assertEquals(true, replayRequested)
    }

    private fun render(
        isDebug: Boolean,
        state: SttViewModel.UiState = SttViewModel.UiState(),
        auth: CodexAuthUiState = CodexAuthUiState(),
        routeState: SettingsRouteUiState = SettingsRouteUiState(),
        onDownloadModel: (ModelDownloader.ModelInfo) -> Unit = {},
        onReplayOnboarding: () -> Unit = {},
    ) {
        compose.setContent {
            SttBenchmarkTheme(darkTheme = false) {
                SettingsScreen(
                    state = state,
                    auth = auth,
                    routeState = routeState,
                    availableModels = ModelDownloader.MODELS,
                    isDebug = isDebug,
                    onAuthorize = {},
                    canAuthorize = true,
                    onCancelAuthorization = {},
                    onProbe = {},
                    onLogout = {},
                    onReplayOnboarding = onReplayOnboarding,
                    onShowModelCatalog = {},
                    onShowModelPath = {},
                    onShowPerformanceHistory = {},
                    onRequestModelDeletion = {},
                    onModelInputChanged = {},
                    onDismissDialog = {},
                    onLoadModel = {},
                    onDownloadModel = onDownloadModel,
                    onDeleteModel = {},
                )
            }
        }
    }

    private companion object {
        const val MODEL_PATH = "/managed/models/ggml-base.bin"
    }
}
