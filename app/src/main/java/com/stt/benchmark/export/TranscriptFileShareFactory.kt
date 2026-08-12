package com.stt.benchmark.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.stt.benchmark.data.TranscriptSourceDocument
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** 앱 cache의 제한된 TXT 파일을 content URI로만 Android Sharesheet에 전달한다. */
internal class TranscriptFileShareFactory(
    private val context: Context,
    private val authority: String = "${context.packageName}.fileprovider",
    private val cacheRoot: File = context.cacheDir,
) {
    fun createChooser(document: TranscriptSourceDocument, nowMs: Long = System.currentTimeMillis()): Intent =
        Intent.createChooser(create(document, nowMs), SHARE_TITLE)

    @Synchronized
    fun create(document: TranscriptSourceDocument, nowMs: Long = System.currentTimeMillis()): Intent {
        cleanupExpired(nowMs)
        val exportDir = exportDir().apply { mkdirs() }
        check(exportDir.isDirectory) { "transcript share cache is unavailable" }
        val timestamp = CACHE_NAME_FORMAT.format(
            Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()),
        )
        val file = File(
            exportDir,
            "LongSTT_전사_${timestamp}_${UUID.randomUUID().toString().take(8)}.txt",
        )
        try {
            FileOutputStream(file).use { TranscriptExportWriter.write(document, it) }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Long STT 전체 전사", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Synchronized
    fun cleanupExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - CACHE_RETENTION_MS
        return exportDir().listFiles().orEmpty().count { file ->
            file.isFile && file.lastModified() < cutoff && file.delete()
        }
    }

    private fun exportDir(): File = File(cacheRoot, CACHE_DIRECTORY)

    companion object {
        const val SHARE_TITLE = "전체 전사 파일 공유"
        const val MIME_TYPE = "text/plain"
        const val CACHE_DIRECTORY = "transcript_exports"
        const val CACHE_RETENTION_MS = 24L * 60L * 60L * 1_000L
        private val CACHE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
