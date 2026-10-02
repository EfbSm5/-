package com.example.agent.rootpilot.information

import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class DeviceInfoTool(val wireName: String, val description: String) {
    SCREEN_CONTEXT("get_screen_context", "Read current foreground app, Activity, focus package and keyboard visibility."),
    ACTIVITY_STACK("get_activity_stack", "Read Activity components in the current foreground task, ordered from top to bottom. This is not a Fragment, Compose navigation or thread stack."),
    UI_TREE("get_ui_tree", "Read exposed UI semantics in the current foreground app: labels, states and physical-pixel bounds. Password and sensitive node text is omitted. The tree can be unavailable or incomplete."),
    ;

    companion object {
        fun fromWireName(name: String): DeviceInfoTool? = entries.singleOrNull { it.wireName == name }

        val definitions: List<JsonObject> = entries.map { tool ->
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.wireName)
                    put("description", tool.description)
                    putJsonObject("parameters") {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("required", JsonArray(emptyList()))
                        put("additionalProperties", false)
                    }
                }
            }
        }
    }
}

enum class DeviceInfoSource(val wireName: String) {
    SCREEN_OBSERVER("root_screen_observer"),
    ACTIVITY_DUMP("root_dumpsys_activity"),
    UI_SEMANTICS("android_accessibility"),
}

enum class DeviceInfoUnavailable(val wireName: String) {
    NOT_SUPPORTED("not_supported"),
    NOT_ENABLED("not_enabled"),
    TARGET_NOT_READY("target_not_ready"),
    PROTECTED_APP("protected_app"),
    TIMEOUT("timeout"),
    OUTPUT_LIMIT("output_limit"),
    COMMAND_FAILED("command_failed"),
    INVALID_FORMAT("invalid_format"),
    COLLECTION_FAILED("collection_failed"),
}

/** Results live only in one planning step. No raw dumps or model-facing data enter task history. */
data class DeviceInfoResult(
    val source: DeviceInfoSource,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val data: JsonObject? = null,
    val unavailable: DeviceInfoUnavailable? = null,
    val truncated: Boolean = false,
) {
    init {
        require((data != null) != (unavailable != null))
        require(finishedAtMillis >= startedAtMillis)
        require(data != null || !truncated)
    }

    fun toModelJson(tool: DeviceInfoTool, observationId: String): String = buildJsonObject {
        put("tool", tool.wireName)
        put("observation_id", observationId)
        put("source", source.wireName)
        put("status", if (unavailable == null) "available" else "unavailable")
        put("reason", unavailable?.wireName)
        put("sample_started_elapsed_ms", startedAtMillis)
        put("sample_finished_elapsed_ms", finishedAtMillis)
        put("truncated", truncated)
        put("data", data ?: JsonNull)
    }.toString()

    override fun toString(): String = "DeviceInfoResult(source=$source, unavailable=$unavailable, truncated=$truncated)"
}

fun ScreenObservation.publicMetadata(): JsonObject = buildJsonObject {
    put("foreground_package", foregroundPackage)
    put("foreground_activity", foregroundActivity)
    put("focused_package", focusedPackage)
    put("keyboard_visible", keyboardVisible)
}

fun interface UiTreeProvider {
    suspend fun query(expected: ScreenObservation): DeviceInfoResult
}
