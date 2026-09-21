package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResultCardTest {
    @Test
    fun completedStateDoesNotTreatModelReportAsVerifiedResult() {
        val result = RootPilotUiState(status = RootPilotStatus.COMPLETED, errorMessage = "已全部完成", modelReportedResult = true).taskResultContent()!!
        assertEquals("模型报告完成", result.title)
        assertTrue(result.description.contains("模型报告"))
        assertTrue(result.description.contains("不代表实际结果已验证"))
    }

    @Test
    fun lastTodoDoesNotProveItWasSavedForAnyTerminalState() {
        for (status in listOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)) {
            val state = RootPilotUiState(status = status, lastAction = RootPilotAction.CreateTodo("待办正文", null, "保存原因"))
            val result = state.taskResultContent()!!
            assertFalse(result.toString().contains("已保存"))
            assertEquals(state.copy(lastAction = null).taskResultContent(), result)
        }
    }

    @Test
    fun rawInputReasonAndMessagesNeverEnterResultContent() {
        for (status in RootPilotStatus.entries) {
            val state = RootPilotUiState(
                status = status,
                lastAction = RootPilotAction.Type("PRIVATE_INPUT", "PRIVATE_REASON"),
                pendingAction = RootPilotAction.AskUser("PRIVATE_QUESTION"),
                errorMessage = "PRIVATE_MESSAGE",
            )
            assertFalse(state.taskResultContent().toString().contains("PRIVATE_"))
        }
    }

    @Test
    fun stoppingIsDistinctFromStoppedAndNeitherClaimsOperationsRolledBack() {
        val result = RootPilotUiState(status = RootPilotStatus.STOPPED).taskResultContent()!!
        assertTrue(result.description.contains("不会自动撤销"))
        val stopping = RootPilotUiState(status = RootPilotStatus.STOPPING).taskResultContent()!!
        assertEquals("正在停止", stopping.title)
        assertTrue(stopping.description.contains("正在等待本次运行结束"))
    }

    @Test
    fun singleStepAndModelFailureAreNotPresentedAsWholeTaskSuccess() {
        val completed = RootPilotUiState(status = RootPilotStatus.COMPLETED).taskResultContent()!!
        assertEquals("本次执行完成", completed.title)
        assertTrue(completed.description.contains("不代表整项任务已完成"))
        val failure = RootPilotUiState(status = RootPilotStatus.FAILED, modelReportedResult = true).taskResultContent()!!
        assertEquals("模型报告未完成", failure.title)
    }

    @Test
    fun recoveryAndAskUserRequireManualHandlingButNormalApprovalsDoNotCreateResults() {
        assertEquals("需人工处理", RootPilotUiState(status = RootPilotStatus.RECOVERY_REQUIRED).taskResultContent()!!.title)
        assertEquals("需人工处理", RootPilotUiState(status = RootPilotStatus.WAITING_CONFIRMATION,
            pendingAction = RootPilotAction.AskUser("请接管")).taskResultContent()!!.title)
        assertNull(RootPilotUiState(status = RootPilotStatus.WAITING_CONFIRMATION,
            pendingAction = RootPilotAction.Type("文本", "原因")).taskResultContent())
        for (status in listOf(RootPilotStatus.IDLE, RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL,
            RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN)) {
            assertNull(RootPilotUiState(status = status).taskResultContent())
        }
    }
}
