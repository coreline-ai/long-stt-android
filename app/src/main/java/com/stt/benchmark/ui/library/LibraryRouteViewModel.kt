package com.stt.benchmark.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibraryRouteUiState(
    val selectedSessionId: String = "",
    val deleteSessionId: String = "",
    val selectedGroupId: String = "",
    val deleteGroupId: String = "",
    val selectedAudioPath: String = "",
    val deleteAudioPath: String = "",
)

/** 보관함의 선택·확인 dialog 상태만 저장한다. 원본/결과 삭제는 domain ViewModel에 위임한다. */
class LibraryRouteViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(load())
    val uiState: StateFlow<LibraryRouteUiState> = _uiState.asStateFlow()

    fun selectSession(id: String) = update(selectedSessionId = id)
    fun requestSessionDeletion(id: String) = update(selectedSessionId = "", deleteSessionId = id)
    fun selectGroup(id: String) = update(selectedGroupId = id)
    fun requestGroupDeletion(id: String) = update(selectedGroupId = "", deleteGroupId = id)
    fun selectAudio(path: String) = update(selectedAudioPath = path)
    fun requestAudioDeletion(path: String) = update(selectedAudioPath = "", deleteAudioPath = path)

    fun dismissSession() = update(selectedSessionId = "")
    fun dismissSessionDeletion() = update(deleteSessionId = "")
    fun dismissGroup() = update(selectedGroupId = "")
    fun dismissGroupDeletion() = update(deleteGroupId = "")
    fun dismissAudio() = update(selectedAudioPath = "")
    fun dismissAudioDeletion() = update(deleteAudioPath = "")

    private fun load() = LibraryRouteUiState(
        selectedSessionId = savedStateHandle.get<String>(SELECTED_SESSION).orEmpty(),
        deleteSessionId = savedStateHandle.get<String>(DELETE_SESSION).orEmpty(),
        selectedGroupId = savedStateHandle.get<String>(SELECTED_GROUP).orEmpty(),
        deleteGroupId = savedStateHandle.get<String>(DELETE_GROUP).orEmpty(),
        selectedAudioPath = savedStateHandle.get<String>(SELECTED_AUDIO).orEmpty(),
        deleteAudioPath = savedStateHandle.get<String>(DELETE_AUDIO).orEmpty(),
    )

    private fun update(
        selectedSessionId: String = _uiState.value.selectedSessionId,
        deleteSessionId: String = _uiState.value.deleteSessionId,
        selectedGroupId: String = _uiState.value.selectedGroupId,
        deleteGroupId: String = _uiState.value.deleteGroupId,
        selectedAudioPath: String = _uiState.value.selectedAudioPath,
        deleteAudioPath: String = _uiState.value.deleteAudioPath,
    ) {
        val next = LibraryRouteUiState(
            selectedSessionId,
            deleteSessionId,
            selectedGroupId,
            deleteGroupId,
            selectedAudioPath,
            deleteAudioPath,
        )
        _uiState.value = next
        savedStateHandle[SELECTED_SESSION] = next.selectedSessionId
        savedStateHandle[DELETE_SESSION] = next.deleteSessionId
        savedStateHandle[SELECTED_GROUP] = next.selectedGroupId
        savedStateHandle[DELETE_GROUP] = next.deleteGroupId
        savedStateHandle[SELECTED_AUDIO] = next.selectedAudioPath
        savedStateHandle[DELETE_AUDIO] = next.deleteAudioPath
    }

    private companion object {
        const val SELECTED_SESSION = "library.selectedSession"
        const val DELETE_SESSION = "library.deleteSession"
        const val SELECTED_GROUP = "library.selectedGroup"
        const val DELETE_GROUP = "library.deleteGroup"
        const val SELECTED_AUDIO = "library.selectedAudio"
        const val DELETE_AUDIO = "library.deleteAudio"
    }
}
