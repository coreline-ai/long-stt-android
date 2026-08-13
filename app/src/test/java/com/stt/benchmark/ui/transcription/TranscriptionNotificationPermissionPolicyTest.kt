package com.stt.benchmark.ui.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionNotificationPermissionPolicyTest {
    @Test
    fun notificationPermissionIsNotRequestedBeforeAndroid13() {
        assertFalse(
            shouldRequestTranscriptionNotificationPermission(
                sdkInt = 32,
                notificationsGranted = false,
            ),
        )
    }

    @Test
    fun notificationPermissionIsRequestedOnceWhenMissingOnAndroid13OrLater() {
        assertTrue(
            shouldRequestTranscriptionNotificationPermission(
                sdkInt = 33,
                notificationsGranted = false,
            ),
        )
        assertFalse(
            shouldRequestTranscriptionNotificationPermission(
                sdkInt = 35,
                notificationsGranted = true,
            ),
        )
    }
}
