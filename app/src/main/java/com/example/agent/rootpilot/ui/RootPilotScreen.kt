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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.ApiConfigUiState
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.RootPilotStatus

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RootPilotScreen(
    state: RootPilotUiState,
    apiState: ApiConfigUiState,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onSaveApiConfig: () -> Unit,
    onEditApiConfig: () -> Unit,
    onCancelApiConfigEdit: () -> Unit,
    onClearApiConfig: () -> Unit,
    onTestConnection: () -> Unit,
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
    overlayAllowed: Boolean,
    inputMethodEnabled: Boolean,
    inputMessage: String?,
    onInputMethodSettings: () -> Unit,
    onOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLegacyAgent: () -> Unit = {},
) {
    val busy = state.status in setOf(
        RootPilotStatus.CAPTURING,
        RootPilotStatus.REQUESTING_MODEL,
        RootPilotStatus.EXECUTING,
        RootPilotStatus.WAITING_SCREEN,
        RootPilotStatus.WAITING_CONFIRMATION,
    )
    val recoveryRequired = state.status == RootPilotStatus.RECOVERY_REQUIRED
    val apiControlsEnabled = !busy && !apiState.busy
    val apiReady = apiState.configured && !apiState.editing && !apiState.busy
    val image = state.frame?.let { frame ->
        remember(frame.bytes) {
            BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap()
        }
    }

    Scaffold(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RootPilot", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onOpenLegacyAgent, enabled = !busy && !apiState.editing) {
                Text("旧 Agent（实验入口）")
            }
            TextButton(onClick = onOverlayPermission, enabled = !busy) {
                Text(if (overlayAllowed) "悬浮操作面板已授权 · 管理权限" else "开启悬浮操作面板")
            }
            TextButton(onClick = onInputMethodSettings, enabled = !busy) {
                Text(if (inputMethodEnabled) "Unicode 输入已启用 · 管理输入法" else "启用 RootPilot 输入法（中文 / Unicode）")
            }
            Text("文本通过输入法写入当前光标位置，完成后恢复原输入法；不支持密码框。请先在系统设置中手动启用，平时仍使用常用输入法。")
            inputMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "截图及可启动应用的名称、包名会发送至所配置的 API 服务。默认手机直连 DeepSeek，也可自定义 Relay。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (apiState.configured) "API 已配置" else "API 未配置", modifier = Modifier.testTag("api_status"))
                    if (apiState.editing) {
                        if (apiState.configured) Text("更换配置需重新输入 Token；保存前仍保留原配置。")
                        OutlinedTextField(
                            value = apiState.draft.apiKey,
                            onValueChange = onApiKeyChanged,
                            modifier = Modifier.fillMaxWidth().testTag("api_token"),
                            label = { Text("DeepSeek Token（Relay 可留空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            enabled = apiControlsEnabled,
                        )
                        OutlinedTextField(
                            value = apiState.draft.baseUrl,
                            onValueChange = onBaseUrlChanged,
                            modifier = Modifier.fillMaxWidth().testTag("api_base_url"),
                            label = { Text("API Base URL / Relay 地址") },
                            singleLine = true,
                            enabled = apiControlsEnabled,
                        )
                        OutlinedTextField(
                            value = apiState.draft.model,
                            onValueChange = onModelChanged,
                            modifier = Modifier.fillMaxWidth().testTag("api_model"),
                            label = { Text("模型名称") },
                            singleLine = true,
                            enabled = apiControlsEnabled,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSaveApiConfig, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_save")) {
                                Text("保存配置")
                            }
                            if (apiState.configured) {
                                TextButton(onClick = onCancelApiConfigEdit, enabled = apiControlsEnabled) { Text("取消") }
                            }
                        }
                    } else {
                        Text(apiState.draft.baseUrl)
                        Text(apiState.draft.model)
                        TextButton(onClick = onEditApiConfig, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_edit")) {
                            Text("更换配置")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onTestConnection, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_test")) {
                            Text("测试连接")
                        }
                        TextButton(onClick = onClearApiConfig, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_clear")) {
                            Text("清除配置")
                        }
                    }
                    apiState.message?.let { Text(it, modifier = Modifier.testTag("api_message")) }
                }
            }
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
                "输入支持中文、空格、标点、换行和 emoji，最多 128 个 UTF-16 单元。自动模式可执行点击和滑动；打开应用、输入文本及系统按键始终需要确认。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestRoot, enabled = !busy && !recoveryRequired) { Text("测试 Root") }
                Button(onClick = onCaptureScreen, enabled = !busy && !recoveryRequired) { Text("截取屏幕") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSingleStep, enabled = !busy && !recoveryRequired && apiReady) { Text("单步执行") }
                Button(onClick = onAutoExecute, enabled = !busy && !recoveryRequired && apiReady) { Text("自动执行") }
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
                        Button(onClick = onRecoverInterruptedRun, enabled = apiReady) {
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

internal fun RootPilotAction.describe(): String = when (this) {
    is RootPilotAction.CreateTodo -> "创建本地待办\n标题：$title\n截止时间：${dueAt ?: "无"}\n$reason"
    is RootPilotAction.Tap -> "tap($x,$y)：$reason"
    is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)：$reason"
    is RootPilotAction.OpenApp -> "open_app($packageName)：$reason"
    is RootPilotAction.Type -> "type(${text.length} chars)：$reason"
    is RootPilotAction.Key -> "key($key)：$reason"
    is RootPilotAction.Wait -> "wait($durationMillis)：$reason"
    is RootPilotAction.AskUser -> "ask_user：$message"
    is RootPilotAction.Finish -> "finish($success)：$message"
}

private const val MAX_STEPS = 20
