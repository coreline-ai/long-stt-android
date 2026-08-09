package com.stt.benchmark.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingFileManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validPartIsRenamedAndHashedBeforeReady() {
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { 2_000 })
        val part = manager.createPart("recording_1000_test", 0, "wav")
        writeValidWav(part)

        val result = manager.finalizePart(part)

        assertTrue(result is RecordingFileManager.FinalizeResult.Ready)
        val ready = (result as RecordingFileManager.FinalizeResult.Ready).value
        assertFalse(part.exists())
        assertTrue(ready.file.isFile)
        assertEquals("wav", ready.container)
        assertEquals(64, ready.sha256.length)
        assertEquals(ready.file.length(), ready.sizeBytes)
    }

    @Test
    fun zeroByteAndSpoofedExtensionAreQuarantined() {
        var now = 2_000L
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { now++ })
        val zero = manager.createPart("recording_1000_zero", 0, "wav")
        val zeroResult = manager.finalizePart(zero)
        assertTrue(zeroResult is RecordingFileManager.FinalizeResult.Quarantined)
        assertFalse(zero.exists())

        val spoofed = manager.createPart("recording_1000_spoof", 0, "wav")
        spoofed.writeText("this is not a wav container")
        val spoofedResult = manager.finalizePart(spoofed)
        assertTrue(spoofedResult is RecordingFileManager.FinalizeResult.Quarantined)
        assertFalse(spoofed.exists())
    }

    @Test
    fun renameFailureKeepsPartRetryable() {
        val manager = RecordingFileManager(
            temporaryFolder.root,
            mover = { _, _ -> false },
        )
        val part = manager.createPart("recording_1000_retry", 0, "wav")
        writeValidWav(part)

        val result = manager.finalizePart(part)

        assertTrue(result is RecordingFileManager.FinalizeResult.RetryableFailure)
        assertTrue(part.isFile)
    }

    @Test
    fun externalFileCanNeverBeFinalized() {
        val root = temporaryFolder.newFolder("managed-root")
        val outside = temporaryFolder.newFile("outside.wav.part")
        writeValidWav(outside)
        val manager = RecordingFileManager(root)

        assertTrue(manager.finalizePart(outside) is RecordingFileManager.FinalizeResult.Rejected)
        assertTrue(outside.isFile)
    }

    @Test
    fun unfinishedPartIsQuarantinedDuringRecovery() {
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { 3_000 })
        val part = manager.createPart("recording_1000_interrupted", 0, "wav")
        writeValidWav(part)
        val session = recoverySession(
            sessionId = "recording_1000_interrupted",
            chunk = writingChunk(part),
        )

        val result = manager.reconcile(session)

        assertEquals(RecordingPhase.FAILED, result.session.phase)
        assertEquals(RecordingSessionStore.ChunkStatus.QUARANTINED, result.session.chunks.single().status)
        assertFalse(part.exists())
        assertTrue(File(result.session.chunks.single().quarantinePath).isFile)
    }

    @Test
    fun finalCreatedBeforeCheckpointIsRecoveredAsReady() {
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { 3_000 })
        val part = manager.createPart("recording_1000_recover", 0, "wav")
        writeValidWav(part)
        val originalChunk = writingChunk(part)
        assertTrue(manager.finalizePart(part) is RecordingFileManager.FinalizeResult.Ready)

        val result = manager.reconcile(
            recoverySession("recording_1000_recover", originalChunk)
        )

        assertEquals(RecordingPhase.SAVED, result.session.phase)
        assertEquals(RecordingSessionStore.ChunkStatus.READY, result.session.chunks.single().status)
        assertEquals(64, result.session.chunks.single().sha256.length)
    }

    @Test
    fun readyHashMismatchIsQuarantinedInsteadOfExposed() {
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { 4_000 })
        val part = manager.createPart("recording_1000_hash", 0, "wav")
        writeValidWav(part)
        val ready = (manager.finalizePart(part) as RecordingFileManager.FinalizeResult.Ready).value
        val chunk = RecordingSessionStore.RecordingChunk(
            index = 0,
            status = RecordingSessionStore.ChunkStatus.READY,
            finalPath = ready.file.absolutePath,
            container = "wav",
            sizeBytes = ready.sizeBytes,
            sha256 = "0".repeat(64),
            createdAtMs = 1_000,
            finalizedAtMs = 2_000,
        )

        val result = manager.reconcile(recoverySession("recording_1000_hash", chunk))

        assertEquals(RecordingPhase.FAILED, result.session.phase)
        assertEquals(RecordingSessionStore.ChunkStatus.QUARANTINED, result.session.chunks.single().status)
        assertFalse(ready.file.exists())
    }

    @Test
    fun partialRecoveryPreservesReadyChunkButSessionRemainsFailed() {
        var now = 5_000L
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { now++ })
        val readyPart = manager.createPart("recording_1000_partial", 0, "wav")
        writeValidWav(readyPart)
        val ready = (manager.finalizePart(readyPart) as RecordingFileManager.FinalizeResult.Ready).value
        val interruptedPart = manager.createPart("recording_1000_partial", 1, "wav")
        writeValidWav(interruptedPart)
        val session = RecordingSessionStore.RecordingSession(
            sessionId = "recording_1000_partial",
            phase = RecordingPhase.RECOVERY_REQUIRED,
            createdAtMs = 1_000,
            updatedAtMs = 2_000,
            chunks = listOf(
                RecordingSessionStore.RecordingChunk(
                    index = 0,
                    status = RecordingSessionStore.ChunkStatus.READY,
                    finalPath = ready.file.absolutePath,
                    container = "wav",
                    sizeBytes = ready.sizeBytes,
                    sha256 = ready.sha256,
                    createdAtMs = 1_000,
                    finalizedAtMs = 2_000,
                ),
                writingChunk(interruptedPart).copy(index = 1),
            ),
        )

        val result = manager.reconcile(session)

        assertEquals(RecordingPhase.FAILED, result.session.phase)
        assertEquals(1, result.session.readyChunks.size)
        assertEquals(RecordingSessionStore.ChunkStatus.QUARANTINED, result.session.chunks[1].status)
    }

    @Test
    fun untrackedPartIsAlsoQuarantinedDuringReconcile() {
        val manager = RecordingFileManager(temporaryFolder.root, nowMs = { 6_000 })
        val orphan = manager.createPart("recording_1000_orphan", 7, "wav")
        writeValidWav(orphan)
        val session = RecordingSessionStore.RecordingSession(
            sessionId = "recording_1000_orphan",
            phase = RecordingPhase.RECOVERY_REQUIRED,
            createdAtMs = 1_000,
            updatedAtMs = 2_000,
        )

        val result = manager.reconcile(session)

        assertFalse(orphan.exists())
        assertTrue(result.actions.any { it.contains("orphan part 격리") })
        assertEquals(RecordingPhase.FAILED, result.session.phase)
    }

    private fun recoverySession(
        sessionId: String,
        chunk: RecordingSessionStore.RecordingChunk,
    ) = RecordingSessionStore.RecordingSession(
        sessionId = sessionId,
        phase = RecordingPhase.RECOVERY_REQUIRED,
        createdAtMs = 1_000,
        updatedAtMs = 2_000,
        chunks = listOf(chunk),
    )

    private fun writingChunk(part: File) = RecordingSessionStore.RecordingChunk(
        index = 0,
        status = RecordingSessionStore.ChunkStatus.WRITING,
        partPath = part.absolutePath,
        container = "wav",
        createdAtMs = 1_000,
    )

    private fun writeValidWav(file: File) {
        val bytes = ByteArray(64)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        file.writeBytes(bytes)
    }
}
