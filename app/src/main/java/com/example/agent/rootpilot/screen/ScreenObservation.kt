package com.example.agent.rootpilot.screen

/** Best-effort metadata sampled sequentially; null means unknown, not absent. */
data class ScreenObservation(
    val foregroundPackage: String?,
    val foregroundActivity: String?,
    val focusedPackage: String?,
    val focusedWindowId: String?,
    val keyboardVisible: Boolean?,
    val observedAtMillis: Long,
)

fun interface ScreenObserver {
    suspend fun observe(): ScreenObservation
}
