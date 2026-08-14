package com.stt.benchmark.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingTranscriptionGroupStoreTest {
    private lateinit var testRoot: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        testRoot = Files.createTempDirectory("long-stt-group-store").toFile()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = testRoot
        }
    }

    @After
    fun clean() {
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
    }

    @Test
    fun orderedChildrenAndPartialStateRoundTrip() {
        val store = RecordingTranscriptionGroupStore(context)
        val group = group(
            status = RecordingTranscriptionGroupStore.GroupStatus.RUNNING,
            children = listOf(child(0), child(2)),
            partial = true,
            excluded = listOf(1),
        )

        store.save(group)
        val loaded = store.load(group.groupId)!!

        assertEquals(listOf(0, 2), loaded.children.map { it.sequence })
        assertEquals(listOf(1), loaded.excludedSequences)
        assertEquals(0f, loaded.progress)
        assertEquals(group.groupId, store.latestActive()?.groupId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOrUnorderedSequenceIsRejected() {
        RecordingTranscriptionGroupStore(context).save(
            group(
                status = RecordingTranscriptionGroupStore.GroupStatus.READY,
                children = listOf(child(1), child(0)),
            )
        )
    }

    @Test
    fun terminalGroupCanBeDeletedWithoutTouchingOtherGroups() {
        val store = RecordingTranscriptionGroupStore(context)
        val first = group(
            id = "recording_stt_1000_first",
            status = RecordingTranscriptionGroupStore.GroupStatus.COMPLETED,
            children = listOf(child(0, RecordingTranscriptionGroupStore.ChildStatus.COMPLETED)),
        )
        val second = group(id = "recording_stt_1000_second")
        store.save(first)
        store.save(second)

        assertTrue(store.delete(first.groupId))
        assertNull(store.load(first.groupId))
        assertEquals(second.groupId, store.load(second.groupId)?.groupId)
    }

    @Test
    fun processDeathMarksRunningGroupInterruptedWithoutDeletingCompletedChildren() {
        val store = RecordingTranscriptionGroupStore(context)
        val running = group(
            status = RecordingTranscriptionGroupStore.GroupStatus.RUNNING,
            children = listOf(
                child(0, RecordingTranscriptionGroupStore.ChildStatus.COMPLETED),
                child(1, RecordingTranscriptionGroupStore.ChildStatus.RUNNING),
            ),
        ).copy(currentChildIndex = 1)
        store.save(running)

        val reconciled = store.reconcileAfterProcessDeath(3_000L).single()

        assertEquals(RecordingTranscriptionGroupStore.GroupStatus.INTERRUPTED, reconciled.status)
        assertEquals(RecordingTranscriptionGroupStore.ChildStatus.COMPLETED, reconciled.children[0].status)
        assertEquals(RecordingTranscriptionGroupStore.ChildStatus.INTERRUPTED, reconciled.children[1].status)
    }

    private fun group(
        id: String = "recording_stt_1000_group",
        status: RecordingTranscriptionGroupStore.GroupStatus = RecordingTranscriptionGroupStore.GroupStatus.READY,
        children: List<RecordingTranscriptionGroupStore.Child> = listOf(child(0)),
        partial: Boolean = false,
        excluded: List<Int> = emptyList(),
    ) = RecordingTranscriptionGroupStore.Group(
        groupId = id,
        recordingSessionId = "recording_1000_group",
        modelPath = "/model.bin",
        status = status,
        isPartial = partial,
        excludedSequences = excluded,
        currentChildIndex = 0,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        children = children,
    )

    private fun child(
        sequence: Int,
        status: RecordingTranscriptionGroupStore.ChildStatus = RecordingTranscriptionGroupStore.ChildStatus.PENDING,
    ) = RecordingTranscriptionGroupStore.Child(
        sequence = sequence,
        mediaId = "media-$sequence",
        audioPath = "/audio-$sequence.wav",
        status = status,
    )
}
