package com.stt.benchmark.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class CompletedResultTargetStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val store get() = CompletedResultTargetStore(context)

    @Before
    fun setUp() {
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun privateStoreRoundTripsOnlyValidatedOpaqueTarget() {
        val target = target(CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION, "stt_completed_1")

        assertTrue(store.save(target))
        assertEquals(target, store.load())
        assertFalse(target.toString().contains(target.id))
    }

    @Test
    fun malformedStoredValuesAreRejectedAndRemoved() {
        context.getSharedPreferences("completed_result_target", Context.MODE_PRIVATE)
            .edit()
            .putString(CompletedResultTargetStore.KEY_TYPE, "UNKNOWN")
            .putString(CompletedResultTargetStore.KEY_ID, "unsafe/id")
            .commit()

        assertNull(store.load())
        assertTrue(
            context.getSharedPreferences("completed_result_target", Context.MODE_PRIVATE)
                .all
                .isEmpty(),
        )
    }

    @Test
    fun clearIfMatchesCannotDeleteAnotherCompletedTarget() {
        val session = target(CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION, "stt_completed_1")
        val group = target(CompletedResultTargetStore.Type.RECORDING_GROUP, "recording_group_1")

        store.save(session)
        assertFalse(store.clearIfMatches(group))
        assertEquals(session, store.load())
        assertTrue(store.clearIfMatches(session))
        assertNull(store.load())
    }

    @Test
    fun launchContractAcceptsOnlyNamespacedActionAndValidatedExtras() {
        val target = target(CompletedResultTargetStore.Type.RECORDING_GROUP, "recording_group_1")
        val intent = CompletedResultLaunchContract.write(Intent(), target)

        assertEquals(CompletedResultLaunchContract.ACTION_OPEN_COMPLETED_RESULT, intent.action)
        assertEquals(target, CompletedResultLaunchContract.read(intent))
        assertNull(CompletedResultLaunchContract.read(Intent().putExtras(intent)))
        assertNull(
            CompletedResultLaunchContract.read(
                intent.putExtra(CompletedResultLaunchContract.EXTRA_TARGET_ID, "unsafe/id"),
            ),
        )
    }

    @Test
    fun pendingIntentRequestCodeIsStableAndTargetSpecific() {
        val first = target(CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION, "stt_completed_1")
        val second = target(CompletedResultTargetStore.Type.TRANSCRIPTION_SESSION, "stt_completed_2")

        assertEquals(
            CompletedResultLaunchContract.requestCode(first),
            CompletedResultLaunchContract.requestCode(first),
        )
        assertTrue(
            CompletedResultLaunchContract.requestCode(first) !=
                CompletedResultLaunchContract.requestCode(second),
        )
    }

    private fun target(type: CompletedResultTargetStore.Type, id: String) =
        CompletedResultTargetStore.Target.create(type, id)!!
}
