package com.stt.benchmark.recording

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFileInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsActualWavFormatAndDuration() {
        val file = temporaryFolder.newFile("sample.wav")
        writeSilentWav(file, durationSeconds = 1)

        val metadata = AudioFileInspector.inspect(file)

        assertEquals("audio/raw", metadata.codec)
        assertEquals(48_000, metadata.sampleRateHz)
        assertEquals(1, metadata.channelCount)
        assertEquals(1_000L, metadata.durationMs)
    }

    private fun writeSilentWav(file: File, durationSeconds: Int) {
        val dataBytes = 48_000 * 2 * durationSeconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(dataBytes + 36)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(48_000)
            putInt(96_000)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
        RandomAccessFile(file, "rw").use { wav ->
            wav.write(header)
            wav.setLength(44L + dataBytes)
        }
    }
}
