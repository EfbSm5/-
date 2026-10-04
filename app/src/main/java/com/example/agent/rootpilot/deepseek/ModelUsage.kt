package com.example.agent.rootpilot.deepseek

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
enum class ModelFinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, OTHER }

/** Observed response counts only; absent server token fields remain unknown. */
@Serializable
data class ModelUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
    val usageMalformed: Boolean = false,
    val observedReasoningChars: Long = 0,
    val observedContentChars: Long = 0,
    val observedWhitespaceChars: Long = 0,
    val finishReason: ModelFinishReason? = null,
) {
    init {
        require(listOf(promptTokens, completionTokens, reasoningTokens, totalTokens).all { it == null || it in 0..2_000_000 })
        require(listOf(observedReasoningChars, observedContentChars).all { it in 0..4_194_304 })
        require(observedWhitespaceChars in 0..observedContentChars)
    }
}

/** Request-local accumulator. It never owns text and cannot accept or reject a response. */
internal class ModelUsageCollector {
    private var prompt: Long? = null
    private var completion: Long? = null
    private var reasoning: Long? = null
    private var total: Long? = null
    private var malformed = false
    private var reasoningChars = 0L
    private var contentChars = 0L
    private var whitespaceChars = 0L
    private var finish: ModelFinishReason? = null

    fun observeUsage(value: JsonElement?) {
        if (value == null || value == JsonNull) return
        if (value !is JsonObject) { malformed = true; return }
        fun number(parent: JsonObject, name: String): Long? {
            val field = parent[name] ?: return null
            val number = (field as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (number == null || number !in 0..2_000_000) { malformed = true; return null }
            return number
        }
        prompt = number(value, "prompt_tokens")
        completion = number(value, "completion_tokens")
        total = number(value, "total_tokens")
        reasoning = when (val details = value["completion_tokens_details"]) {
            is JsonObject -> number(details, "reasoning_tokens")
            null, JsonNull -> null
            else -> { malformed = true; null }
        }
        if (reasoning != null && completion != null && reasoning!! > completion!!) malformed = true
        if (prompt != null && completion != null && total != null && prompt!! + completion!! != total) malformed = true
    }

    fun observeText(thought: String, answer: String) {
        reasoningChars += thought.length
        contentChars += answer.length
        whitespaceChars += answer.count(Char::isWhitespace)
    }

    fun observeFinish(value: String?) {
        if (value == null) return
        finish = when (value) {
            "stop" -> ModelFinishReason.STOP
            "length" -> ModelFinishReason.LENGTH
            "tool_calls" -> ModelFinishReason.TOOL_CALLS
            "content_filter" -> ModelFinishReason.CONTENT_FILTER
            else -> ModelFinishReason.OTHER
        }
    }

    fun snapshot() = ModelUsage(
        promptTokens = prompt.takeUnless { malformed }, completionTokens = completion.takeUnless { malformed },
        reasoningTokens = reasoning.takeUnless { malformed }, totalTokens = total.takeUnless { malformed },
        usageMalformed = malformed, observedReasoningChars = reasoningChars, observedContentChars = contentChars,
        observedWhitespaceChars = whitespaceChars, finishReason = finish,
    )
}
