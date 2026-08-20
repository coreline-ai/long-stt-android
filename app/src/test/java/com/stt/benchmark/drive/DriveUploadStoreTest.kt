package com.stt.benchmark.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveUploadStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val source = TranscriptSourceRef(
        type = TranscriptSourceType.TRANSCRIPTION_SESSION,
        id = "stt_synthetic_drive_test",
    )

    @Before
    @After
    fun clean() {
        File(context.filesDir, "drive_uploads").deleteRecursively()
    }

    @Test
    fun automaticUploadStartsOnlyAfterExplicitConnectionAndEnablement() {
        val store = DriveUploadStore(context)

        assertNull(store.enqueueAutomatic(source, DriveArtifact.TRANSCRIPT, completedAtMs = 100L))

        store.markConnected()
        store.setAutoUploadMode(DriveAutoUploadMode.TRANSCRIPT_AND_SUMMARY, nowMs = 200L)
        assertNull(store.enqueueAutomatic(source, DriveArtifact.TRANSCRIPT, completedAtMs = 199L))

        val job = requireNotNull(
            store.enqueueAutomatic(source, DriveArtifact.TRANSCRIPT, completedAtMs = 200L, nowMs = 201L),
        )
        assertEquals(DriveUploadStatus.QUEUED, job.status)
        assertEquals(setOf(DriveArtifact.TRANSCRIPT), job.requestedArtifacts)
    }

    @Test
    fun completedArtifactsRemainDeduplicatedAndPartialFailureIsVisible() {
        val store = DriveUploadStore(context)
        val first = store.enqueue(source, setOf(DriveArtifact.TRANSCRIPT), nowMs = 100L)

        store.markArtifactCompleted(first.jobId, DriveArtifact.TRANSCRIPT, "drive_file_transcript", nowMs = 110L)
        val merged = store.enqueue(source, setOf(DriveArtifact.SUMMARY), nowMs = 120L)
        assertEquals(first.jobId, merged.jobId)
        assertEquals(DriveUploadStatus.QUEUED, merged.status)
        assertEquals(setOf(DriveArtifact.TRANSCRIPT, DriveArtifact.SUMMARY), merged.requestedArtifacts)
        assertEquals("drive_file_transcript", merged.driveFileIds[DriveArtifact.TRANSCRIPT])

        store.markFailed(merged.jobId, "SUMMARY_UNAVAILABLE", nowMs = 130L)
        val partial = requireNotNull(store.find(merged.jobId))
        assertEquals(DriveUploadStatus.PARTIAL_COMPLETED, partial.status)
        assertEquals(setOf(DriveArtifact.TRANSCRIPT), partial.completedArtifacts)

        store.markArtifactCompleted(merged.jobId, DriveArtifact.SUMMARY, "drive_file_summary", nowMs = 140L)
        val completed = requireNotNull(store.find(merged.jobId))
        assertEquals(DriveUploadStatus.COMPLETED, completed.status)
        assertEquals(2, completed.driveFileIds.size)

        val duplicate = store.enqueue(source, setOf(DriveArtifact.SUMMARY), nowMs = 150L)
        assertEquals(DriveUploadStatus.COMPLETED, duplicate.status)
        assertFalse(duplicate.hasPendingArtifact)
    }

    @Test
    fun persistedStateContainsOnlyOpaqueUploadMetadata() {
        val store = DriveUploadStore(context)
        val job = store.enqueue(source, setOf(DriveArtifact.TRANSCRIPT), nowMs = 100L)
        store.markArtifactCompleted(job.jobId, DriveArtifact.TRANSCRIPT, "drive_file_synthetic", nowMs = 110L)

        val state = File(context.filesDir, "drive_uploads/state.json").readText()
        assertEquals(1, JSONObject(state).getJSONArray("jobs").length())
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.jobs.size)
        assertEquals(source, snapshot.jobs.single().source)
        assertNotNull(snapshot.latestFor(source))
        listOf("transcriptText", "audioPath", "modelPath", "accessToken", "refreshToken", "account", "oauth").forEach { forbidden ->
            assertFalse(state.contains(forbidden, ignoreCase = true))
        }
        assertTrue(state.contains("driveFileIds"))
    }
}
