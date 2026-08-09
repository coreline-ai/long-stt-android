package com.stt.benchmark.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaLibraryStoreCompatibilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun cleanIndex() {
        File(context.filesDir, "media_library.json").delete()
        File(context.filesDir, "media_library.json.bak").delete()
        File(context.filesDir, "recordings").deleteRecursively()
        File(context.filesDir, "legacy_fixture.wav").delete()
    }

    @Test
    fun versionOneIndexLoadsWithImportedMetadataDefaults() {
        val audio = File(context.filesDir, "legacy_fixture.wav").apply { writeBytes(ByteArray(32)) }
        val legacy = JSONObject().apply {
            put("version", 1)
            put("selectedAudioPath", audio.absolutePath)
            put("selectedModelPath", "")
            put("hiddenAudioPaths", JSONArray())
            put("audios", JSONArray().put(JSONObject().apply {
                put("id", "legacy-id")
                put("path", audio.absolutePath)
                put("displayName", "legacy.wav")
                put("sizeBytes", audio.length())
                put("durationMs", 1_000)
                put("importedAtMs", 1_000)
                put("lastSelectedAtMs", 0)
            }))
        }
        File(context.filesDir, "media_library.json").writeText(legacy.toString())

        val entry = MediaLibraryStore(context).listAudios().first { it.id == "legacy-id" }

        assertEquals(MediaLibraryStore.AudioSource.IMPORTED, entry.source)
        assertEquals("", entry.recordingSessionId)
        assertEquals(-1, entry.sequence)
        assertEquals("", entry.codec)
        assertEquals("", entry.sha256)
    }

    @Test
    fun recordedMetadataPersistsAndRegistrationIsIdempotent() {
        val sessionId = "recording_1000_library"
        val audio = File(context.filesDir, "recordings/$sessionId/chunk_0000.wav").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }
        val store = MediaLibraryStore(context)
        val sha = "a".repeat(64)

        val first = store.registerAudio(
            file = audio,
            displayName = "첫 녹음",
            durationMs = 5_000,
            source = MediaLibraryStore.AudioSource.RECORDED,
            recordingSessionId = sessionId,
            sequence = 0,
            codec = "pcm_s16le",
            sha256 = sha,
        )
        val second = store.registerAudio(
            file = audio,
            displayName = "",
            durationMs = 0,
        )
        val reloaded = MediaLibraryStore(context).listAudios().first { it.path == audio.absolutePath }

        assertEquals(first.id, second.id)
        assertEquals(first.id, reloaded.id)
        assertEquals(MediaLibraryStore.AudioSource.RECORDED, reloaded.source)
        assertEquals(sessionId, reloaded.recordingSessionId)
        assertEquals(0, reloaded.sequence)
        assertEquals("pcm_s16le", reloaded.codec)
        assertEquals(sha, reloaded.sha256)
        assertTrue(reloaded.sizeBytes > 0L)
    }

    @Test
    fun concurrentStoreInstancesDoNotLoseRecordedMediaEntries() {
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { sequence ->
                executor.submit {
                    val audio = File(context.filesDir, "recordings/concurrent/chunk_$sequence.wav").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(64) { sequence.toByte() })
                    }
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "동시 등록 시작 대기 시간이 초과됐습니다." }
                    MediaLibraryStore(context).registerAudio(
                        file = audio,
                        displayName = "",
                        durationMs = 1_000,
                        source = MediaLibraryStore.AudioSource.RECORDED,
                        recordingSessionId = "recording_concurrent_library",
                        sequence = sequence,
                        codec = "pcm_s16le",
                        sha256 = "%064x".format(sequence + 1),
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val entries = MediaLibraryStore(context).listAudios()
            .filter { it.recordingSessionId == "recording_concurrent_library" }

        assertEquals(workers, entries.size)
        assertEquals((0 until workers).toSet(), entries.map { it.sequence }.toSet())
    }
}
