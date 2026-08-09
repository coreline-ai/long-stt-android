package com.stt.benchmark.recording

import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingSessionStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun cleanStore() {
        File(context.filesDir, "recording_sessions").deleteRecursively()
    }

    @Test
    fun sessionRoundTripsWithReadyChunkMetadata() {
        val store = RecordingSessionStore(context)
        val session = sampleSession().copy(
            phase = RecordingPhase.SAVED,
            chunks = listOf(
                RecordingSessionStore.RecordingChunk(
                    index = 0,
                    status = RecordingSessionStore.ChunkStatus.READY,
                    finalPath = File(context.filesDir, "recordings/recording_1000_test/chunk_0000.wav").path,
                    container = "wav",
                    codec = "pcm_s16le",
                    sampleRateHz = 48_000,
                    channelCount = 1,
                    durationMs = 10_000,
                    sizeBytes = 1_024,
                    sha256 = "a".repeat(64),
                    createdAtMs = 1_000,
                    finalizedAtMs = 2_000,
                )
            ),
        )

        store.save(session)

        assertEquals(session, store.load(session.sessionId))
        assertEquals(listOf(session), store.listAll())
    }

    @Test
    fun interruptedAtomicWriteRecoversPreviousCheckpoint() {
        val store = RecordingSessionStore(context)
        val session = sampleSession()
        store.save(session)
        val atomic = AtomicFile(store.checkpointFile(session.sessionId))
        atomic.startWrite().use { it.write("{broken".toByteArray()) }

        assertEquals(session, store.load(session.sessionId))
    }

    @Test
    fun unknownSchemaIsReportedAndNeverLoaded() {
        val store = RecordingSessionStore(context)
        val sessionId = "recording_1000_unknown"
        val file = store.checkpointFile(sessionId).apply { parentFile?.mkdirs() }
        file.writeText(JSONObject().put("version", 99).put("sessionId", sessionId).toString())

        val result = store.read(sessionId)
        assertTrue(result is RecordingSessionStore.ReadResult.UnsupportedSchema)
        assertEquals(99, (result as RecordingSessionStore.ReadResult.UnsupportedSchema).version)
        assertNull(store.load(sessionId))
    }

    @Test
    fun processDeathMarksOnlyActiveSessionsForRecovery() {
        val store = RecordingSessionStore(context)
        store.save(sampleSession().copy(phase = RecordingPhase.RECORDING))
        store.save(sampleSession("recording_2000_saved").copy(phase = RecordingPhase.SAVED))

        val changed = store.reconcileAfterProcessDeath(nowMs = 3_000)

        assertEquals(1, changed.size)
        assertEquals(RecordingPhase.RECOVERY_REQUIRED, changed.single().phase)
        assertEquals(RecordingPhase.SAVED, store.load("recording_2000_saved")?.phase)
    }

    @Test
    fun invalidOrDuplicateChunkMetadataIsRejectedBeforeWrite() {
        val store = RecordingSessionStore(context)
        val duplicate = RecordingSessionStore.RecordingChunk(
            index = 0,
            status = RecordingSessionStore.ChunkStatus.WRITING,
            partPath = "/managed/chunk.wav.part",
            createdAtMs = 1_000,
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.save(sampleSession().copy(chunks = listOf(duplicate, duplicate)))
        }
    }

    @Test
    fun readyChunksArePersistedInSequenceOrder() {
        val store = RecordingSessionStore(context)
        val chunks = listOf(2, 0, 1).map { index ->
            RecordingSessionStore.RecordingChunk(
                index = index,
                status = RecordingSessionStore.ChunkStatus.READY,
                finalPath = "/recordings/chunk_$index.wav",
                sizeBytes = 100,
                sha256 = index.toString().repeat(64),
                createdAtMs = 1_000,
            )
        }
        val session = sampleSession("recording_1000_order").copy(
            phase = RecordingPhase.SAVED,
            chunks = chunks,
        )

        store.save(session)

        assertEquals(listOf(0, 1, 2), store.load(session.sessionId)?.readyChunks?.map { it.index })
    }

    private fun sampleSession(sessionId: String = "recording_1000_test") =
        RecordingSessionStore.RecordingSession(
            sessionId = sessionId,
            phase = RecordingPhase.PREPARING,
            createdAtMs = 1_000,
            updatedAtMs = 1_000,
        )
}
