package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekDeviceDecisionTest {
    private fun request(bytes: ByteArray = byteArrayOf(1)) = DeepSeekVisionRequest(
        RootPilotConfig(), ScreenshotFrame(bytes, 100, 200, "data:image/png;base64,AA=="), emptyList(), 1, 0,
    )
    private fun build(history: List<ToolChatTurn> = emptyList(), allow: Boolean = true) = Json.parseToJsonElement(
        buildDeviceDecisionRequest(request(), history, allow, "Return action JSON.", "question"),
    ).jsonObject

    @Test fun nativeRequestContainsImageSchemasAndNoParallelCalls() {
        val body = build()
        assertEquals(JsonArray(DeviceInfoTool.definitions), body["tools"])
        assertEquals(JsonPrimitive("auto"), body["tool_choice"])
        assertEquals(JsonPrimitive(false), body["parallel_tool_calls"])
        assertEquals(JsonPrimitive("json_object"), body["response_format"]!!.jsonObject["type"])
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(JsonPrimitive("question"), content[0].jsonObject["text"])
        assertEquals(JsonPrimitive(request().frame.dataUrl), content[1].jsonObject["image_url"]!!.jsonObject["url"])
        assertEquals(JsonPrimitive("none"), build(allow = false)["tool_choice"])
    }

    @Test fun resultIdAndAssistantReasoningArePreservedAcrossToolRoundTrip() {
        val history = listOf(ToolChatTurn("assistant", "", "reasoning", listOf(ChatToolCall("call-1", "get_ui_tree", "{}"))),
            ToolChatTurn("tool", "{\"status\":\"unavailable\"}", toolCallId = "call-1"))
        val messages = build(history)["messages"]!!.jsonArray
        assertEquals(JsonPrimitive("reasoning"), messages[2].jsonObject["reasoning_content"])
        assertEquals(JsonPrimitive("call-1"), messages[3].jsonObject["tool_call_id"])
        val instructions = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("untrusted DATA"))
        assertTrue(instructions.contains("no click_node or set_text"))
    }

    @Test fun deviceBudgetAndThinkingAreUnchangedByToolAvailability() {
        for (allow in listOf(true, false)) {
            val body = build(allow = allow)
            assertEquals(JsonPrimitive(65_536), body["max_tokens"])
            assertEquals(JsonPrimitive("enabled"), body["thinking"]!!.jsonObject["type"])
            assertEquals(JsonPrimitive("low"), body["reasoning_effort"])
        }
    }

    @Test fun unresolvedCallAndOversizedImageFailBeforeTransport() {
        assertThrows(IllegalArgumentException::class.java) { build(listOf(ToolChatTurn("tool", "secret", toolCallId = "missing"))) }
        val oversized = request().copy(frame = request().frame.copy(dataUrl = "x".repeat(MAX_TOOL_REQUEST_BYTES)))
        assertThrows(IllegalArgumentException::class.java) {
            buildDeviceDecisionRequest(oversized, emptyList(), true, "instructions", "question")
        }
    }
}
