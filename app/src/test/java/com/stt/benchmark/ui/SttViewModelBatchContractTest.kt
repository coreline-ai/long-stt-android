package com.stt.benchmark.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
