package com.example.agent.rootpilot.information

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceInformationTest {
    @Test fun schemasExposeOnlyThreeEmptyReadOnlyCalls() {
        assertEquals(listOf("get_screen_context", "get_activity_stack", "get_ui_tree"),
            DeviceInfoTool.definitions.map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        DeviceInfoTool.definitions.forEach {
            val parameters = it["function"]!!.jsonObject["parameters"]!!.jsonObject
            assertTrue(parameters["properties"]!!.jsonObject.isEmpty())
            assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        }
        assertNull(DeviceInfoTool.fromWireName("click_node"))
        assertNull(DeviceInfoTool.fromWireName("shell"))
    }

    @Test fun unavailableIsNotAnEmptyTreeAndIncludesSamplingMetadata() {
        val result = DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 10, 20,
            unavailable = DeviceInfoUnavailable.NOT_ENABLED)
        val wire = Json.parseToJsonElement(result.toModelJson(DeviceInfoTool.UI_TREE, "sample")).jsonObject
        assertEquals(JsonPrimitive("unavailable"), wire["status"])
        assertEquals(JsonPrimitive("not_enabled"), wire["reason"])
        assertEquals(JsonNull, wire["data"])
        assertEquals(JsonPrimitive(10), wire["sample_started_elapsed_ms"])
        assertEquals(JsonPrimitive(20), wire["sample_finished_elapsed_ms"])
        assertEquals(JsonPrimitive("sample"), wire["observation_id"])
    }

    @Test fun invalidResultContractsAreRejectedAndDebugOutputIsRedacted() {
        assertThrows(IllegalArgumentException::class.java) { DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 1, 0, buildJsonObject {}) }
        assertThrows(IllegalArgumentException::class.java) { DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 0, 1,
            buildJsonObject {}, DeviceInfoUnavailable.TIMEOUT) }
        val result = DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 0, 1,
            buildJsonObject { put("text", "private-content") })
        assertFalse(result.toString().contains("private-content"))
    }

    @Test fun sensitiveNodeLabelsAreOmittedWhileBoundsAndStatesRemain() {
        val node = node(sensitive = true)
        val formatted = UiTreeSnapshot.format(listOf(node), "com.test.app", false)
        val wire = formatted.data["nodes"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, wire["text"])
        assertEquals(JsonNull, wire["content_description"])
        assertEquals(JsonNull, wire["hint"])
        assertEquals(JsonPrimitive(true), wire["text_redacted"])
        assertEquals(JsonPrimitive(42), wire["bounds"]!!.jsonObject["left"])
        assertEquals(JsonPrimitive(true), wire["editable"])
        assertFalse(formatted.data.toString().contains("private-content"))
        assertFalse(node.toString().contains("private-content"))
        assertFalse(formatted.toString().contains("private-content"))
    }

    @Test fun labelsAndNodeCountsAreBoundedAndTruncationIsExplicit() {
        val long = UiTreeSnapshot.format(listOf(node(text = "字".repeat(121))), "com.test.app", false)
        assertTrue(long.truncated)
        assertEquals(120, long.data["nodes"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.length)
        val many = UiTreeSnapshot.format((0..200).map { node(id = "n$it") }, "com.test.app", false)
        assertTrue(many.truncated)
        assertEquals(200, many.data["nodes"]!!.jsonArray.size)
        assertTrue(UiTreeSnapshot.format(listOf(node()), "com.test.app", true).truncated)
        assertFalse(UiTreeSnapshot.format(listOf(node()), "com.test.app", false).truncated)
    }

    @Test fun foreignPackagesAndDuplicateNodeIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UiTreeSnapshot.format(listOf(node()), "com.foreign.app", false) }
        assertThrows(IllegalArgumentException::class.java) { UiTreeSnapshot.format(listOf(node(), node()), "com.test.app", false) }
        assertThrows(IllegalArgumentException::class.java) { UiTreeSnapshot.format(emptyList(), "com.test.app", false) }
    }

    @Test fun virtualTreeNamesItsDisplayWithoutChangingBoundsOrSensitiveTextFiltering() {
        val formatted = UiTreeSnapshot.format(listOf(node(sensitive = true)), "com.test.app", false, 9)
        assertEquals(JsonPrimitive(9), formatted.data["display_id"])
        assertEquals(JsonPrimitive("physical_screen_pixels"), formatted.data["bounds_unit"])
        assertEquals(JsonPrimitive(42), formatted.data["nodes"]!!.jsonArray.single().jsonObject["bounds"]!!.jsonObject["left"])
        assertFalse(formatted.data.toString().contains("private-content"))
        assertFalse(formatted.truncated)
        assertFalse(UiTreeSnapshot.format(listOf(node()), "com.test.app", false).data.containsKey("display_id"))
        assertThrows(IllegalArgumentException::class.java) { UiTreeSnapshot.format(listOf(node()), "com.test.app", false, -1) }
    }

    private fun node(id: String = "n0", text: String = "private-content", sensitive: Boolean = false) = UiNodeSnapshot(
        id, null, "com.test.app", "android.widget.EditText", "com.test.app:id/input", text,
        "private-content-description", "private-content-hint", 42, 20, 100, 80,
        true, true, true, true, false, false, false, false, sensitive,
    )
}
