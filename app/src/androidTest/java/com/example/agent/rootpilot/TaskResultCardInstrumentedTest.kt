package com.example.agent.rootpilot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.SavedTodoResult
import com.example.agent.rootpilot.ui.TaskResultCard
import com.example.agent.ui.theme.AgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskResultCardInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun resultsFollowCurrentStateWithoutRenderingPrivatePayloadOrStaleSuccess() {
        val state = mutableStateOf(RootPilotUiState(status = RootPilotStatus.COMPLETED, modelReportedResult = true,
            lastAction = RootPilotAction.Type("PRIVATE_TEXT", "PRIVATE_REASON"), errorMessage = "模型说明：已到达目标页面"))
        compose.setContent { AgentTheme { TaskResultCard(state.value) } }
        compose.onNodeWithTag("task_result_title").assertTextEquals("模型报告完成")
        compose.onNodeWithTag("task_result_description").assertTextContains("不代表实际结果已验证", substring = true)
        compose.onNodeWithText("PRIVATE_", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("task_result_message").assertTextEquals("模型说明：已到达目标页面")
        compose.runOnIdle { state.value = state.value.copy(status = RootPilotStatus.FAILED,
            modelReportedResult = false, errorMessage = "连接失败") }
        compose.onNodeWithTag("task_result_title").assertTextEquals("运行失败")
        compose.onNodeWithTag("task_result_message").assertTextEquals("连接失败")
        compose.runOnIdle { state.value = state.value.copy(status = RootPilotStatus.STOPPING) }
        compose.onNodeWithTag("task_result_description").assertTextContains("正在等待本次运行结束", substring = true)
        compose.runOnIdle { state.value = state.value.copy(status = RootPilotStatus.STOPPED) }
        compose.onNodeWithTag("task_result_title").assertTextEquals("任务已停止")
        compose.runOnIdle { state.value = state.value.copy(status = RootPilotStatus.RECOVERY_REQUIRED) }
        compose.onNodeWithTag("task_result_title").assertTextEquals("需人工处理")
        compose.runOnIdle { state.value = state.value.copy(status = RootPilotStatus.REQUESTING_MODEL) }
        compose.onNodeWithTag("task_result").assertDoesNotExist()
    }

    @Test
    fun onlySuccessfulSavedTodoEvidenceIsShownAndSurvivesLaterFailure() {
        val state = mutableStateOf(RootPilotUiState(status = RootPilotStatus.FAILED,
            lastAction = RootPilotAction.CreateTodo("未保存标题", null, "原因")))
        compose.setContent { AgentTheme { TaskResultCard(state.value) } }
        compose.onNodeWithText("本次已保存的待办").assertDoesNotExist()
        compose.onNodeWithText("未保存标题", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(savedTodos = listOf(
                SavedTodoResult("已保存的测试标题", "2026-09-22T09:00:00+08:00"),
                SavedTodoResult("无到期待办", null),
            ))
        }
        compose.onNodeWithText("本次已保存的待办").assertExists()
        compose.onNodeWithText("标题：已保存的测试标题\n截止时间：2026-09-22T09:00:00+08:00").assertExists()
        compose.onNodeWithText("标题：无到期待办\n截止时间：无").assertExists()
        compose.runOnIdle { state.value = RootPilotUiState(status = RootPilotStatus.REQUESTING_MODEL) }
        compose.onNodeWithTag("task_result").assertDoesNotExist()
    }
}
