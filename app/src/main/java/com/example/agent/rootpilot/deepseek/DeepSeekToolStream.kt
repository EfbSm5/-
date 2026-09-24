package com.example.agent.rootpilot.deepseek

import java.io.EOFException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.BufferedSource

private const val MAX_EVENT_BYTES = 262_144L
private const val MAX_STREAM_BYTES = 4_194_304L
private const val UPDATE_INTERVAL_NANOS = 80_000_000L

private class PendingTool(val id: String, val name: String, val arguments: StringBuilder)

/** Draft snapshots never carry tools. Only a matching finish and DONE release complete calls. */
internal suspend fun readDeepSeekToolStream(
    source: BufferedSource,
    onUpdate: suspend (ModelStreamSnapshot) -> Unit,
): ToolChatResult.Success {
    val reasoning = StringBuilder()
    val content = StringBuilder()
    val calls = sortedMapOf<Int, PendingTool>()
    val ids = mutableSetOf<String>()
    val data = StringBuilder()
    var eventBytes = 0L
    var totalBytes = 0L
    var finish: String? = null
    var lastUpdate = 0L
    var published: ModelStreamSnapshot? = null

    fun invalid(): Nothing = throw SerializationException("Invalid or incomplete tool stream")
    fun JsonObject.text(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    }
    fun JsonObject.index(): Int {
        val value = this["index"] as? JsonPrimitive ?: invalid()
        return value.takeUnless { it.isString }?.intOrNull ?: invalid()
    }
    suspend fun publish(final: Boolean = false) {
        currentCoroutineContext().ensureActive()
        val now = System.nanoTime()
        if (!final && published != null && now - lastUpdate < UPDATE_INTERVAL_NANOS) return
        val snapshot = ModelStreamSnapshot(reasoning.toString(), content.toString())
        if (snapshot != published || final) {
            onUpdate(snapshot)
            published = snapshot
            lastUpdate = System.nanoTime()
        }
    }

    while (true) {
        currentCoroutineContext().ensureActive()
        // Bound even an unterminated event before allocating its full text.
        val line = try {
            source.readUtf8LineStrict(MAX_EVENT_BYTES)
        } catch (_: EOFException) {
            invalid()
        }
        // UTF-8 length plus two conservatively bounds either LF or CRLF delimiters.
        val bytes = line.toByteArray(Charsets.UTF_8).size + 2L
        eventBytes += bytes
        totalBytes += bytes
        if (eventBytes > MAX_EVENT_BYTES || totalBytes > MAX_STREAM_BYTES) invalid()
        if (line.isNotEmpty()) {
            if (line == "data" || line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(if (line == "data") "" else line.substring(5).removePrefix(" "))
            }
            continue
        }
        eventBytes = 0
        if (data.isEmpty()) continue
        val event = data.toString()
        data.setLength(0)
        if (event == "[DONE]") {
            when (finish) {
                "stop" -> if (calls.isNotEmpty() || content.isBlank()) invalid()
                "tool_calls" -> {
                    if (calls.isEmpty() || calls.keys.toList() != (0 until calls.size).toList()) invalid()
                    calls.values.forEach {
                        if (Json.parseToJsonElement(it.arguments.toString()) !is JsonObject) invalid()
                    }
                }
                else -> invalid()
            }
            publish(final = true)
            currentCoroutineContext().ensureActive()
            return ToolChatResult.Success(content.toString(), reasoning.toString(), calls.values.map {
                ChatToolCall(it.id, it.name, it.arguments.toString())
            })
        }
        val root = Json.parseToJsonElement(event) as? JsonObject ?: invalid()
        if ("error" in root) invalid()
        val choices = root["choices"] as? JsonArray ?: invalid()
        if (choices.isEmpty()) {
            if (root["usage"] !is JsonObject) invalid()
            continue
        }
        if (choices.size != 1 || finish != null) invalid()
        val choice = choices[0] as? JsonObject ?: invalid()
        if (choice.index() != 0) invalid()
        val delta = choice["delta"] as? JsonObject ?: invalid()
        if ("function_call" in delta || "tool_calls" in choice || "message" in choice) invalid()
        if (delta.text("role")?.let { it != "assistant" } == true) invalid()
        val thought = delta.text("reasoning_content").orEmpty()
        val answer = delta.text("content").orEmpty()
        if (reasoning.length.toLong() + content.length + thought.length + answer.length > MAX_STREAM_TEXT_CHARS) invalid()
        if ("tool_calls" in delta) {
            val fragments = delta["tool_calls"] as? JsonArray ?: invalid()
            if (fragments.isEmpty() || fragments.size > MAX_TOOL_CALLS) invalid()
            val eventIndices = mutableSetOf<Int>()
            fragments.forEach { element ->
                val fragment = element as? JsonObject ?: invalid()
                val index = fragment.index()
                if (index !in 0 until MAX_TOOL_CALLS || !eventIndices.add(index)) invalid()
                val function = fragment["function"] as? JsonObject ?: invalid()
                val arguments = function.text("arguments").orEmpty()
                val existing = calls[index]
                if (existing == null) {
                    val id = fragment.text("id") ?: invalid()
                    val name = function.text("name") ?: invalid()
                    if (fragment.text("type") != "function" || id.isBlank() || id.length > MAX_TOOL_ID_CHARS ||
                        !ids.add(id) || !TOOL_NAME_PATTERN.matches(name) || arguments.length > MAX_TOOL_ARGUMENT_CHARS
                    ) invalid()
                    calls[index] = PendingTool(id, name, StringBuilder(arguments))
                } else {
                    // The API puts identity in the first fragment only; subsequent fragments append arguments.
                    if ("id" in fragment || "type" in fragment || "name" in function ||
                        existing.arguments.length.toLong() + arguments.length > MAX_TOOL_ARGUMENT_CHARS
                    ) invalid()
                    existing.arguments.append(arguments)
                }
            }
        }
        val end = choice.text("finish_reason")
        if (end != null) {
            if (end !in setOf("stop", "tool_calls") || (end == "stop") != calls.isEmpty()) invalid()
            finish = end
        }
        reasoning.append(thought)
        content.append(answer)
        if (thought.isNotEmpty() || answer.isNotEmpty()) publish()
    }
}
