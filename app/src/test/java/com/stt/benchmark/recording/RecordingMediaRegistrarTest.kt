package com.stt.benchmark.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.MediaLibraryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingMediaRegistrarTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clean() {
        File(context.filesDir, "media_library.json").delete()
        File(context.filesDir, "media_library.json.bak").delete()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    @Test
    fun completeRegistrationIsIdempotent() {
        val session = session(
            phase = RecordingPhase.SAVED,
            chunks = listOf(readyChunk(0, "first"), readyChunk(1, "second")),
        )
        val registrar = RecordingMediaRegistrar(context)

        val first = registrar.register(session)
        val second = registrar.register(session)
        val library = MediaLibraryStore(context).listAudios()

        assertTrue(first is RecordingMediaRegistrar.RegistrationResult.Complete)
        assertTrue(second is RecordingMediaRegistrar.RegistrationResult.Complete)
        assertEquals(2, library.size)
        assertEquals(
            first.entries.map { it.id }.toSet(),
            second.entries.map { it.id }.toSet(),
        )
        assertEquals(listOf(0, 1), library.sortedBy { it.sequence }.map { it.sequence })
        assertTrue(library.all { it.source == MediaLibraryStore.AudioSource.RECORDED })
    }

    @Test
    fun missingSequenceAndNonReadyChunkProduceExplicitPartialResult() {
        val session = session(
            phase = RecordingPhase.FAILED,
            chunks = listOf(
                readyChunk(0, "first"),
                RecordingSessionStore.RecordingChunk(
                    index = 2,
                    status = RecordingSessionStore.ChunkStatus.MISSING,
                    issue = "파일 없음",
                    createdAtMs = 1_002L,
                ),
            ),
        )

        val result = RecordingMediaRegistrar(context).register(session)

        assertTrue(result is RecordingMediaRegistrar.RegistrationResult.Partial)
        result as RecordingMediaRegistrar.RegistrationResult.Partial
        assertEquals(listOf(1, 2), result.excludedSequences)
        assertEquals(listOf(0), result.entries.map { it.sequence })
    }

    @Test
    fun readyFileWithChangedPayloadIsNotRegistered() {
        val chunk = readyChunk(0, "trusted")
        File(chunk.finalPath).appendText("tampered")
        val session = session(phase = RecordingPhase.SAVED, chunks = listOf(chunk))

        val result = RecordingMediaRegistrar(context).register(session)

        assertTrue(result is RecordingMediaRegistrar.RegistrationResult.Blocked)
        assertTrue(MediaLibraryStore(context).listAudios().isEmpty())
    }

    @Test
    fun activeSessionIsNeverExposedToLibrary() {
        val session = session(
            phase = RecordingPhase.RECORDING,
            chunks = listOf(readyChunk(0, "data")),
        )

        val result = RecordingMediaRegistrar(context).register(session)

        assertTrue(result is RecordingMediaRegistrar.RegistrationResult.Blocked)
        assertTrue(MediaLibraryStore(context).listAudios().isEmpty())
    }

    private fun session(
        phase: RecordingPhase,
        chunks: List<RecordingSessionStore.RecordingChunk>,
    ) = RecordingSessionStore.RecordingSession(
        sessionId = "recording_1000_registrar",
        phase = phase,
        currentChunkIndex = chunks.maxOfOrNull { it.index } ?: 0,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        startedAtMs = 1_000L,
        stoppedAtMs = 2_000L,
        chunks = chunks,
    )

    private fun readyChunk(index: Int, content: String): RecordingSessionStore.RecordingChunk {
        val file = File(
            context.filesDir,
            "recordings/recording_1000_registrar/chunk_${index.toString().padStart(4, '0')}.wav",
        ).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        return RecordingSessionStore.RecordingChunk(
            index = index,
            status = RecordingSessionStore.ChunkStatus.READY,
            finalPath = file.absolutePath,
            container = "wav",
            codec = "pcm_s16le",
            durationMs = 1_000L,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            createdAtMs = 1_001L + index,
            finalizedAtMs = 2_000L + index,
        )
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
