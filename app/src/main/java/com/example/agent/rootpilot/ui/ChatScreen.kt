package com.example.agent.rootpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.chat.ChatUiState
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    configured: Boolean,
    onDraftChange: (String) -> Unit,
    onEffortChange: (ThinkingEffort) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardVisible = WindowInsets.isImeVisible
    val list = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.isScrollInProgress, list.canScrollForward, autoScrolling) }
            .collect { (scrolling, canScroll, automatic) ->
                if (scrolling && !automatic) followTail = !canScroll
            }
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()) {
        if (state.messages.isEmpty()) followTail = true
        if (followTail && !list.isScrollInProgress) {
            withFrameNanos { }
            if (followTail && !list.isScrollInProgress) {
                autoScrolling = true
                try { list.scrollToItem(state.messages.size) } finally { autoScrolling = false }
            }
        }
    }
    Column(modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Preserve room for send/stop when the IME reduces the viewport, especially at large font sizes.
        if (!keyboardVisible) {
            Text("聊天不截图、不操作设备；历史仅保留在本次会话内存中，不跨进程。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("DeepSeek 聊天", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onNewConversation, enabled = !state.generating,
                    modifier = Modifier.testTag("chat_new")) { Text("新对话") }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.messages, key = { it.id }) { message ->
                var showReasoning by rememberSaveable(message.id) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().testTag("chat_message_${message.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (message.role == "user") "你" else "DeepSeek", style = MaterialTheme.typography.labelLarge)
                        if (message.reasoning.isNotEmpty()) {
                            Button(onClick = { showReasoning = !showReasoning },
                                modifier = Modifier.testTag("chat_reasoning_${message.id}")) {
                                Text(if (showReasoning) "收起思考" else "展开思考")
                            }
                            if (showReasoning) StreamingMarkdown(message.reasoning,
                                Modifier.fillMaxWidth().testTag("chat_reasoning_body_${message.id}"))
                        }
                        if (message.content.isNotEmpty()) StreamingMarkdown(message.content,
                            Modifier.fillMaxWidth().testTag("chat_content_${message.id}"))
                        if (message.role == "assistant" && !message.complete) {
                            if (message.id == state.messages.lastOrNull()?.id && state.generating) {
                                Text("正在生成…", Modifier.testTag("chat_generating_${message.id}"))
                            } else {
                                Text("未完成，不用于后续上下文", Modifier.testTag("chat_incomplete_${message.id}"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(1.dp)) }
        }
        if (!configured) Text("请先在设置中保存 API 配置。")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!keyboardVisible) Text("思考强度：${state.effort.displayLabel()}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThinkingEffort.entries.forEach { effort ->
                val selected = state.effort == effort
                Button(onClick = { onEffortChange(effort) }, enabled = !state.generating,
                    modifier = Modifier.weight(1f).testTag("chat_effort_${effort.name}")
                        .semantics { this.selected = selected },
                    minWidth = 0.dp, minHeight = 48.dp,
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                ) {
                    Text(effort.displayLabel(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
        if (!keyboardVisible) Text("较高档位可能更慢，单次请求时限为 120 秒。", style = MaterialTheme.typography.bodySmall)
        TextField(value = state.draft, onValueChange = onDraftChange,
            label = "消息", maxLines = if (keyboardVisible) 3 else 5,
            modifier = Modifier.fillMaxWidth().testTag("chat_draft"))
        Text("${state.draft.length}/16000", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = configured && !state.generating && state.draft.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f).testTag("chat_send")) { Text("发送") }
            if (state.generating) Button(onClick = onStop, modifier = Modifier.testTag("chat_stop")) { Text("停止") }
        }
    }
}

private fun ThinkingEffort.displayLabel(): String = when (this) {
    ThinkingEffort.NONE -> "关闭"
    ThinkingEffort.LOW -> "低"
    ThinkingEffort.HIGH -> "高"
    ThinkingEffort.MAX -> "最高"
}
