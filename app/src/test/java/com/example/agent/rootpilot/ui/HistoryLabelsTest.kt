package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLabelsTest {
    @Test fun durationUsesNonNegativeElapsedTime() {
        assertEquals("0秒", historyDuration(-1))
        assertEquals("0秒", historyDuration(999))
        assertEquals("59秒", historyDuration(59_999))
        assertEquals("1分1秒", historyDuration(61_000))
    }

    @Test fun everyFixedCodeHasDisplayText() {
        TraceReason.entries.forEach { assertTrue(it.historyLabel().isNotBlank()) }
        TraceStage.entries.forEach { assertTrue(it.historyLabel().isNotBlank()) }
        TraceStatus.entries.forEach { assertTrue(it.historyLabel().isNotBlank()) }
        TraceEvent.entries.forEach { assertTrue(it.historyLabel().isNotBlank()) }
        assertEquals("规划循环结束", TraceEvent.RUN_END.historyLabel())
        assertEquals("请求停止（尚未结束）", TraceEvent.STOP_REQUESTED.historyLabel())
    }
}
