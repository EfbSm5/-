package com.example.agent.rootpilot.parity

import org.junit.Assert.*
import org.junit.Test

class ProbeEnvironmentPolicyTest {
    @Test fun onlyUnlockedInteractivePhysicalSystemUserPasses() {
        assertTrue(ProbeEnvironmentPolicy.usable(0, 0, false, false, true))
    }

    @Test fun eitherLockStateRejects() {
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, true, false, true))
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, false, true, true))
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, true, true, true))
    }

    @Test fun noninteractiveDeviceRejects() {
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, false, false, false))
    }

    @Test fun unknownLockOrPowerIsNotGuessed() {
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, null, false, true))
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, false, null, true))
        assertFalse(ProbeEnvironmentPolicy.usable(0, 0, false, false, null))
    }

    @Test fun otherOrUnknownUsersReject() {
        for (user in listOf(null, -1, 1, 10)) assertFalse(ProbeEnvironmentPolicy.usable(user, 0, false, false, true))
    }

    @Test fun virtualOrUnknownDeviceContextsReject() {
        for (device in listOf(null, -1, 1, 7)) assertFalse(ProbeEnvironmentPolicy.usable(0, device, false, false, true))
    }

    @Test fun allOtherBooleanCombinationsReject() {
        for (locked in listOf(null, false, true)) for (keyguard in listOf(null, false, true)) for (awake in listOf(null, false, true)) {
            assertEquals(locked == false && keyguard == false && awake == true,
                ProbeEnvironmentPolicy.usable(0, 0, locked, keyguard, awake))
        }
    }
}
