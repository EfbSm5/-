package com.example.agent.rootpilot.parity

internal enum class OwnedImeProbeVisibility { VISIBLE, HIDDEN, UNKNOWN }

internal data class ImeWindowMetadata(
    val displayId: Int,
    val windowId: Int,
    val rootWindowId: Int?,
    val rootRefreshed: Boolean,
    val rootVisible: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Probe-only metadata; an absent or stale IME window never proves visibility. */
internal object OwnedImeProbeObservation {
    fun hiddenByStableInsets(beforeVisible: Boolean?, afterVisible: Boolean?): Boolean =
        beforeVisible == false && afterVisible == false

    fun classify(
        expectedDisplayId: Int,
        expectedSessionId: String,
        observedDisplayId: Int?,
        observedSessionId: String?,
        width: Int,
        height: Int,
        windows: List<ImeWindowMetadata>?,
        hiddenConfirmed: Boolean,
    ): OwnedImeProbeVisibility {
        if (expectedDisplayId <= 0 || expectedSessionId.isBlank() ||
            observedDisplayId != expectedDisplayId || observedSessionId != expectedSessionId ||
            width <= 0 || height <= 0 || windows == null) return OwnedImeProbeVisibility.UNKNOWN
        if (windows.isEmpty()) return if (hiddenConfirmed) OwnedImeProbeVisibility.HIDDEN else OwnedImeProbeVisibility.UNKNOWN
        val window = windows.singleOrNull() ?: return OwnedImeProbeVisibility.UNKNOWN
        return if (window.displayId == expectedDisplayId && window.windowId >= 0 &&
            window.rootWindowId == window.windowId && window.rootRefreshed && window.rootVisible &&
            window.left >= 0 && window.top >= 0 && window.right > window.left && window.bottom > window.top &&
            window.right <= width && window.bottom <= height) OwnedImeProbeVisibility.VISIBLE else OwnedImeProbeVisibility.UNKNOWN
    }
}
