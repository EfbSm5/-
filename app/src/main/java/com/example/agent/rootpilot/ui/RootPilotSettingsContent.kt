package com.example.agent.rootpilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.ApiConfigUiState
import com.example.agent.rootpilot.AppLaunchUiState
import com.example.agent.rootpilot.model.ExecutionDisplay
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
    showPermissions: Boolean,
    onToggleDebug: () -> Unit,
    onOpenPermissions: () -> Unit,
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
    uiTreeConnected: Boolean,
    onAccessibilitySettings: () -> Unit,
    onExecutionDisplayChanged: (ExecutionDisplay) -> Unit,
    onVirtualDisplayStartPackageChanged: (String) -> Unit,
) {
    val virtualDisplay = state.config.executionDisplay == ExecutionDisplay.VIRTUAL
    val displayControlsEnabled = !busy && !recoveryRequired
    if (!showDebug && !showPermissions) {
        Text("API 配置", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(apiState.draft.model, style = MaterialTheme.typography.titleMedium)
                    Text(apiState.draft.baseUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SettingsGroup("任务偏好") {
            Button(
                modifier = Modifier.fillMaxWidth().testTag("launch_apps"),
                onClick = onOpenApps,
            ) {
                Text("允许启动的应用（${appLaunchState.apps.count { it.packageName in appLaunchState.allowedPackages }}）")
            }
            SettingsToggleRow(
                label = "独立副屏（实验）",
                checked = virtualDisplay, enabled = displayControlsEnabled,
                onCheckedChange = { onExecutionDisplayChanged(if (it) ExecutionDisplay.VIRTUAL else ExecutionDisplay.MAIN) },
                testTag = "execution_display_virtual",
            )
            if (virtualDisplay) {
                VirtualDisplayAppSelector(
                    appLaunchState = appLaunchState,
                    selectedPackage = state.config.virtualDisplayStartPackage,
                    enabled = displayControlsEnabled,
                    onSelected = onVirtualDisplayStartPackageChanged,
                )
                Text("每次任务新建副屏，退出会关闭其中页面；已有应用可能被迁到副屏，不提供账号或数据隔离。",
                    style = MaterialTheme.typography.bodySmall)
                Text("支持应用启动、点击、滑动、BACK／ENTER及普通文本输入。文字输入需开启页面结构读取：空框整段填写，非空框仅按明确旧文和光标／选区插入或替换选中内容；旧文和最终整框均最多128 UTF-16。输入会重建纯文本，不保证格式、撤销或 composing；不支持密码／敏感字段或 HOME，目标变化即拒绝且不重放。起始应用需确认打开；同意上传后，副屏截图仍发送至所配置的 API。",
                    style = MaterialTheme.typography.bodySmall)
                if (recoveryRequired) {
                    Text("请先恢复或放弃上次任务，再修改执行屏幕和起始应用。", style = MaterialTheme.typography.bodySmall)
                }
            }
            SettingsToggleRow(
                label = "每一步都需要人工确认",
                checked = virtualDisplay || state.config.manualConfirmation, enabled = !busy && !virtualDisplay,
                onCheckedChange = onManualConfirmationChanged,
            )
            Text(if (virtualDisplay) "副屏实验模式下，打开应用、点击、滑动、文字输入及按键都需要确认。"
                else "自动模式可执行点击和滑动；打开应用、输入文本及系统按键始终需要确认。",
                style = MaterialTheme.typography.bodySmall)
            Text("同意上传后，截图、当前任务的 Activity 信息、页面控件结构及勾选的应用名称、包名会发送至所配置的 API 服务。",
                style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup("更多") {
            Button(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth().testTag("open_permissions")) {
                Text("权限与输入")
            }
            Text("悬浮窗${if (overlayAllowed) "已授权" else "未授权"} · 输入法${if (inputMethodEnabled) "已启用" else "未启用"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onToggleDebug, modifier = Modifier.fillMaxWidth().testTag("toggle_debug")) {
                Text("调试工具")
            }
        }
    }
    if (showPermissions) {
        SettingsGroup("系统权限") {
            Button(onClick = onOverlayPermission, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (overlayAllowed) "悬浮操作面板已授权 · 管理权限" else "开启悬浮操作面板")
            }
            Button(onClick = onInputMethodSettings, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (inputMethodEnabled) "Unicode 输入已启用 · 管理输入法" else "启用 RootPilot 输入法（中文 / Unicode）")
            }
            Text("主屏文本通过输入法写入当前光标位置，完成后恢复原输入法；不支持密码框。副屏空框填写通过页面结构服务完成，不切换输入法。主屏输入请先手动启用 RootPilot 输入法，平时仍使用常用输入法。",
                style = MaterialTheme.typography.bodySmall)
            inputMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onAccessibilitySettings, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("accessibility_settings")) {
                Text(if (uiTreeConnected) "页面结构读取已连接 · 管理服务" else "启用页面结构读取（可选）")
            }
            Text("手动启用 RootPilot 页面结构读取后，任务可按需查询控件文字、位置和状态，并在逐次确认后填写本次副屏的空白普通输入框。不点击、不填写主屏或密码／敏感框；未启用时仍可用截图，读取结果不保存。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showDebug) {
        SettingsGroup("诊断与实验") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestRoot, enabled = !busy && !recoveryRequired) { Text("测试 Root") }
                Button(onClick = onCaptureScreen, enabled = !busy && !recoveryRequired && !virtualDisplay) { Text("截取屏幕") }
            }
            Button(onClick = onSingleStep, enabled = !busy && !recoveryRequired && apiReady) { Text("单步执行") }
            if (virtualDisplay) {
                Text("副屏单步每次新建和关闭副屏，确认打开起始应用后执行一个规划动作；下次单步重新开始。副屏模式不提供任务外截屏。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("最近动作：${state.lastAction?.describe() ?: "无"}")
            val frame = state.frame?.takeIf { !virtualDisplay }
            val imageState = rememberFrameImageState(frame?.bytes)
            if (frame != null) {
                Text("当前截图：${frame.width}x${frame.height}")
                when (imageState) {
                    is FrameImageState.Ready -> Image(
                        bitmap = imageState.image,
                        contentDescription = "当前手机屏幕截图",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    )

                    FrameImageState.Undecodable -> Text("截图无法显示，请重新截取或检查任务状态。",
                        style = MaterialTheme.typography.bodySmall)

                    FrameImageState.Empty, FrameImageState.Decoding -> Unit
                }
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
private fun VirtualDisplayAppSelector(
    appLaunchState: AppLaunchUiState,
    selectedPackage: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val apps = appLaunchState.apps.filter { it.packageName in appLaunchState.allowedPackages }
    val selectedApp = apps.firstOrNull { it.packageName == selectedPackage }
    val selectionEnabled = enabled && !appLaunchState.busy
    Text("副屏起始应用", style = MaterialTheme.typography.titleSmall)
    Box(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true }, enabled = selectionEnabled,
            modifier = Modifier.fillMaxWidth().testTag("virtual_display_start_app"),
        ) {
            Text(when {
                selectedApp != null -> "${selectedApp.label}\n$selectedPackage"
                appLaunchState.busy -> "正在核对起始应用…"
                selectedPackage.isNotEmpty() -> "已选应用不可用：$selectedPackage"
                else -> "请选择起始应用"
            })
        }
        DropdownMenu(
            expanded = expanded && selectionEnabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            DropdownMenuItem(
                text = { Text("不选择起始应用") },
                onClick = { expanded = false; onSelected("") },
            )
            apps.forEach { app ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { expanded = false; onSelected(app.packageName) },
                    modifier = Modifier.testTag("virtual_display_app_${app.packageName}"),
                )
            }
        }
    }
    when {
        appLaunchState.busy -> Text("正在读取或保存应用列表…", style = MaterialTheme.typography.bodySmall)
        selectedPackage.isNotEmpty() && selectedApp == null -> Text(
            "起始应用不可用：不在允许启动列表或已卸载。请重新允许该应用，或在可编辑时重新选择起始应用。",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
        apps.isEmpty() -> Text("请先在允许启动的应用中勾选应用，再明确选择起始应用。", style = MaterialTheme.typography.bodySmall)
        selectedPackage.isEmpty() -> Text("需明确选择起始应用后才能开始；选择本身不会启动应用。", style = MaterialTheme.typography.bodySmall)
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
    testTag: String = "manual_confirmation",
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.testTag(testTag))
    }
}
