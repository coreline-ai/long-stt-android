package com.stt.benchmark.recording

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingRecoveryCoordinatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun cleanRecordingFiles() {
        File(context.filesDir, "recording_sessions").deleteRecursively()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    @Test
    fun startupMovesActiveCheckpointAndPartToSafeTerminalState() {
        val store = RecordingSessionStore(context)
        val files = RecordingFileManager(context.filesDir, nowMs = { 3_000 })
        val sessionId = "recording_1000_startup"
        val part = files.createPart(sessionId, 0, "wav").apply { writeValidWav(this) }
        store.save(
            RecordingSessionStore.RecordingSession(
                sessionId = sessionId,
                phase = RecordingPhase.RECORDING,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
                chunks = listOf(
                    RecordingSessionStore.RecordingChunk(
                        index = 0,
                        status = RecordingSessionStore.ChunkStatus.WRITING,
                        partPath = part.absolutePath,
                        container = "wav",
                        createdAtMs = 1_000,
                    )
                ),
            )
        )

        val report = RecordingRecoveryCoordinator(context, store, files).reconcile(nowMs = 2_000)

        assertEquals(listOf(sessionId), report.reconciledSessionIds)
        assertEquals(RecordingPhase.FAILED, store.load(sessionId)?.phase)
        assertEquals(RecordingSessionStore.ChunkStatus.QUARANTINED, store.load(sessionId)?.chunks?.single()?.status)
        assertFalse(part.exists())
    }

    @Test
    fun partWithoutAnyCheckpointIsQuarantined() {
        val store = RecordingSessionStore(context)
        val files = RecordingFileManager(context.filesDir, nowMs = { 4_000 })
        val part = files.createPart("recording_1000_no_checkpoint", 0, "wav").apply { writeValidWav(this) }

        val coordinator = RecordingRecoveryCoordinator(context, store, files)
        val skipped = coordinator.reconcile(startupCutoffExclusiveMs = part.lastModified())

        assertTrue(part.isFile)
        assertTrue(skipped.actions.none { it.contains("checkpoint 없는 part 격리") })

        val report = coordinator.reconcile()

        assertFalse(part.exists())
        assertTrue(report.actions.any { it.contains("checkpoint 없는 part 격리") })
    }

    @Test
    fun unsupportedCheckpointKeepsOwnershipOfItsPartFiles() {
        val store = RecordingSessionStore(context)
        val files = RecordingFileManager(context.filesDir, nowMs = { 5_000 })
        val sessionId = "recording_1000_future"
        val part = files.createPart(sessionId, 0, "wav").apply { writeValidWav(this) }
        store.checkpointFile(sessionId).apply {
            parentFile?.mkdirs()
            writeText(JSONObject().put("version", 99).put("sessionId", sessionId).toString())
        }

        val report = RecordingRecoveryCoordinator(context, store, files).reconcile()

        assertTrue(part.isFile)
        assertTrue(report.actions.none { it.contains(sessionId) })
        assertTrue(store.read(sessionId) is RecordingSessionStore.ReadResult.UnsupportedSchema)
    }

    private fun writeValidWav(file: File) {
        val bytes = ByteArray(64)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        file.writeBytes(bytes)
    }
}
