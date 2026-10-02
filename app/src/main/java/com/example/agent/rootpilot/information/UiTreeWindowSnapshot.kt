package com.example.agent.rootpilot.information

/** Accessibility window IDs are independent of dumpsys tokens; bind the tree to input focus. */
internal data class UiTreeWindowSnapshot(val id: Int, val displayId: Int, val inputFocused: Boolean) {
    fun accepts(nodeWindowId: Int, originalWindowId: Int = id): Boolean =
        id >= 0 && id == nodeWindowId && id == originalWindowId && displayId == 0 && inputFocused
}
