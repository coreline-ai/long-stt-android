package com.stt.benchmark.data

import java.io.File
import java.io.InputStream

/** SAF 입력을 전체 메모리 적재 없이 `.part → final` 파일로 완성한다. */
internal object AudioImportFileWriter {
    fun copy(input: InputStream, pendingFile: File, finalFile: File): Long {
        require(pendingFile.parentFile?.canonicalFile == finalFile.parentFile?.canonicalFile) {
            "pending and final audio must share a directory"
        }
        check(!finalFile.exists()) { "final audio already exists" }
        pendingFile.delete()
        return try {
            pendingFile.outputStream().buffered().use { output -> input.copyTo(output) }
            val size = pendingFile.length()
            check(size > 0L) { "imported audio is empty" }
            check(pendingFile.renameTo(finalFile)) { "audio finalization failed" }
            size
        } catch (error: Throwable) {
            pendingFile.delete()
            throw error
        }
    }
}
