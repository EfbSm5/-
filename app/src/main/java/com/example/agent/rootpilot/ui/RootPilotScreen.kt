package com.example.agent.rootpilot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.RootPilotViewModel
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.RootPilotStatus

@Composable
fun RootPilotScreen(
    state: RootPilotUiState,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onTaskChanged: (String) -> Unit,
    onTestRoot: () -> Unit,
    onCaptureScreen: () -> Unit,
    onSingleStep: () -> Unit,
    onAutoExecute: () -> Unit,
    onStop: () -> Unit,
    onConfirmAction: () -> Unit,
    onRecoverInterruptedRun: () -> Unit,
    onDiscardInterruptedRun: () -> Unit,
    onManualConfirmationChanged: (Boolean) -> Unit,
    onScreenUploadChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.status in setOf(
        RootPilotStatus.CAPTURING,
        RootPilotStatus.REQUESTING_MODEL,
        RootPilotStatus.EXECUTING,
        RootPilotStatus.WAITING_SCREEN,
        RootPilotStatus.WAITING_CONFIRMATION,
    )
    val recoveryRequired = state.status == RootPilotStatus.RECOVERY_REQUIRED
    val image = state.frame?.let { frame ->
        remember(frame.bytes) {
            BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap()
        }
    }

    Scaffold(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RootPilot", style = MaterialTheme.typography.headlineMedium)
            Text(
                "个人 Root 手机智能操作 Demo。截图会通过 Relay 上传给 DeepSeek。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.config.apiKey,
                onValueChange = onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepSeek API Key（Relay 模式可留空）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = state.config.baseUrl,
                onValueChange = onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Base URL / Relay 地址") },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = state.config.model,
                onValueChange = onModelChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = state.config.task,
                onValueChange = onTaskChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自然语言任务") },
                minLines = 3,
                enabled = !busy,
            )
            ToggleRow(
                label = "允许上传当前屏幕截图",
                checked = state.config.allowScreenUpload,
                enabled = !busy,
                onCheckedChange = onScreenUploadChanged,
            )
            ToggleRow(
                label = "每一步都需要人工确认",
                checked = state.config.manualConfirmation,
                enabled = !busy,
                onCheckedChange = onManualConfirmationChanged,
            )
            Text(
                "输入动作仅支持安全 ASCII（字母、数字和 ._@+-）；自动模式会执行点击和滑动，输入文本及系统按键仍需确认。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestRoot, enabled = !busy && !recoveryRequired) { Text("测试 Root") }
                Button(onClick = onCaptureScreen, enabled = !busy && !recoveryRequired) { Text("截取屏幕") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSingleStep, enabled = !busy && !recoveryRequired) { Text("单步执行") }
                Button(onClick = onAutoExecute, enabled = !busy && !recoveryRequired) { Text("自动执行") }
                Button(onClick = onStop, enabled = busy) {
                    Text("立即停止")
                }
            }
            if (state.status == RootPilotStatus.WAITING_CONFIRMATION) {
                Button(onClick = onConfirmAction, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.pendingAction is RootPilotAction.AskUser) {
                            "我已按提示处理，继续"
                        } else {
                            "确认执行当前动作"
                        },
                    )
                }
            }

            if (recoveryRequired) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("上次任务中断", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "无法确认上一步 Root 动作是否已经生效，不会自动重放。" +
                                "请确认当前屏幕后重新规划，或放弃上次任务。",
                        )
                        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = onRecoverInterruptedRun) {
                            Text("从当前屏幕重新规划")
                        }
                        TextButton(onClick = onDiscardInterruptedRun) {
                            Text("放弃上次任务")
                        }
                    }
                }
            }

            Text("状态：${state.status.displayName()}")
            Text("当前步骤：${state.step + 1} / $MAX_STEPS")
            Text("最近动作：${state.lastAction?.describe() ?: "无"}")
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            image?.let {
                Text("当前截图：${state.frame.width}x${state.frame.height}")
                Image(
                    bitmap = it,
                    contentDescription = "当前手机屏幕截图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                )
            }

            HorizontalDivider()
            Text("执行日志", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.logs) { log -> Text(log, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun RootPilotStatus.displayName(): String = when (this) {
    RootPilotStatus.IDLE -> "空闲"
    RootPilotStatus.CAPTURING -> "截取屏幕"
    RootPilotStatus.REQUESTING_MODEL -> "请求模型"
    RootPilotStatus.WAITING_CONFIRMATION -> "等待人工确认"
    RootPilotStatus.EXECUTING -> "执行动作"
    RootPilotStatus.WAITING_SCREEN -> "等待页面稳定"
    RootPilotStatus.COMPLETED -> "已完成"
    RootPilotStatus.FAILED -> "失败"
    RootPilotStatus.STOPPED -> "已停止"
    RootPilotStatus.RECOVERY_REQUIRED -> "需要恢复确认"
}

private fun RootPilotAction.describe(): String = when (this) {
    is RootPilotAction.Tap -> "tap($x,$y)：$reason"
    is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)：$reason"
    is RootPilotAction.Type -> "type(${text.length} chars)：$reason"
    is RootPilotAction.Key -> "key($key)：$reason"
    is RootPilotAction.Wait -> "wait($durationMillis)：$reason"
    is RootPilotAction.AskUser -> "ask_user：$message"
    is RootPilotAction.Finish -> "finish($success)：$message"
}

private const val MAX_STEPS = 20
