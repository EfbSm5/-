package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DeepSeekToolStreamTest {
    @Test
    fun interleavedCallsAndUnicodeArguments_areAssembledOnlyAfterDone() = runBlocking {
        val updates = mutableListOf<ModelStreamSnapshot>()
        val result = readDeepSeekToolStream(Buffer().writeUtf8(
            event(buildJsonObject { put("reasoning_content", "思考") }) +
                event(buildJsonObject { put("content", "说明"); put("tool_calls", JsonArray(listOf(
                    fragment(1, "second", "lookup", "{"), fragment(0, "first", "lookup", "{\"x\":\""),
                ))) }) +
                tools(fragment(0, arguments = "你🙂\"}"), fragment(1, arguments = "}")) +
                event(finish = "tool_calls") + "data: {\"choices\":[],\"usage\":{}}\n\n" + DONE,
        )) { updates += it }
        assertEquals(ToolChatResult.Success("说明", "思考", listOf(
            ChatToolCall("first", "lookup", "{\"x\":\"你🙂\"}"), ChatToolCall("second", "lookup", "{}"),
        )), result)
        assertEquals(ModelStreamSnapshot("思考", "说明"), updates.last())
        assertTrue(updates.all { !it.content.contains("lookup") && !it.content.contains("first") })
    }

    @Test
    fun stopAndEmptyToolContent_areValid() = runBlocking {
        val result = readDeepSeekToolStream(Buffer().writeUtf8(event(buildJsonObject {
            put("content", "answer")
        }, "stop") + DONE)) {}
        assertEquals(ToolChatResult.Success("answer", "", emptyList()), result)
        assertEquals(listOf(ChatToolCall("id", "lookup", "{}")), parse(start + end).toolCalls)
    }

    @Test
    fun truncatedAndAbnormalStreams_neverReleaseCalls() = runBlocking {
        listOf(
            start, start + event(finish = "tool_calls"), start + DONE,
            start + event(finish = "tool_calls") + "data: [DONE]\n",
            start + event(finish = "length") + DONE,
            start + event(finish = "content_filter") + DONE,
            start + event(finish = "stop") + DONE,
            event(finish = "tool_calls") + DONE,
            start + event(finish = "tool_calls") + event() + DONE,
            tools(fragment(0, "id", "lookup", "{\"x\":")) + end,
            tools(fragment(0, "id", "lookup", "[]")) + end,
            tools(fragment(0, "id", "lookup", "")) + end,
        ).forEach { rejected(it) }
    }

    @Test
    fun identityTypesIndicesAndLegacyMixing_areRejected() = runBlocking {
        listOf(
            tools(fragment(0, "id", "lookup", "{}"), fragment(1, "id", "lookup", "{}")) + end,
            tools(fragment(1, "id", "lookup", "{}")) + end,
            tools(fragment(-1, "id", "lookup", "{}")) + end,
            tools(fragment(128, "id", "lookup", "{}")) + end,
            tools(fragment(0, "id", "lookup", "{}", "custom")) + end,
            tools(fragment(0, arguments = "{}")) + end,
            start + tools(fragment(0, "different", "lookup", "{}")) + end,
            tools(fragment(0, "id", "lookup", "{"), fragment(0, arguments = "}")) + end,
            event(buildJsonObject { put("function_call", buildJsonObject {}); put("content", "private") }) + end,
            event(buildJsonObject { put("tool_calls", JsonNull) }) + end,
            event(buildJsonObject { put("tool_calls", JsonArray(emptyList())) }) + end,
            event(buildJsonObject { put("role", "tool"); put("content", "private") }, "stop") + DONE,
            event(buildJsonObject { put("content", 123) }, "stop") + DONE,
            "data: {\"error\":{\"message\":\"private\"}}\n\n" + end,
        ).forEach { rejected(it) }
    }

    @Test
    fun argumentCallTextEventAndTotalByteLimits_areEnforced() = runBlocking {
        val maxArgument = "{\"x\":\"" + "x".repeat(MAX_TOOL_ARGUMENT_CHARS - 8) + "\"}"
        assertEquals(MAX_TOOL_ARGUMENT_CHARS, maxArgument.length)
        assertEquals(maxArgument, parse(tools(fragment(0, "id", "lookup", maxArgument)) + end).toolCalls.single().arguments)
        rejected(tools(fragment(0, "id", "lookup", maxArgument + " ")) + end)
        rejected(tools(fragment(0, "id", "lookup", maxArgument)) + tools(fragment(0, arguments = " ")) + end)
        val many = (0 until MAX_TOOL_CALLS).joinToString("") { tools(fragment(it, "id$it", "lookup", "{}")) }
        assertEquals(MAX_TOOL_CALLS, parse(many + end).toolCalls.size)
        rejected(many + tools(fragment(MAX_TOOL_CALLS, "extra", "lookup", "{}")) + end)
        rejected("data: " + "x".repeat(262_144))
        rejected((1..5).joinToString("") { event(buildJsonObject { put("content", "x".repeat(60_000)) }) } + DONE)
        rejected((":" + "你".repeat(10_000) + "\r\n\r\n").repeat(140) + start + end)
    }

    @Test
    fun ordinaryStream_stillRejectsToolCallsIncludingNullAndLegacy() = runBlocking {
        listOf(start + end, event(buildJsonObject { put("tool_calls", JsonNull) }, "stop") + DONE,
            event(buildJsonObject { put("function_call", buildJsonObject {}); put("content", "text") }, "stop") + DONE,
        ).forEach {
            try {
                readDeepSeekStream(Buffer().writeUtf8(it)) {}
                fail("ordinary stream accepted tools")
            } catch (_: SerializationException) { }
        }
    }

    @Test
    fun request_preservesToolsAndAllAssistantReasoningAndEffort() {
        val schema = Json.parseToJsonElement("""{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}""").jsonObject
        val messages = listOf(
            ToolChatTurn("user", "question"),
            ToolChatTurn("assistant", "explanation", "first reasoning", listOf(ChatToolCall("id", "lookup", "{}"))),
            ToolChatTurn("tool", "result", toolCallId = "id"),
            ToolChatTurn("assistant", "answer", "second reasoning"),
            ToolChatTurn("user", "next"),
        )
        ThinkingEffort.entries.forEach { effort ->
            val body = Json.parseToJsonElement(buildToolChatRequest(RootPilotConfig(), messages, listOf(schema), effort)).jsonObject
            assertEquals(JsonArray(listOf(schema)), body["tools"])
            assertEquals(JsonPrimitive(true), body["stream"])
            val wire = body["messages"]!!.jsonArray
            assertEquals(JsonPrimitive("first reasoning"), wire[1].jsonObject["reasoning_content"])
            assertEquals(JsonPrimitive("second reasoning"), wire[3].jsonObject["reasoning_content"])
            assertEquals(JsonPrimitive("id"), wire[2].jsonObject["tool_call_id"])
            assertEquals(JsonPrimitive("{}"), wire[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"])
            assertEquals(if (effort == ThinkingEffort.NONE) null else JsonPrimitive(effort.wireValue), body["reasoning_effort"])
        }
    }

    @Test
    fun invalidHistoriesAndOversizedRequests_areRejectedWithoutLeakingData() {
        val schema = Json.parseToJsonElement("""{"type":"function","function":{"name":"lookup"}}""").jsonObject
        listOf(
            listOf(ToolChatTurn("tool", "secret", toolCallId = "missing")),
            listOf(ToolChatTurn("user", "secret", reasoningContent = "private")),
            listOf(ToolChatTurn("assistant", "", toolCalls = listOf(ChatToolCall("id", "lookup", "{}")))),
            listOf(ToolChatTurn("user", "你".repeat(MAX_TOOL_REQUEST_BYTES / 2))),
        ).forEach { messages ->
            assertThrows(IllegalArgumentException::class.java) { buildToolChatRequest(RootPilotConfig(), messages, listOf(schema), ThinkingEffort.HIGH) }
        }
        val call = ChatToolCall("private-id", "private-name", "private-args")
        assertFalse(call.toString().contains("private"))
        assertFalse(ToolChatTurn("private", "private", "private", listOf(call), "private").toString().contains("private"))
        assertFalse(ToolChatResult.Success("private", "private", listOf(call)).toString().contains("private"))
    }

    private suspend fun parse(stream: String) = readDeepSeekToolStream(Buffer().writeUtf8(stream)) {}
    private suspend fun rejected(stream: String) {
        try { parse(stream); fail("invalid stream accepted") } catch (_: SerializationException) { }
    }

    private fun fragment(index: Int, id: String? = null, name: String? = null, arguments: String, type: String = "function") = buildJsonObject {
        put("index", index)
        if (id != null) { put("id", id); put("type", type) }
        putJsonObject("function") { if (name != null) put("name", name); put("arguments", arguments) }
    }
    private fun tools(vararg fragments: JsonObject) = event(buildJsonObject { put("tool_calls", JsonArray(fragments.toList())) })
    private fun event(delta: JsonObject = buildJsonObject {}, finish: String? = null) = "data: " + buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { put("index", 0); put("delta", delta); finish?.let { put("finish_reason", it) } }) }
    } + "\n\n"
    private val start get() = tools(fragment(0, "id", "lookup", "{}"))
    private val end get() = event(finish = "tool_calls") + DONE
    private companion object { const val DONE = "data: [DONE]\n\n" }
}
