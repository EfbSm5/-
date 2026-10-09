package com.example.agent.rootpilot.parity

/** Explicit physical system-user scope for the standalone test helper; unknown is never unlocked. */
internal object ProbeEnvironmentPolicy {
    fun usable(userId: Int?, deviceId: Int?, deviceLocked: Boolean?, keyguardLocked: Boolean?, interactive: Boolean?): Boolean =
        userId == 0 && deviceId == 0 && deviceLocked == false && keyguardLocked == false && interactive == true
}
