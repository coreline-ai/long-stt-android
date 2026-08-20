package com.stt.benchmark.drive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveRestClientTest {
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun clean() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun resumableUploadUsesGoogleSessionUrlAndTextPlainMetadata() = runBlocking {
        val session = FakeConnection(
            url = URL("https://www.googleapis.com/upload/drive/v3/files"),
            responseCode = HttpURLConnection.HTTP_OK,
            responseHeaders = mapOf("Location" to "https://www.googleapis.com/upload/resumable/synthetic"),
        )
        val upload = FakeConnection(
            url = URL("https://www.googleapis.com/upload/resumable/synthetic"),
            responseCode = HttpURLConnection.HTTP_CREATED,
            responseBody = "{\"id\":\"drive_file_synthetic\",\"size\":\"9\"}",
        )
        val connections = ArrayDeque(listOf(session, upload))
        val client = GoogleDriveRestClient { _ -> connections.removeFirst() }
        val file = syntheticFile("합성 전사")

        val uploaded = client.uploadResumable(
            accessToken = "synthetic-token",
            file = file,
            folderId = "drive_folder_synthetic",
            exportId = "export_synthetic",
            artifact = DriveArtifact.TRANSCRIPT,
            onProgress = { _, _ -> },
        )

        assertEquals("drive_file_synthetic", uploaded.id)
        assertEquals("Bearer synthetic-token", session.requestHeaders["Authorization"])
        assertEquals("text/plain", session.requestHeaders["X-Upload-Content-Type"])
        assertEquals("text/plain", upload.requestHeaders["Content-Type"])
        assertTrue(session.requestBody.toString(Charsets.UTF_8.name()).contains("longSttExportId"))
        assertFalse(session.requestBody.toString(Charsets.UTF_8.name()).contains("audioPath"))
        assertFalse(session.requestBody.toString(Charsets.UTF_8.name()).contains("transcriptText"))
    }

    @Test
    fun resumableUploadRejectsUntrustedSessionLocation() {
        val session = FakeConnection(
            url = URL("https://www.googleapis.com/upload/drive/v3/files"),
            responseCode = HttpURLConnection.HTTP_OK,
            responseHeaders = mapOf("Location" to "https://invalid.example/upload"),
        )
        val client = GoogleDriveRestClient { session }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.uploadResumable(
                    accessToken = "synthetic-token",
                    file = syntheticFile("합성"),
                    folderId = "drive_folder_synthetic",
                    exportId = "export_synthetic",
                    artifact = DriveArtifact.TRANSCRIPT,
                    onProgress = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun driveAuthorizationScopeIsRestrictedToAppCreatedFiles() {
        assertEquals(
            "https://www.googleapis.com/auth/drive.file",
            GoogleDriveAuthorizationGateway.DRIVE_FILE_SCOPE_URI,
        )
    }

    private fun syntheticFile(text: String): File = File.createTempFile("long-stt-drive-test", ".txt").also { file ->
        temporaryFiles += file
        file.writeText(text, Charsets.UTF_8)
    }

    private class FakeConnection(
        url: URL,
        private val responseCode: Int,
        private val responseBody: String = "{}",
        private val responseHeaders: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        val requestHeaders = linkedMapOf<String, String>()
        val requestBody = ByteArrayOutputStream()

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getResponseCode(): Int = responseCode

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))

        override fun getOutputStream(): OutputStream = requestBody

        override fun getHeaderField(name: String?): String? = name?.let(responseHeaders::get)

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }
    }
}
