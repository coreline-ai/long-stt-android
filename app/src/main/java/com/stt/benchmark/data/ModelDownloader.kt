package com.stt.benchmark.data

import android.content.Context
import com.stt.benchmark.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * HuggingFace에서 whisper.cpp 모델을 다운로드.
 * 앱 내부 저장소(filesDir)에 저장.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
        private const val MODELS_DIR = "models"

        // 사용 가능한 모델 목록
        val MODELS = listOf(
            ModelInfo("tiny", "ggml-tiny.bin", 39, "가장 빠름 • 정확도 낮음"),
            ModelInfo("base", "ggml-base.bin", 74, "권장 시작점 • 균형"),
            ModelInfo("base-q5_1", "ggml-base-q5_1.bin", 57, "양자화 base • 빠르고 가벼움"),
            ModelInfo("small", "ggml-small.bin", 466, "정확도 양호 • 느림"),
            ModelInfo("small-q5_1", "ggml-small-q5_1.bin", 181, "양자화 small • 정확도/속도 균형"),
            ModelInfo("medium", "ggml-medium.bin", 1424, "정확도 우수 • 매우 느림")
        )
    }

    data class ModelInfo(
        val displayName: String,
        val fileName: String,
        val sizeMb: Int,
        val description: String
    ) {
        val url: String get() = "$BASE_URL/$fileName"
        val localPath: String get() = "${fileName.substringBeforeLast(".")}"

        fun existsIn(filesDir: File): Boolean {
            return localFile(filesDir).exists() || legacyFile(filesDir).exists()
        }

        fun localFile(filesDir: File): File {
            return File(File(filesDir, MODELS_DIR), fileName)
        }

        fun legacyFile(filesDir: File): File = File(filesDir, fileName)

        fun installedFile(filesDir: File): File? = when {
            localFile(filesDir).isFile -> localFile(filesDir)
            legacyFile(filesDir).isFile -> legacyFile(filesDir)
            else -> null
        }
    }

    /**
     * 모델 다운로드 (진행률 콜백 포함).
     * @param model 다운로드할 모델 정보
     * @param onProgress 진행률 (0.0 ~ 1.0)
     * @return 저장된 파일 경로, 실패 시 null
     */
    suspend fun download(
        model: ModelInfo,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        // 새 다운로드는 models/ 아래에 저장한다. 이전 앱 버전의 루트 모델은
        // installedFile()로 계속 선택할 수 있어 불필요한 재다운로드를 막는다.
        model.installedFile(context.filesDir)?.let { existing ->
            AppLog.i(TAG, "모델이 이미 설치되어 있음")
            onProgress(1.0f)
            return@withContext existing
        }
        val targetFile = model.localFile(context.filesDir)
        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.part")
        targetFile.parentFile?.mkdirs()

        try {
            if (tempFile.exists()) tempFile.delete()
            AppLog.i(TAG, "모델 다운로드 시작")
            val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.e(TAG, "다운로드 실패: HTTP $responseCode")
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong
            AppLog.i(TAG, "총 크기: ${totalBytes / 1024 / 1024}MB")

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                tempFile.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val progress = downloadedBytes.toFloat() / totalBytes
                            onProgress(progress)
                        }
                    }
                }
            }

            if (totalBytes > 0L && tempFile.length() != totalBytes) {
                throw IllegalStateException("모델 크기 불일치: ${tempFile.length()}/$totalBytes")
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("임시 모델 파일을 완료 파일로 변경하지 못했습니다")
            }
            AppLog.i(TAG, "모델 다운로드 완료 (${targetFile.length() / 1024 / 1024}MB)")
            onProgress(1.0f)
            targetFile
        } catch (cancelled: CancellationException) {
            tempFile.delete()
            throw cancelled
        } catch (e: Exception) {
            AppLog.e(TAG, "모델 다운로드 실패", e)
            // 실패 시 부분 파일만 정리하며 기존 완료 모델은 보존한다.
            tempFile.delete()
            null
        }
    }
}
