package com.stt.benchmark.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 사용자가 앱에 가져온 오디오와 설치된 모델의 선택 상태를 보관한다.
 * 실제 큰 파일은 개별 파일로 유지하고, 이 파일은 목록 메타데이터만 원자적으로 저장한다.
 */
class MediaLibraryStore(private val context: Context) {

    data class AudioEntry(
        val id: String,
        val path: String,
        val displayName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val importedAtMs: Long,
        val lastSelectedAtMs: Long = 0L
    )

    data class ModelEntry(
        val path: String,
        val displayName: String,
        val sizeBytes: Long,
        val installedAtMs: Long
    )

    private data class Index(
        val selectedAudioPath: String = "",
        val selectedModelPath: String = "",
        val audios: List<AudioEntry> = emptyList(),
        /** 실제 파일은 남기되 목록에서 숨긴 경로. 레거시 스캔으로 다시 나타나는 것을 막는다. */
        val hiddenAudioPaths: Set<String> = emptySet()
    )

    private val rootDir = context.filesDir.canonicalFile
    private val indexFile = File(rootDir, INDEX_FILENAME)
    private val modelsDir = File(rootDir, MODELS_DIR).apply { mkdirs() }

    @Synchronized
    fun listAudios(): List<AudioEntry> {
        val index = loadIndex()
        val refreshed = index.audios
            .filter { File(it.path).isFile }
            .map { entry ->
                val file = File(entry.path)
                entry.copy(sizeBytes = file.length())
            }
        val knownPaths = refreshed.mapTo(mutableSetOf()) { it.path }
        val legacy = rootDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && !file.name.startsWith('.') &&
                    file.extension.lowercase() in AUDIO_EXTENSIONS &&
                    file.absolutePath !in index.hiddenAudioPaths &&
                    knownPaths.add(file.absolutePath)
            }
            .map { file ->
                AudioEntry(
                    id = UUID.randomUUID().toString(),
                    path = file.absolutePath,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    durationMs = 0L,
                    importedAtMs = file.lastModified()
                )
            }
        val merged = refreshed + legacy
        if (merged != index.audios) saveIndex(index.copy(audios = merged))
        return merged.sortedWith(compareByDescending<AudioEntry> { it.lastSelectedAtMs }.thenByDescending { it.importedAtMs })
    }

    @Synchronized
    fun selectedAudioPath(): String = loadIndex().selectedAudioPath
        .takeIf { File(it).isFile && isManagedFile(File(it)) }
        .orEmpty()

    @Synchronized
    fun selectedModelPath(): String = loadIndex().selectedModelPath
        .takeIf { File(it).isFile && isManagedFile(File(it)) }
        .orEmpty()

    @Synchronized
    fun registerAudio(file: File, displayName: String, durationMs: Long = 0L): AudioEntry {
        require(isManagedFile(file) && file.isFile) { "앱 내부 오디오 파일만 등록할 수 있습니다" }
        val now = System.currentTimeMillis()
        val index = loadIndex()
        val existing = index.audios.firstOrNull { it.path == file.absolutePath }
        val entry = existing?.copy(
            displayName = displayName.ifBlank { existing.displayName },
            sizeBytes = file.length(),
            durationMs = durationMs.takeIf { it > 0L } ?: existing.durationMs
        ) ?: AudioEntry(
            id = UUID.randomUUID().toString(),
            path = file.absolutePath,
            displayName = displayName.ifBlank { file.name },
            sizeBytes = file.length(),
            durationMs = durationMs.coerceAtLeast(0L),
            importedAtMs = now
        )
        val next = index.audios.filterNot { it.path == file.absolutePath } + entry
        saveIndex(index.copy(audios = next, hiddenAudioPaths = index.hiddenAudioPaths - file.absolutePath))
        return entry
    }

    @Synchronized
    fun selectAudio(path: String) {
        val file = File(path)
        require(isManagedFile(file) && file.isFile) { "선택할 오디오 파일이 없습니다" }
        val now = System.currentTimeMillis()
        val index = loadIndex()
        val indexed = if (index.audios.any { it.path == file.absolutePath }) {
            index
        } else {
            index.copy(
                audios = index.audios + AudioEntry(
                    id = UUID.randomUUID().toString(),
                    path = file.absolutePath,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    durationMs = 0L,
                    importedAtMs = now
                ),
                hiddenAudioPaths = index.hiddenAudioPaths - file.absolutePath
            )
        }
        val next = indexed.audios.map {
            if (it.path == file.absolutePath) it.copy(lastSelectedAtMs = now, sizeBytes = file.length()) else it
        }
        saveIndex(indexed.copy(selectedAudioPath = file.absolutePath, audios = next))
    }

    /** 목록에서만 숨기며 실제 오디오 파일과 전사 결과는 지우지 않는다. */
    @Synchronized
    fun forgetAudio(path: String) {
        val index = loadIndex()
        saveIndex(
            index.copy(
                selectedAudioPath = index.selectedAudioPath.takeUnless { it == path }.orEmpty(),
                audios = index.audios.filterNot { it.path == path },
                hiddenAudioPaths = index.hiddenAudioPaths + path
            )
        )
    }

    @Synchronized
    fun clearSelectedAudio() {
        val index = loadIndex()
        if (index.selectedAudioPath.isNotBlank()) saveIndex(index.copy(selectedAudioPath = ""))
    }

    /** 호출자는 활성 세션/연결 결과를 확인한 뒤 실제 삭제를 허용해야 한다. */
    @Synchronized
    fun deleteAudioFile(path: String): Boolean {
        val file = File(path)
        if (!isManagedFile(file)) return false
        val deleted = !file.exists() || file.delete()
        if (deleted) forgetAudio(path)
        return deleted
    }

    @Synchronized
    fun selectModel(path: String) {
        val file = File(path)
        require(isManagedFile(file) && file.isFile) { "선택할 모델 파일이 없습니다" }
        val index = loadIndex()
        saveIndex(index.copy(selectedModelPath = file.absolutePath))
    }

    @Synchronized
    fun listInstalledModels(): List<ModelEntry> {
        val files = buildList {
            modelsDir.listFiles()?.filterTo(this) { it.isFile && it.extension.equals("bin", true) }
            // 이전 버전이 filesDir 루트에 저장한 모델도 계속 사용할 수 있어야 한다.
            rootDir.listFiles()?.filterTo(this) {
                it.isFile && it.extension.equals("bin", true) && !it.name.startsWith('.')
            }
        }.distinctBy { it.canonicalPath }

        return files.map { file ->
            ModelEntry(
                path = file.absolutePath,
                displayName = file.name.removeSuffix(".bin"),
                sizeBytes = file.length(),
                installedAtMs = file.lastModified()
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    @Synchronized
    fun deleteModelFile(path: String): Boolean {
        val file = File(path)
        if (!isManagedFile(file) || !file.extension.equals("bin", true)) return false
        if (!file.exists() || file.delete()) {
            val index = loadIndex()
            if (index.selectedModelPath == path) saveIndex(index.copy(selectedModelPath = ""))
            return true
        }
        return false
    }

    private fun isManagedFile(file: File): Boolean = try {
        val canonical = file.canonicalFile
        canonical.path.startsWith(rootDir.path + File.separator)
    } catch (_: Exception) {
        false
    }

    private fun loadIndex(): Index {
        if (!indexFile.exists()) return Index()
        return try {
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            val array = json.optJSONArray("audios") ?: JSONArray()
            Index(
                selectedAudioPath = json.optString("selectedAudioPath"),
                selectedModelPath = json.optString("selectedModelPath"),
                audios = List(array.length()) { i ->
                    val item = array.getJSONObject(i)
                    AudioEntry(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        path = item.getString("path"),
                        displayName = item.optString("displayName"),
                        sizeBytes = item.optLong("sizeBytes"),
                        durationMs = item.optLong("durationMs"),
                        importedAtMs = item.optLong("importedAtMs"),
                        lastSelectedAtMs = item.optLong("lastSelectedAtMs")
                    )
                },
                hiddenAudioPaths = (json.optJSONArray("hiddenAudioPaths") ?: JSONArray()).let { hidden ->
                    buildSet { repeat(hidden.length()) { add(hidden.optString(it)) } }.filter { it.isNotBlank() }.toSet()
                }
            )
        } catch (_: Exception) {
            // 보관함 인덱스 손상은 전사 파일을 삭제하지 않는다. 다음 등록부터 새 인덱스를 만든다.
            Index()
        }
    }

    private fun saveIndex(index: Index) {
        val json = JSONObject().apply {
            put("version", 1)
            put("selectedAudioPath", index.selectedAudioPath)
            put("selectedModelPath", index.selectedModelPath)
            put("hiddenAudioPaths", JSONArray(index.hiddenAudioPaths.toList()))
            put("audios", JSONArray().apply {
                index.audios.forEach { entry ->
                    put(JSONObject().apply {
                        put("id", entry.id)
                        put("path", entry.path)
                        put("displayName", entry.displayName)
                        put("sizeBytes", entry.sizeBytes)
                        put("durationMs", entry.durationMs)
                        put("importedAtMs", entry.importedAtMs)
                        put("lastSelectedAtMs", entry.lastSelectedAtMs)
                    })
                }
            })
        }
        val atomic = AtomicFile(indexFile)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
    }

    private companion object {
        const val INDEX_FILENAME = "media_library.json"
        const val MODELS_DIR = "models"
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "mp4", "ogg", "flac")
    }
}
