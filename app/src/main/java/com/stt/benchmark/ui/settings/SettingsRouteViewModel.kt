package com.stt.benchmark.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SettingsDialog { NONE, MODEL_CATALOG, MODEL_PATH, DELETE_MODEL, PERFORMANCE_HISTORY }

data class SettingsRouteUiState(
    val dialog: SettingsDialog = SettingsDialog.NONE,
    val deleteModelPath: String = "",
    val modelInputPath: String = "",
)

/** 설정 route의 관리 dialog 상태를 process recreation 가능한 값으로만 저장한다. */
class SettingsRouteViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsRouteUiState(
            dialog = savedStateHandle.get<String>(DIALOG_KEY)?.let {
                runCatching { SettingsDialog.valueOf(it) }.getOrNull()
            } ?: SettingsDialog.NONE,
            deleteModelPath = savedStateHandle.get<String>(DELETE_MODEL_KEY).orEmpty(),
            modelInputPath = savedStateHandle.get<String>(MODEL_INPUT_KEY).orEmpty(),
        )
    )
    val uiState: StateFlow<SettingsRouteUiState> = _uiState.asStateFlow()

    fun showModelCatalog() = update(dialog = SettingsDialog.MODEL_CATALOG)
    fun showModelPath(defaultPath: String) = update(
        dialog = SettingsDialog.MODEL_PATH,
        modelInputPath = _uiState.value.modelInputPath.ifBlank { defaultPath },
    )
    fun showPerformanceHistory() = update(dialog = SettingsDialog.PERFORMANCE_HISTORY)
    fun requestModelDeletion(path: String) = update(
        dialog = SettingsDialog.DELETE_MODEL,
        deleteModelPath = path,
    )
    fun setModelInputPath(path: String) = update(modelInputPath = path)
    fun dismissDialog() = update(dialog = SettingsDialog.NONE, deleteModelPath = "")

    private fun update(
        dialog: SettingsDialog = _uiState.value.dialog,
        deleteModelPath: String = _uiState.value.deleteModelPath,
        modelInputPath: String = _uiState.value.modelInputPath,
    ) {
        val next = SettingsRouteUiState(dialog, deleteModelPath, modelInputPath)
        _uiState.value = next
        savedStateHandle[DIALOG_KEY] = next.dialog.name
        savedStateHandle[DELETE_MODEL_KEY] = next.deleteModelPath
        savedStateHandle[MODEL_INPUT_KEY] = next.modelInputPath
    }

    private companion object {
        const val DIALOG_KEY = "settings.dialog"
        const val DELETE_MODEL_KEY = "settings.deleteModelPath"
        const val MODEL_INPUT_KEY = "settings.modelInputPath"
    }
}
