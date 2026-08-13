package com.stt.benchmark.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibraryRouteUiState(
    val selectedSessionId: String = "",
    val fullTranscriptSessionId: String = "",
    val deleteSessionId: String = "",
    val selectedGroupId: String = "",
    val fullTranscriptGroupId: String = "",
    val fullTranscriptInitialSectionKey: String = "",
    val deleteGroupId: String = "",
    val selectedAudioPath: String = "",
    val deleteAudioPath: String = "",
    val summaryConsentSourceType: String = "",
    val summaryConsentSourceId: String = "",
    val pendingExportSourceType: String = "",
    val pendingExportSourceId: String = "",
    val exportInProgress: Boolean = false,
    val exportStatusMessage: String = "",
)

val LibraryRouteUiState.summaryConsentSource: SummaryRequestPolicy.Source?
    get() = summaryConsentSourceType.takeIf(String::isNotBlank)?.let { type ->
        runCatching {
            SummaryRequestPolicy.Source(
                type = SummarySessionStore.SourceType.valueOf(type),
                id = summaryConsentSourceId,
            )
        }.getOrNull()
    }

val LibraryRouteUiState.pendingExportSource: TranscriptSourceRef?
    get() = pendingExportSourceType.takeIf(String::isNotBlank)?.let { type ->
        runCatching {
            TranscriptSourceRef(
                type = TranscriptSourceType.valueOf(type),
                id = pendingExportSourceId,
            )
        }.getOrNull()
    }

/** 보관함의 선택·확인 dialog 상태만 저장한다. 원본/결과 삭제는 domain ViewModel에 위임한다. */
class LibraryRouteViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(load())
    val uiState: StateFlow<LibraryRouteUiState> = _uiState.asStateFlow()

    fun selectSession(id: String) = update(selectedSessionId = id)
    fun showFullSessionTranscript(id: String, initialSectionKey: String = "") = update(
        fullTranscriptSessionId = id,
        fullTranscriptGroupId = "",
        fullTranscriptInitialSectionKey = initialSectionKey,
    )
    fun requestSessionDeletion(id: String) = update(selectedSessionId = "", deleteSessionId = id)
    fun selectGroup(id: String) = update(selectedGroupId = id)
    fun openCompletedResult(target: CompletedResultTarget) = when (target.type) {
        CompletedResultTarget.Type.TRANSCRIPTION_SESSION -> update(
            selectedSessionId = target.id,
            selectedGroupId = "",
            fullTranscriptSessionId = "",
            fullTranscriptGroupId = "",
            fullTranscriptInitialSectionKey = "",
        )

        CompletedResultTarget.Type.RECORDING_GROUP -> update(
            selectedSessionId = "",
            selectedGroupId = target.id,
            fullTranscriptSessionId = "",
            fullTranscriptGroupId = "",
            fullTranscriptInitialSectionKey = "",
        )
    }
    fun showFullGroupTranscript(id: String, initialSectionKey: String = "") = update(
        fullTranscriptSessionId = "",
        fullTranscriptGroupId = id,
        fullTranscriptInitialSectionKey = initialSectionKey,
    )
    fun openTranscriptCitation(target: CompletedResultTarget, sectionKey: String) = when (target.type) {
        CompletedResultTarget.Type.TRANSCRIPTION_SESSION -> showFullSessionTranscript(target.id, sectionKey)
        CompletedResultTarget.Type.RECORDING_GROUP -> showFullGroupTranscript(target.id, sectionKey)
    }
    fun requestGroupDeletion(id: String) = update(selectedGroupId = "", deleteGroupId = id)
    fun selectAudio(path: String) = update(selectedAudioPath = path)
    fun requestAudioDeletion(path: String) = update(selectedAudioPath = "", deleteAudioPath = path)
    fun requestSummaryConsent(source: SummaryRequestPolicy.Source) = update(
        summaryConsentSourceType = source.type.name,
        summaryConsentSourceId = source.id,
    )
    fun requestTranscriptSave(source: TranscriptSourceRef) = update(
        pendingExportSourceType = source.type.name,
        pendingExportSourceId = source.id,
        exportInProgress = true,
        exportStatusMessage = "저장 위치를 선택하세요.",
    )
    fun beginTranscriptExport(message: String) = update(
        exportInProgress = true,
        exportStatusMessage = message,
    )
    fun finishTranscriptExport(message: String) = update(
        pendingExportSourceType = "",
        pendingExportSourceId = "",
        exportInProgress = false,
        exportStatusMessage = message,
    )

    fun dismissSession() = update(selectedSessionId = "")
    fun dismissFullTranscript() = update(
        fullTranscriptSessionId = "",
        fullTranscriptGroupId = "",
        fullTranscriptInitialSectionKey = "",
    )
    fun dismissSessionDeletion() = update(deleteSessionId = "")
    fun dismissGroup() = update(selectedGroupId = "")
    fun dismissGroupDeletion() = update(deleteGroupId = "")
    fun dismissAudio() = update(selectedAudioPath = "")
    fun dismissAudioDeletion() = update(deleteAudioPath = "")
    fun dismissSummaryConsent() = update(summaryConsentSourceType = "", summaryConsentSourceId = "")
    fun dismissTranscriptExport() = update(
        pendingExportSourceType = "",
        pendingExportSourceId = "",
        exportInProgress = false,
        exportStatusMessage = "",
    )

    private fun load() = LibraryRouteUiState(
        selectedSessionId = savedStateHandle.get<String>(SELECTED_SESSION).orEmpty(),
        fullTranscriptSessionId = savedStateHandle.get<String>(FULL_TRANSCRIPT_SESSION).orEmpty(),
        deleteSessionId = savedStateHandle.get<String>(DELETE_SESSION).orEmpty(),
        selectedGroupId = savedStateHandle.get<String>(SELECTED_GROUP).orEmpty(),
        fullTranscriptGroupId = savedStateHandle.get<String>(FULL_TRANSCRIPT_GROUP).orEmpty(),
        fullTranscriptInitialSectionKey = savedStateHandle.get<String>(FULL_TRANSCRIPT_INITIAL_SECTION).orEmpty(),
        deleteGroupId = savedStateHandle.get<String>(DELETE_GROUP).orEmpty(),
        selectedAudioPath = savedStateHandle.get<String>(SELECTED_AUDIO).orEmpty(),
        deleteAudioPath = savedStateHandle.get<String>(DELETE_AUDIO).orEmpty(),
        summaryConsentSourceType = savedStateHandle.get<String>(SUMMARY_CONSENT_TYPE).orEmpty(),
        summaryConsentSourceId = savedStateHandle.get<String>(SUMMARY_CONSENT_ID).orEmpty(),
        pendingExportSourceType = savedStateHandle.get<String>(PENDING_EXPORT_TYPE).orEmpty(),
        pendingExportSourceId = savedStateHandle.get<String>(PENDING_EXPORT_ID).orEmpty(),
    )

    private fun update(
        selectedSessionId: String = _uiState.value.selectedSessionId,
        fullTranscriptSessionId: String = _uiState.value.fullTranscriptSessionId,
        deleteSessionId: String = _uiState.value.deleteSessionId,
        selectedGroupId: String = _uiState.value.selectedGroupId,
        fullTranscriptGroupId: String = _uiState.value.fullTranscriptGroupId,
        fullTranscriptInitialSectionKey: String = _uiState.value.fullTranscriptInitialSectionKey,
        deleteGroupId: String = _uiState.value.deleteGroupId,
        selectedAudioPath: String = _uiState.value.selectedAudioPath,
        deleteAudioPath: String = _uiState.value.deleteAudioPath,
        summaryConsentSourceType: String = _uiState.value.summaryConsentSourceType,
        summaryConsentSourceId: String = _uiState.value.summaryConsentSourceId,
        pendingExportSourceType: String = _uiState.value.pendingExportSourceType,
        pendingExportSourceId: String = _uiState.value.pendingExportSourceId,
        exportInProgress: Boolean = _uiState.value.exportInProgress,
        exportStatusMessage: String = _uiState.value.exportStatusMessage,
    ) {
        val next = LibraryRouteUiState(
            selectedSessionId = selectedSessionId,
            fullTranscriptSessionId = fullTranscriptSessionId,
            deleteSessionId = deleteSessionId,
            selectedGroupId = selectedGroupId,
            fullTranscriptGroupId = fullTranscriptGroupId,
            fullTranscriptInitialSectionKey = fullTranscriptInitialSectionKey,
            deleteGroupId = deleteGroupId,
            selectedAudioPath = selectedAudioPath,
            deleteAudioPath = deleteAudioPath,
            summaryConsentSourceType = summaryConsentSourceType,
            summaryConsentSourceId = summaryConsentSourceId,
            pendingExportSourceType = pendingExportSourceType,
            pendingExportSourceId = pendingExportSourceId,
            exportInProgress = exportInProgress,
            exportStatusMessage = exportStatusMessage,
        )
        _uiState.value = next
        savedStateHandle[SELECTED_SESSION] = next.selectedSessionId
        savedStateHandle[FULL_TRANSCRIPT_SESSION] = next.fullTranscriptSessionId
        savedStateHandle[DELETE_SESSION] = next.deleteSessionId
        savedStateHandle[SELECTED_GROUP] = next.selectedGroupId
        savedStateHandle[FULL_TRANSCRIPT_GROUP] = next.fullTranscriptGroupId
        savedStateHandle[FULL_TRANSCRIPT_INITIAL_SECTION] = next.fullTranscriptInitialSectionKey
        savedStateHandle[DELETE_GROUP] = next.deleteGroupId
        savedStateHandle[SELECTED_AUDIO] = next.selectedAudioPath
        savedStateHandle[DELETE_AUDIO] = next.deleteAudioPath
        savedStateHandle[SUMMARY_CONSENT_TYPE] = next.summaryConsentSourceType
        savedStateHandle[SUMMARY_CONSENT_ID] = next.summaryConsentSourceId
        savedStateHandle[PENDING_EXPORT_TYPE] = next.pendingExportSourceType
        savedStateHandle[PENDING_EXPORT_ID] = next.pendingExportSourceId
    }

    private companion object {
        const val SELECTED_SESSION = "library.selectedSession"
        const val FULL_TRANSCRIPT_SESSION = "library.fullTranscriptSession"
        const val DELETE_SESSION = "library.deleteSession"
        const val SELECTED_GROUP = "library.selectedGroup"
        const val FULL_TRANSCRIPT_GROUP = "library.fullTranscriptGroup"
        const val FULL_TRANSCRIPT_INITIAL_SECTION = "library.fullTranscriptInitialSection"
        const val DELETE_GROUP = "library.deleteGroup"
        const val SELECTED_AUDIO = "library.selectedAudio"
        const val DELETE_AUDIO = "library.deleteAudio"
        const val SUMMARY_CONSENT_TYPE = "library.summaryConsentType"
        const val SUMMARY_CONSENT_ID = "library.summaryConsentId"
        const val PENDING_EXPORT_TYPE = "library.pendingExportType"
        const val PENDING_EXPORT_ID = "library.pendingExportId"
    }
}
