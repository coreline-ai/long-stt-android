package com.stt.benchmark.ui.transcription

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranscriptionDialog { NONE, MODEL_PICKER, AUDIO_PICKER, DELETE_AUDIO }

data class TranscriptionRouteUiState(
    val dialog: TranscriptionDialog = TranscriptionDialog.NONE,
    val audioMenuPath: String = "",
    val deleteAudioPath: String = "",
)

/** 전사 route의 일시적인 dialog/선택 상태만 소유한다. STT Service는 소유하지 않는다. */
class TranscriptionRouteViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TranscriptionRouteUiState(
            dialog = savedStateHandle.get<String>(DIALOG_KEY)?.let {
                runCatching { TranscriptionDialog.valueOf(it) }.getOrNull()
            } ?: TranscriptionDialog.NONE,
            audioMenuPath = savedStateHandle.get<String>(AUDIO_MENU_KEY).orEmpty(),
            deleteAudioPath = savedStateHandle.get<String>(DELETE_AUDIO_KEY).orEmpty(),
        )
    )
    val uiState: StateFlow<TranscriptionRouteUiState> = _uiState.asStateFlow()

    fun showModelPicker() = update(dialog = TranscriptionDialog.MODEL_PICKER)

    fun showAudioPicker() = update(dialog = TranscriptionDialog.AUDIO_PICKER)

    fun showAudioMenu(path: String) = update(audioMenuPath = path)

    fun dismissAudioMenu() = update(audioMenuPath = "")

    fun confirmAudioDeletion(path: String) = update(
        dialog = TranscriptionDialog.DELETE_AUDIO,
        audioMenuPath = "",
        deleteAudioPath = path,
    )

    fun dismissDialog() = update(
        dialog = TranscriptionDialog.NONE,
        deleteAudioPath = "",
    )

    private fun update(
        dialog: TranscriptionDialog = _uiState.value.dialog,
        audioMenuPath: String = _uiState.value.audioMenuPath,
        deleteAudioPath: String = _uiState.value.deleteAudioPath,
    ) {
        val next = TranscriptionRouteUiState(dialog, audioMenuPath, deleteAudioPath)
        _uiState.value = next
        savedStateHandle[DIALOG_KEY] = next.dialog.name
        savedStateHandle[AUDIO_MENU_KEY] = next.audioMenuPath
        savedStateHandle[DELETE_AUDIO_KEY] = next.deleteAudioPath
    }

    private companion object {
        const val DIALOG_KEY = "transcription.dialog"
        const val AUDIO_MENU_KEY = "transcription.audioMenuPath"
        const val DELETE_AUDIO_KEY = "transcription.deleteAudioPath"
    }
}
