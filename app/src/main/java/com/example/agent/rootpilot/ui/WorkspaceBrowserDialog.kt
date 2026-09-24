package com.example.agent.rootpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.files.FileEntry
import com.example.agent.rootpilot.files.WorkspaceBrowserState
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun WorkspaceBrowserDialog(
    state: WorkspaceBrowserState,
    onSelect: (FileEntry) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    DisposableEffect(Unit) { onDispose { onClose() } }
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (state.previewPath == null) "工作区文件" else "文本预览") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅在本机浏览，不会发送给模型", style = MaterialTheme.typography.bodySmall)
                Text(state.previewPath ?: state.path.ifEmpty { "授权目录" }, Modifier.testTag("workspace_path"))
                Row {
                    if (state.previewPath != null || state.path.isNotEmpty()) {
                        TextButton(onClick = onBack, enabled = !state.busy,
                            modifier = Modifier.testTag("workspace_back")) { Text("返回上级") }
                    }
                    if (state.previewPath == null) {
                        TextButton(onClick = onRefresh, enabled = !state.busy,
                            modifier = Modifier.testTag("workspace_refresh")) { Text("刷新") }
                    }
                }
                if (state.busy) Text("正在读取…", Modifier.testTag("workspace_loading"))
                state.error?.let { Text(it, Modifier.testTag("workspace_error"), color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("workspace_list")) {
                    if (state.previewPath != null) {
                        state.text?.let { text ->
                            item { Text(if (text.isEmpty()) "（空文件）" else text, Modifier.testTag("workspace_preview")) }
                        }
                    } else if (!state.busy && state.error == null) {
                        if (state.entries.isEmpty()) item { Text("目录为空", Modifier.testTag("workspace_empty")) }
                        itemsIndexed(state.entries) { index, entry ->
                            TextButton(onClick = { onSelect(entry) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("workspace_entry_$index")) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(entry.name)
                                    Text(if (entry.isDirectory) "文件夹 · 点击进入" else "文件 · 点击预览",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("workspace_close")) { Text("关闭") } },
    )
}
