package com.example.agent.rootpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.chat.FileAgentUiState
import com.example.agent.rootpilot.files.TextChangeDiff
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun FileAgentPanel(
    state: FileAgentUiState,
    generating: Boolean,
    onChooseDirectory: () -> Unit,
    onClearDirectory: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onExportBackup: (String) -> Unit,
    onBrowse: (() -> Unit)? = null,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    val canConfigure = !generating && !state.busy
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (state.enabled) "文件 Agent · 已开启" else "文件 Agent · 已关闭",
                style = MaterialTheme.typography.labelLarge)
            state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { managing = true }, enabled = canConfigure,
            modifier = Modifier.testTag("file_agent_manage")) { Text("工作区") }
    }
    if (managing) AlertDialog(
        onDismissRequest = { managing = false },
        title = { Text("文件工作区") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.directoryLabel ?: "尚未授权目录")
                Text("开启后，模型可读取所选目录的文本文件；文件名与读取内容会发送至当前 API 服务。请只选择专用、非敏感目录。写入每次都需确认，不支持删除或 Shell。")
                Text("切换模式、更换或撤销目录会清空本次聊天。备份仅保存在本机，卸载应用会丢失，请及时导出。")
                if (onBrowse != null) TextButton(onClick = { managing = false; onBrowse() },
                    enabled = canConfigure && state.directoryLabel != null,
                    modifier = Modifier.testTag("file_agent_browse")) { Text("浏览文件（仅本机）") }
                TextButton(onClick = { managing = false; onChooseDirectory() }, enabled = canConfigure,
                    modifier = Modifier.testTag("file_agent_choose")) { Text("选择或更换目录") }
                TextButton(onClick = { onEnabledChange(!state.enabled); managing = false },
                    enabled = canConfigure && state.directoryLabel != null,
                    modifier = Modifier.testTag("file_agent_enable")) {
                    Text(if (state.enabled) "关闭并清空聊天" else "同意发送文件内容并开启")
                }
                TextButton(onClick = { onClearDirectory(); managing = false },
                    enabled = canConfigure && state.directoryLabel != null,
                    modifier = Modifier.testTag("file_agent_clear")) { Text("撤销目录授权并清空聊天") }
                if (state.backups.isNotEmpty()) {
                    Text("原文备份（导出后可手动恢复）")
                    state.backups.forEach { backup ->
                        TextButton(onClick = { managing = false; onExportBackup(backup.id) }, enabled = canConfigure) {
                            Text("导出 ${backup.label}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { managing = false }) { Text("关闭") } },
    )
}

@Composable
internal fun FileWriteConfirmation(state: FileAgentUiState, onDecision: (String, Boolean) -> Unit) {
    val pending = state.pending ?: return
    var fullText by remember(pending.id) { mutableStateOf(false) }
    val diff = remember(pending.id, pending.before, pending.after) { TextChangeDiff.compute(pending.before, pending.after) }
    AlertDialog(
        onDismissRequest = { onDecision(pending.id, false) },
        title = { Text(if (pending.before == null) "确认创建文件" else "确认修改文件") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pending.path)
                Text("写入前会检查文件是否变化；修改原文会先留备份。停止不能撤销已经写入的内容。")
                Text("差异：删除 ${diff.removedLineCount} 行 · 新增 ${diff.addedLineCount} 行",
                    modifier = Modifier.testTag("file_diff_summary"))
                TextButton(onClick = { fullText = !fullText }, modifier = Modifier.testTag("file_diff_toggle")) {
                    Text(if (fullText) "查看差异" else "查看完整文本")
                }
                if (fullText) {
                    Text("修改前", style = MaterialTheme.typography.titleSmall)
                    Text(pending.before ?: "（新文件）", modifier = Modifier.testTag("file_before"))
                    Text("修改后", style = MaterialTheme.typography.titleSmall)
                    Text(pending.after, modifier = Modifier.testTag("file_after"))
                } else {
                    Text("− 删除 / + 新增；行号为旧行:新行。按变更区段展示，非最小差异。",
                        style = MaterialTheme.typography.bodySmall)
                    if (diff.truncated) Text("部分上下文或差异已省略，请查看完整文本后确认。",
                        Modifier.testTag("file_diff_truncated"), color = MaterialTheme.colorScheme.error)
                    if (pending.before == pending.after) Text("内容未变化")
                    if (pending.before == null && pending.after.isEmpty()) Text("创建空文件")
                    diff.lines.forEachIndexed { index, line ->
                        val prefix = when (line.kind) {
                            TextChangeDiff.Kind.ADDED -> "+"
                            TextChangeDiff.Kind.REMOVED -> "−"
                            TextChangeDiff.Kind.UNCHANGED -> " "
                        }
                        val background = when (line.kind) {
                            TextChangeDiff.Kind.ADDED -> MaterialTheme.colorScheme.secondaryContainer
                            TextChangeDiff.Kind.REMOVED -> MaterialTheme.colorScheme.errorContainer
                            TextChangeDiff.Kind.UNCHANGED -> MaterialTheme.colorScheme.surface
                        }
                        val foreground = when (line.kind) {
                            TextChangeDiff.Kind.ADDED -> MaterialTheme.colorScheme.onSecondaryContainer
                            TextChangeDiff.Kind.REMOVED -> MaterialTheme.colorScheme.onErrorContainer
                            TextChangeDiff.Kind.UNCHANGED -> MaterialTheme.colorScheme.onSurface
                        }
                        val ending = when (line.ending) {
                            TextChangeDiff.LineEnding.NONE -> "无末尾换行"
                            else -> line.ending.name
                        }
                        Text("$prefix ${line.oldLineNumber ?: "·"}:${line.newLineNumber ?: "·"}  ${line.text}  ⟦$ending⟧",
                            modifier = Modifier.fillMaxWidth().background(background).padding(6.dp).testTag("file_diff_line_$index"),
                            color = foreground, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(pending.id, true) }, Modifier.testTag("file_write_confirm")) { Text("本次允许写入") }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(pending.id, false) }, Modifier.testTag("file_write_reject")) { Text("拒绝") }
        },
    )
}
