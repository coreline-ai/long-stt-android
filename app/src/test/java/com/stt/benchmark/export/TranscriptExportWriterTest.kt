package com.stt.benchmark.export

import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class TranscriptExportWriterTest {
    @Test
    fun utf8ExportPreservesEverySectionWithoutInternalMetadata() {
        val longText = "한글 전체 전사 ".repeat(15_000)
        val document = document(
            sections = listOf(
                section("U0001", "구간 1/2", 0L, 3_723_000L, longText),
                section("U0002", "구간 2/2", 3_723_000L, 7_446_000L, "마지막 문장"),
            ),
        )
        val output = ByteArrayOutputStream()

        TranscriptExportWriter.write(document, output)
        val text = output.toString(Charsets.UTF_8.name())

        assertTrue(text.startsWith("Long STT 전체 전사\n구간 2개"))
        assertTrue(text.contains("[00:00:00–01:02:03] 구간 1/2"))
        assertTrue(text.contains(longText))
        assertTrue(text.endsWith("마지막 문장\n"))
        assertFalse(text.contains(document.source.id))
        assertFalse(text.contains("audioPath"))
        assertFalse(text.contains("modelPath"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size > 200_000)
    }

    @Test
    fun defaultFileNameIsGenericAndDeterministic() {
        val fileName = TranscriptExportWriter.defaultFileName(
            updatedAtMs = 1_786_438_200_000L,
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertTrue(fileName.matches(Regex("LongSTT_전사_\\d{8}_\\d{4}\\.txt")))
        assertFalse(fileName.contains("stt_"))
        assertFalse(fileName.contains("audio"))
    }

    @Test
    fun documentSaverRejectsNonContentAndUnavailableOutput() {
        val document = document()
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptDocumentSaver { ByteArrayOutputStream() }.save(
                android.net.Uri.parse("file:///private/export.txt"),
                document,
            )
        }
        assertThrows(IOException::class.java) {
            TranscriptDocumentSaver { null }.save(
                android.net.Uri.parse("content://documents/export.txt"),
                document,
            )
        }
    }

    @Test
    fun documentSaverWritesToSelectedContentUri() {
        val output = ByteArrayOutputStream()
        val selected = android.net.Uri.parse("content://documents/export.txt")

        TranscriptDocumentSaver { uri ->
            assertEquals(selected, uri)
            output
        }.save(selected, document())

        assertTrue(output.toString(Charsets.UTF_8.name()).contains("안전한 전사"))
    }

    private fun document(
        sections: List<TranscriptSourceSection> = listOf(
            section("U0001", "구간 1/1", 0L, 1_000L, "안전한 전사"),
        ),
    ) = TranscriptSourceDocument(
        source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_private_source"),
        updatedAtMs = 1_786_438_200_000L,
        sections = sections,
    )

    private fun section(key: String, label: String, start: Long, end: Long, text: String) =
        TranscriptSourceSection(key, label, start, end, text)
}
