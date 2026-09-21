package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.deepseek.ChatTurn
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekChatClient
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val complete: Boolean = false,
) {
    override fun toString() = "ChatMessage(id=$id, role=$role, complete=$complete)"
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val effort: ThinkingEffort = ThinkingEffort.HIGH,
    val generating: Boolean = false,
    val error: String? = null,
) {
    override fun toString() = "ChatUiState(messages=${messages.size}, generating=$generating)"
}

/** Conversation content is memory-only and is never part of device task recovery. */
class ChatController(
    private val scope: CoroutineScope,
    private val client: DeepSeekChatClient,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(ChatUiState())
    val state = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var nextId = 0L
    private var stopping = false
    private var boundConfig: RootPilotConfig? = null

    fun updateDraft(value: String) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(draft = value.take(MAX_INPUT_CHARS))
    }

    fun setEffort(value: ThinkingEffort) = synchronized(lock) {
        if (activeJob == null) mutableState.value = mutableState.value.copy(effort = value)
    }

    fun newConversation() = synchronized(lock) {
        if (activeJob == null) {
            boundConfig = null
            mutableState.value = ChatUiState(effort = mutableState.value.effort)
        }
    }

    fun send(config: RootPilotConfig, configured: Boolean) = synchronized(lock) {
        var current = mutableState.value
        if (activeJob != null || current.draft.isBlank()) return@synchronized
        if (!configured) {
            mutableState.value = current.copy(error = "请先在设置中保存 API 配置")
            return@synchronized
        }
        val previousConfig = boundConfig
        if (previousConfig != null && (previousConfig.apiKey != config.apiKey ||
                previousConfig.baseUrl != config.baseUrl || previousConfig.model != config.model)) {
            current = current.copy(messages = emptyList(), error = null)
            mutableState.value = current
        }
        boundConfig = config.copy(task = "")
        val prompt = current.draft.trim()
        // Incomplete/cancelled exchanges remain visible but never become model history.
        val history = current.messages.chunked(2)
            .filter { it.size == 2 && it.all(ChatMessage::complete) }
            .flatten().map { ChatTurn(it.role, it.content) } + ChatTurn("user", prompt)
        if (history.sumOf { it.content.length } > MAX_HISTORY_CHARS || current.messages.size >= MAX_MESSAGES) {
            mutableState.value = current.copy(error = "当前会话已达长度上限，请新建对话")
            return@synchronized
        }
        val user = ChatMessage(++nextId, "user", prompt)
        val assistant = ChatMessage(++nextId, "assistant")
        mutableState.value = current.copy(
            messages = current.messages + user + assistant, draft = "", generating = true, error = null,
        )
        stopping = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = client.streamChat(config, history, current.effort) { snapshot ->
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (!stopping) updateAssistant(assistant.id, snapshot.content, snapshot.reasoning)
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (!stopping) when (result) {
                        is DeepSeekActionResult.Success -> mutableState.value = mutableState.value.copy(
                            messages = mutableState.value.messages.map {
                                when (it.id) {
                                    user.id -> it.copy(complete = true)
                                    assistant.id -> it.copy(content = result.rawActionJson, complete = true)
                                    else -> it
                                }
                            },
                        )
                        is DeepSeekActionResult.Failure -> mutableState.value = mutableState.value.copy(error = result.message)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!stopping) mutableState.value = mutableState.value.copy(error = "聊天请求失败，请稍后重试")
                }
            }
        }
        activeJob = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJob === job) {
                    activeJob = null
                    stopping = false
                    mutableState.value = mutableState.value.copy(generating = false)
                }
            }
        }
        job.start()
        Unit
    }

    fun stop() = synchronized(lock) {
        if (activeJob == null || stopping) return@synchronized
        stopping = true
        mutableState.value = mutableState.value.copy(error = "已停止生成；未完成的回复不会用于后续上下文")
        // Keep generating true until the socket reader has finished cancellation cleanup.
        activeJob?.cancel()
        Unit
    }

    private fun updateAssistant(id: Long, content: String, reasoning: String) {
        mutableState.value = mutableState.value.copy(messages = mutableState.value.messages.map {
            if (it.id == id) it.copy(content = content, reasoning = reasoning) else it
        })
    }

    private companion object {
        const val MAX_INPUT_CHARS = 16_000
        const val MAX_HISTORY_CHARS = 64_000
        const val MAX_MESSAGES = 100
    }
}
