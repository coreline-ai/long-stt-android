package com.stt.benchmark.ui.recording

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.stt.benchmark.R
import com.stt.benchmark.recording.RecordingPhase
import com.stt.benchmark.recording.RecordingRuntimeSnapshot
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenStatesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permissionStatesExposeOnlyTheSafeNextAction() {
        setScreen(RecordingUiState(availability = RecordingAvailability.PERMISSION_REQUIRED))
        compose.onNodeWithText("마이크 권한 필요").assertIsDisplayed()
        compose.onNodeWithText("마이크 권한 허용").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("녹음 시작").assertIsNotEnabled()
    }

    @Test
    fun permanentDenialUsesApplicationSettingsAction() {
        setScreen(RecordingUiState(availability = RecordingAvailability.PERMISSION_PERMANENTLY_DENIED))
        compose.onNodeWithText("권한 설정 필요").assertIsDisplayed()
        compose.onNodeWithText("앱 설정에서 마이크 권한 열기").performScrollTo().assertHasClickAction()
    }

    @Test
    fun oneTimeDenialKeepsAnExplicitRetryAction() {
        setScreen(RecordingUiState(availability = RecordingAvailability.PERMISSION_DENIED))
        compose.onNodeWithText("권한 다시 확인").assertIsDisplayed()
        compose.onNodeWithText("마이크 권한 다시 요청").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("녹음 시작").assertIsNotEnabled()
    }

    @Test
    fun readyAndRecordingStatesHaveDistinctTalkBackActions() {
        setScreen(
            RecordingUiState(
                availability = RecordingAvailability.READY,
                runtime = RecordingRuntimeSnapshot(phase = RecordingPhase.RECORDING, elapsedMs = 65_000L),
            )
        )
        compose.onNodeWithText("녹음 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("녹음 정지").assertIsEnabled().assertHasClickAction()
        compose.onNodeWithContentDescription("녹음 시간 01:05").assertIsDisplayed()
    }

    @Test
    fun finalizingAndFailedStatesDisableDuplicateControl() {
        setScreen(
            RecordingUiState(
                availability = RecordingAvailability.READY,
                runtime = RecordingRuntimeSnapshot(phase = RecordingPhase.FINALIZING),
            )
        )
        compose.onNodeWithText("안전 저장 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("녹음 시작").assertIsNotEnabled()
    }

    @Test
    fun recordingPhasesSelectTheDedicatedArtworkWithoutUnsafeSuccessFallback() {
        assertEquals(R.drawable.art_recording_ready, recordingArtwork(RecordingPhase.IDLE).imageRes)
        assertEquals(R.drawable.art_recording_ready, recordingArtwork(RecordingPhase.PREPARING).imageRes)
        assertEquals(R.drawable.art_recording_active, recordingArtwork(RecordingPhase.RECORDING).imageRes)
        assertEquals(R.drawable.art_recording_active, recordingArtwork(RecordingPhase.ROLLING_OVER).imageRes)
        assertEquals(R.drawable.art_recording_active, recordingArtwork(RecordingPhase.FINALIZING).imageRes)
        assertEquals(R.drawable.art_recording_saved, recordingArtwork(RecordingPhase.SAVED).imageRes)
        assertEquals(R.drawable.art_recording_ready, recordingArtwork(RecordingPhase.FAILED).imageRes)
        assertEquals(R.drawable.art_recording_ready, recordingArtwork(RecordingPhase.RECOVERY_REQUIRED).imageRes)
    }

    @Test
    fun fontScaleTwoAndReducedMotionKeepCoreControlsVisible() {
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
                        reducedMotion = true,
                    )
                }
            }
        }
        compose.onNodeWithText("녹음 준비").assertIsDisplayed()
        compose.onNodeWithContentDescription("녹음 시작").assertIsDisplayed()
    }

    @Test
    fun routeTransitionNoticeIsReachableAtFontScaleTwo() {
        val notice = "입력 장치 변경 후 새 파일에서 녹음을 이어가고 있습니다."
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                SttBenchmarkTheme(darkTheme = true) {
                    RecordingScreen(
                        state = RecordingUiState(
                            availability = RecordingAvailability.READY,
                            runtime = RecordingRuntimeSnapshot(
                                phase = RecordingPhase.RECORDING,
                                message = notice,
                            ),
                        ),
                        onStart = {},
                        onStop = {},
                        onRequestPermission = {},
                        onOpenAppSettings = {},
                        onOpenTranscription = {},
                        reducedMotion = true,
                    )
                }
            }
        }

        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("녹음 정지").assertIsDisplayed()
    }

    @Test
    fun savedRecordingOffersDirectSequentialTranscription() {
        var requested: Pair<String, Boolean>? = null
        setScreen(
            state = RecordingUiState(
                availability = RecordingAvailability.READY,
                recentSessions = listOf(recent(phase = RecordingPhase.SAVED)),
            ),
            onTranscribe = { id, partial -> requested = id to partial },
        )

        compose.onNodeWithText("녹음 전체 순차 전사").performScrollTo().performClick()
        assertEquals("recording_1000_ui" to false, requested)
    }

    @Test
    fun damagedRecordingRequiresExplicitPartialConfirmation() {
        var requested: Pair<String, Boolean>? = null
        setScreen(
            state = RecordingUiState(
                availability = RecordingAvailability.READY,
                recentSessions = listOf(recent(phase = RecordingPhase.FAILED, missing = 1)),
            ),
            onTranscribe = { id, partial -> requested = id to partial },
        )

        compose.onNodeWithText("보존 구간 순차 전사").performScrollTo().performClick()
        compose.onNodeWithText("보존된 구간만 전사할까요?").assertIsDisplayed()
        compose.onNodeWithText("보존 구간 전사").performClick()
        assertEquals("recording_1000_ui" to true, requested)
    }

    private fun setScreen(
        state: RecordingUiState,
        onTranscribe: (String, Boolean) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            SttBenchmarkTheme(darkTheme = true) {
                RecordingScreen(
                    state = state,
                    onStart = {},
                    onStop = {},
                    onRequestPermission = {},
                    onOpenAppSettings = {},
                    onOpenTranscription = {},
                    onTranscribeRecording = onTranscribe,
                )
            }
        }
    }

    private fun recent(
        phase: RecordingPhase,
        missing: Int = 0,
    ) = RecentRecordingUi(
        sessionId = "recording_1000_ui",
        phase = phase,
        updatedAtMs = 2_000L,
        durationMs = 60_000L,
        readyChunkCount = 1,
        quarantinedChunkCount = 0,
        missingChunkCount = missing,
        container = "wav",
        message = "",
    )
}
