package com.stt.benchmark.recording

import kotlin.math.ceil

/** AAC 준비 실패 뒤 PCM WAV fallback까지 버틸 수 있는 보수적 저장공간 preflight. */
object RecordingStorageEstimator {
    enum class Mode { AAC_CONSTRAINED, AAC_DEFAULT, PCM_WAV }

    data class Estimate(
        val durationMs: Long,
        val aacConstrainedBytes: Long,
        val aacDefaultBytes: Long,
        val pcmWavBytes: Long,
        val reserveBytes: Long,
    ) {
        val requiredWorstCaseBytes: Long = maxOf(aacConstrainedBytes, aacDefaultBytes, pcmWavBytes) + reserveBytes
    }

    data class Preflight(
        val allowed: Boolean,
        val availableBytes: Long,
        val estimate: Estimate,
        val shortageBytes: Long,
    )

    fun estimate(
        durationMs: Long,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 1,
        pcmBitsPerSample: Int = 16,
        reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    ): Estimate {
        require(durationMs > 0L) { "durationMs는 0보다 커야 합니다" }
        require(sampleRateHz in 8_000..192_000) { "지원하지 않는 sample rate" }
        require(channelCount in 1..2) { "지원하지 않는 channel count" }
        require(pcmBitsPerSample in setOf(16, 24, 32)) { "지원하지 않는 PCM bit depth" }
        require(reserveBytes >= 0L) { "reserveBytes는 음수일 수 없습니다" }
        return Estimate(
            durationMs = durationMs,
            aacConstrainedBytes = encodedBytes(durationMs, AAC_CONSTRAINED_BITRATE, CONTAINER_OVERHEAD_RATIO),
            aacDefaultBytes = encodedBytes(durationMs, AAC_DEFAULT_BITRATE, CONTAINER_OVERHEAD_RATIO),
            pcmWavBytes = pcmBytes(durationMs, sampleRateHz, channelCount, pcmBitsPerSample) + WAV_HEADER_BYTES,
            reserveBytes = reserveBytes,
        )
    }

    fun preflight(availableBytes: Long, durationMs: Long): Preflight {
        require(availableBytes >= 0L) { "availableBytes는 음수일 수 없습니다" }
        val estimate = estimate(durationMs)
        val shortage = (estimate.requiredWorstCaseBytes - availableBytes).coerceAtLeast(0L)
        return Preflight(
            allowed = shortage == 0L,
            availableBytes = availableBytes,
            estimate = estimate,
            shortageBytes = shortage,
        )
    }

    fun estimateBytes(durationMs: Long, mode: Mode): Long = when (mode) {
        Mode.AAC_CONSTRAINED -> estimate(durationMs).aacConstrainedBytes
        Mode.AAC_DEFAULT -> estimate(durationMs).aacDefaultBytes
        Mode.PCM_WAV -> estimate(durationMs).pcmWavBytes
    }

    /** reserve를 제외한 공간으로 버틸 수 있는 PCM WAV 기준 최대 녹음 시간. */
    fun estimateMaxDurationMs(
        availableBytes: Long,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 1,
        pcmBitsPerSample: Int = 16,
        reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    ): Long {
        require(availableBytes >= 0L) { "availableBytes는 음수일 수 없습니다" }
        require(sampleRateHz in 8_000..192_000) { "지원하지 않는 sample rate" }
        require(channelCount in 1..2) { "지원하지 않는 channel count" }
        require(pcmBitsPerSample in setOf(16, 24, 32)) { "지원하지 않는 PCM bit depth" }
        require(reserveBytes >= 0L) { "reserveBytes는 음수일 수 없습니다" }
        val usableBytes = (availableBytes - reserveBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)
        val bytesPerSecond = sampleRateHz.toLong() * channelCount.toLong() * (pcmBitsPerSample / 8L)
        val wholeSeconds = usableBytes / bytesPerSecond
        val remainingMs = (usableBytes % bytesPerSecond) * 1_000L / bytesPerSecond
        return wholeSeconds * 1_000L + remainingMs
    }

    private fun encodedBytes(durationMs: Long, bitrateBitsPerSecond: Long, overheadRatio: Double): Long {
        val payload = durationMs.toDouble() / 1_000.0 * bitrateBitsPerSecond.toDouble() / 8.0
        return ceil(payload * overheadRatio).toLong()
    }

    private fun pcmBytes(durationMs: Long, sampleRateHz: Int, channels: Int, bitsPerSample: Int): Long {
        val bytesPerSecond = sampleRateHz.toLong() * channels.toLong() * (bitsPerSample / 8L)
        return ceil(durationMs.toDouble() / 1_000.0 * bytesPerSecond.toDouble()).toLong()
    }

    private const val AAC_CONSTRAINED_BITRATE = 96_000L
    private const val AAC_DEFAULT_BITRATE = 192_000L
    private const val CONTAINER_OVERHEAD_RATIO = 1.05
    private const val WAV_HEADER_BYTES = 44L
    const val DEFAULT_RESERVE_BYTES = 64L * 1024L * 1024L
}
