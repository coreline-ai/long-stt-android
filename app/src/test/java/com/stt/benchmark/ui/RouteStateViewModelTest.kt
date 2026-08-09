package com.stt.benchmark.ui

import androidx.lifecycle.SavedStateHandle
import com.stt.benchmark.ui.library.LibraryRouteViewModel
import com.stt.benchmark.ui.settings.SettingsDialog
import com.stt.benchmark.ui.settings.SettingsRouteViewModel
import com.stt.benchmark.ui.transcription.TranscriptionDialog
import com.stt.benchmark.ui.transcription.TranscriptionRouteViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStateViewModelTest {
    @Test
    fun transcriptionDialogAndDeleteTargetSurviveRecreation() {
        val handle = SavedStateHandle()
        val first = TranscriptionRouteViewModel(handle)

        first.confirmAudioDeletion("/managed/audio.m4a")
        val restored = TranscriptionRouteViewModel(handle).uiState.value

        assertEquals(TranscriptionDialog.DELETE_AUDIO, restored.dialog)
        assertEquals("/managed/audio.m4a", restored.deleteAudioPath)
        first.dismissDialog()
        assertEquals(TranscriptionDialog.NONE, first.uiState.value.dialog)
        assertTrue(first.uiState.value.deleteAudioPath.isBlank())
    }

    @Test
    fun libraryKeepsStableIdsInsteadOfWholeStoreObjects() {
        val handle = SavedStateHandle()
        val first = LibraryRouteViewModel(handle)

        first.selectGroup("recording_group_1")
        first.requestSessionDeletion("stt_session_1")
        val restored = LibraryRouteViewModel(handle).uiState.value

        assertEquals("recording_group_1", restored.selectedGroupId)
        assertEquals("stt_session_1", restored.deleteSessionId)
        first.dismissGroup()
        first.dismissSessionDeletion()
        assertTrue(first.uiState.value.selectedGroupId.isBlank())
        assertTrue(first.uiState.value.deleteSessionId.isBlank())
    }

    @Test
    fun settingsRestoresModelManagementDialogAndInput() {
        val handle = SavedStateHandle()
        val first = SettingsRouteViewModel(handle)

        first.showModelPath("/managed/models/ggml-base.bin")
        first.setModelInputPath("/managed/models/ggml-small.bin")
        val restored = SettingsRouteViewModel(handle).uiState.value

        assertEquals(SettingsDialog.MODEL_PATH, restored.dialog)
        assertEquals("/managed/models/ggml-small.bin", restored.modelInputPath)
        first.dismissDialog()
        assertEquals(SettingsDialog.NONE, first.uiState.value.dialog)
    }
}
