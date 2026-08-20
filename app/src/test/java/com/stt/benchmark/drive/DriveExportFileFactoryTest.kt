package com.stt.benchmark.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveExportFileFactoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val cacheRoot get() = File(context.cacheDir, "drive_export_factory_test")
    private val job = DriveUploadJob(
        jobId = "drive_synthetic_export",
        exportId = "export_synthetic_export",
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_synthetic_export"),
        requestedArtifacts = setOf(DriveArtifact.TRANSCRIPT, DriveArtifact.SUMMARY),
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Before
    @After
    fun clean() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun filesUseShortLivedCacheAndExcludeInternalSourceMetadata() {
        val factory = DriveExportFileFactory(cacheRoot)
        val document = TranscriptSourceDocument(
            source = job.source,
            updatedAtMs = 1_000L,
            sections = listOf(
                TranscriptSourceSection("U0001", "구간 1/1", 0L, 1_000L, "합성 전사 내용"),
            ),
        )

        val transcript = factory.createTranscript(job, document).file
        val summary = factory.createSummary(job, "합성 요약").file
        val transcriptText = transcript.readText(Charsets.UTF_8)

        assertEquals("transcript.txt", transcript.name)
        assertEquals("summary.txt", summary.name)
        assertTrue(transcript.parentFile?.parentFile?.name == DriveExportFileFactory.CACHE_DIRECTORY)
        assertTrue(transcriptText.contains("합성 전사 내용"))
        assertFalse(transcriptText.contains(job.source.id))
        assertFalse(transcriptText.contains("audioPath"))
        assertEquals(DriveExportFileFactory.MIME_TYPE, "text/plain")
    }

    @Test
    fun expiredJobDirectoryIsRemovedWithoutTouchingNewExports() {
        val factory = DriveExportFileFactory(cacheRoot)
        val document = TranscriptSourceDocument(
            source = job.source,
            updatedAtMs = 1_000L,
            sections = listOf(TranscriptSourceSection("U0001", "구간", 0L, 1L, "합성")),
        )
        val file = factory.createTranscript(job, document).file
        requireNotNull(file.parentFile).setLastModified(0L)

        assertEquals(1, factory.cleanupExpired(nowMs = DriveExportFileFactory.CACHE_RETENTION_MS + 1L))
        assertFalse(file.exists())
    }
}
