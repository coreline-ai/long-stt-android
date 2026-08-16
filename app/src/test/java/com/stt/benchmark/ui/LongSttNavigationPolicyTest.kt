package com.stt.benchmark.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun transcriptChatKeepsTheLibraryBottomDestinationSelected() {
        assertEquals(
            AppDestination.LIBRARY,
            resolveSelectedTopLevelDestination(
                isTranscriptChat = true,
                hierarchyRoutes = emptySet(),
            ),
        )
        assertEquals(
            AppDestination.SETTINGS,
            resolveSelectedTopLevelDestination(
                isTranscriptChat = false,
                hierarchyRoutes = setOf(AppDestination.SETTINGS.route),
            ),
        )
    }
}
