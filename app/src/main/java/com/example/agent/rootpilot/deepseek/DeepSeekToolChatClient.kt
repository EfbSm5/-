package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface DeepSeekToolChatClient {
    suspend fun streamToolChat(
        config: RootPilotConfig,
        messages: List<ToolChatTurn>,
        tools: List<JsonObject>,
        effort: ThinkingEffort,
        onUpdate: suspend (ModelStreamSnapshot) -> Unit,
    ): ToolChatResult
}

data class ToolChatTurn(
    val role: String,
    val content: String,
    val reasoningContent: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    override fun toString() = "ToolChatTurn(contentLength=${content.length}, toolCallCount=${toolCalls.size})"
}

data class ChatToolCall(val id: String, val name: String, val arguments: String) {
    override fun toString() = "ChatToolCall(argumentsLength=${arguments.length})"
}

sealed interface ToolChatResult {
    data class Success(
        val content: String,
        val reasoning: String,
        val toolCalls: List<ChatToolCall>,
    ) : ToolChatResult {
        override fun toString() = "ToolChatResult.Success(contentLength=${content.length}, reasoningLength=${reasoning.length}, toolCallCount=${toolCalls.size})"
    }

    data class Failure(val message: String) : ToolChatResult
}

internal const val MAX_TOOL_CALLS = 128
internal const val MAX_TOOL_ARGUMENT_CHARS = 65_536
internal const val MAX_TOOL_REQUEST_BYTES = 4_194_304
internal const val MAX_TOOL_ID_CHARS = 256
internal val TOOL_NAME_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")

internal fun buildToolChatRequest(
    config: RootPilotConfig,
    messages: List<ToolChatTurn>,
    tools: List<JsonObject>,
    effort: ThinkingEffort,
): String {
    require(messages.isNotEmpty() && messages.size <= 1024)
    require(tools.size in 1..MAX_TOOL_CALLS)
    val names = mutableSetOf<String>()
    tools.forEach { tool ->
        require(tool["type"] == JsonPrimitive("function"))
        val function = tool["function"] as? JsonObject
        val name = function?.get("name") as? JsonPrimitive
        require(name != null && name.isString && TOOL_NAME_PATTERN.matches(name.content) && names.add(name.content))
    }
    val seenIds = mutableSetOf<String>()
    val pendingIds = mutableSetOf<String>()
    var textChars = 0L
    messages.forEach { turn ->
        require(turn.role in setOf("system", "user", "assistant", "tool"))
        require(turn.role == "assistant" || (turn.reasoningContent == null && turn.toolCalls.isEmpty()))
        require(turn.role == "tool" || turn.toolCallId == null)
        if (turn.role == "tool") {
            require(turn.toolCallId != null && pendingIds.remove(turn.toolCallId))
        } else {
            require(pendingIds.isEmpty())
        }
        require(turn.toolCalls.size <= MAX_TOOL_CALLS)
        turn.toolCalls.forEach { call ->
            require(call.id.isNotBlank() && call.id.length <= MAX_TOOL_ID_CHARS && seenIds.add(call.id))
            require(TOOL_NAME_PATTERN.matches(call.name))
            require(call.arguments.length <= MAX_TOOL_ARGUMENT_CHARS)
            pendingIds.add(call.id)
            textChars += call.id.length + call.name.length + call.arguments.length
        }
        textChars += turn.content.length.toLong() + (turn.reasoningContent?.length ?: 0)
        require(textChars <= MAX_TOOL_REQUEST_BYTES)
    }
    require(pendingIds.isEmpty())
    return buildJsonObject {
        put("model", config.model)
        put("stream", true)
        put("max_tokens", 16_384)
        putJsonObject("thinking") { put("type", if (effort == ThinkingEffort.NONE) "disabled" else "enabled") }
        if (effort != ThinkingEffort.NONE) put("reasoning_effort", effort.wireValue)
        put("tools", JsonArray(tools))
        putJsonArray("messages") {
            messages.forEach { turn ->
                add(buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                    // Tool-enabled requests retain reasoning from every assistant turn.
                    turn.reasoningContent?.let { put("reasoning_content", it) }
                    turn.toolCallId?.let { put("tool_call_id", it) }
                    if (turn.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        turn.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            })
                        }
                    }
                })
            }
        }
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_TOOL_REQUEST_BYTES) }
}
