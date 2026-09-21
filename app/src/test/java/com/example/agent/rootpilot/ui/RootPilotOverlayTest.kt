package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun overlayInputDetailsDirectToImeWithoutLeakingTextOrReason() {
        val action = RootPilotAction.Type("第一行\n第二行", "输入到 com.example.notes：填写")
        assertEquals("输入文本（7 字符）\n请在输入法面板核对完整文本", action.overlayDetails())
        assertFalse(action.overlayDetails().contains(action.text))
        assertFalse(action.overlayDetails().contains(action.reason))
        assertTrue(action.requiresFullOverlayPreview())
        assertTrue(RootPilotAction.CreateTodo("标题", null, "原因").requiresFullOverlayPreview())
        assertTrue(RootPilotAction.AskUser("问题").requiresFullOverlayPreview())
        assertFalse(RootPilotAction.Tap(1, 2, "点击").requiresFullOverlayPreview())
    }
}
