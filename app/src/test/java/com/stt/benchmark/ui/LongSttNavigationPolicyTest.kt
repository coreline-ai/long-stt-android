package com.stt.benchmark.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSttNavigationPolicyTest {
    @Test
    fun recordingStartDestinationNeverRestoresASavedChildBackStack() {
        assertFalse(shouldRestoreTopLevelState(AppDestination.RECORDING))
    }

    @Test
    fun liveLibraryIsRecreatedWhileOtherPeerTabsMayRestoreState() {
        assertFalse(shouldRestoreTopLevelState(AppDestination.LIBRARY))
        assertTrue(shouldRestoreTopLevelState(AppDestination.TRANSCRIPTION))
        assertTrue(shouldRestoreTopLevelState(AppDestination.SETTINGS))
    }
}
