package com.stt.benchmark.summary

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class SummaryShareIntentFactoryTest {
    @Test
    fun sharePayloadContainsOnlyGenericSubjectAndSavedSummaryText() {
        val intent = SummaryShareIntentFactory.create("  저장된 최종 요약  ")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Long STT 요약", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("저장된 최종 요약", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), intent.extras?.keySet())
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    @Test
    fun chooserWrapsTextShareWithoutTargetingAProviderApp() {
        val chooser = SummaryShareIntentFactory.createChooser("저장된 최종 요약")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(SummaryShareIntentFactory.SHARE_TITLE, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertNull(chooser.component)
        assertNull(chooser.`package`)
        assertEquals(Intent.ACTION_SEND, target?.action)
        assertEquals("text/plain", target?.type)
        assertNull(target?.component)
        assertNull(target?.`package`)
    }

    @Test
    fun blankOrOversizedTextCannotBecomeSharePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            SummaryShareIntentFactory.create("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SummaryShareIntentFactory.create("x".repeat(SummaryRequestPolicy.MAX_SUMMARY_CHARS + 1))
        }
    }
}
