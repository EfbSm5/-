package com.example.agent.rootpilot

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotLauncherInstrumentedTest {
    @Test fun launcherOpensRootPilotAndLegacyActivityRemainsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val launcher = requireNotNull(manager.getLaunchIntentForPackage(context.packageName))
        assertEquals(ComponentName(context, RootPilotActivity::class.java), launcher.component)
        @Suppress("DEPRECATION")
        val legacy = manager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertNotNull(legacy)
    }
}
