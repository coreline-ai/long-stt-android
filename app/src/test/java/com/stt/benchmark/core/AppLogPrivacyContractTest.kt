package com.stt.benchmark.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLogPrivacyContractTest {
    @Test
    fun throwableMessageIsNotWrittenToDebugLog() {
        val sensitive = "content://provider/private/audio-name.wav"
        ShadowLog.clear()

        AppLog.e("PrivacyAudit", "오디오 가져오기 실패", IllegalStateException(sensitive))

        val logs = ShadowLog.getLogsForTag("PrivacyAudit").joinToString { it.msg }
        assertTrue(logs.contains("IllegalStateException"))
        assertFalse(logs.contains(sensitive))
    }
}
