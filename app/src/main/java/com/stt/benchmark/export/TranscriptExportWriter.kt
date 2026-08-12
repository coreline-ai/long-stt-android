package com.stt.benchmark.export

import com.stt.benchmark.data.TranscriptSourceDocument
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 전체 문자열 복사본을 만들지 않고 완료 전사를 UTF-8 TXT로 순차 기록한다. */
object TranscriptExportWriter {
    fun defaultFileName(
        updatedAtMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timestamp = FILE_NAME_FORMAT.format(Instant.ofEpochMilli(updatedAtMs).atZone(zoneId))
        return "LongSTT_전사_$timestamp.txt"
    }

    fun write(document: TranscriptSourceDocument, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Long STT 전체 전사")
            writer.appendLine("구간 ${document.sections.size}개")
            writer.appendLine()
            document.sections.forEachIndexed { index, section ->
                writer.append('[')
                writer.append(formatElapsed(section.startMs))
                writer.append('–')
                writer.append(formatElapsed(section.endMs))
                writer.append("] ")
                writer.appendLine(section.label)
                writer.appendLine(section.text)
                if (index < document.sections.lastIndex) writer.appendLine()
            }
        }
    }

    internal fun formatElapsed(timeMs: Long): String {
        val totalSeconds = timeMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }

    private val FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.ROOT)
}
