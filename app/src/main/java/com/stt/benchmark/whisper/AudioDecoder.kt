package com.stt.benchmark.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import kotlin.math.min

/**
 * 오디오 → whisper.cpp 입력(16kHz mono float32).
 *
 * - [decodeToFloatArray]: 전체 디코딩 (짧은 파일)
 * - [decodeWindow]: 시간 구간 디코딩 (장시간 배치, WAV/M4A/MP3)
 * - [readWavRange]: PCM WAV 직접 구간 읽기
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L
    /** seek 동기 프레임 이후 end 판정 여유 */
    private const val END_MARGIN_US = 50_000L
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val PCM_WAV_FORMAT = 1
    /** 10분 구간에서 한 번에 읽을 수 있는 원본 PCM 상한. */
    private const val MAX_WINDOW_SOURCE_BYTES = 128L * 1024L * 1024L

    /**
     * 구간 디코딩 결과. [decodedStartMs]/[decodedEndMs]는 PCM 샘플이 실제로
     * 포함하는 원본 타임라인 범위이며, 호출자는 이 값으로 coverage를 검증한다.
     */
    data class DecodedAudioWindow(
        val pcm: FloatArray,
        val requestedStartMs: Long,
        val requestedEndMs: Long,
        val decodedStartMs: Long,
        val decodedEndMs: Long
    ) {
        val isEmpty: Boolean get() = pcm.isEmpty()
    }

    private data class WavFormat(
        val dataOffset: Long,
        val dataSize: Long,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val audioFormat: Int
    )

    private data class PcmRange(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val decodedStartUs: Long,
        val decodedEndUs: Long
    )

    /**
     * 파일 전체 디코딩 (≤10분 권장).
     */
    fun decodeToFloatArray(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(filePath)
            val track = findAudioTrack(extractor) ?: return FloatArray(0)
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.i(TAG, "전체 디코딩: $mime, ${sampleRate}Hz, ${channels}ch → 16kHz mono")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val raw = decodeToPcmRange(
                extractor = extractor,
                codec = codec,
                startUs = 0L,
                endUs = Long.MAX_VALUE,
                dropBeforeUs = 0L,
                initialSampleRate = sampleRate,
                initialChannels = channels
            )
            Log.i(TAG, "PCM 디코딩 완료: ${raw.samples.size} interleaved samples")

            toWhisperFloats(raw.samples, raw.channels, raw.sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "전체 디코딩 실패: $filePath", e)
            FloatArray(0)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * 장시간 전사를 위한 안전한 duration 조회. 값을 얻지 못하면 호출자는 전체
     * 디코딩으로 폴백하지 말고 명시적으로 실패시켜야 한다.
     */
    fun durationMs(filePath: String): Long? {
        try {
            RandomAccessFile(filePath, "r").use { raf ->
                val wav = readWavFormat(raf, raf.length())
                if (wav != null) {
                    val bytesPerFrame = wav.channels * ((wav.bitsPerSample + 7) / 8)
                    if (bytesPerFrame > 0) {
                        val frames = wav.dataSize / bytesPerFrame
                        val duration = frames * 1000L / wav.sampleRate
                        if (duration > 0L) return duration
                    }
                }
            }
        } catch (_: Exception) {
            // RIFF가 아니거나 WAV header가 손상된 경우 MediaExtractor를 시도한다.
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            val track = findAudioTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000L
            duration.takeIf { it > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "오디오 길이 조회 실패: $filePath", e)
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * [startMs, startMs+durationMs) 구간을 16kHz mono float32 로 디코딩.
     * WAV(PCM)는 직접 읽기, 그 외(M4A/MP3 등)는 MediaCodec seek 디코드.
     */
    fun decodeWindow(filePath: String, startMs: Long, durationMs: Long): FloatArray {
        return decodeWindowWithMetadata(filePath, startMs, durationMs).pcm
    }

    /**
     * [startMs, startMs+durationMs) 구간과 실제 PCM 범위를 함께 반환한다.
     * 장시간 전사는 반드시 이 API를 사용해 coverage를 확인한다.
     */
    fun decodeWindowWithMetadata(
        filePath: String,
        startMs: Long,
        durationMs: Long
    ): DecodedAudioWindow {
        if (durationMs <= 0L) return emptyWindow(startMs, startMs)
        val start = startMs.coerceAtLeast(0L)
        return if (isPcmWavFile(filePath)) {
            decodeWavWindow(filePath, start, durationMs)
        } else {
            decodeCompressedWindow(filePath, start, durationMs)
        }
    }

    /**
     * 16kHz 모노 PCM WAV 구간 직접 읽기.
     */
    fun readWavRange(filePath: String, startMs: Long, durationMs: Long): FloatArray {
        return decodeWavWindow(filePath, startMs, durationMs).pcm
    }

    private fun decodeWavWindow(filePath: String, startMs: Long, durationMs: Long): DecodedAudioWindow {
        val requestedEndMs = startMs + durationMs
        return try {
            val file = java.io.File(filePath)
            RandomAccessFile(file, "r").use { raf ->
                val wav = readWavFormat(raf, file.length()) ?: return emptyWindow(startMs, requestedEndMs)
                if (wav.audioFormat != PCM_WAV_FORMAT || wav.bitsPerSample != 16) {
                    Log.w(TAG, "지원하지 않는 WAV 형식: format=${wav.audioFormat}, bits=${wav.bitsPerSample}")
                    return emptyWindow(startMs, requestedEndMs)
                }

                val bytesPerFrame = wav.channels * (wav.bitsPerSample / 8)
                val totalFrames = wav.dataSize / bytesPerFrame
                val startFrame = (startMs * wav.sampleRate / 1000L).coerceIn(0L, totalFrames)
                val endFrame = ((requestedEndMs * wav.sampleRate + 999L) / 1000L)
                    .coerceIn(startFrame, totalFrames)
                val framesToRead = endFrame - startFrame
                if (framesToRead <= 0L || framesToRead * bytesPerFrame > MAX_WINDOW_SOURCE_BYTES) {
                    Log.w(TAG, "WAV 구간 길이 거부: frames=$framesToRead")
                    return emptyWindow(startMs, requestedEndMs)
                }

                val byteCount = framesToRead * bytesPerFrame
                if (byteCount > Int.MAX_VALUE) return emptyWindow(startMs, requestedEndMs)
                val buffer = ByteArray(byteCount.toInt())
                raf.seek(wav.dataOffset + startFrame * bytesPerFrame)
                raf.readFully(buffer)

                val sourceMono = pcm16ToMono(buffer, wav.channels)
                val pcm = if (wav.sampleRate == TARGET_SAMPLE_RATE) sourceMono else {
                    resampleLinear(sourceMono, wav.sampleRate, TARGET_SAMPLE_RATE)
                }
                val decodedStartMs = startFrame * 1000L / wav.sampleRate
                val decodedEndMs = endFrame * 1000L / wav.sampleRate
                Log.i(TAG, "WAV 구간: $decodedStartMs~$decodedEndMs ms → ${pcm.size} samples @16k")
                DecodedAudioWindow(pcm, startMs, requestedEndMs, decodedStartMs, decodedEndMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV 구간 읽기 실패", e)
            emptyWindow(startMs, requestedEndMs)
        }
    }

    // -------------------------------------------------------------------------
    // MediaCodec window decode (M4A / MP3 / AAC …)
    // -------------------------------------------------------------------------

    private fun decodeCompressedWindow(
        filePath: String,
        startMs: Long,
        durationMs: Long
    ): DecodedAudioWindow {
        val startUs = startMs * 1000L
        val endUs = (startMs + durationMs) * 1000L
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(filePath)
            val track = findAudioTrack(extractor) ?: return emptyWindow(startMs, startMs + durationMs)
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyWindow(startMs, startMs + durationMs)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.i(
                TAG,
                "압축 구간 디코드: $mime ${sampleRate}Hz ${channels}ch " +
                    "[${startMs}ms, ${startMs + durationMs}ms)"
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // 키프레임/동기 지점으로 이동 후 start 이전 프레임은 drop
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val raw = decodeToPcmRange(
                extractor = extractor,
                codec = codec,
                startUs = startUs,
                endUs = endUs,
                dropBeforeUs = startUs,
                initialSampleRate = sampleRate,
                initialChannels = channels
            )

            val floats = toWhisperFloats(raw.samples, raw.channels, raw.sampleRate)
            val decodedStartMs = raw.decodedStartUs / 1000L
            val decodedEndMs = (raw.decodedEndUs + 999L) / 1000L

            Log.i(
                TAG,
                "압축 구간 완료: ${decodedStartMs}~${decodedEndMs}ms, " +
                    "in=${raw.samples.size} → out=${floats.size} samples"
            )
            DecodedAudioWindow(floats, startMs, startMs + durationMs, decodedStartMs, decodedEndMs)
        } catch (e: Exception) {
            Log.e(TAG, "압축 구간 디코드 실패: $filePath [$startMs+${durationMs}ms]", e)
            emptyWindow(startMs, startMs + durationMs)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * @param startUs 논리 시작 (로그용)
     * @param endUs 이 시각 이후 출력은 수집 중단
     * @param dropBeforeUs 이 시각 이전 PTS 출력은 버림
     */
    private fun decodeToPcmRange(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startUs: Long,
        endUs: Long,
        dropBeforeUs: Long,
        initialSampleRate: Int,
        initialChannels: Int
    ): PcmRange {
        val bufferInfo = MediaCodec.BufferInfo()
        val chunks = ArrayList<ShortArray>(256)
        var sawInputEos = false
        var sawOutputEos = false
        var reachedEnd = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var sampleRate = initialSampleRate.coerceAtLeast(1)
        var channels = initialChannels.coerceAtLeast(1)
        var decodedStartUs = -1L
        var decodedEndUs = -1L
        var fallbackPtsUs = startUs

        // 안전 루프 한도 (비정상 hang 방지)
        var idleLoops = 0
        val maxIdleLoops = 5000

        while (!sawOutputEos && !reachedEnd && idleLoops < maxIdleLoops) {
            var progressed = false

            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    progressed = true
                    val inputBuffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val sampleTime = extractor.sampleTime

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEos = true
                    } else {
                        // end 이후 입력은 더 이상 넣지 않고 EOS
                        if (sampleTime != -1L && sampleTime > endUs + END_MARGIN_US) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    progressed = true
                    val fmt = codec.outputFormat
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (Build.VERSION.SDK_INT >= 24 && fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    Log.i(TAG, "출력 포맷: $fmt")
                }
                outIndex >= 0 -> {
                    progressed = true
                    val pts = if (bufferInfo.presentationTimeUs >= 0L) {
                        bufferInfo.presentationTimeUs
                    } else {
                        fallbackPtsUs
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)!!
                        val chunk = bufferToShorts(
                            outputBuffer,
                            bufferInfo.offset,
                            bufferInfo.size,
                            pcmEncoding
                        )
                        val clipped = clipPcmToRange(
                            samples = chunk,
                            channels = channels,
                            sampleRate = sampleRate,
                            ptsUs = pts,
                            startUs = dropBeforeUs,
                            endUs = endUs
                        )
                        if (clipped.samples.isNotEmpty()) {
                            chunks.add(clipped.samples)
                            if (decodedStartUs < 0L) decodedStartUs = clipped.startUs
                            decodedEndUs = clipped.endUs
                        }
                        val frameCount = chunk.size / channels.coerceAtLeast(1)
                        fallbackPtsUs = pts + frameCount.toLong() * MICROS_PER_SECOND / sampleRate
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                    // 목표 끝을 포함한 PCM을 수집했으면 즉시 종료한다.
                    if (endUs != Long.MAX_VALUE && fallbackPtsUs >= endUs) {
                        reachedEnd = true
                    }
                }
            }

            if (!progressed) idleLoops++ else idleLoops = 0
        }

        if (idleLoops >= maxIdleLoops) {
            Log.w(TAG, "디코드 루프 idle 한도 도달 (partial ${chunks.size} chunks)")
        }

        val total = chunks.sumOf { it.size }
        val result = ShortArray(total)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, o, c.size)
            o += c.size
        }
        val actualStart = if (decodedStartUs >= 0L) decodedStartUs else startUs
        val actualEnd = if (decodedEndUs >= 0L) decodedEndUs else actualStart
        Log.d(
            TAG,
            "decodeToPcmRange: shorts=${result.size}, rate=$sampleRate, ch=$channels, " +
                "actual=$actualStart~$actualEnd, requested=$startUs~$endUs"
        )
        return PcmRange(result, sampleRate, channels, actualStart, actualEnd)
    }

    private data class ClippedPcm(val samples: ShortArray, val startUs: Long, val endUs: Long)

    /** PTS가 걸친 MediaCodec 출력 버퍼에서 요청 구간에 속한 frame만 남긴다. */
    private fun clipPcmToRange(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        ptsUs: Long,
        startUs: Long,
        endUs: Long
    ): ClippedPcm {
        val safeChannels = channels.coerceAtLeast(1)
        val safeRate = sampleRate.coerceAtLeast(1)
        val frameCount = samples.size / safeChannels
        if (frameCount <= 0) return ClippedPcm(ShortArray(0), startUs, startUs)

        val firstFrame = if (startUs <= ptsUs) 0 else {
            ceilDivide((startUs - ptsUs) * safeRate, MICROS_PER_SECOND)
                .coerceIn(0L, frameCount.toLong()).toInt()
        }
        val lastFrame = if (endUs == Long.MAX_VALUE) frameCount else {
            ceilDivide((endUs - ptsUs) * safeRate, MICROS_PER_SECOND)
                .coerceIn(0L, frameCount.toLong()).toInt()
        }
        if (lastFrame <= firstFrame) return ClippedPcm(ShortArray(0), startUs, startUs)

        val clipped = samples.copyOfRange(firstFrame * safeChannels, lastFrame * safeChannels)
        val clipStartUs = maxOf(startUs, ptsUs + firstFrame.toLong() * MICROS_PER_SECOND / safeRate)
        val rawEndUs = ptsUs + lastFrame.toLong() * MICROS_PER_SECOND / safeRate
        val clipEndUs = if (endUs == Long.MAX_VALUE) rawEndUs else minOf(endUs, rawEndUs)
        return ClippedPcm(clipped, clipStartUs, clipEndUs)
    }

    private fun ceilDivide(value: Long, divisor: Long): Long {
        if (value <= 0L) return 0L
        return (value + divisor - 1L) / divisor
    }

    private fun bufferToShorts(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int
    ): ShortArray {
        buffer.position(offset)
        buffer.limit(offset + size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = size / 4
                ShortArray(floats) {
                    val f = buffer.float.coerceIn(-1f, 1f)
                    (f * 32767f).toInt().toShort()
                }
            }
            else -> {
                // PCM_16BIT 기본
                val count = size / 2
                ShortArray(count) { buffer.short }
            }
        }
    }

    private fun toWhisperFloats(
        interleavedShorts: ShortArray,
        channels: Int,
        sampleRate: Int
    ): FloatArray {
        if (interleavedShorts.isEmpty()) return FloatArray(0)
        val ch = channels.coerceAtLeast(1)
        val frames = interleavedShorts.size / ch
        val mono = FloatArray(frames)
        if (ch == 1) {
            for (i in 0 until frames) {
                mono[i] = interleavedShorts[i] / 32768.0f
            }
        } else {
            var idx = 0
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) {
                    sum += interleavedShorts[idx++] / 32768.0f
                }
                mono[i] = sum / ch
            }
        }
        return if (sampleRate != TARGET_SAMPLE_RATE) {
            resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            mono
        }
    }

    private fun pcm16ToMono(buffer: ByteArray, channels: Int): FloatArray {
        val safeChannels = channels.coerceAtLeast(1)
        val frames = buffer.size / (2 * safeChannels)
        val mono = FloatArray(frames)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(safeChannels) { sum += bb.short / 32768.0f }
            mono[frame] = sum / safeChannels
        }
        return mono
    }

    private fun emptyWindow(startMs: Long, endMs: Long): DecodedAudioWindow =
        DecodedAudioWindow(FloatArray(0), startMs, endMs, startMs, startMs)

    /**
     * RIFF chunk는 파일이 손상돼도 길이 필드를 신뢰하면 안 된다. 필요한 header만
     * 읽고 각 chunk의 다음 offset이 파일 범위를 벗어나면 즉시 거부한다.
     */
    private fun readWavFormat(raf: RandomAccessFile, fileLength: Long): WavFormat? {
        if (fileLength < 12L) return null
        val header = ByteArray(12)
        raf.seek(0L)
        raf.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WAVE") {
            return null
        }

        var offset = 12L
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = 0L

        while (offset <= fileLength - 8L) {
            raf.seek(offset)
            val chunkHeader = ByteArray(8)
            raf.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = littleEndianUInt32(chunkHeader, 4)
            val payloadOffset = offset + 8L
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = payloadOffset + paddedSize
            if (nextOffset < payloadOffset || nextOffset > fileLength) {
                Log.w(TAG, "손상된 WAV chunk: id=$id size=$chunkSize")
                return null
            }

            when (id) {
                "fmt " -> {
                    if (chunkSize < 16L) return null
                    val fmt = ByteArray(16)
                    raf.seek(payloadOffset)
                    raf.readFully(fmt)
                    audioFormat = littleEndianUInt16(fmt, 0)
                    channels = littleEndianUInt16(fmt, 2)
                    val parsedRate = littleEndianUInt32(fmt, 4)
                    if (parsedRate > Int.MAX_VALUE) return null
                    sampleRate = parsedRate.toInt()
                    bitsPerSample = littleEndianUInt16(fmt, 14)
                }
                "data" -> {
                    dataOffset = payloadOffset
                    dataSize = chunkSize
                }
            }
            if (audioFormat >= 0 && dataOffset >= 0L) break
            offset = nextOffset
        }

        if (audioFormat < 0 || dataOffset < 0L || channels !in 1..8 ||
            sampleRate !in 8_000..192_000 || bitsPerSample !in 1..64) {
            return null
        }
        return WavFormat(dataOffset, dataSize, channels, sampleRate, bitsPerSample, audioFormat)
    }

    private fun littleEndianUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** RIFF/WAVE PCM 여부 (확장자 + 헤더) */
    fun isPcmWavFile(filePath: String): Boolean {
        if (!filePath.endsWith(".wav", ignoreCase = true)) return false
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val format = readWavFormat(raf, raf.length())
                format?.audioFormat == PCM_WAV_FORMAT && format.bitsPerSample == 16
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        return FloatArray(outLen) { i ->
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            if (srcIdx + 1 < input.size) {
                input[srcIdx] * (1 - frac) + input[srcIdx + 1] * frac
            } else {
                input[srcIdx.coerceAtMost(input.lastIndex)]
            }
        }
    }
}
