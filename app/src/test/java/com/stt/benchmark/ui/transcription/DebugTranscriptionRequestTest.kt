package com.stt.benchmark.ui.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugTranscriptionRequestTest {
    @Test
    fun validDebugExtrasKeepAutomationContractAfterRouteSplit() {
        val request = DebugTranscriptionRequest.create(
            enabled = true,
            modelPath = "/managed/model.bin",
            audioPath = "/managed/audio.m4a",
            note = "smoke",
        )

        assertEquals("/managed/model.bin", request?.modelPath)
        assertEquals("/managed/audio.m4a", request?.audioPath)
        assertEquals("smoke", request?.note)
    }

    @Test
    fun releaseOrIncompleteExtrasNeverExposeAutomation() {
        assertNull(DebugTranscriptionRequest.create(false, "/model", "/audio", "note"))
        assertNull(DebugTranscriptionRequest.create(true, "", "/audio", "note"))
        assertNull(DebugTranscriptionRequest.create(true, "/model", null, "note"))
    }
}
