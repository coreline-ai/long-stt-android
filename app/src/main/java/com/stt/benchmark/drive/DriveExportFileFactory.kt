package com.stt.benchmark.drive

import android.content.Context
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.export.TranscriptExportWriter
import java.io.File
import java.io.FileOutputStream

/** Drive 업로드에만 쓰는 짧은 수명의 UTF-8 cache 파일 생성기. */
class DriveExportFileFactory(
    private val cacheRoot: File,
) {
    constructor(context: Context) : this(context.cacheDir)

    data class PreparedFile(
        val file: File,
        val mimeType: String = MIME_TYPE,
    ) {
        val sizeBytes: Long get() = file.length().coerceAtLeast(0L)
    }

    fun createTranscript(job: DriveUploadJob, document: TranscriptSourceDocument): PreparedFile =
        create(job, DriveArtifact.TRANSCRIPT) { output ->
            TranscriptExportWriter.write(document, output)
        }

    fun createSummary(job: DriveUploadJob, summary: String): PreparedFile {
        require(summary.isNotBlank()) { "summary must not be blank" }
        return create(job, DriveArtifact.SUMMARY) { output ->
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("Long STT 요약")
                writer.appendLine()
                writer.append(summary.trim())
                writer.appendLine()
            }
        }
    }

    @Synchronized
    fun cleanupExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - CACHE_RETENTION_MS
        return exportRoot().listFiles().orEmpty().fold(0) { count, directory ->
            if (directory.isDirectory && directory.lastModified() < cutoff) {
                directory.listFiles().orEmpty().forEach(File::delete)
                count + if (directory.delete()) 1 else 0
            } else {
                count
            }
        }
    }

    private fun create(
        job: DriveUploadJob,
        artifact: DriveArtifact,
        write: (FileOutputStream) -> Unit,
    ): PreparedFile {
        val directory = File(exportRoot(), job.jobId).apply { mkdirs() }
        check(directory.isDirectory) { "Drive export cache is unavailable" }
        val file = File(directory, artifact.fileName())
        try {
            FileOutputStream(file, false).use(write)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return PreparedFile(file)
    }

    private fun exportRoot(): File = File(cacheRoot, CACHE_DIRECTORY).apply { mkdirs() }

    companion object {
        const val CACHE_DIRECTORY = "drive_exports"
        const val MIME_TYPE = "text/plain"
        const val CACHE_RETENTION_MS = 24L * 60L * 60L * 1_000L
    }
}
