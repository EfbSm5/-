package com.example.agent.rootpilot.information

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
}
