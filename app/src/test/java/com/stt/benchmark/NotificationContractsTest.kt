package com.stt.benchmark

import com.stt.benchmark.recording.RecorderNotificationFactory
import com.stt.benchmark.recording.RecorderService
import com.stt.benchmark.service.TranscriptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContractsTest {
    @Test
    fun recorderAndTranscriptionKeepDistinctNotificationIdentities() {
        val channels = setOf(
            RecorderNotificationFactory.CHANNEL_ID,
            TranscriptionService.CHANNEL_ID,
        )
        val ids = setOf(
            RecorderNotificationFactory.NOTIFICATION_ID,
            TranscriptionService.NOTIFICATION_ID,
            TranscriptionService.COMPLETION_NOTIFICATION_ID,
        )

        assertEquals(2, channels.size)
        assertEquals(3, ids.size)
        assertTrue(channels.none(String::isBlank))
    }

    @Test
    fun serviceActionsAreNamespacedAndDoNotCollide() {
        val actions = setOf(
            RecorderService.ACTION_START,
            RecorderService.ACTION_STOP,
            TranscriptionService.ACTION_START,
            TranscriptionService.ACTION_RESUME,
            TranscriptionService.ACTION_CANCEL,
            TranscriptionService.ACTION_QUERY,
            TranscriptionService.ACTION_STATUS,
        )

        assertEquals(7, actions.size)
        assertTrue(actions.all { it.startsWith("com.stt.benchmark.") })
    }
}
