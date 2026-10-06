package com.example.agent.rootpilot.root

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in observation only; never launches an app, captures pixels or sends a model request. */
@RunWith(AndroidJUnit4::class)
class RootScreenObserverInstrumentedTest {
    @Test fun productionObserverRecognizesKnownForeground() = runBlocking {
        val expected = when (InstrumentationRegistry.getArguments().getString("observerKnownPage")) {
            "rootpilot" -> "com.example.agent"
            "market" -> "com.xiaomi.market"
            else -> null
        }
        assumeTrue(expected != null)
        val observation = RootScreenObserver().observe()
        assertTrue("known_foreground_required", expected == observation.foregroundPackage)
        assertTrue("known_window_owner_required", expected == observation.focusedPackage)
        assertNotNull("foreground_activity_required", observation.foregroundActivity)
        assertNotNull("focused_window_required", observation.focusedWindowId)
        assertNotNull("keyboard_state_required", observation.keyboardVisible)
        assertTrue("default_display_required", observation.displayId == 0)
        assertTrue("no_virtual_session_expected", observation.sessionId == null)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("observerKnownPage", InstrumentationRegistry.getArguments().getString("observerKnownPage"))
            putBoolean("completeIdentity", true)
            putBoolean("keyboardVisible", observation.keyboardVisible!!)
            putInt("modelRequests", 0)
        })
    }
}
