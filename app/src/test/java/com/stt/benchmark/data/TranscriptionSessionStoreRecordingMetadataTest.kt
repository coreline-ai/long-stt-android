package com.stt.benchmark.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionSessionStoreRecordingMetadataTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clean() {
        File(context.filesDir, "stt_sessions").deleteRecursively()
    }

    @Test
    fun schemaV1LoadsWithEmptyRecordingMetadata() {
        val file = File(context.filesDir, "stt_sessions/stt_legacy.json").apply { parentFile?.mkdirs() }
        file.writeText(JSONObject().apply {
            put("version", 1)
            put("sessionId", "stt_legacy")
            put("status", "COMPLETED")
            put("modelPath", "/model.bin")
            put("audioPath", "/audio.wav")
            put("durationMs", 1_000L)
            put("totalChunks", 1)
            put("createdAtMs", 1_000L)
            put("updatedAtMs", 2_000L)
        }.toString())

        val loaded = TranscriptionSessionStore(context).load("stt_legacy")!!

        assertEquals("", loaded.recordingSessionId)
        assertEquals("", loaded.recordingGroupId)
        assertEquals("", loaded.mediaId)
        assertEquals(-1, loaded.recordingSequence)
    }

    @Test
    fun recordingMetadataRoundTripsInSchemaV2() {
        val store = TranscriptionSessionStore(context)
        val checkpoint = TranscriptionSessionStore.Checkpoint(
            sessionId = "stt_recording_child",
            status = TranscriptionSessionStore.Status.PREPARING,
            modelPath = "/model.bin",
            audioPath = "/audio.wav",
            note = "recording",
            durationMs = 1_000L,
            totalChunks = 1,
            currentChunk = 0,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            recordingSessionId = "recording_1000_meta",
            recordingGroupId = "recording_stt_1000_meta",
            mediaId = "media-1",
            recordingSequence = 3,
        )

        store.save(checkpoint)
        val loaded = store.load(checkpoint.sessionId)!!

        assertEquals(checkpoint.recordingSessionId, loaded.recordingSessionId)
        assertEquals(checkpoint.recordingGroupId, loaded.recordingGroupId)
        assertEquals(checkpoint.mediaId, loaded.mediaId)
        assertEquals(3, loaded.recordingSequence)
    }
}
