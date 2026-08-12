package com.stt.benchmark.export

import android.content.Context
import android.net.Uri
import com.stt.benchmark.data.TranscriptSourceDocument
import java.io.IOException
import java.io.OutputStream

/** Storage Access Framework가 반환한 URI에만 전사 TXT를 기록하는 경계. */
class TranscriptDocumentSaver(
    private val openOutputStream: (Uri) -> OutputStream?,
) {
    constructor(context: Context) : this(
        openOutputStream = { uri -> context.contentResolver.openOutputStream(uri, "wt") },
    )

    fun save(uri: Uri, document: TranscriptSourceDocument) {
        require(uri.scheme == "content") { "document URI must use content scheme" }
        val output = openOutputStream(uri) ?: throw IOException("document output is unavailable")
        TranscriptExportWriter.write(document, output)
    }
}
