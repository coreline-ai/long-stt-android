package com.stt.benchmark.summary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummarySessionStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val source = SummaryRequestPolicy.Source(
        SummarySessionStore.SourceType.RECORDING_GROUP,
        "recording_stt_123",
    )

    @Before
    @After
    fun clean() {
        File(context.filesDir, "summary_sessions").deleteRecursively()
    }

    @Test
    fun completedSummaryRoundTripsWithoutTranscriptField() {
        val store = SummarySessionStore(context)
        val transcript = "this must remain outside the summary store"
        val saved = store.saveCompleted(source, "짧은 요약", nowMs = 1_000L)

        assertEquals(saved, store.find(source))
        assertEquals(listOf(saved), store.listAll())
        val json = File(context.filesDir, "summary_sessions/recording_group_recording_stt_123.json").readText()
        assertFalse(json.contains(transcript))
        assertFalse(json.contains("transcript", ignoreCase = true))
    }

    @Test
    fun repeatForSameSourceKeepsCreatedTimeAndReplacesOnlySummaryOutput() {
        val store = SummarySessionStore(context)
        store.saveCompleted(source, "첫 요약", nowMs = 1_000L)
        val updated = store.saveCompleted(source, "두 번째 요약", nowMs = 2_000L)

        assertEquals(1_000L, updated.createdAtMs)
        assertEquals(2_000L, updated.updatedAtMs)
        assertEquals("두 번째 요약", updated.summary)
        assertNotNull(store.find(source))
    }
}
