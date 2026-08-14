package com.stt.benchmark.data

import android.content.Context
import com.stt.benchmark.core.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 고정된 Hugging Face revision에서 whisper.cpp 모델을 내려받는다.
 *
 * 모델은 native parser의 입력이므로, HTTPS만으로 신뢰하지 않고 허용 host·정확한
 * byte size·SHA-256까지 확인한 경우에만 앱 내부 저장소의 완료 파일로 승격한다.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        internal const val MODEL_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        private const val BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/$MODEL_REVISION"
        private const val MODELS_DIR = "models"
        private const val MAX_REDIRECTS = 4

        /**
         * SHA-256/byte size는 2026-08-15에 해당 revision의 Hugging Face LFS metadata에서
         * 확인했다. 모델 교체는 revision·size·digest를 한 묶음으로 갱신해야 한다.
         */
        val MODELS = listOf(
            ModelInfo(
                "tiny", "ggml-tiny.bin", 39, "가장 빠름 • 정확도 낮음", 77_691_713L,
                "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            ),
            ModelInfo(
                "base", "ggml-base.bin", 74, "권장 시작점 • 균형", 147_951_465L,
                "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            ),
            ModelInfo(
                "base-q5_1", "ggml-base-q5_1.bin", 57, "양자화 base • 빠르고 가벼움", 59_707_625L,
                "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            ),
            ModelInfo(
                "small", "ggml-small.bin", 466, "정확도 양호 • 느림", 487_601_967L,
                "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            ),
            ModelInfo(
                "small-q5_1", "ggml-small-q5_1.bin", 181, "양자화 small • 정확도/속도 균형", 190_085_487L,
                "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            ),
            ModelInfo(
                "medium", "ggml-medium.bin", 1424, "정확도 우수 • 매우 느림", 1_533_763_059L,
                "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208",
            ),
        )
    }

    data class ModelInfo(
        val displayName: String,
        val fileName: String,
        val sizeMb: Int,
        val description: String,
        val expectedBytes: Long,
        val sha256: String,
    ) {
        init {
            require(fileName.matches(Regex("ggml-[a-z0-9_-]+\\.bin"))) { "invalid model file name" }
            require(expectedBytes > 0) { "expectedBytes must be positive" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid model SHA-256" }
        }

        /** 고정 revision을 포함하므로 mutable main branch를 사용하지 않는다. */
        val url: String get() = "$BASE_URL/$fileName"
        val localPath: String get() = fileName.substringBeforeLast(".")

        fun existsIn(filesDir: File): Boolean = installedFile(filesDir) != null

        fun localFile(filesDir: File): File = File(File(filesDir, MODELS_DIR), fileName)

        fun legacyFile(filesDir: File): File = File(filesDir, fileName)

        /** 완료된 카탈로그 모델은 크기와 SHA-256이 모두 맞을 때만 재사용한다. */
        fun installedFile(filesDir: File): File? = knownFiles(filesDir).firstOrNull(::isVerified)

        fun isVerified(file: File): Boolean =
            file.isFile && file.length() == expectedBytes && ModelDownloadSecurity.sha256(file) == sha256

        internal fun knownFiles(filesDir: File): List<File> = listOf(localFile(filesDir), legacyFile(filesDir))
    }

    /**
     * @return 검증을 통과한 완료 파일, 실패 시 null. 실패한 partial 파일은 항상 삭제한다.
     */
    suspend fun download(
        model: ModelInfo,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        model.installedFile(context.filesDir)?.let { existing ->
            AppLog.i(TAG, "검증된 모델이 이미 설치되어 있음")
            onProgress(1.0f)
            return@withContext existing
        }
        // 이름은 같지만 과거 버전/손상 파일인 경우에는 완료 파일로 신뢰하지 않는다.
        model.knownFiles(context.filesDir).forEach { candidate ->
            if (candidate.isFile && !model.isVerified(candidate)) candidate.delete()
        }

        val targetFile = model.localFile(context.filesDir)
        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.part")
        targetFile.parentFile?.mkdirs()

        var connection: HttpURLConnection? = null
        try {
            if (tempFile.exists()) tempFile.delete()
            AppLog.i(TAG, "검증된 모델 다운로드 시작")
            val verifiedConnection = openTrustedConnection(URL(model.url))
            connection = verifiedConnection
            val declaredBytes = verifiedConnection.contentLengthLong
            if (declaredBytes > 0L && declaredBytes != model.expectedBytes) {
                throw IllegalStateException("model content length does not match the pinned catalog")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L
            val buffer = ByteArray(8 * 1024)
            verifiedConnection.inputStream.use { input ->
                tempFile.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        downloadedBytes += read
                        check(downloadedBytes <= model.expectedBytes) {
                            "model download exceeds the pinned byte size"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(downloadedBytes.toFloat() / model.expectedBytes.toFloat())
                    }
                }
            }

            check(downloadedBytes == model.expectedBytes) {
                "model byte size does not match the pinned catalog"
            }
            check(ModelDownloadSecurity.hex(digest.digest()) == model.sha256) {
                "model SHA-256 does not match the pinned catalog"
            }
            check(tempFile.renameTo(targetFile)) { "model finalization failed" }
            AppLog.i(TAG, "모델 다운로드 및 무결성 검증 완료")
            onProgress(1.0f)
            targetFile
        } catch (cancelled: CancellationException) {
            tempFile.delete()
            throw cancelled
        } catch (error: Exception) {
            AppLog.w(TAG, "모델 다운로드 또는 무결성 검증 실패", error)
            tempFile.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** HTTP redirect도 허용 host를 하나씩 검증한 뒤에만 따른다. */
    private fun openTrustedConnection(initialUrl: URL): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            require(ModelDownloadSecurity.isTrustedModelUrl(currentUrl)) { "untrusted model URL" }
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = false
            }
            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    check(!location.isNullOrBlank()) { "model redirect is missing Location" }
                    check(redirects < MAX_REDIRECTS) { "model redirect limit exceeded" }
                    redirects++
                    currentUrl = URL(currentUrl, location)
                }
                else -> {
                    connection.disconnect()
                    throw IllegalStateException("model download returned HTTP $responseCode")
                }
            }
        }
    }
}

/** Shared pure helpers so model-catalog integrity policy is unit-testable. */
internal object ModelDownloadSecurity {
    private val trustedHosts = setOf("huggingface.co", "hf.co")

    fun isTrustedModelUrl(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host.lowercase()
        return trustedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
