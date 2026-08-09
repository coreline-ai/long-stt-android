package com.stt.benchmark.ui.onboarding

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingPreferencesTest {
    @Test
    fun completionCanBePersistedAndResetFromSettings() {
        val preferences = OnboardingPreferences(ApplicationProvider.getApplicationContext())

        preferences.setComplete(false)
        assertFalse(preferences.isComplete())

        preferences.setComplete(true)
        assertTrue(preferences.isComplete())

        preferences.setComplete(false)
        assertFalse(preferences.isComplete())
    }
}
