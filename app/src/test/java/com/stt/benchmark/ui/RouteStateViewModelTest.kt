package com.stt.benchmark.ui

import androidx.lifecycle.SavedStateHandle
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.summary.SummaryRequestPolicy
import com.stt.benchmark.summary.SummarySessionStore
import com.stt.benchmark.ui.library.LibraryRouteViewModel
import com.stt.benchmark.ui.library.pendingExportSource
import com.stt.benchmark.ui.library.summaryConsentSource
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
    fun libraryCompletedResultOpeningSelectsExactlyOneOpaqueTarget() {
        val handle = SavedStateHandle()
        val viewModel = LibraryRouteViewModel(handle)
        val session = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_session_1",
        )!!
        val group = CompletedResultTarget.create(
            CompletedResultTarget.Type.RECORDING_GROUP,
            "recording_group_1",
        )!!

        viewModel.openCompletedResult(session)
        assertEquals(session.id, viewModel.uiState.value.selectedSessionId)
        assertTrue(viewModel.uiState.value.selectedGroupId.isBlank())

        viewModel.openCompletedResult(group)
        assertTrue(viewModel.uiState.value.selectedSessionId.isBlank())
        assertEquals(group.id, viewModel.uiState.value.selectedGroupId)

        val restored = LibraryRouteViewModel(handle).uiState.value
        assertTrue(restored.selectedSessionId.isBlank())
        assertEquals(group.id, restored.selectedGroupId)
    }

    @Test
    fun librarySummaryConsentRestoresOnlyOpaqueSourceKey() {
        val handle = SavedStateHandle()
        val first = LibraryRouteViewModel(handle)
        val source = SummaryRequestPolicy.Source(
            SummarySessionStore.SourceType.RECORDING_GROUP,
            "recording_stt_123",
        )

        first.requestSummaryConsent(source)
        val restored = LibraryRouteViewModel(handle).uiState.value

        assertEquals(source, restored.summaryConsentSource)
        first.dismissSummaryConsent()
        assertTrue(first.uiState.value.summaryConsentSourceType.isBlank())
        assertTrue(first.uiState.value.summaryConsentSourceId.isBlank())
    }

    @Test
    fun libraryFullTranscriptViewerRestoresOnlySelectedOpaqueId() {
        val handle = SavedStateHandle()
        val first = LibraryRouteViewModel(handle)

        first.showFullSessionTranscript("stt_session_1")
        var restored = LibraryRouteViewModel(handle).uiState.value
        assertEquals("stt_session_1", restored.fullTranscriptSessionId)
        assertTrue(restored.fullTranscriptGroupId.isBlank())

        first.showFullGroupTranscript("recording_group_1")
        restored = LibraryRouteViewModel(handle).uiState.value
        assertTrue(restored.fullTranscriptSessionId.isBlank())
        assertEquals("recording_group_1", restored.fullTranscriptGroupId)

        first.dismissFullTranscript()
        assertTrue(first.uiState.value.fullTranscriptGroupId.isBlank())
    }

    @Test
    fun libraryTranscriptSaveRestoresOnlyOpaqueSourceAndKeepsTransientStatusLocal() {
        val handle = SavedStateHandle()
        val first = LibraryRouteViewModel(handle)
        val source = TranscriptSourceRef(TranscriptSourceType.RECORDING_GROUP, "recording_group_1")

        first.requestTranscriptSave(source)
        assertTrue(first.uiState.value.exportInProgress)
        assertEquals("저장 위치를 선택하세요.", first.uiState.value.exportStatusMessage)
        first.beginTranscriptExport("저장 중")
        val restored = LibraryRouteViewModel(handle).uiState.value

        assertEquals(source, restored.pendingExportSource)
        assertTrue(!restored.exportInProgress)
        assertTrue(restored.exportStatusMessage.isBlank())

        first.finishTranscriptExport("저장 완료")
        assertTrue(first.uiState.value.pendingExportSource == null)
        assertEquals("저장 완료", first.uiState.value.exportStatusMessage)
        first.dismissTranscriptExport()
        assertTrue(first.uiState.value.exportStatusMessage.isBlank())
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
