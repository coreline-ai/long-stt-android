package com.stt.benchmark.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class DriveRequestPhase { QUERY, FOLDER, SESSION, UPLOAD }

internal class DriveHttpException(
    val statusCode: Int,
    val phase: DriveRequestPhase,
) : IOException("Drive request failed: $phase/$statusCode")

internal data class DriveRemoteFile(
    val id: String,
    val sizeBytes: Long = 0L,
)

/** 최소 Drive v3 REST 호출. 응답 본문·token·파일 경로를 기록하지 않는다. */
internal class GoogleDriveRestClient(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    suspend fun ensureRootFolder(accessToken: String): DriveRemoteFile = withContext(Dispatchers.IO) {
        findByQuery(accessToken, rootFolderQuery()) ?: createFolder(
            accessToken = accessToken,
            name = ROOT_FOLDER_NAME,
            parentId = null,
            properties = mapOf(ROOT_PROPERTY_KEY to ROOT_PROPERTY_VALUE),
        )
    }

    suspend fun ensureExportFolder(
        accessToken: String,
        rootFolderId: String,
        exportId: String,
        createdAtMs: Long,
    ): DriveRemoteFile = withContext(Dispatchers.IO) {
        require(exportId.matches(EXPORT_ID_REGEX)) { "invalid Drive export" }
        findByQuery(accessToken, exportFolderQuery(rootFolderId, exportId)) ?: createFolder(
            accessToken = accessToken,
            name = exportFolderName(createdAtMs),
            parentId = rootFolderId,
            properties = mapOf(EXPORT_PROPERTY_KEY to exportId),
        )
    }

    suspend fun findArtifact(
        accessToken: String,
        exportId: String,
        artifact: DriveArtifact,
        folderId: String,
    ): DriveRemoteFile? = withContext(Dispatchers.IO) {
        findByQuery(accessToken, artifactQuery(exportId, artifact, folderId))
    }

    suspend fun uploadResumable(
        accessToken: String,
        file: File,
        folderId: String,
        exportId: String,
        artifact: DriveArtifact,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): DriveRemoteFile = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "Drive export file is unavailable" }
        require(exportId.matches(EXPORT_ID_REGEX)) { "invalid Drive export" }
        val totalBytes = file.length()
        val sessionUrl = createUploadSession(
            accessToken = accessToken,
            folderId = folderId,
            exportId = exportId,
            artifact = artifact,
            totalBytes = totalBytes,
        )
        uploadFile(
            sessionUrl = sessionUrl,
            accessToken = accessToken,
            file = file,
            totalBytes = totalBytes,
            onProgress = onProgress,
        )
    }

    private fun findByQuery(accessToken: String, query: String): DriveRemoteFile? {
        val url = URL("$FILES_ENDPOINT?" + parameters(
            "q" to query,
            "spaces" to "drive",
            "pageSize" to "1",
            "fields" to "files(id,size)",
        ))
        val connection = connection(url, "GET", accessToken)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpException(code, DriveRequestPhase.QUERY)
            val array = JSONObject(connection.readResponse()).optJSONArray("files")
            array?.optJSONObject(0)?.toRemoteFile()
        } finally {
            connection.disconnect()
        }
    }

    private fun createFolder(
        accessToken: String,
        name: String,
        parentId: String?,
        properties: Map<String, String>,
    ): DriveRemoteFile {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            if (parentId != null) put("parents", listOf(parentId))
            put("appProperties", JSONObject(properties))
        }
        val connection = connection(URL(FILES_ENDPOINT), "POST", accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        return try {
            connection.outputStream.use { it.write(metadata.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpException(code, DriveRequestPhase.FOLDER)
            JSONObject(connection.readResponse()).toRemoteFile()
                ?: throw IOException("Drive folder response was empty")
        } finally {
            connection.disconnect()
        }
    }

    private fun createUploadSession(
        accessToken: String,
        folderId: String,
        exportId: String,
        artifact: DriveArtifact,
        totalBytes: Long,
    ): URL {
        val metadata = JSONObject().apply {
            put("name", artifact.fileName())
            put("mimeType", DriveExportFileFactory.MIME_TYPE)
            put("parents", listOf(folderId))
            put("appProperties", JSONObject().apply {
                put(EXPORT_PROPERTY_KEY, exportId)
                put(ARTIFACT_PROPERTY_KEY, artifact.name)
            })
        }
        val connection = connection(
            URL("$UPLOAD_ENDPOINT?" + parameters(
                "uploadType" to "resumable",
                "fields" to "id,size",
            )),
            "POST",
            accessToken,
        ).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", DriveExportFileFactory.MIME_TYPE)
            setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
        }
        return try {
            connection.outputStream.use { it.write(metadata.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpException(code, DriveRequestPhase.SESSION)
            val location = connection.getHeaderField("Location").orEmpty()
            require(location.startsWith("https://www.googleapis.com/")) { "invalid Drive upload session" }
            URL(location)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadFile(
        sessionUrl: URL,
        accessToken: String,
        file: File,
        totalBytes: Long,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): DriveRemoteFile {
        var offset = 0L
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        BufferedInputStream(file.inputStream()).use { input ->
            while (offset < totalBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), totalBytes - offset).toInt())
                if (count <= 0) throw IOException("Drive export file changed during upload")
                val end = offset + count - 1L
                val connection = connection(sessionUrl, "PUT", accessToken).apply {
                    doOutput = true
                    setFixedLengthStreamingMode(count)
                    setRequestProperty("Content-Type", DriveExportFileFactory.MIME_TYPE)
                    setRequestProperty("Content-Range", "bytes $offset-$end/$totalBytes")
                }
                try {
                    connection.outputStream.use { it.write(buffer, 0, count) }
                    when (val code = connection.responseCode) {
                        HTTP_RESUME_INCOMPLETE -> {
                            offset += count
                            onProgress(offset, totalBytes)
                        }

                        HttpURLConnection.HTTP_OK,
                        HttpURLConnection.HTTP_CREATED,
                        -> {
                            return JSONObject(connection.readResponse()).toRemoteFile()
                                ?: throw IOException("Drive upload response was empty")
                        }

                        else -> throw DriveHttpException(code, DriveRequestPhase.UPLOAD)
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
        throw IOException("Drive upload ended without completion")
    }

    private fun connection(url: URL, method: String, accessToken: String): HttpURLConnection =
        openConnection(url).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.readResponse(): String = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun JSONObject.toRemoteFile(): DriveRemoteFile? = optString("id")
        .takeIf(String::isNotBlank)
        ?.let { DriveRemoteFile(id = it, sizeBytes = optLong("size", 0L).coerceAtLeast(0L)) }

    private fun rootFolderQuery(): String =
        "appProperties has { key='$ROOT_PROPERTY_KEY' and value='$ROOT_PROPERTY_VALUE' } and " +
            "mimeType='$FOLDER_MIME_TYPE' and trashed=false"

    private fun exportFolderQuery(rootFolderId: String, exportId: String): String =
        "appProperties has { key='$EXPORT_PROPERTY_KEY' and value='$exportId' } and " +
            "'$rootFolderId' in parents and mimeType='$FOLDER_MIME_TYPE' and trashed=false"

    private fun artifactQuery(exportId: String, artifact: DriveArtifact, folderId: String): String =
        "appProperties has { key='$EXPORT_PROPERTY_KEY' and value='$exportId' } and " +
            "appProperties has { key='$ARTIFACT_PROPERTY_KEY' and value='${artifact.name}' } and " +
            "'$folderId' in parents and trashed=false"

    private fun parameters(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, UTF_8)}=${URLEncoder.encode(value, UTF_8)}"
    }

    private fun exportFolderName(createdAtMs: Long): String = FOLDER_NAME_FORMAT.format(
        Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()),
    )

    companion object {
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val ROOT_FOLDER_NAME = "Long STT"
        private const val ROOT_PROPERTY_KEY = "longSttRoot"
        private const val ROOT_PROPERTY_VALUE = "1"
        private const val EXPORT_PROPERTY_KEY = "longSttExportId"
        private const val ARTIFACT_PROPERTY_KEY = "longSttArtifact"
        private const val UTF_8 = "UTF-8"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val CHUNK_SIZE_BYTES = 256 * 1024
        private const val HTTP_RESUME_INCOMPLETE = 308
        private val EXPORT_ID_REGEX = Regex("[A-Za-z0-9_-]+")
        private val FOLDER_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
    }
}
