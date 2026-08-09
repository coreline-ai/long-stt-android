package com.stt.benchmark.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.stt.benchmark.ui.onboarding.OnboardingScreen
import com.stt.benchmark.ui.recording.RecordingAvailability
import com.stt.benchmark.ui.recording.RecordingScreen
import com.stt.benchmark.ui.recording.RecordingUiState
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingMovesThroughTwoRealSteps() {
        compose.setContent {
            var completed by remember { mutableStateOf(false) }
            SttBenchmarkTheme(darkTheme = true) {
                if (completed) Text("소개 완료") else OnboardingScreen(onComplete = { completed = true })
            }
        }

        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("다음").performScrollTo().assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 / 2").assertExists()
        compose.onNodeWithText("녹음 화면으로").performScrollTo().assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("소개 완료").assertExists()
    }

    @Test
    fun recordingRouteRequestsPermissionBeforeStartingService() {
        compose.setContent {
            var opened by remember { mutableStateOf(false) }
            SttBenchmarkTheme(darkTheme = true) {
                if (opened) {
                    Text("전사 화면 요청됨")
                } else {
                    RecordingScreen(
                        state = RecordingUiState(
                            availability = RecordingAvailability.PERMISSION_REQUIRED,
                        ),
                        onStart = {},
                        onStop = {},
                        onRequestPermission = {},
                        onOpenAppSettings = {},
                        onOpenTranscription = { opened = true },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("녹음 시작").assertIsNotEnabled()
        compose.onNodeWithText("마이크 권한 허용").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("오디오 파일 전사").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithText("전사 화면 요청됨").assertExists()
    }
}
