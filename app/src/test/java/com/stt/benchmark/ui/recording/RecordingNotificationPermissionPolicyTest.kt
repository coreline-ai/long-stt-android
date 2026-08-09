package com.stt.benchmark.ui.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotificationPermissionPolicyTest {

    @Test
    fun notificationPermissionIsNotRequestedBeforeAndroid13() {
        assertFalse(
            shouldRequestRecordingNotificationPermission(
                sdkInt = 32,
                notificationsGranted = false,
            )
        )
    }

    @Test
    fun notificationPermissionIsRequestedForAndroid13AndLaterWhenMissing() {
        assertTrue(
            shouldRequestRecordingNotificationPermission(
                sdkInt = 33,
                notificationsGranted = false,
            )
        )
    }

    @Test
    fun notificationPermissionIsNotRequestedAgainWhenAlreadyGranted() {
        assertFalse(
            shouldRequestRecordingNotificationPermission(
                sdkInt = 35,
                notificationsGranted = true,
            )
        )
    }
}
