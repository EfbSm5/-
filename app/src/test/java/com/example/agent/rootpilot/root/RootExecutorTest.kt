package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.model.RootPilotApp
import org.junit.Assert.assertEquals
import org.junit.Test

class RootExecutorTest {
    @Test
    fun openAppCommandIsFixedToSettings() {
        assertEquals(
            "am start -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p com.android.settings",
            RootCommandBuilder.openApp(RootPilotApp.SETTINGS),
        )
    }
}
