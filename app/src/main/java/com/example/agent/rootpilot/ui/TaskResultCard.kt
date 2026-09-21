package com.example.agent.rootpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState

internal data class TaskResultContent(val title: String, val description: String)

// Only savedTodos is write evidence; lastAction can still be an unapproved action.
internal fun RootPilotUiState.taskResultContent(): TaskResultContent? = when (status) {
    RootPilotStatus.COMPLETED -> if (modelReportedResult) TaskResultContent(
        "模型报告完成",
        "模型的 Finish 仅表示模型报告，不代表实际结果已验证。请核对目标页面或下方保存结果。",
    ) else TaskResultContent(
        "本次执行完成",
        "本次执行已结束，不代表整项任务已完成。请核对目标页面或下方保存结果。",
    )
    RootPilotStatus.FAILED -> if (modelReportedResult) TaskResultContent(
        "模型报告未完成",
        "这是模型报告的结果。请核对当前页面和已发生的操作，再决定下一步。",
    ) else TaskResultContent(
        "运行失败",
        "请核对当前页面和已发生的操作，再决定下一步。可在设置的调试工具中查看阶段日志。",
    )
    RootPilotStatus.STOPPED -> TaskResultContent(
        "任务已停止",
        "本次运行已停止，已发生的操作不会自动撤销。请先核对实际结果。",
    )
    RootPilotStatus.STOPPING -> TaskResultContent(
        "正在停止",
        "已请求停止，正在等待本次运行结束。已发生的操作不会自动撤销，请核对实际结果。",
    )
    RootPilotStatus.RECOVERY_REQUIRED -> TaskResultContent(
        "需人工处理",
        "上次运行中断，部分操作可能已生效。请核对后使用下方恢复选项；不会自动重放。",
    )
    RootPilotStatus.WAITING_CONFIRMATION -> if (pendingAction is RootPilotAction.AskUser) {
        TaskResultContent("需人工处理", "请核对下方接管提示，处理后再选择继续，或停止任务。")
    } else null
    else -> null
}

@Composable
internal fun TaskResultCard(state: RootPilotUiState, modifier: Modifier = Modifier) {
    val content = state.taskResultContent()
    if (content == null && state.savedTodos.isEmpty()) return
    Card(modifier.fillMaxWidth().testTag("task_result")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (content != null) {
                Text(content.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("task_result_title"))
                state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(message, modifier = Modifier.testTag("task_result_message"),
                        color = if (state.status == RootPilotStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface)
                }
                Text(content.description, modifier = Modifier.testTag("task_result_description"))
            }
            if (state.savedTodos.isNotEmpty()) {
                Text("本次已保存的待办", style = MaterialTheme.typography.titleSmall)
                state.savedTodos.forEach { todo ->
                    Text("标题：${todo.title}\n截止时间：${todo.dueAt ?: "无"}")
                }
                Text("仅列出已收到保存成功回执的项目；未列出不代表没有写入。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
