package com.example.agent.rootpilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.history.RunHistoryError
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryState
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun RunHistoryScreen(state: RunHistoryState, onClear: () -> Unit, onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val selected = state.records.firstOrNull { it.id == selectedId }
    BackHandler {
        when {
            confirmClear -> confirmClear = false
            selectedId != null -> selectedId = null
            else -> onBack()
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (selectedId == null) "任务历史" else "任务详情", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                confirmClear = false
                if (selectedId != null) selectedId = null else onBack()
            }, modifier = Modifier.testTag("history_back")) { Text(if (selectedId == null) "返回设置" else "返回列表") }
            if (selectedId == null) {
                Button(onClick = { confirmClear = true },
                    enabled = state.records.isNotEmpty() || state.error != null,
                    modifier = Modifier.testTag("history_clear")) { Text("清除历史") }
            }
        }
        state.error?.let {
            Text(when (it) {
                RunHistoryError.READ_FAILED -> "历史读取失败，记录可能不可用。可清除历史后重新记录。"
                RunHistoryError.WRITE_FAILED -> "历史保存失败，当前显示可能未落盘。任务执行不受影响。"
                RunHistoryError.CLEAR_FAILED -> "历史清除失败，未确认删除成功，请重试。"
            }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history_error"))
        }
        if (confirmClear) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("清除全部历史？此操作不可恢复，不会停止任务或清除 API 配置、待办与恢复记录。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmClear = false }) { Text("取消") }
                        Button(onClick = { confirmClear = false; onClear() },
                            modifier = Modifier.testTag("history_confirm_clear")) { Text("确认清除") }
                    }
                }
            }
        }
        if (selectedId == null) {
            Text("仅保存在本机，保留最近 50 次任务；不记录正文、截图和凭据，不提供动作重放。运行中耗时截至最后记录。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).testTag("history_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.records.isEmpty()) item { Text("暂无任务历史", modifier = Modifier.testTag("history_empty")) }
                itemsIndexed(state.records, key = { _, record -> record.id }) { index, record ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HistorySummary(record)
                            Button(onClick = { selectedId = record.id },
                                modifier = Modifier.testTag("history_record_$index")) { Text("查看详情") }
                        }
                    }
                }
            }
        } else if (selected == null) {
            Text("该记录已清除或超出保留数量。")
        } else {
            LazyColumn(Modifier.weight(1f).testTag("history_detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    HistorySummary(selected)
                    Text("执行成功仅表示执行器返回成功，不证明目标页面变化；模型报告完成不等于独立验收。", style = MaterialTheme.typography.bodySmall)
                    if (selected.status == RunHistoryStatus.INTERRUPTED) {
                        Text("未记录到完整收尾，最后动作是否生效未知；耗时截至最后记录，不会自动重放。")
                    }
                    if (selected.eventsTruncated) Text("记录较多，仅保留最后 256 条阶段事件。")
                }
                itemsIndexed(selected.events) { index, event ->
                    Column(Modifier.fillMaxWidth().testTag("history_event_$index")) {
                        if (index == 0 || selected.events[index - 1].step != event.step) {
                            Text(if (event.step < 0) "任务控制" else "步骤 ${event.step + 1}",
                                style = MaterialTheme.typography.titleMedium)
                        }
                        HistoryEvent(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySummary(record: RunHistoryRecord) {
    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(record.startedAtEpochMs)))
    Text("${record.status.historyLabel()} · ${historyDuration(record.durationMs)} · ${record.stepCount} 步")
    if (record.reason != TraceReason.NONE) Text("原因：${record.reason.historyLabel()}")
}

@Composable
private fun HistoryEvent(event: RunTraceEvent) {
    val result = if (event.stage == TraceStage.EXECUTION && event.status == TraceStatus.SUCCESS) {
        if (event.actionType == TraceActionType.CREATE_TODO) "本地待办已保存" else "执行器返回成功"
    } else event.status.historyLabel()
    Text("${historyDuration(event.elapsedMs)} · ${event.stage.historyLabel()} · ${event.event.historyLabel()} · $result")
    if (event.actionType != TraceActionType.NONE) Text("动作：${event.actionType.historyLabel()}")
    if (event.actionType == TraceActionType.FINISH && event.event == TraceEvent.RUN_END) Text("模型报告结果，未独立验证")
    if (event.reason != TraceReason.NONE) Text("${event.reason.historyLabel()}（${event.reason.name.lowercase()}）")
}

internal fun RunHistoryStatus.historyLabel(): String = when (this) {
    RunHistoryStatus.RUNNING -> "运行中"
    RunHistoryStatus.COMPLETED -> "运行完成"
    RunHistoryStatus.FAILED -> "失败"
    RunHistoryStatus.STOPPED -> "已停止"
    RunHistoryStatus.INTERRUPTED -> "已中断"
}

internal fun TraceActionType.historyLabel(): String = when (this) {
    TraceActionType.NONE -> "无"
    TraceActionType.TAP -> "点击"
    TraceActionType.SWIPE -> "滑动"
    TraceActionType.TYPE -> "输入文本"
    TraceActionType.KEY -> "系统按键"
    TraceActionType.WAIT -> "等待"
    TraceActionType.OPEN_APP -> "打开应用"
    TraceActionType.CREATE_TODO -> "保存本地待办"
    TraceActionType.ASK_USER -> "请求人工接管"
    TraceActionType.FINISH -> "模型报告结束"
}
