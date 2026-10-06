package com.example.agent.rootpilot.information

import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation

/** Accessibility window IDs are independent of dumpsys tokens; bind the tree to input focus. */
internal data class UiTreeWindowSnapshot(val id: Int, val displayId: Int, val inputFocused: Boolean) {
    fun accepts(nodeWindowId: Int, originalWindowId: Int = id, expectedDisplayId: Int = 0): Boolean =
        id >= 0 && id == nodeWindowId && id == originalWindowId &&
            expectedDisplayId >= 0 && displayId == expectedDisplayId && inputFocused
}

internal fun ScreenObservation.matchesUiTreeSession(session: DisplaySession?): Boolean =
    displayId == (session?.displayId ?: 0) && sessionId == session?.sessionId
