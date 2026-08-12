package com.stt.benchmark.ui

import com.stt.benchmark.data.BenchmarkRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttViewModelBatchContractTest {
    @Test
    fun productionBatchPathAcceptsExactlyOneAudio() {
        assertEquals("오디오 파일을 선택하세요", SttViewModel.batchInputError(emptyList()))
        assertNull(SttViewModel.batchInputError(listOf("one.wav")))
        assertEquals(
            "현재는 체크포인트 안전성을 위해 오디오를 한 개씩 전사합니다",
            SttViewModel.batchInputError(listOf("one.wav", "two.wav")),
        )
    }

    @Test
    fun preparingNewAudioClearsCompletedResultNavigationState() {
        val completedTarget = CompletedResultTarget.create(
            CompletedResultTarget.Type.TRANSCRIPTION_SESSION,
            "stt_completed_1",
        )!!
        val completed = SttViewModel.UiState(
            state = SttViewModel.SttState.DONE,
            modelLoaded = true,
            audioPath = "/managed/old.wav",
            audioPaths = listOf("/managed/old.wav"),
            batchStatus = "전사 완료",
            completedResultTarget = completedTarget,
            deviceInfo = BenchmarkRecorder.DeviceInfo(
                manufacturer = "",
                model = "",
                androidVersion = "",
                sdkLevel = 0,
                cpuCores = 1,
                maxMemoryMb = 1,
            ),
        )

        val prepared = completed.withPreparedAudio(listOf("/managed/new.wav"))

        assertEquals(SttViewModel.SttState.READY, prepared.state)
        assertEquals(listOf("/managed/new.wav"), prepared.audioPaths)
        assertTrue(prepared.batchStatus.isBlank())
        assertNull(prepared.completedResultTarget)
    }
}
