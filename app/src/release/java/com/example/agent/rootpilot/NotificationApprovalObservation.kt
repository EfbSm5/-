package com.example.agent.rootpilot

/** Release builds retain no notification approval observations. */
internal object NotificationApprovalObservation {
    fun succeeded(@Suppress("UNUSED_PARAMETER") runId: String?, @Suppress("UNUSED_PARAMETER") token: String) = Unit
}
