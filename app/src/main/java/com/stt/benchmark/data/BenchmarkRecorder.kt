package com.stt.benchmark.data

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.stt.benchmark.core.AppLog
import com.stt.benchmark.whisper.TranscriptionResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BenchmarkRecorder(private val context: Context) {

    companion object {
        private const val TAG = "BenchmarkRecorder"
        private const val LEGACY_FILENAME = "stt_benchmark_results.csv"
        private const val FILENAME_V2 = "stt_benchmark_results_v2.csv"
        private const val CSV_HEADER_V2 =
            "schema_version,session_id,timestamp,device,android_version,cpu_cores,engine,model,audio_file," +
                "audio_duration_ms,elapsed_ms,rtf,speed_multiplier,segments,chars,note"
    }

    data class DeviceInfo(
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val androidVersion: String = Build.VERSION.RELEASE,
        val sdkLevel: Int = Build.VERSION.SDK_INT,
        val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
        val maxMemoryMb: Long = Runtime.getRuntime().maxMemory() / 1024 / 1024
    ) {
        override fun toString(): String =
            "$manufacturer $model (Android $androidVersion, SDK $sdkLevel, $cpuCores cores, $maxMemoryMb MB)"
    }

    data class BenchmarkRecord(
        val sessionId: String = "",
        val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()),
        val device: DeviceInfo = DeviceInfo(),
        val engineName: String,
        val modelName: String,
        val audioFile: String,
        val audioDurationMs: Long,
        val elapsedMs: Long,
        val rtf: Float,
        val speedMultiplier: Float,
        val segmentCount: Int,
        val charCount: Int,
        val note: String = "",
        val text: String = ""
    )

    /** CSV에서 모든 레코드 로드 */
    fun loadAll(): List<BenchmarkRecord> {
        val legacy = readCsv(File(context.filesDir, LEGACY_FILENAME))
            ?.lineSequence()
            ?.drop(1)
            ?.mapNotNull(::parseLegacyLine)
            ?.toList()
            .orEmpty()
        val current = readCsv(File(context.filesDir, FILENAME_V2))
            ?.lineSequence()
            ?.drop(1)
            ?.mapNotNull(::parseV2Line)
            ?.toList()
            .orEmpty()
        return (legacy + current).sortedByDescending { it.timestamp }
    }

    /** v1 CSV 라인 파싱 (마지막 text 컬럼은 quoted). */
    private fun parseLegacyLine(line: String): BenchmarkRecord? {
        return try {
            val parts = parseCsvColumns(line) ?: return null
            if (parts.size < 15) return null
            BenchmarkRecord(
                timestamp = parts[0],
                engineName = parts[4],
                modelName = parts[5],
                audioFile = parts[6],
                audioDurationMs = parts[7].toLongOrNull() ?: 0,
                elapsedMs = parts[8].toLongOrNull() ?: 0,
                rtf = parts[9].toFloatOrNull() ?: 0f,
                speedMultiplier = parts[10].toFloatOrNull() ?: 0f,
                segmentCount = parts[11].toIntOrNull() ?: 0,
                charCount = parts[12].toIntOrNull() ?: 0,
                note = parts[13],
                text = parts[14]
            )
        } catch (e: Exception) { null }
    }

    /** v2는 stable sessionId와 성능 지표만 저장하고 transcript 원문을 포함하지 않는다. */
    private fun parseV2Line(line: String): BenchmarkRecord? {
        return try {
            val parts = parseCsvColumns(line) ?: return null
            if (parts.size < 16 || parts[0] != "2") return null
            BenchmarkRecord(
                sessionId = parts[1],
                timestamp = parts[2],
                engineName = parts[6],
                modelName = parts[7],
                audioFile = parts[8],
                audioDurationMs = parts[9].toLongOrNull() ?: 0L,
                elapsedMs = parts[10].toLongOrNull() ?: 0L,
                rtf = parts[11].toFloatOrNull() ?: 0f,
                speedMultiplier = parts[12].toFloatOrNull() ?: 0f,
                segmentCount = parts[13].toIntOrNull() ?: 0,
                charCount = parts[14].toIntOrNull() ?: 0,
                note = parts[15],
                text = ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCsvColumns(line: String): List<String>? {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (inQuotes) {
                if (ch == '"' && index + 1 < line.length && line[index + 1] == '"') {
                    current.append('"')
                    index += 2
                    continue
                }
                if (ch == '"') inQuotes = false else current.append(ch)
            } else when (ch) {
                '"' -> inQuotes = true
                ',' -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        if (inQuotes) return null
        parts.add(current.toString())
        return parts
    }

    @Synchronized
    fun appendResult(
        result: TranscriptionResult,
        audioFile: String,
        modelName: String,
        note: String = "",
        sessionId: String = ""
    ): BenchmarkRecord {
        val record = BenchmarkRecord(
            sessionId = sessionId.ifBlank { "legacy_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}" },
            engineName = result.engineName,
            modelName = modelName,
            audioFile = File(audioFile).name,
            audioDurationMs = result.audioDurationMs,
            elapsedMs = result.elapsedMs,
            rtf = result.rtf,
            speedMultiplier = result.speedMultiplier,
            segmentCount = result.segments.size,
            charCount = result.text.length,
            note = note,
            text = ""
        )
        val csvFile = File(context.filesDir, FILENAME_V2)
        csvFile.parentFile?.mkdirs()
        val previous = readCsv(csvFile).orEmpty()
        val isNew = previous.isBlank()
        val next = buildString {
            if (isNew) appendLine(CSV_HEADER_V2) else append(previous)
            if (isNotEmpty() && last() != '\n') append('\n')
            appendLine(toCsvLineV2(record))
        }
        val atomic = AtomicFile(csvFile)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(next.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            throw error
        }
        AppLog.i(TAG, "결과 저장: ${csvFile.absolutePath}")
        return record
    }

    /**
     * v2는 sessionId로 삭제한다. v1에는 sessionId가 없으므로 이전 형식에 한해
     * 오디오·모델·note·전체 텍스트가 일치하는 가장 최근 한 행을 삭제한다.
     */
    @Synchronized
    fun deleteMatchingResult(
        sessionId: String,
        audioFile: String,
        modelName: String,
        note: String,
        text: String
    ): Boolean {
        if (sessionId.isNotBlank() && deleteV2BySessionId(sessionId)) return true

        val csvFile = File(context.filesDir, LEGACY_FILENAME)
        val lines = readCsv(csvFile)?.lines()?.toMutableList() ?: return false
        val targetIndex = lines.indices.reversed().firstOrNull { index ->
            if (index == 0) return@firstOrNull false
            val record = parseLegacyLine(lines[index]) ?: return@firstOrNull false
            record.audioFile == File(audioFile).name &&
                record.modelName == modelName &&
                record.note == note &&
                record.text == text
        } ?: return false

        lines.removeAt(targetIndex)
        val next = lines.joinToString(separator = "\n", postfix = "\n")
        val atomic = AtomicFile(csvFile)
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(next.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            true
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            AppLog.e(TAG, "CSV 결과 삭제 실패", error)
            false
        }
    }

    private fun deleteV2BySessionId(sessionId: String): Boolean {
        val csvFile = File(context.filesDir, FILENAME_V2)
        val lines = readCsv(csvFile)?.lines()?.toMutableList() ?: return false
        val targetIndex = lines.indices.reversed().firstOrNull { index ->
            index > 0 && parseV2Line(lines[index])?.sessionId == sessionId
        } ?: return false
        lines.removeAt(targetIndex)
        return writeCsv(csvFile, lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun toCsvLineV2(r: BenchmarkRecord): String {
        val safe = { s: String -> "\"${s.replace("\"", "\"\"").replace(Regex("[\\r\\n]+"), " ")}\"" }
        return listOf(
            "2",
            safe(r.sessionId),
            r.timestamp,
            "${r.device.manufacturer}/${r.device.model}",
            r.device.androidVersion,
            r.device.cpuCores.toString(),
            r.engineName,
            r.modelName,
            r.audioFile,
            r.audioDurationMs.toString(),
            r.elapsedMs.toString(),
            "%.3f".format(Locale.US, r.rtf),
            "%.2f".format(Locale.US, r.speedMultiplier),
            r.segmentCount.toString(),
            r.charCount.toString(),
            safe(r.note)
        ).joinToString(",")
    }

    private fun readCsv(file: File): String? = try {
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (error: Exception) {
        AppLog.e(TAG, "CSV 로드 실패: ${file.name}", error)
        null
    }

    private fun writeCsv(file: File, content: String): Boolean {
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            true
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            AppLog.e(TAG, "CSV 저장 실패: ${file.name}", error)
            false
        }
    }

    fun formatReport(record: BenchmarkRecord): String = buildString {
        appendLine("═══ STT 벤치마크 결과 ═══")
        appendLine("시간: ${record.timestamp}")
        appendLine("기기: ${record.device}")
        appendLine("엔진: ${record.engineName} (${record.modelName})")
        appendLine("오디오: ${record.audioFile}")
        appendLine()
        appendLine("── 성능 지표 ──")
        appendLine("오디오 길이: ${formatMs(record.audioDurationMs)}")
        appendLine("처리 시간:   ${formatMs(record.elapsedMs)}")
        appendLine("RTF:         %.3f (1.0 미만 = 실시간보다 빠름)".format(Locale.US, record.rtf))
        appendLine("속도 배수:   %.2fx".format(Locale.US, record.speedMultiplier))
        appendLine("세그먼트:    ${record.segmentCount}개")
        appendLine("글자 수:     ${record.charCount}자")
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000.0
        return "%d분 %.1f초".format(Locale.US, (totalSec / 60).toInt(), totalSec % 60)
    }
}
