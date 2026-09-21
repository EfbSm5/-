package com.example.agent.rootpilot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.ApiConfigUiState
import com.example.agent.rootpilot.AppLaunchUiState
import com.example.agent.rootpilot.model.RootPilotUiState

@Composable
internal fun RootPilotSettingsContent(
    state: RootPilotUiState,
    apiState: ApiConfigUiState,
    appLaunchState: AppLaunchUiState,
    busy: Boolean,
    recoveryRequired: Boolean,
    apiReady: Boolean,
    apiControlsEnabled: Boolean,
    overlayAllowed: Boolean,
    inputMethodEnabled: Boolean,
    inputMessage: String?,
    showDebug: Boolean,
    onToggleDebug: () -> Unit,
    onOpenApps: () -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onSaveApiConfig: () -> Unit,
    onEditApiConfig: () -> Unit,
    onCancelApiConfigEdit: () -> Unit,
    onClearApiConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onOverlayPermission: () -> Unit,
    onInputMethodSettings: () -> Unit,
    onManualConfirmationChanged: (Boolean) -> Unit,
    onTestRoot: () -> Unit,
    onCaptureScreen: () -> Unit,
    onSingleStep: () -> Unit,
    onOpenLegacyAgent: () -> Unit,
) {
    Text("API 配置", style = MaterialTheme.typography.titleMedium)
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
                    Button(onClick = onSaveApiConfig, colors = ButtonDefaults.buttonColorsPrimary(), enabled = apiControlsEnabled, modifier = Modifier.testTag("api_save")) {
                        Text("保存配置")
                    }
                    if (apiState.configured) {
                        Button(onClick = onCancelApiConfigEdit, enabled = apiControlsEnabled) { Text("取消") }
                    }
                }
            } else {
                Text(apiState.draft.baseUrl)
                Text(apiState.draft.model)
                Button(onClick = onEditApiConfig, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_edit")) {
                    Text("更换配置")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestConnection, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_test")) {
                    Text("测试连接")
                }
                Button(onClick = onClearApiConfig, enabled = apiControlsEnabled, modifier = Modifier.testTag("api_clear")) {
                    Text("清除配置")
                }
            }
            apiState.message?.let { Text(it, modifier = Modifier.testTag("api_message")) }
        }
    }
    SettingsGroup("应用与权限") {
        Button(
            modifier = Modifier.fillMaxWidth().testTag("launch_apps"),
            onClick = onOpenApps,
        ) {
            Text("允许启动的应用（${appLaunchState.apps.count { it.packageName in appLaunchState.allowedPackages }}）")
        }
        Button(onClick = onOverlayPermission, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (overlayAllowed) "悬浮操作面板已授权 · 管理权限" else "开启悬浮操作面板")
        }
        Button(onClick = onInputMethodSettings, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (inputMethodEnabled) "Unicode 输入已启用 · 管理输入法" else "启用 RootPilot 输入法（中文 / Unicode）")
        }
        Text("文本通过输入法写入当前光标位置，完成后恢复原输入法；不支持密码框。请先在系统设置中手动启用，平时仍使用常用输入法。")
        inputMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    SettingsGroup("执行方式") {
        SettingsToggleRow(
            label = "每一步都需要人工确认",
            checked = state.config.manualConfirmation, enabled = !busy,
            onCheckedChange = onManualConfirmationChanged,
        )
        Text("自动模式可执行点击和滑动；打开应用、输入文本及系统按键始终需要确认。",
            style = MaterialTheme.typography.bodySmall)
        Text("截图及勾选的可启动应用名称、包名会发送至所配置的 API 服务。",
            style = MaterialTheme.typography.bodySmall)
    }
    SettingsGroup("调试工具") {
        Button(onClick = onToggleDebug, modifier = Modifier.testTag("toggle_debug")) {
            Text(if (showDebug) "收起调试工具" else "展开调试工具")
        }
        if (showDebug) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestRoot, enabled = !busy && !recoveryRequired) { Text("测试 Root") }
                Button(onClick = onCaptureScreen, enabled = !busy && !recoveryRequired) { Text("截取屏幕") }
            }
            Button(onClick = onSingleStep, enabled = !busy && !recoveryRequired && apiReady) { Text("单步执行") }
            Text("最近动作：${state.lastAction?.describe() ?: "无"}")
            val image = state.frame?.let { frame ->
                remember(frame.bytes) {
                    BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)?.asImageBitmap()
                }
            }
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

            Button(onClick = onOpenLegacyAgent, enabled = !busy && !apiState.editing) {
                Text("旧 Agent（实验入口）")
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.testTag("manual_confirmation"))
    }
}
