package com.stt.benchmark.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class RecorderBackendFactoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun cleanFiles() {
        File(context.filesDir, "recordings").deleteRecursively()
    }

    @Test
    fun attemptsConstrainedAacThenDefaultAacThenWav() {
        val attempts = mutableListOf<RecorderBackendMode>()
        val manager = RecordingFileManager(context.filesDir, nowMs = { attempts.size.toLong() + 1_000 })
        val factory = RecorderBackendFactory(context, manager) { mode, part ->
            FakeBackend(part, mode, failStart = mode != RecorderBackendMode.PCM_WAV).also { attempts += mode }
        }

        val result = factory.start("recording_1000_fallback", 0)

        assertTrue(result is RecorderBackendFactory.StartResult.Started)
        assertEquals(
            listOf(
                RecorderBackendMode.AAC_CONSTRAINED,
                RecorderBackendMode.AAC_DEFAULT,
                RecorderBackendMode.PCM_WAV,
            ),
            attempts,
        )
        assertEquals(
            RecorderBackendMode.PCM_WAV,
            (result as RecorderBackendFactory.StartResult.Started).backend.mode,
        )
    }

    @Test
    fun allBackendFailuresLeaveNoPartExposed() {
        var now = 2_000L
        val manager = RecordingFileManager(context.filesDir, nowMs = { now++ })
        val factory = RecorderBackendFactory(context, manager) { mode, part ->
            FakeBackend(part, mode, failStart = true)
        }

        val result = factory.start("recording_1000_all_fail", 0)

        assertTrue(result is RecorderBackendFactory.StartResult.Failed)
        val sessionDir = File(context.filesDir, "recordings/recording_1000_all_fail")
        assertFalse(sessionDir.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun asynchronousBackendFailureIsForwardedToServiceCallback() {
        val manager = RecordingFileManager(context.filesDir)
        val factory = RecorderBackendFactory(context, manager) { mode, part ->
            FakeBackend(part, mode, failStart = false)
        }
        var delivered: Throwable? = null

        val result = factory.start("recording_1000_async", 0) { delivered = it }
        val backend = (result as RecorderBackendFactory.StartResult.Started).backend as FakeBackend
        backend.signalFailure(IllegalStateException("async"))

        assertEquals("async", delivered?.message)
    }

    private class FakeBackend(
        override val partFile: File,
        override val mode: RecorderBackendMode,
        private val failStart: Boolean,
    ) : RecorderBackend {
        private var failureListener: (Throwable) -> Unit = {}

        override fun setFailureListener(listener: (Throwable) -> Unit) {
            failureListener = listener
        }

        override fun start() {
            if (failStart) error("expected")
            partFile.writeBytes(ByteArray(64))
        }
        override fun stop() = Unit
        override fun maxAmplitude(): Int = 0
        override fun release() = Unit

        fun signalFailure(error: Throwable) = failureListener(error)
    }
}
