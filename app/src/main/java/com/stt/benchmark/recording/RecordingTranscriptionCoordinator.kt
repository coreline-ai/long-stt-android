package com.stt.benchmark.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.service.TranscriptionService
import java.io.File

/** 녹음 그룹을 기존 단일 파일 [TranscriptionService] 호출로만 직렬 실행한다. */
class RecordingTranscriptionCoordinator(
    private val recordingStore: RecordingSessionStore,
    private val registrar: RecordingMediaRegistrar,
    private val groupStore: RecordingTranscriptionGroupStore,
    private val launcher: ChildLauncher,
    private val modelReadable: (String) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class ChildLaunchRequest(
        val groupId: String,
        val recordingSessionId: String,
        val mediaId: String,
        val sequence: Int,
        val modelPath: String,
        val audioPath: String,
        val note: String,
    )

    /** A child claimed in the group checkpoint, ready for a foreground-service handoff. */
    data class PreparedLaunch(
        val group: RecordingTranscriptionGroupStore.Group,
        val request: ChildLaunchRequest,
    )

    fun interface ChildLauncher {
        fun launch(request: ChildLaunchRequest)
    }

    sealed interface StartResult {
        data class Started(val group: RecordingTranscriptionGroupStore.Group) : StartResult
        data class PartialConfirmationRequired(
            val readySequences: List<Int>,
            val excludedSequences: List<Int>,
            val reason: String,
        ) : StartResult
        data class ModelRequired(val group: RecordingTranscriptionGroupStore.Group) : StartResult
        data class Blocked(val reason: String) : StartResult
    }

    data class ChildEvent(
        val groupId: String,
        val mediaId: String,
        val sttSessionId: String,
        val status: TranscriptionSessionStore.Status,
        val detail: String,
    )

    sealed interface EventResult {
        data object NotHandled : EventResult
        data class Updated(
            val group: RecordingTranscriptionGroupStore.Group,
            val launchNext: Boolean,
        ) : EventResult
    }

    constructor(context: Context) : this(
        recordingStore = RecordingSessionStore(context),
        registrar = RecordingMediaRegistrar(context),
        groupStore = RecordingTranscriptionGroupStore(context),
        launcher = ChildLauncher { request ->
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java).apply {
                    action = TranscriptionService.ACTION_START
                    putExtra(TranscriptionService.EXTRA_MODEL_PATH, request.modelPath)
                    putExtra(TranscriptionService.EXTRA_AUDIO_PATH, request.audioPath)
                    putExtra(TranscriptionService.EXTRA_NOTE, request.note)
                    putExtra(TranscriptionService.EXTRA_RECORDING_SESSION_ID, request.recordingSessionId)
                    putExtra(TranscriptionService.EXTRA_RECORDING_GROUP_ID, request.groupId)
                    putExtra(TranscriptionService.EXTRA_MEDIA_ID, request.mediaId)
                    putExtra(TranscriptionService.EXTRA_RECORDING_SEQUENCE, request.sequence)
                },
            )
        },
        modelReadable = { path ->
            kotlin.runCatching {
                val root = context.filesDir.canonicalFile
                val model = File(path).canonicalFile
                model.isFile && model.canRead() && model.extension.equals("bin", true) &&
                    model.path.startsWith(root.path + File.separator)
            }.getOrDefault(false)
        },
    )

    @Synchronized
    fun start(recordingSessionId: String, modelPath: String, allowPartial: Boolean): StartResult {
        val active = groupStore.latestActive()
        if (active != null) return StartResult.Blocked("이미 녹음 그룹 전사가 진행 중입니다.")
        val session = recordingStore.load(recordingSessionId)
            ?: return StartResult.Blocked("녹음 세션을 찾을 수 없습니다.")
        val registration = registrar.register(session)
        if (registration is RecordingMediaRegistrar.RegistrationResult.Blocked) {
            return StartResult.Blocked(registration.reason)
        }
        val isPartial = registration is RecordingMediaRegistrar.RegistrationResult.Partial
        val excluded = (registration as? RecordingMediaRegistrar.RegistrationResult.Partial)
            ?.excludedSequences.orEmpty()
        if (isPartial && !allowPartial) {
            return StartResult.PartialConfirmationRequired(
                readySequences = registration.entries.map { it.sequence },
                excludedSequences = excluded,
                reason = (registration as RecordingMediaRegistrar.RegistrationResult.Partial).reason,
            )
        }
        val entries = registration.entries.sortedBy { it.sequence }
        if (entries.isEmpty()) return StartResult.Blocked("전사 가능한 READY 청크가 없습니다.")

        val now = nowMs()
        val baseGroup = RecordingTranscriptionGroupStore.Group(
            groupId = groupStore.newGroupId(now),
            recordingSessionId = recordingSessionId,
            modelPath = modelPath,
            status = if (modelReadable(modelPath)) {
                RecordingTranscriptionGroupStore.GroupStatus.READY
            } else {
                RecordingTranscriptionGroupStore.GroupStatus.MODEL_REQUIRED
            },
            isPartial = isPartial,
            excludedSequences = excluded,
            currentChildIndex = 0,
            createdAtMs = now,
            updatedAtMs = now,
            errorMessage = if (modelReadable(modelPath)) "" else "전사 모델을 설치하거나 선택하세요.",
            children = entries.map { entry ->
                RecordingTranscriptionGroupStore.Child(
                    sequence = entry.sequence,
                    mediaId = entry.id,
                    audioPath = entry.path,
                )
            },
        )
        groupStore.save(baseGroup)
        if (baseGroup.status == RecordingTranscriptionGroupStore.GroupStatus.MODEL_REQUIRED) {
            return StartResult.ModelRequired(baseGroup)
        }
        return when (val launched = launchCurrent(baseGroup.groupId)) {
            null -> StartResult.Blocked("첫 녹음 청크 전사를 시작하지 못했습니다.")
            else -> StartResult.Started(launched)
        }
    }

    /** PENDING child에 대해서만 launcher를 호출해 완료 event 중복에 의한 이중 시작을 막는다. */
    @Synchronized
    fun launchCurrent(groupId: String): RecordingTranscriptionGroupStore.Group? {
        val prepared = prepareCurrentLaunch(groupId) ?: return groupStore.load(groupId)
        return try {
            launcher.launch(prepared.request)
            prepared.group
        } catch (error: Throwable) {
            val child = prepared.group.children[prepared.group.currentChildIndex]
            prepared.group.copy(
                status = RecordingTranscriptionGroupStore.GroupStatus.FAILED,
                updatedAtMs = nowMs(),
                errorMessage = "전사 서비스를 시작하지 못했습니다: ${error.javaClass.simpleName}",
                children = prepared.group.children.replace(
                    prepared.group.currentChildIndex,
                    child.copy(
                        status = RecordingTranscriptionGroupStore.ChildStatus.FAILED,
                        errorMessage = error.javaClass.simpleName,
                    ),
                ),
            ).also(groupStore::save)
        }
    }

    /**
     * Mark the current child STARTING without invoking Android from the caller.  The active
     * [TranscriptionService] uses this during terminal handoff, so a background ViewModel never
     * has to start a second foreground service after the previous child has ended.
     */
    @Synchronized
    fun prepareCurrentLaunch(groupId: String): PreparedLaunch? {
        val group = groupStore.load(groupId) ?: return null
        if (group.isTerminal) return null
        val child = group.children.getOrNull(group.currentChildIndex) ?: return null
        if (child.status != RecordingTranscriptionGroupStore.ChildStatus.PENDING) return null
        val starting = group.copy(
            status = RecordingTranscriptionGroupStore.GroupStatus.RUNNING,
            updatedAtMs = nowMs(),
            errorMessage = "",
            children = group.children.replace(group.currentChildIndex, child.copy(
                status = RecordingTranscriptionGroupStore.ChildStatus.STARTING,
                errorMessage = "",
            )),
        )
        groupStore.save(starting)
        return PreparedLaunch(
            group = starting,
            request = ChildLaunchRequest(
                groupId = starting.groupId,
                recordingSessionId = starting.recordingSessionId,
                mediaId = child.mediaId,
                sequence = child.sequence,
                modelPath = starting.modelPath,
                audioPath = child.audioPath,
                note = "녹음 ${child.sequence + 1}/${starting.children.size}",
            ),
        )
    }

    @Synchronized
    fun onChildEvent(event: ChildEvent): EventResult {
        if (event.groupId.isBlank() || event.mediaId.isBlank()) return EventResult.NotHandled
        val group = groupStore.load(event.groupId) ?: return EventResult.NotHandled
        val childIndex = group.children.indexOfFirst { it.mediaId == event.mediaId }
        if (childIndex < 0) return EventResult.NotHandled
        val oldChild = group.children[childIndex]
        if (oldChild.status == RecordingTranscriptionGroupStore.ChildStatus.COMPLETED) {
            return EventResult.Updated(group, launchNext = false)
        }

        val mapped = event.status.toChildStatus()
        var updated = group.copy(
            updatedAtMs = nowMs(),
            children = group.children.replace(
                childIndex,
                oldChild.copy(
                    sttSessionId = event.sttSessionId.ifBlank { oldChild.sttSessionId },
                    status = mapped,
                    errorMessage = if (mapped in setOf(
                            RecordingTranscriptionGroupStore.ChildStatus.FAILED,
                            RecordingTranscriptionGroupStore.ChildStatus.CANCELLED,
                            RecordingTranscriptionGroupStore.ChildStatus.INTERRUPTED,
                        )
                    ) event.detail else "",
                ),
            ),
        )
        var launchNext = false
        updated = when (mapped) {
            RecordingTranscriptionGroupStore.ChildStatus.COMPLETED -> {
                if (childIndex != group.currentChildIndex) {
                    updated
                } else if (childIndex == group.children.lastIndex) {
                    updated.copy(
                        status = if (group.isPartial) {
                            RecordingTranscriptionGroupStore.GroupStatus.PARTIAL_COMPLETED
                        } else {
                            RecordingTranscriptionGroupStore.GroupStatus.COMPLETED
                        },
                        errorMessage = "",
                    )
                } else {
                    launchNext = true
                    updated.copy(
                        status = RecordingTranscriptionGroupStore.GroupStatus.RUNNING,
                        currentChildIndex = childIndex + 1,
                        errorMessage = "",
                    )
                }
            }
            RecordingTranscriptionGroupStore.ChildStatus.FAILED -> updated.copy(
                status = RecordingTranscriptionGroupStore.GroupStatus.FAILED,
                errorMessage = event.detail.ifBlank { "녹음 청크 전사 실패" },
            )
            RecordingTranscriptionGroupStore.ChildStatus.CANCELLED -> updated.copy(
                status = RecordingTranscriptionGroupStore.GroupStatus.CANCELLED,
                errorMessage = event.detail,
            )
            RecordingTranscriptionGroupStore.ChildStatus.INTERRUPTED -> updated.copy(
                status = RecordingTranscriptionGroupStore.GroupStatus.INTERRUPTED,
                errorMessage = event.detail,
            )
            else -> updated.copy(status = RecordingTranscriptionGroupStore.GroupStatus.RUNNING)
        }
        groupStore.save(updated)
        return EventResult.Updated(updated, launchNext)
    }

    fun listGroups(): List<RecordingTranscriptionGroupStore.Group> = groupStore.listAll()

    private fun TranscriptionSessionStore.Status.toChildStatus() = when (this) {
        TranscriptionSessionStore.Status.PREPARING,
        TranscriptionSessionStore.Status.RUNNING,
        TranscriptionSessionStore.Status.COOLING,
        -> RecordingTranscriptionGroupStore.ChildStatus.RUNNING
        TranscriptionSessionStore.Status.COMPLETED -> RecordingTranscriptionGroupStore.ChildStatus.COMPLETED
        TranscriptionSessionStore.Status.FAILED -> RecordingTranscriptionGroupStore.ChildStatus.FAILED
        TranscriptionSessionStore.Status.CANCELLED -> RecordingTranscriptionGroupStore.ChildStatus.CANCELLED
        TranscriptionSessionStore.Status.INTERRUPTED -> RecordingTranscriptionGroupStore.ChildStatus.INTERRUPTED
    }

    private fun <T> List<T>.replace(index: Int, value: T): List<T> = mapIndexed { i, item ->
        if (i == index) value else item
    }
}
