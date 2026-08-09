package com.stt.benchmark.recording

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingInputRouteTest {
    @Test
    fun routeMappingUsesOnlySafeGenericCategories() {
        assertEquals(RecordingInputRoute.UNKNOWN, RecordingInputRoute.fromDeviceType(null))
        assertEquals(
            RecordingInputRoute.BUILT_IN,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
        assertEquals(
            RecordingInputRoute.BLUETOOTH,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(
            RecordingInputRoute.USB,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE),
        )
        assertEquals(
            RecordingInputRoute.WIRED,
            RecordingInputRoute.fromDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(RecordingInputRoute.OTHER, RecordingInputRoute.fromDeviceType(-1))
    }
}
