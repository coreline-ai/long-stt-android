package com.stt.benchmark.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.stt.benchmark.ui.onboarding.OnboardingScreen
import com.stt.benchmark.ui.recording.RecordingAvailability
import com.stt.benchmark.ui.recording.RecordingScreen
import com.stt.benchmark.ui.recording.RecordingUiState
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityLayoutContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemBarIconPolicyAlwaysOpposesBackgroundBrightness() {
        assertFalse(usesLightSystemBarIcons(darkBackground = true))
        assertTrue(usesLightSystemBarIcons(darkBackground = false))
    }

    @Test
    fun bottomNavigationCompactsVisibleLabelsOnlyWhenLargeTextWouldOverlap() {
        assertTrue(shouldShowNavigationLabels(fontScale = 1f))
        assertTrue(shouldShowNavigationLabels(fontScale = 1.3f))
        assertFalse(shouldShowNavigationLabels(fontScale = 2f))
    }

    @Test
    fun onboardingCoreNavigationRemainsReachableAt100PercentFontScale() {
        assertOnboardingNavigationAt(fontScale = 1f)
    }

    @Test
    fun onboardingCoreNavigationRemainsReachableAt130PercentFontScale() {
        assertOnboardingNavigationAt(fontScale = 1.3f)
    }

    @Test
    fun onboardingCoreNavigationRemainsReachableAt200PercentFontScale() {
        assertOnboardingNavigationAt(fontScale = 2f)
    }

    @Test
    fun recordingPrimaryAndSecondaryActionsMeetThe48DpTouchContract() {
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

        assertAtLeast48Dp(compose.onNodeWithContentDescription("녹음 시작"))
        val importAction = compose.onNodeWithText("오디오 파일 전사")
        importAction.performScrollTo().assertIsDisplayed()
        assertAtLeast48Dp(importAction)
    }

    private fun assertAtLeast48Dp(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * compose.density.density
        assertTrue("width ${bounds.width} is below 48dp", bounds.width >= minimum)
        assertTrue("height ${bounds.height} is below 48dp", bounds.height >= minimum)
    }

    private fun assertOnboardingNavigationAt(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                SttBenchmarkTheme(darkTheme = true) {
                    OnboardingScreen(onComplete = {})
                }
            }
        }

        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("다음").performScrollTo().assertIsDisplayed()
    }
}
