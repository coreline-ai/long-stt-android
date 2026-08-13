package com.stt.benchmark.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class AudioImportFileWriterTest {
    @Test
    fun largeInputIsCopiedWithBoundedReadRequestsAndAtomicFinalName() {
        val directory = Files.createTempDirectory("long-stt-import").toFile()
        val pending = File(directory, ".synthetic.wav.part")
        val final = File(directory, "synthetic.wav")
        val input = GeneratedInputStream(TOTAL_BYTES)

        try {
            val copied = AudioImportFileWriter.copy(input, pending, final)

            assertEquals(TOTAL_BYTES.toLong(), copied)
            assertEquals(TOTAL_BYTES.toLong(), final.length())
            assertFalse(pending.exists())
            assertTrue(input.maxRequestedBytes <= 64 * 1024)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedCopyRemovesPartialFileAndDoesNotPublishFinalFile() {
        val directory = Files.createTempDirectory("long-stt-import-failure").toFile()
        val pending = File(directory, ".synthetic.wav.part")
        val final = File(directory, "synthetic.wav")

        try {
            assertThrows(IOException::class.java) {
                AudioImportFileWriter.copy(
                    input = GeneratedInputStream(TOTAL_BYTES, failAfterBytes = 128 * 1024),
                    pendingFile = pending,
                    finalFile = final,
                )
            }
            assertFalse(pending.exists())
            assertFalse(final.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class GeneratedInputStream(
        private val totalBytes: Int,
        private val failAfterBytes: Int = Int.MAX_VALUE,
    ) : InputStream() {
        private var emitted = 0
        var maxRequestedBytes = 0
            private set

        override fun read(): Int {
            if (emitted >= failAfterBytes) throw IOException("synthetic read failure")
            if (emitted >= totalBytes) return -1
            emitted++
            return emitted and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maxRequestedBytes = maxOf(maxRequestedBytes, length)
            if (emitted >= failAfterBytes) throw IOException("synthetic read failure")
            if (emitted >= totalBytes) return -1
            val count = minOf(length, totalBytes - emitted, failAfterBytes - emitted)
            repeat(count) { index -> buffer[offset + index] = ((emitted + index) and 0xff).toByte() }
            emitted += count
            return count
        }
    }

    private companion object {
        const val TOTAL_BYTES = 8 * 1024 * 1024
    }
}
