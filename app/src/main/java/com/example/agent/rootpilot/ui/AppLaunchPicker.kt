package com.example.agent.rootpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.AppLaunchUiState

@Composable
internal fun AppLaunchPicker(
    state: AppLaunchUiState,
    onAllowedChanged: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = state.apps.filter {
        it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.Inherit),
    ) {
        RootPilotSettingsTheme {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("允许启动的应用", style = MaterialTheme.typography.titleLarge)
                    Text("选择会自动保存，首次默认不选。只限制 RootPilot 的打开应用动作，不阻止链接跳转、其他界面操作或当前屏幕上传。", style = MaterialTheme.typography.bodySmall)
                    Text("已允许 ${state.apps.count { it.packageName in state.allowedPackages }} 个应用")
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("搜索名称或包名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("launch_app_search"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onClear, enabled = !state.busy, modifier = Modifier.testTag("launch_apps_clear")) { Text("全不选") }
                        Button(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
                    }
                    if (state.busy) Text("正在读取或保存…")
                    state.message?.let { Text(it) }
                    if (apps.isEmpty() && !state.busy) Text(if (query.isBlank()) "没有可启动的应用" else "没有匹配的应用")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            val checked = app.packageName in state.allowedPackages
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("launch_app_${app.packageName}")
                                    .toggleable(value = checked, enabled = !state.busy, role = Role.Checkbox,
                                        onValueChange = { onAllowedChanged(app.packageName, it) })
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off,
                                    onClick = null, enabled = !state.busy)
                                Column(Modifier.weight(1f)) {
                                    Text(app.label)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                }
            }
        }
    }
}
