package com.example.agent.rootpilot.action

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

sealed interface ActionParseResult {
    data class Success(val action: RootPilotAction) : ActionParseResult

    data class Failure(val message: String) : ActionParseResult
}

class ActionParser(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    },
) {
    fun parse(rawJson: String): ActionParseResult = try {
        val element = json.parseToJsonElement(rawJson)
        val objectKeys = element.jsonObject.keys
        val rawAction = json.decodeFromString<RawAction>(rawJson)
        ActionParseResult.Success(rawAction.toDomain(objectKeys))
    } catch (error: SerializationException) {
        ActionParseResult.Failure("动作 JSON 不合法：${error.message}")
    } catch (error: IllegalStateException) {
        ActionParseResult.Failure(error.message ?: "动作必须是 JSON 对象")
    } catch (error: IllegalArgumentException) {
        ActionParseResult.Failure(error.message ?: "动作参数不合法")
    }

    private fun RawAction.toDomain(keys: Set<String>): RootPilotAction {
        val normalizedAction = action.trim()
        require(normalizedAction.isNotEmpty()) { "action 不能为空" }
        return when (normalizedAction) {
            "tap" -> {
                requireKeys(keys, "action", "x", "y", "reason")
                RootPilotAction.Tap(
                    x = x.requireCoordinate("x"),
                    y = y.requireCoordinate("y"),
                    reason = reason.requireReason(),
                )
            }

            "swipe" -> {
                requireKeys(keys, "action", "x1", "y1", "x2", "y2", "duration_ms", "reason")
                RootPilotAction.Swipe(
                    x1 = x1.requireCoordinate("x1"),
                    y1 = y1.requireCoordinate("y1"),
                    x2 = x2.requireCoordinate("x2"),
                    y2 = y2.requireCoordinate("y2"),
                    durationMillis = durationMillis.requireRange("duration_ms", 100, 2_000),
                    reason = reason.requireReason(),
                )
            }

            "open_app" -> {
                requireKeys(keys, "action", "package_name", "reason")
                RootPilotAction.OpenApp(
                    packageName = packageName.requirePackageName(),
                    reason = reason.requireReason(),
                )
            }

            "type" -> {
                requireKeys(keys, "action", "text", "reason")
                val normalizedText = text.requireText()
                require(TYPE_TEXT_PATTERN.matches(normalizedText)) {
                    "type.text 只能包含安全 ASCII 字符"
                }
                RootPilotAction.Type(normalizedText, reason.requireReason())
            }

            "key" -> {
                requireKeys(keys, "action", "key", "reason")
                RootPilotAction.Key(
                    key = runCatching { RootPilotKey.valueOf(key.orEmpty()) }
                        .getOrElse { throw IllegalArgumentException("key 不支持：$key") },
                    reason = reason.requireReason(),
                )
            }

            "wait" -> {
                requireKeys(keys, "action", "duration_ms", "reason")
                RootPilotAction.Wait(
                    durationMillis = durationMillis.requireRange("duration_ms", 300, 5_000),
                    reason = reason.requireReason(),
                )
            }

            "ask_user" -> {
                requireKeys(keys, "action", "message")
                RootPilotAction.AskUser(message.requireMessage())
            }

            "finish" -> {
                requireKeys(keys, "action", "success", "message")
                RootPilotAction.Finish(
                    success = requireNotNull(success) { "finish.success 不能为空" },
                    message = message.requireMessage(),
                )
            }

            else -> throw IllegalArgumentException("不支持的 action：$normalizedAction")
        }
    }

    private fun requireKeys(actual: Set<String>, vararg expected: String) {
        require(actual == expected.toSet()) {
            "动作字段不符合协议，期望：${expected.toSet()}，实际：$actual"
        }
    }

    private fun Int?.requireCoordinate(name: String): Int {
        require(this != null && this in 0..1_000) { "$name 必须在 0 到 1000 之间" }
        return this
    }

    private fun Int?.requireRange(name: String, min: Int, max: Int): Int {
        require(this != null && this in min..max) { "$name 必须在 $min 到 $max 之间" }
        return this
    }

    private fun String?.requireReason(): String = this
        ?.trim()
        ?.also { require(it.isNotEmpty() && it.length <= MAX_REASON_LENGTH) { "reason 不合法" } }
        ?: throw IllegalArgumentException("reason 不能为空")

    private fun String?.requireMessage(): String = this
        ?.trim()
        ?.also { require(it.isNotEmpty() && it.length <= MAX_MESSAGE_LENGTH) { "message 不合法" } }
        ?: throw IllegalArgumentException("message 不能为空")

    private fun String?.requireText(): String = this
        ?.also { require(it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH) { "type.text 不合法" } }
        ?: throw IllegalArgumentException("type.text 不能为空")

    private fun String?.requirePackageName(): String = this
        ?.trim()
        ?.also {
            require(it.isNotEmpty() && it.length <= MAX_PACKAGE_NAME_LENGTH) {
                "package_name 不合法"
            }
            require(PACKAGE_NAME_PATTERN.matches(it)) {
                "package_name 格式不合法"
            }
        }
        ?: throw IllegalArgumentException("package_name 不能为空")

    @Serializable
    private data class RawAction(
        val action: String,
        val x: Int? = null,
        val y: Int? = null,
        val x1: Int? = null,
        val y1: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        @SerialName("duration_ms") val durationMillis: Int? = null,
        val text: String? = null,
        @SerialName("package_name") val packageName: String? = null,
        val key: String? = null,
        val reason: String? = null,
        val message: String? = null,
        val success: Boolean? = null,
    )

    private companion object {
        const val MAX_REASON_LENGTH = 200
        const val MAX_MESSAGE_LENGTH = 500
        const val MAX_TEXT_LENGTH = 128
        const val MAX_PACKAGE_NAME_LENGTH = 200
        val TYPE_TEXT_PATTERN = Regex("^[A-Za-z0-9._@+\\-]+$")
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
