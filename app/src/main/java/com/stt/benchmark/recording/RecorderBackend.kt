package com.stt.benchmark.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface RecorderBackend {
    val partFile: File
    val mode: RecorderBackendMode
    fun setFailureListener(listener: (Throwable) -> Unit) = Unit
    /** Reports only a generic input category; device names and identifiers are intentionally omitted. */
    fun setInputRouteListener(listener: (RecordingInputRoute) -> Unit) = Unit
    fun start()
    fun stop()
    fun maxAmplitude(): Int
    fun release()
}

internal enum class RecorderBackendMode(val container: String) {
    AAC_CONSTRAINED("m4a"),
    AAC_DEFAULT("m4a"),
    PCM_WAV("wav"),
}

/** Safe, user-facing input route category for hardware QA. It never exposes a device name or ID. */
enum class RecordingInputRoute(val label: String) {
    UNKNOWN("입력 감지 중"),
    BUILT_IN("내장 마이크"),
    BLUETOOTH("Bluetooth 입력"),
    USB("USB 입력"),
    WIRED("유선 입력"),
    OTHER("기타 입력"),
    ;

    companion object {
        fun fromDeviceType(deviceType: Int?): RecordingInputRoute = when (deviceType) {
            null -> UNKNOWN
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> BUILT_IN
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> BLUETOOTH

            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> USB

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> WIRED

            else -> OTHER
        }
    }
}

internal class RecorderBackendFactory(
    private val context: Context,
    private val fileManager: RecordingFileManager,
    private val builder: (RecorderBackendMode, File) -> RecorderBackend = { mode, file ->
        when (mode) {
            RecorderBackendMode.AAC_CONSTRAINED -> MediaRecorderBackend(
                context = context,
                partFile = file,
                mode = mode,
                bitrate = 96_000,
                sampleRateHz = 48_000,
            )
            RecorderBackendMode.AAC_DEFAULT -> MediaRecorderBackend(
                context = context,
                partFile = file,
                mode = mode,
                bitrate = null,
                sampleRateHz = null,
            )
            RecorderBackendMode.PCM_WAV -> WavAudioRecordBackend(file)
        }
    },
) {
    sealed interface StartResult {
        data class Started(val backend: RecorderBackend) : StartResult
        data class Failed(val attempts: List<String>) : StartResult
    }

    fun start(
        sessionId: String,
        chunkIndex: Int,
        onInputRoute: (RecordingInputRoute) -> Unit = {},
        // Keep this last so existing callers using a trailing failure lambda remain source-compatible.
        onFailure: (Throwable) -> Unit = {},
    ): StartResult {
        val failures = mutableListOf<String>()
        for (mode in RecorderBackendMode.entries) {
            val part = try {
                fileManager.createPart(sessionId, chunkIndex, mode.container)
            } catch (error: Throwable) {
                failures += "${mode.name}:${error.javaClass.simpleName}"
                continue
            }
            val backend = try {
                builder(mode, part)
            } catch (error: Throwable) {
                fileManager.quarantineManagedFile(part, "backend 생성 실패")
                failures += "${mode.name}:${error.javaClass.simpleName}"
                continue
            }
            try {
                backend.setFailureListener(onFailure)
                backend.setInputRouteListener(onInputRoute)
                backend.start()
                return StartResult.Started(backend)
            } catch (error: Throwable) {
                runCatching(backend::release)
                fileManager.quarantineManagedFile(part, "backend 시작 실패")
                failures += "${mode.name}:${error.javaClass.simpleName}"
            }
        }
        return StartResult.Failed(failures)
    }
}

@Suppress("DEPRECATION")
private class MediaRecorderBackend(
    context: Context,
    override val partFile: File,
    override val mode: RecorderBackendMode,
    private val bitrate: Int?,
    private val sampleRateHz: Int?,
) : RecorderBackend {
    private val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
    private var started = false
    private var released = false
    private var failureListener: ((Throwable) -> Unit)? = null
    private var inputRouteListener: ((RecordingInputRoute) -> Unit)? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    override fun setFailureListener(listener: (Throwable) -> Unit) {
        failureListener = listener
        recorder.setOnErrorListener { _, what, extra ->
            if (started) {
                failureListener?.invoke(
                    IllegalStateException("MediaRecorder error what=$what extra=$extra")
                )
            }
        }
    }

    override fun setInputRouteListener(listener: (RecordingInputRoute) -> Unit) {
        inputRouteListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val callback = AudioRouting.OnRoutingChangedListener { dispatchInputRoute() }
            routingListener = callback
            recorder.addOnRoutingChangedListener(callback, null)
        }
    }

    override fun start() {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        bitrate?.let(recorder::setAudioEncodingBitRate)
        sampleRateHz?.let(recorder::setAudioSamplingRate)
        recorder.setOutputFile(partFile.absolutePath)
        recorder.prepare()
        recorder.start()
        started = true
        dispatchInputRoute()
    }

    override fun stop() {
        if (started) {
            recorder.stop()
            started = false
        }
        release()
    }

    override fun maxAmplitude(): Int = if (started) runCatching { recorder.maxAmplitude }.getOrDefault(0) else 0

    override fun release() {
        if (released) return
        if (started) runCatching { recorder.stop() }
        started = false
        recorder.setOnErrorListener(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            routingListener?.let(recorder::removeOnRoutingChangedListener)
        }
        routingListener = null
        runCatching { recorder.reset() }
        recorder.release()
        released = true
    }

    private fun dispatchInputRoute() {
        val deviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            recorder.routedDevice?.type
        } else {
            null
        }
        inputRouteListener?.invoke(RecordingInputRoute.fromDeviceType(deviceType))
    }
}

@SuppressLint("MissingPermission")
private class WavAudioRecordBackend(
    override val partFile: File,
) : RecorderBackend {
    override val mode = RecorderBackendMode.PCM_WAV
    private val minBufferBytes = AudioRecord.getMinBufferSize(
        SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).also { require(it > 0) { "AudioRecord buffer를 만들 수 없습니다" } }
    private val bufferBytes = maxOf(minBufferBytes, 8_192)
    private val audioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferBytes)
        .build()
        .also { require(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" } }
    private val recording = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)
    @Volatile private var writerFailure: Throwable? = null
    @Volatile private var failureListener: ((Throwable) -> Unit)? = null
    @Volatile private var inputRouteListener: ((RecordingInputRoute) -> Unit)? = null
    private var writerThread: Thread? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var released = false

    override fun setFailureListener(listener: (Throwable) -> Unit) {
        failureListener = listener
    }

    override fun setInputRouteListener(listener: (RecordingInputRoute) -> Unit) {
        inputRouteListener = listener
        val callback = AudioRouting.OnRoutingChangedListener { dispatchInputRoute() }
        routingListener = callback
        audioRecord.addOnRoutingChangedListener(callback, null)
    }

    override fun start() {
        FileOutputStream(partFile, false).use { output -> output.write(ByteArray(WAV_HEADER_BYTES)) }
        recording.set(true)
        audioRecord.startRecording()
        require(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 시작 실패" }
        dispatchInputRoute()
        writerThread = Thread(::writeLoop, "LongStt-WavRecorder").apply { start() }
    }

    override fun stop() {
        recording.set(false)
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
        }
        writerThread?.join(WRITER_JOIN_TIMEOUT_MS)
        check(writerThread?.isAlive != true) { "WAV writer가 종료되지 않았습니다" }
        writerFailure?.let { throw IllegalStateException("WAV writer 실패", it) }
        patchWavHeader(partFile)
        release()
    }

    override fun maxAmplitude(): Int = amplitude.get()

    override fun release() {
        if (released) return
        recording.set(false)
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching(audioRecord::stop)
        }
        writerThread?.join(WRITER_JOIN_TIMEOUT_MS)
        routingListener?.let(audioRecord::removeOnRoutingChangedListener)
        routingListener = null
        audioRecord.release()
        released = true
    }

    private fun dispatchInputRoute() {
        val deviceType = audioRecord.routedDevice?.type
        inputRouteListener?.invoke(RecordingInputRoute.fromDeviceType(deviceType))
    }

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val samples = ShortArray(bufferBytes / 2)
        val bytes = ByteArray(bufferBytes)
        try {
            FileOutputStream(partFile, true).buffered().use { output ->
                while (recording.get()) {
                    val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) throw IllegalStateException("AudioRecord read 오류: $count")
                    if (count == 0) continue
                    var peak = 0
                    var byteIndex = 0
                    repeat(count) { index ->
                        val sample = samples[index].toInt()
                        peak = maxOf(peak, kotlin.math.abs(sample).coerceAtMost(Short.MAX_VALUE.toInt()))
                        bytes[byteIndex++] = (sample and 0xFF).toByte()
                        bytes[byteIndex++] = ((sample ushr 8) and 0xFF).toByte()
                    }
                    amplitude.set(peak)
                    output.write(bytes, 0, count * 2)
                }
            }
        } catch (error: Throwable) {
            if (recording.get()) {
                writerFailure = error
                failureListener?.invoke(error)
            }
        }
    }

    private fun patchWavHeader(file: File) {
        val dataBytes = file.length() - WAV_HEADER_BYTES
        require(dataBytes > 0L && dataBytes <= UInt.MAX_VALUE.toLong()) { "WAV payload 크기가 잘못되었습니다" }
        val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((dataBytes + 36L).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(CHANNEL_COUNT.toShort())
            putInt(SAMPLE_RATE_HZ)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.write(header)
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_BYTES = 44
        const val WRITER_JOIN_TIMEOUT_MS = 5_000L
    }
}
