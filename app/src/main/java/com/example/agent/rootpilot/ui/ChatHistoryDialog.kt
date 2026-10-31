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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.chat.ChatHistoryError
import com.example.agent.rootpilot.chat.ChatHistoryState
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.Text

/** Saved conversations are only listed here; opening one never sends anything until the user sends a new message. */
@Composable
internal fun ChatHistoryDialog(
    state: ChatHistoryState,
    currentSessionId: String?,
    generating: Boolean,
    onOpen: ((String) -> Unit)?,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val confirmDelete = state.sessions.firstOrNull { it.id == confirmDeleteId }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("聊天历史") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅保存普通聊天中已完成的问答（不含思考过程），在本机加密存储，最多保留最近 30 个会话；文件 Agent 对话不保存。" +
                    "续聊时，该会话的已完成问答会作为上下文发送给当前配置的模型。",
                    style = MaterialTheme.typography.bodySmall)
                if (onOpen == null) Text("文件 Agent 开启时不能打开历史会话。", Modifier.testTag("chat_history_open_blocked"),
                    style = MaterialTheme.typography.bodySmall)
                if (state.loading) Text("正在读取…", Modifier.testTag("chat_history_loading"))
                state.error?.let {
                    Text(when (it) {
                        ChatHistoryError.READ_FAILED -> "历史读取失败，列表可能不完整。可清除全部后重新记录。"
                        ChatHistoryError.WRITE_FAILED -> "最近一次会话保存失败，当前对话可能未保存。"
                        ChatHistoryError.DELETE_FAILED -> "删除未完成，请重试。"
                        ChatHistoryError.CLEAR_FAILED -> "清除未完成，列表为本机剩余会话，请重试。"
                    }, Modifier.testTag("chat_history_error"), color = MaterialTheme.colorScheme.error)
                }
                if (state.unreadable > 0) {
                    Text("另有 ${state.unreadable} 个会话无法解密或已损坏，未列出；可通过“清除全部”删除。",
                        Modifier.testTag("chat_history_unreadable"), style = MaterialTheme.typography.bodySmall)
                }
                when {
                    confirmClear -> {
                        Text("清除全部聊天历史？此操作不可恢复，当前打开的已保存会话也会关闭；不影响 API 配置和任务历史。")
                        Row {
                            TextButton(onClick = { confirmClear = false }) { Text("取消") }
                            TextButton(onClick = { confirmClear = false; onClear() }, enabled = !generating,
                                modifier = Modifier.testTag("chat_history_confirm_clear")) { Text("确认清除") }
                        }
                    }
                    confirmDelete != null -> {
                        Text("删除“${confirmDelete.title}”？此操作不可恢复" +
                            if (confirmDelete.id == currentSessionId) "，当前对话也会清空。" else "。")
                        Row {
                            TextButton(onClick = { confirmDeleteId = null }) { Text("取消") }
                            TextButton(onClick = { confirmDeleteId = null; onDelete(confirmDelete.id) }, enabled = !generating,
                                modifier = Modifier.testTag("chat_history_confirm_delete")) { Text("确认删除") }
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("chat_history_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.loading && state.sessions.isEmpty()) {
                            item { Text("暂无已保存的会话", Modifier.testTag("chat_history_empty")) }
                        }
                        itemsIndexed(state.sessions, key = { _, session -> session.id }) { index, session ->
                            Column(Modifier.fillMaxWidth().testTag("chat_history_item_$index")) {
                                Text(session.title.ifEmpty { "（无标题）" }, maxLines = 2)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(session.updatedAtEpochMs)) + " · ${session.messageCount / 2} 轮问答" +
                                    if (session.id == currentSessionId) " · 当前会话" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(onClick = { onOpen?.invoke(session.id); onClose() },
                                        enabled = onOpen != null && !generating && session.id != currentSessionId,
                                        modifier = Modifier.testTag("chat_history_open_$index")) { Text("继续聊天") }
                                    TextButton(onClick = { confirmDeleteId = session.id }, enabled = !generating,
                                        modifier = Modifier.testTag("chat_history_delete_$index")) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("chat_history_close")) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDeleteId = null; confirmClear = true },
                enabled = !generating && !confirmClear &&
                    (state.sessions.isNotEmpty() || state.unreadable > 0 || state.error != null),
                modifier = Modifier.testTag("chat_history_clear")) { Text("清除全部") }
        },
    )
}
