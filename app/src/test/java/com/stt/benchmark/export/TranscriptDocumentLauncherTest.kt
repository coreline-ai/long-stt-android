package com.stt.benchmark.export

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptDocumentLauncherTest {
    @Test
    fun successfulLaunchReturnsStarted() {
        assertEquals(
            TranscriptDocumentLauncher.Result.STARTED,
            TranscriptDocumentLauncher.launch { },
        )
    }

    @Test
    fun missingDocumentHandlerReturnsFailureWithoutThrowing() {
        assertEquals(
            TranscriptDocumentLauncher.Result.NO_HANDLER,
            TranscriptDocumentLauncher.launch { throw ActivityNotFoundException("synthetic") },
        )
    }

    @Test
    fun blockedDocumentPickerReturnsFailureWithoutThrowing() {
        assertEquals(
            TranscriptDocumentLauncher.Result.BLOCKED,
            TranscriptDocumentLauncher.launch { throw SecurityException("synthetic") },
        )
    }
}
