package com.example.agent.rootpilot.information

import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import org.junit.Assert.*
import org.junit.Test

class UiTreeWindowSnapshotTest {
    @Test fun samePackageNonFocusedActiveWindowIsNotEligible() {
        assertFalse(UiTreeWindowSnapshot(7, 0, false).accepts(7))
    }

    @Test fun onlyDefaultDisplayInputFocusAndStableIdentityAreAccepted() {
        assertTrue(UiTreeWindowSnapshot(7, 0, true).accepts(7))
        assertFalse(UiTreeWindowSnapshot(7, 1, true).accepts(7))
        assertFalse(UiTreeWindowSnapshot(8, 0, true).accepts(8, originalWindowId = 7))
        assertFalse(UiTreeWindowSnapshot(7, 0, true).accepts(8))
        assertFalse(UiTreeWindowSnapshot(-1, 0, true).accepts(-1))
    }

    @Test fun explicitlyBoundVirtualWindowDoesNotAcceptTheMainOrAnotherDisplay() {
        assertTrue(UiTreeWindowSnapshot(7, 9, true).accepts(7, expectedDisplayId = 9))
        assertFalse(UiTreeWindowSnapshot(7, 0, true).accepts(7, expectedDisplayId = 9))
        assertFalse(UiTreeWindowSnapshot(7, 10, true).accepts(7, expectedDisplayId = 9))
        assertFalse(UiTreeWindowSnapshot(7, 9, true).accepts(7))
        assertFalse(UiTreeWindowSnapshot(7, 9, true).accepts(7, expectedDisplayId = -1))
    }

    @Test fun virtualWindowStillRequiresFocusAndBothNodeAndOriginalWindowIdentities() {
        assertFalse(UiTreeWindowSnapshot(7, 9, false).accepts(7, expectedDisplayId = 9))
        assertFalse(UiTreeWindowSnapshot(7, 9, true).accepts(8, expectedDisplayId = 9))
        assertFalse(UiTreeWindowSnapshot(8, 9, true).accepts(8, 7, 9))
    }

    @Test fun reusedDisplayIdCannotMatchAReplacementSession() {
        val expected = ScreenObservation("com.test.app", "com.test.app.Main", "com.test.app", "window", false,
            1, 9, "original")
        assertTrue(expected.matchesUiTreeSession(DisplaySession(9, "original")))
        assertFalse(expected.matchesUiTreeSession(DisplaySession(9, "replacement")))
        assertFalse(expected.matchesUiTreeSession(DisplaySession(10, "original")))
        assertFalse(expected.matchesUiTreeSession(null))
        assertFalse(expected.copy(sessionId = null).matchesUiTreeSession(DisplaySession(9, "original")))
    }

    @Test fun mainTreeDoesNotAcceptAVirtualSessionOrDisplay() {
        val expected = ScreenObservation("com.test.app", "com.test.app.Main", "com.test.app", "window", false, 1)
        assertTrue(expected.matchesUiTreeSession(null))
        assertFalse(expected.copy(displayId = 9).matchesUiTreeSession(null))
        assertFalse(expected.copy(sessionId = "original").matchesUiTreeSession(null))
        assertFalse(expected.matchesUiTreeSession(DisplaySession(9, "original")))
    }
}
