package com.example.agent.rootpilot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryState
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.ui.RootPilotTheme
import com.example.agent.rootpilot.ui.RunHistoryScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunHistoryScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHistoryCannotBeClearedAndCanReturn() {
        var back = 0
        compose.setContent { RootPilotTheme { RunHistoryScreen(RunHistoryState(), {}, { back++ }) } }
        compose.onNodeWithTag("history_empty").assertExists()
        compose.onNodeWithTag("history_clear").assertIsNotEnabled()
        compose.onNodeWithTag("history_back").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun detailShowsFixedFailureAndReturnsToList() {
        compose.setContent { RootPilotTheme { RunHistoryScreen(RunHistoryState(listOf(record())), {}, {}) } }
        compose.onNodeWithTag("history_record_0").performClick()
        compose.onNodeWithText("任务详情").assertExists()
        compose.onNodeWithText("动作：输入文本").assertExists()
        compose.onNodeWithText("执行器返回失败（execution_failed）").performScrollTo().assertExists()
        compose.onNodeWithTag("history_back").performClick()
        compose.onNodeWithTag("history_record_0").assertExists()
    }

    @Test fun clearingRequiresConfirmationAndUpdatesList() {
        val state = mutableStateOf(RunHistoryState(listOf(record())))
        var clears = 0
        compose.setContent {
            RootPilotTheme { RunHistoryScreen(state.value, { clears++; state.value = RunHistoryState() }, {}) }
        }
        compose.onNodeWithTag("history_clear").performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("history_record_0").assertExists()
        compose.onNodeWithTag("history_clear").performClick()
        compose.onNodeWithTag("history_confirm_clear").performClick()
        compose.onNodeWithTag("history_empty").assertExists()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test fun interruptedRecordDoesNotClaimLastActionSucceeded() {
        val interrupted = record().copy(status = RunHistoryStatus.INTERRUPTED)
        compose.setContent { RootPilotTheme { RunHistoryScreen(RunHistoryState(listOf(interrupted)), {}, {}) } }
        compose.onNodeWithTag("history_record_0").performClick()
        compose.onNodeWithText("未记录到完整收尾，最后动作是否生效未知", substring = true)
            .performScrollTo().assertExists()
    }

    private fun record(): RunHistoryRecord {
        val id = "00000000-0000-0000-0000-000000000001"
        return RunHistoryRecord(id, 1_700_000_000_000L, durationMs = 2_000,
            status = RunHistoryStatus.FAILED, stepCount = 1, reason = TraceReason.EXECUTION_FAILED,
            events = listOf(RunTraceEvent(id, 0, 2_000, TraceActionType.TYPE,
                TraceStage.EXECUTION, TraceEvent.RESULT, TraceStatus.FAILED, TraceReason.EXECUTION_FAILED)))
    }
}
