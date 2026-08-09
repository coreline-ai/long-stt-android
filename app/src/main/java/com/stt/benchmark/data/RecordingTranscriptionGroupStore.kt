package com.stt.benchmark.data

import android.content.Context
import android.util.AtomicFile
import com.stt.benchmark.recording.RecordingStateReducer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 녹음 한 세션과 순서가 있는 단일 파일 STT child들의 부모 checkpoint. */
class RecordingTranscriptionGroupStore(context: Context) {
    enum class GroupStatus {
        READY,
        RUNNING,
        COMPLETED,
        PARTIAL_COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED,
        MODEL_REQUIRED,
    }

    enum class ChildStatus { PENDING, STARTING, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

    data class Child(
        val sequence: Int,
        val mediaId: String,
        val audioPath: String,
        val sttSessionId: String = "",
        val status: ChildStatus = ChildStatus.PENDING,
        val errorMessage: String = "",
    )

    data class Group(
        val groupId: String,
        val recordingSessionId: String,
        val modelPath: String,
        val status: GroupStatus,
        val isPartial: Boolean,
        val excludedSequences: List<Int>,
        val currentChildIndex: Int,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val errorMessage: String = "",
        val children: List<Child>,
    ) {
        val completedChildren: Int get() = children.count { it.status == ChildStatus.COMPLETED }
        val progress: Float get() = if (children.isEmpty()) 0f else completedChildren.toFloat() / children.size
        val isTerminal: Boolean get() = status in TERMINAL_STATUSES
    }

    private val groupsDir = File(context.filesDir, GROUPS_DIR).apply { mkdirs() }

    fun newGroupId(nowMs: Long = System.currentTimeMillis()): String =
        "recording_stt_${nowMs}_${UUID.randomUUID().toString().take(8)}"

    @Synchronized
    fun save(group: Group) {
        validate(group)
        val atomic = AtomicFile(groupFile(group.groupId))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(toJson(group).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    fun load(groupId: String): Group? {
        if (!GROUP_ID_REGEX.matches(groupId)) return null
        return try {
            val text = AtomicFile(groupFile(groupId)).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            fromJson(JSONObject(text)).also(::validate)
        } catch (_: Exception) {
            null
        }
    }

    fun listAll(): List<Group> = groupsDir.listFiles().orEmpty()
        .mapNotNull { file ->
            when {
                file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                file.name.endsWith(".json.bak") -> file.name.removeSuffix(".json.bak")
                else -> null
            }?.let { it to file.lastModified() }
        }
        .distinctBy { it.first }
        .sortedByDescending { it.second }
        .mapNotNull { load(it.first) }

    fun latestActive(): Group? = listAll().firstOrNull { !it.isTerminal }

    /** 새 Application process에서는 그룹을 자동 실행하지 않고 사용자 확인 가능한 중단 상태로 고정한다. */
    @Synchronized
    fun reconcileAfterProcessDeath(nowMs: Long = System.currentTimeMillis()): List<Group> =
        listAll().mapNotNull { group ->
            // 이 process가 뜬 뒤 생성/갱신된 그룹은 startup reconciliation 대상이 아니다.
            if (group.isTerminal || group.updatedAtMs >= nowMs) return@mapNotNull null
            val current = group.children[group.currentChildIndex]
            val reconciled = group.copy(
                status = GroupStatus.INTERRUPTED,
                updatedAtMs = maxOf(nowMs, group.updatedAtMs),
                errorMessage = "앱 프로세스 중단 뒤 현재 child 상태를 확인해야 합니다.",
                children = group.children.mapIndexed { index, child ->
                    if (index == group.currentChildIndex && child.status in setOf(
                            ChildStatus.STARTING,
                            ChildStatus.RUNNING,
                        )
                    ) {
                        current.copy(
                            status = ChildStatus.INTERRUPTED,
                            errorMessage = "프로세스 중단",
                        )
                    } else {
                        child
                    }
                },
            )
            save(reconciled)
            reconciled
        }

    fun groupsForRecording(recordingSessionId: String): List<Group> = listAll()
        .filter { it.recordingSessionId == recordingSessionId }

    fun countForMedia(mediaId: String): Int = listAll().count { group ->
        group.children.any { it.mediaId == mediaId }
    }

    @Synchronized
    fun delete(groupId: String): Boolean {
        if (!GROUP_ID_REGEX.matches(groupId)) return false
        return runCatching { AtomicFile(groupFile(groupId)).delete() }.isSuccess
    }

    private fun validate(group: Group) {
        require(GROUP_ID_REGEX.matches(group.groupId)) { "잘못된 녹음 전사 groupId" }
        require(RecordingStateReducer.isValidSessionId(group.recordingSessionId)) { "잘못된 녹음 sessionId" }
        require(group.createdAtMs > 0L && group.updatedAtMs >= group.createdAtMs) { "잘못된 그룹 timestamp" }
        require(group.children.isNotEmpty()) { "그룹에는 child가 필요합니다" }
        require(group.currentChildIndex in group.children.indices) { "현재 child index가 범위를 벗어났습니다" }
        require(group.children.map { it.sequence }.distinct().size == group.children.size) { "중복 child sequence" }
        require(group.children.map { it.mediaId }.distinct().size == group.children.size) { "중복 child mediaId" }
        require(group.children.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "child는 녹음 sequence 오름차순이어야 합니다"
        }
        group.children.forEach { child ->
            require(child.sequence >= 0 && child.mediaId.isNotBlank() && child.audioPath.isNotBlank()) {
                "불완전한 child 메타데이터"
            }
        }
        if (group.status in setOf(GroupStatus.COMPLETED, GroupStatus.PARTIAL_COMPLETED)) {
            require(group.children.all { it.status == ChildStatus.COMPLETED }) { "완료 그룹에 미완료 child가 있습니다" }
        }
        if (group.status == GroupStatus.PARTIAL_COMPLETED) require(group.isPartial) {
            "partial 완료 그룹에 partial 표기가 없습니다"
        }
    }

    private fun groupFile(groupId: String) = File(groupsDir, "$groupId.json")

    private fun toJson(group: Group): JSONObject = JSONObject().apply {
        put("version", CURRENT_SCHEMA_VERSION)
        put("groupId", group.groupId)
        put("recordingSessionId", group.recordingSessionId)
        put("modelPath", group.modelPath)
        put("status", group.status.name)
        put("isPartial", group.isPartial)
        put("excludedSequences", JSONArray(group.excludedSequences))
        put("currentChildIndex", group.currentChildIndex)
        put("createdAtMs", group.createdAtMs)
        put("updatedAtMs", group.updatedAtMs)
        put("errorMessage", group.errorMessage)
        put("children", JSONArray().apply {
            group.children.forEach { child ->
                put(JSONObject().apply {
                    put("sequence", child.sequence)
                    put("mediaId", child.mediaId)
                    put("audioPath", child.audioPath)
                    put("sttSessionId", child.sttSessionId)
                    put("status", child.status.name)
                    put("errorMessage", child.errorMessage)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Group {
        require(json.optInt("version", 1) == CURRENT_SCHEMA_VERSION) { "지원하지 않는 그룹 schema" }
        val children = json.getJSONArray("children")
        val excluded = json.optJSONArray("excludedSequences") ?: JSONArray()
        return Group(
            groupId = json.getString("groupId"),
            recordingSessionId = json.getString("recordingSessionId"),
            modelPath = json.optString("modelPath"),
            status = GroupStatus.valueOf(json.getString("status")),
            isPartial = json.optBoolean("isPartial"),
            excludedSequences = List(excluded.length()) { excluded.getInt(it) },
            currentChildIndex = json.getInt("currentChildIndex"),
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
            errorMessage = json.optString("errorMessage"),
            children = List(children.length()) { index ->
                val child = children.getJSONObject(index)
                Child(
                    sequence = child.getInt("sequence"),
                    mediaId = child.getString("mediaId"),
                    audioPath = child.getString("audioPath"),
                    sttSessionId = child.optString("sttSessionId"),
                    status = ChildStatus.valueOf(child.getString("status")),
                    errorMessage = child.optString("errorMessage"),
                )
            },
        )
    }

    companion object {
        val TERMINAL_STATUSES = setOf(
            GroupStatus.COMPLETED,
            GroupStatus.PARTIAL_COMPLETED,
            GroupStatus.FAILED,
            GroupStatus.CANCELLED,
            GroupStatus.INTERRUPTED,
            GroupStatus.MODEL_REQUIRED,
        )
        private const val GROUPS_DIR = "recording_transcription_groups"
        private const val CURRENT_SCHEMA_VERSION = 1
        private val GROUP_ID_REGEX = Regex("[A-Za-z0-9_-]+")
    }
}
