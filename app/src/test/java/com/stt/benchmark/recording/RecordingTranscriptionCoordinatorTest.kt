package com.stt.benchmark.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RecordingTranscriptionCoordinatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var recordingStore: RecordingSessionStore
    private lateinit var groupStore: RecordingTranscriptionGroupStore
    private val launches = mutableListOf<RecordingTranscriptionCoordinator.ChildLaunchRequest>()

    @Before
    fun setUp() {
        clean()
        recordingStore = RecordingSessionStore(context)
        groupStore = RecordingTranscriptionGroupStore(context)
        launches.clear()
    }

    @After
    fun clean() {
        File(context.filesDir, "recordings").deleteRecursively()
        File(context.filesDir, "recording_sessions").deleteRecursively()
        File(context.filesDir, "recording_transcription_groups").deleteRecursively()
        File(context.filesDir, "media_library.json").delete()
        File(context.filesDir, "media_library.json.bak").delete()
    }

    @Test
    fun completedChildrenLaunchExactlyOnceInRecordingSequence() {
        recordingStore.save(session(listOf(readyChunk(0), readyChunk(1))))
        val coordinator = coordinator()

        val started = coordinator.start(SESSION_ID, "/model.bin", allowPartial = false)
            as RecordingTranscriptionCoordinator.StartResult.Started
        assertEquals(listOf(0), launches.map { it.sequence })

        val firstCompleted = coordinator.onChildEvent(event(started.group, 0, TranscriptionSessionStore.Status.COMPLETED))
            as RecordingTranscriptionCoordinator.EventResult.Updated
        assertTrue(firstCompleted.launchNext)
        coordinator.launchCurrent(started.group.groupId)
        assertEquals(listOf(0, 1), launches.map { it.sequence })

        val duplicate = coordinator.onChildEvent(event(started.group, 0, TranscriptionSessionStore.Status.COMPLETED))
            as RecordingTranscriptionCoordinator.EventResult.Updated
        assertFalse(duplicate.launchNext)
        coordinator.launchCurrent(started.group.groupId)
        assertEquals(listOf(0, 1), launches.map { it.sequence })

        val completed = coordinator.onChildEvent(event(started.group, 1, TranscriptionSessionStore.Status.COMPLETED))
            as RecordingTranscriptionCoordinator.EventResult.Updated
        assertEquals(RecordingTranscriptionGroupStore.GroupStatus.COMPLETED, completed.group.status)
        assertEquals(2, completed.group.completedChildren)
    }

    @Test
    fun foregroundServiceCanClaimNextChildWithoutASecondBackgroundLauncher() {
        recordingStore.save(session(listOf(readyChunk(0), readyChunk(1))))
        val coordinator = coordinator()
        val started = coordinator.start(SESSION_ID, "/model.bin", allowPartial = false)
            as RecordingTranscriptionCoordinator.StartResult.Started

        val completed = coordinator.onChildEvent(
            event(started.group, 0, TranscriptionSessionStore.Status.COMPLETED),
        ) as RecordingTranscriptionCoordinator.EventResult.Updated
        assertTrue(completed.launchNext)

        val prepared = coordinator.prepareCurrentLaunch(started.group.groupId)
        assertEquals(1, prepared?.request?.sequence)
        assertEquals(listOf(0), launches.map { it.sequence })
        assertEquals(
            RecordingTranscriptionGroupStore.ChildStatus.STARTING,
            prepared?.group?.children?.get(1)?.status,
        )
        assertEquals(null, coordinator.prepareCurrentLaunch(started.group.groupId))
    }

    @Test
    fun partialRecordingRequiresConfirmationAndKeepsExcludedRange() {
        recordingStore.save(
            session(
                chunks = listOf(
                    readyChunk(0),
                    RecordingSessionStore.RecordingChunk(
                        index = 1,
                        status = RecordingSessionStore.ChunkStatus.MISSING,
                        issue = "missing",
                        createdAtMs = 1_001L,
                    ),
                ),
                phase = RecordingPhase.FAILED,
            )
        )
        val coordinator = coordinator()

        val confirmation = coordinator.start(SESSION_ID, "/model.bin", allowPartial = false)
            as RecordingTranscriptionCoordinator.StartResult.PartialConfirmationRequired
        assertEquals(listOf(0), confirmation.readySequences)
        assertEquals(listOf(1), confirmation.excludedSequences)
        assertTrue(launches.isEmpty())

        val started = coordinator.start(SESSION_ID, "/model.bin", allowPartial = true)
            as RecordingTranscriptionCoordinator.StartResult.Started
        assertTrue(started.group.isPartial)
        assertEquals(listOf(1), started.group.excludedSequences)
        assertEquals(listOf(0), launches.map { it.sequence })
    }

    @Test
    fun missingModelPersistsModelRequiredGroupWithoutLaunchingChild() {
        recordingStore.save(session(listOf(readyChunk(0))))
        val coordinator = coordinator(modelReadable = false)

        val result = coordinator.start(SESSION_ID, "", allowPartial = false)

        assertTrue(result is RecordingTranscriptionCoordinator.StartResult.ModelRequired)
        val group = (result as RecordingTranscriptionCoordinator.StartResult.ModelRequired).group
        assertEquals(RecordingTranscriptionGroupStore.GroupStatus.MODEL_REQUIRED, group.status)
        assertTrue(launches.isEmpty())
        assertEquals(group.groupId, groupStore.load(group.groupId)?.groupId)
    }

    @Test
    fun failedChildStopsGroupAndPreservesCompletedChildren() {
        recordingStore.save(session(listOf(readyChunk(0), readyChunk(1))))
        val coordinator = coordinator()
        val group = (coordinator.start(SESSION_ID, "/model.bin", false)
            as RecordingTranscriptionCoordinator.StartResult.Started).group
        coordinator.onChildEvent(event(group, 0, TranscriptionSessionStore.Status.COMPLETED))
        coordinator.launchCurrent(group.groupId)

        val failed = coordinator.onChildEvent(event(group, 1, TranscriptionSessionStore.Status.FAILED, "decode fail"))
            as RecordingTranscriptionCoordinator.EventResult.Updated

        assertEquals(RecordingTranscriptionGroupStore.GroupStatus.FAILED, failed.group.status)
        assertEquals(1, failed.group.completedChildren)
        assertEquals("decode fail", failed.group.errorMessage)
    }

    private fun coordinator(modelReadable: Boolean = true) = RecordingTranscriptionCoordinator(
        recordingStore = recordingStore,
        registrar = RecordingMediaRegistrar(context, MediaLibraryStore(context)),
        groupStore = groupStore,
        launcher = RecordingTranscriptionCoordinator.ChildLauncher { launches += it },
        modelReadable = { modelReadable },
        nowMs = { 10_000L + launches.size },
    )

    private fun event(
        group: RecordingTranscriptionGroupStore.Group,
        sequence: Int,
        status: TranscriptionSessionStore.Status,
        detail: String = "",
    ) = RecordingTranscriptionCoordinator.ChildEvent(
        groupId = group.groupId,
        mediaId = MediaLibraryStore(context).listAudios().first { it.sequence == sequence }.id,
        sttSessionId = "stt-child-$sequence",
        status = status,
        detail = detail,
    )

    private fun session(
        chunks: List<RecordingSessionStore.RecordingChunk>,
        phase: RecordingPhase = RecordingPhase.SAVED,
    ) = RecordingSessionStore.RecordingSession(
        sessionId = SESSION_ID,
        phase = phase,
        currentChunkIndex = chunks.maxOf { it.index },
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        startedAtMs = 1_000L,
        stoppedAtMs = 2_000L,
        chunks = chunks,
    )

    private fun readyChunk(sequence: Int): RecordingSessionStore.RecordingChunk {
        val file = File(context.filesDir, "recordings/$SESSION_ID/chunk_$sequence.wav").apply {
            parentFile?.mkdirs()
            writeText("audio-$sequence")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return RecordingSessionStore.RecordingChunk(
            index = sequence,
            status = RecordingSessionStore.ChunkStatus.READY,
            finalPath = file.absolutePath,
            container = "wav",
            codec = "pcm_s16le",
            durationMs = 1_000L,
            sizeBytes = file.length(),
            sha256 = hash,
            createdAtMs = 1_000L + sequence,
            finalizedAtMs = 2_000L + sequence,
        )
    }

    companion object {
        const val SESSION_ID = "recording_1000_coordinator"
    }
}
