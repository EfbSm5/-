package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootPilotOverlayTest {
    @Test
    fun panelOnlyShowsWhileThinkingOrAwaitingConfirmation() {
        assertEquals(
            setOf(RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.WAITING_CONFIRMATION),
            RootPilotStatus.entries.filter { it.showsOverlay() }.toSet(),
        )
    }

    @Test
    fun inputPreviewDoesNotExposeTypedText() {
        val preview = RootPilotAction.Type("private", "输入").describe()
        assertFalse(preview.contains("private"))
        assertEquals("type(7 chars)：输入", preview)
    }
}
