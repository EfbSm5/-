package com.example.agent.rootpilot.parity

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedImeProbeObservationTest {
    private val window = ImeWindowMetadata(7, 12, 12, true, true, 0, 1200, 1080, 1920)

    private fun classify(
        windows: List<ImeWindowMetadata>? = listOf(window),
        display: Int? = 7,
        session: String? = "owned-session",
        hidden: Boolean = false,
        expectedDisplay: Int = 7,
        expectedSession: String = "owned-session",
        width: Int = 1080,
        height: Int = 1920,
    ) = OwnedImeProbeObservation.classify(expectedDisplay, expectedSession, display, session, width, height, windows, hidden)

    @Test fun visibleRequiresOneFreshBoundWindowInsideOwnedDisplay() {
        assertEquals(OwnedImeProbeVisibility.VISIBLE, classify())
    }

    @Test fun hiddenRequiresEmptyKnownWindowListAndIndependentConfirmation() {
        assertEquals(OwnedImeProbeVisibility.HIDDEN, classify(emptyList(), hidden = true))
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(emptyList()))
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(null, hidden = true))
    }

    @Test fun insetConfirmationRequiresTwoExplicitHiddenSamples() {
        for (before in listOf(null, false, true)) for (after in listOf(null, false, true)) {
            val confirmed = OwnedImeProbeObservation.hiddenByStableInsets(before, after)
            assertEquals(before == false && after == false, confirmed)
            assertEquals(if (confirmed) OwnedImeProbeVisibility.HIDDEN else OwnedImeProbeVisibility.UNKNOWN,
                classify(emptyList(), hidden = confirmed))
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(null, hidden = confirmed))
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(emptyList(), display = 0, hidden = confirmed))
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(emptyList(), session = "other-session", hidden = confirmed))
        }
    }

    @Test fun mainAndForeignDisplayWindowsDoNotProveOwnedVisibility() {
        for (id in listOf(0, 8)) {
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(listOf(window.copy(displayId = id))))
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(display = id))
        }
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(expectedDisplay = 0, display = 0))
    }

    @Test fun missingOrChangedSessionIsUnknownEvenWithVisibleMetadata() {
        for (session in listOf(null, "other-session")) {
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(session = session))
        }
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(expectedSession = ""))
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(display = null))
    }

    @Test fun staleMissingOrDifferentRootCannotConfirmVisibility() {
        for (sample in listOf(window.copy(rootWindowId = null), window.copy(rootWindowId = 13),
            window.copy(rootRefreshed = false), window.copy(rootVisible = false), window.copy(windowId = -1))) {
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(listOf(sample), hidden = true))
        }
    }

    @Test fun multipleImeWindowsAreAmbiguous() {
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(listOf(window, window)))
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(listOf(window, window.copy(displayId = 0))))
    }

    @Test fun invalidOrOutsideBoundsAreUnknown() {
        for (sample in listOf(window.copy(left = -1), window.copy(top = -1), window.copy(right = 0),
            window.copy(bottom = 1200), window.copy(right = 1081), window.copy(bottom = 1921))) {
            assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(listOf(sample)))
        }
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(width = 0))
        assertEquals(OwnedImeProbeVisibility.UNKNOWN, classify(height = 0))
    }
}
