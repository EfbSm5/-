package com.example.agent.agent.planning

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal object DeepSeekResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }

    fun parseContent(rawResponse: String): String {
        val response = json.decodeFromString<ChatResponse>(rawResponse)
        return response.choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw SerializationException("DeepSeek 返回内容为空")
    }

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
    )

    @Serializable
    private data class Choice(
        val message: Message,
    )

    @Serializable
    private data class Message(
        val content: String? = null,
    )
}
