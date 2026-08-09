package com.stt.benchmark.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AudioFileInspector {
    data class AudioMetadata(
        val codec: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val durationMs: Long,
    )

    fun inspect(file: File): AudioMetadata {
        require(file.isFile && file.length() > 0L) { "검사할 오디오 파일이 없습니다" }
        return runCatching { inspectWithExtractor(file) }
            .recoverCatching {
                if (file.extension.equals("wav", ignoreCase = true)) inspectWav(file) else throw it
            }
            .getOrThrow()
            .also { metadata ->
                require(metadata.codec.isNotBlank()) { "오디오 codec을 확인할 수 없습니다" }
                require(metadata.sampleRateHz > 0 && metadata.channelCount > 0) { "오디오 format이 잘못되었습니다" }
                require(metadata.durationMs > 0L) { "오디오 duration을 확인할 수 없습니다" }
            }
    }

    private fun inspectWithExtractor(file: File): AudioMetadata {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("오디오 track이 없습니다")
            AudioMetadata(
                codec = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                sampleRateHz = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT),
                durationMs = format.longOrZero(MediaFormat.KEY_DURATION) / 1_000L,
            )
        } finally {
            extractor.release()
        }
    }

    private fun inspectWav(file: File): AudioMetadata {
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { wav -> wav.readFully(header) }
        require(header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") { "WAV RIFF header 없음" }
        require(header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "WAV WAVE header 없음" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buffer.getShort(22).toInt() and 0xFFFF
        val sampleRate = buffer.getInt(24)
        val byteRate = buffer.getInt(28)
        val dataBytes = buffer.getInt(40).toLong() and 0xFFFF_FFFFL
        require(byteRate > 0 && dataBytes > 0L) { "WAV payload가 없습니다" }
        return AudioMetadata(
            codec = "audio/raw",
            sampleRateHz = sampleRate,
            channelCount = channels,
            durationMs = dataBytes * 1_000L / byteRate.toLong(),
        )
    }

    private fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) getInteger(key) else 0
    private fun MediaFormat.longOrZero(key: String): Long = if (containsKey(key)) getLong(key) else 0L
}
