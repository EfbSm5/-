package com.example.agent.rootpilot

/** Release builds retain no lifecycle observations. */
internal object ServiceDestroyObservation {
    fun created(@Suppress("UNUSED_PARAMETER") service: RootPilotService) = Unit
    fun destroyed(@Suppress("UNUSED_PARAMETER") service: RootPilotService) = Unit
}
