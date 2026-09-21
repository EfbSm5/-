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

internal const val MAX_STREAM_TEXT_CHARS = 262_144
private const val MAX_EVENT_BYTES = 262_144L
private const val MAX_STREAM_BYTES = 4_194_304L
private const val UPDATE_INTERVAL_NANOS = 80_000_000L

/** Only a stop finish followed by a complete DONE event authorizes using model output. */
internal suspend fun readDeepSeekStream(
    source: BufferedSource,
    onUpdate: suspend (ModelStreamSnapshot) -> Unit,
): String {
    val reasoning = StringBuilder()
    val content = StringBuilder()
    val data = StringBuilder()
    var eventBytes = 0L
    var totalBytes = 0L
    var finished = false
    var lastUpdate = 0L
    var published: ModelStreamSnapshot? = null

    fun invalid(): Nothing = throw SerializationException("Invalid or incomplete stream")
    fun JsonObject.text(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
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
        // readLine() without a limit lets one unterminated event exhaust memory.
        val line = try {
            source.readUtf8LineStrict(MAX_EVENT_BYTES)
        } catch (_: EOFException) {
            invalid()
        }
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
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
            if (!finished || content.isBlank()) invalid()
            publish(final = true)
            return content.toString()
        }
        val root = Json.parseToJsonElement(event) as? JsonObject ?: invalid()
        if ("error" in root) invalid()
        val choices = root["choices"] as? JsonArray ?: invalid()
        // Optional usage-only chunks contain no model delta.
        if (choices.isEmpty()) {
            if (root["usage"] !is JsonObject) invalid()
            continue
        }
        if (choices.size != 1 || finished) invalid()
        val choice = choices[0] as? JsonObject ?: invalid()
        val index = choice["index"] as? JsonPrimitive ?: invalid()
        if (index.isString || index.intOrNull != 0) invalid()
        val delta = choice["delta"] as? JsonObject ?: invalid()
        if ("tool_calls" in delta || "function_call" in delta) invalid()
        val thought = delta.text("reasoning_content").orEmpty()
        val answer = delta.text("content").orEmpty()
        if (reasoning.length.toLong() + content.length + thought.length + answer.length > MAX_STREAM_TEXT_CHARS) invalid()
        reasoning.append(thought)
        content.append(answer)
        val finish = choice.text("finish_reason")
        if (finish != null) {
            if (finish != "stop") invalid()
            finished = true
        }
        if (thought.isNotEmpty() || answer.isNotEmpty()) publish()
    }
}
