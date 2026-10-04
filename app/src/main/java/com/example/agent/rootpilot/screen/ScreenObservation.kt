package com.example.agent.rootpilot.screen

/** Best-effort metadata sampled sequentially; null means unknown, not absent. */
data class ScreenObservation(
    val foregroundPackage: String?,
    val foregroundActivity: String?,
    val focusedPackage: String?,
    val focusedWindowId: String?,
    val keyboardVisible: Boolean?,
    val observedAtMillis: Long,
    val displayId: Int = 0,
    val sessionId: String? = null,
)

/** A runtime-only identity; never recover an execution session from a task snapshot. */
data class DisplaySession(val displayId: Int, val sessionId: String) {
    init {
        require(displayId > 0)
        require(sessionId.isNotBlank())
    }
}

internal fun ScreenObservation.sameTarget(other: ScreenObservation): Boolean =
    foregroundPackage != null && foregroundActivity != null && focusedPackage != null && focusedWindowId != null &&
        foregroundPackage == other.foregroundPackage && foregroundActivity == other.foregroundActivity &&
        focusedPackage == other.focusedPackage && focusedWindowId == other.focusedWindowId &&
        displayId == other.displayId && sessionId == other.sessionId

fun interface ScreenObserver {
    suspend fun observe(): ScreenObservation
}
