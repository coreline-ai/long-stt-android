package com.stt.benchmark.whisper

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperLib
import java.io.File

interface WhisperEngine {
    fun loadModel(modelPath: String): Boolean
    fun transcribe(audioPath: String, language: String = "ko"): TranscriptionResult
    fun transcribePcm(pcm: FloatArray, offsetMs: Long = 0L): TranscriptionResult
    fun setProgressCallback(callback: ((Float) -> Unit)?)
    fun release()
    val engineName: String
}

data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptSegment>,
    val elapsedMs: Long,
    val audioDurationMs: Long,
    val modelSize: String,
    val engineName: String,
    /** 장시간 전사에서 각 primary 청크가 실제 PCM으로 덮은 구간. */
    val chunkCoverage: List<ChunkCoverage> = emptyList()
) {
    val rtf: Float get() = if (audioDurationMs > 0) elapsedMs.toFloat() / audioDurationMs else 0f
    val speedMultiplier: Float get() = if (elapsedMs > 0) audioDurationMs.toFloat() / elapsedMs else 0f
}

data class ChunkCoverage(
    val chunkIndex: Int,
    val primaryStartMs: Long,
    val primaryEndMs: Long,
    val decodedStartMs: Long,
    val decodedEndMs: Long,
    val decodedSamples: Int
)

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Whisper.cpp JNI 구현체.
 * 단일 전사만 담당. 분할 로직은 상위(ViewModel)에서 관리.
 * 같은 컨텍스트로 fullTranscribe를 2번 호출하면 크래시하므로,
 * 배치 전사 시에는 각 청크마다 loadModel() 재호출 필요.
 */
class WhisperCppEngine(
    private val context: Context,
    override val engineName: String = "whisper.cpp"
) : WhisperEngine {

    private var progressCallback: ((Float) -> Unit)? = null
    private var ctx: Long = 0L
    private var isModelLoaded = false

    @Synchronized
    override fun loadModel(modelPath: String): Boolean {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "모델 파일 없음: $modelPath")
            return false
        }

        // 기존 컨텍스트 해제 (같은 프로세스에서 재로드 시 필수)
        if (ctx != 0L) {
            try { WhisperLib.freeContext(ctx) } catch (_: Exception) {}
            ctx = 0L
            isModelLoaded = false
        }

        try {
            val localModel = ensureModelAccessible(modelPath)
            Log.i(TAG, "모델 네이티브 로드 시도: ${localModel.absolutePath}")

            ctx = WhisperLib.initContext(localModel.absolutePath)
            if (ctx == 0L) {
                Log.e(TAG, "모델 로드 실패 (ctx == 0): ${localModel.absolutePath}")
                return false
            }
            Log.i(TAG, "모델 로드 성공: ${localModel.name} (${localModel.length() / 1024 / 1024}MB), ctx=$ctx")
            isModelLoaded = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 예외", e)
            return false
        }
    }

    @Synchronized
    override fun transcribe(audioPath: String, language: String): TranscriptionResult {
        require(isModelLoaded) { "모델이 로드되지 않음" }

        val audioFile = File(audioPath)
        require(audioFile.exists()) { "오디오 파일 없음: $audioPath" }

        progressCallback?.invoke(0.1f)

        val localAudio = ensureModelAccessible(audioPath)
        val modelSize = "${audioFile.length() / 1024 / 1024}MB"

        // 오디오 디코딩
        Log.i(TAG, "오디오 디코딩: ${localAudio.name}")
        val pcmFloats = AudioDecoder.decodeToFloatArray(localAudio.absolutePath)
        if (pcmFloats.isEmpty()) throw RuntimeException("오디오 디코딩 실패")

        progressCallback?.invoke(0.3f)

        // PCM 전사
        val result = transcribePcmInternal(pcmFloats, 0L)
        return result.copy(modelSize = modelSize)
    }

    /**
     * PCM 배열을 직접 전사 (배치 전사용).
     * 호출 전 loadModel()로 ctx가 유효해야 함.
     * @param pcm 16kHz mono float32 배열
     * @param offsetMs 타임스탬프 오프셋 (청크 분할 시 사용)
     */
    @Synchronized
    override fun transcribePcm(pcm: FloatArray, offsetMs: Long): TranscriptionResult {
        require(isModelLoaded) { "모델이 로드되지 않음" }
        require(pcm.isNotEmpty()) { "전사할 PCM 데이터가 없음" }
        return transcribePcmInternal(pcm, offsetMs)
    }

    private fun transcribePcmInternal(pcm: FloatArray, offsetMs: Long): TranscriptionResult {
        val audioDurationMs = (pcm.size.toLong() * 1000L) / 16000L

        Log.i(TAG, "whisper_full 전사 (${pcm.size} samples, offset=${offsetMs}ms, threads=$NUM_THREADS)")
        val startTime = System.currentTimeMillis()

        val nativeResult = WhisperLib.fullTranscribe(ctx, NUM_THREADS, pcm)
        if (nativeResult != 0) {
            throw IllegalStateException("whisper_full 실패 (code=$nativeResult)")
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i(TAG, "전사 완료: ${elapsedMs}ms (RTF=%.3f)".format(elapsedMs.toFloat() / audioDurationMs))

        val segCount = WhisperLib.getTextSegmentCount(ctx)
        Log.i(TAG, "세그먼트 수: $segCount")

        val segments = (0 until segCount).map { i ->
            TranscriptSegment(
                WhisperLib.getTextSegmentT0(ctx, i) * 10 + offsetMs,
                WhisperLib.getTextSegmentT1(ctx, i) * 10 + offsetMs,
                WhisperLib.getTextSegment(ctx, i)
            )
        }
        val fullText = segments.joinToString(" ") { it.text.trim() }.trim()
        Log.i(TAG, "전사 텍스트 길이: ${fullText.length}자")

        progressCallback?.invoke(1.0f)

        return TranscriptionResult(
            text = fullText,
            segments = segments,
            elapsedMs = elapsedMs,
            audioDurationMs = audioDurationMs,
            modelSize = "",
            engineName = engineName
        )
    }

    @Synchronized
    override fun setProgressCallback(callback: ((Float) -> Unit)?) {
        progressCallback = callback
    }

    @Synchronized
    override fun release() {
        if (ctx != 0L) {
            try {
                WhisperLib.freeContext(ctx)
                Log.i(TAG, "컨텍스트 해제 (ctx=$ctx)")
            } catch (e: Exception) {
                Log.e(TAG, "컨텍스트 해제 예외", e)
            }
            ctx = 0L
        }
        isModelLoaded = false
    }

    /**
     * 외부 파일을 앱 내부(filesDir)로 복사. 이미 내부면 그대로 사용.
     */
    private fun ensureModelAccessible(path: String): File {
        val src = File(path)
        val dst = File(context.filesDir, src.name)

        if (src.absolutePath.startsWith(context.filesDir.absolutePath) && src.exists()) {
            return src
        }
        if (dst.exists() && dst.length() > 0) {
            return dst
        }

        Log.i(TAG, "복사: ${src.absolutePath} → ${dst.absolutePath}")
        return try {
            src.inputStream().use { it.copyTo(dst.outputStream()) }
            dst
        } catch (e: Exception) {
            Log.w(TAG, "복사 실패, 원본 사용: $path", e)
            src
        }
    }

    companion object {
        private const val TAG = "WhisperCppEngine"
        private const val NUM_THREADS = 8
    }
}
