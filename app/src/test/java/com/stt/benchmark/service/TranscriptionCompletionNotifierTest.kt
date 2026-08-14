package com.stt.benchmark.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.MainActivity
import com.stt.benchmark.data.CompletedResultLaunchContract
import com.stt.benchmark.data.CompletedResultTargetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class TranscriptionCompletionNotifierTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    @After
    fun clearNotification() {
        manager.cancel(TranscriptionService.COMPLETION_NOTIFICATION_ID)
    }

    @Test
    fun repeatedGroupPostKeepsOneOpaqueExplicitImmutableNotification() {
        val target = target()
        val notifier = TranscriptionCompletionNotifier(context, canPost = { true })

        assertTrue(notifier.post(target))
        assertTrue(notifier.post(target))

        val active = manager.activeNotifications.filter {
            it.id == TranscriptionService.COMPLETION_NOTIFICATION_ID
        }
        assertEquals(1, active.size)
        val contentIntent = active.single().notification.contentIntent
        val pendingIntentShadow = shadowOf(contentIntent)
        val launchIntent = pendingIntentShadow.savedIntent
        assertTrue(pendingIntentShadow.isImmutable)
        assertEquals(MainActivity::class.java.name, launchIntent.component?.className)
        assertEquals(CompletedResultLaunchContract.ACTION_OPEN_COMPLETED_RESULT, launchIntent.action)
        assertEquals(target, CompletedResultLaunchContract.read(launchIntent))
        assertEquals(2, launchIntent.extras?.keySet()?.size)
        assertFalse(launchIntent.extras.toString().contains("transcript", ignoreCase = true))
    }

    @Test
    fun deniedNotificationPermissionDoesNotPost() {
        val notifier = TranscriptionCompletionNotifier(context, canPost = { false })

        assertFalse(notifier.post(target()))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun target() = requireNotNull(
        CompletedResultTargetStore.Target.create(
            CompletedResultTargetStore.Type.RECORDING_GROUP,
            "recording_group_notification_1",
        ),
    )
}
