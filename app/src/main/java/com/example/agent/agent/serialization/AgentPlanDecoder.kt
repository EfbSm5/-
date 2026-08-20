package com.example.agent.agent.serialization

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

sealed interface PlanDecodeResult {
    data class Success(val plan: AgentPlan) : PlanDecodeResult
    data class Failure(val reason: String) : PlanDecodeResult
}

class AgentPlanDecoder(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    },
) {
    fun decode(rawJson: String): PlanDecodeResult = try {
        val rawPlan = json.decodeFromString<RawAgentPlan>(rawJson)
        PlanDecodeResult.Success(rawPlan.toDomain())
    } catch (error: SerializationException) {
        PlanDecodeResult.Failure("JSON 格式不符合计划协议：${error.message}")
    } catch (error: IllegalArgumentException) {
        PlanDecodeResult.Failure(error.message ?: "计划参数不合法")
    }

    private fun RawAgentPlan.toDomain(): AgentPlan {
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotEmpty()) { "goal 不能为空" }
        require(normalizedGoal.length <= MAX_GOAL_LENGTH) { "goal 过长" }
        require(actions.isNotEmpty()) { "actions 不能为空" }
        require(actions.size <= MAX_ACTION_COUNT) { "actions 数量不能超过 $MAX_ACTION_COUNT" }

        return AgentPlan(
            goal = normalizedGoal,
            actions = actions.mapIndexed { index, action -> action.toDomain(index) },
        )
    }

    private fun RawAgentAction.toDomain(index: Int): AgentAction = when (type) {
        "create_todo" -> {
            require(packageName == null && question == null) {
                "actions[$index] 的 create_todo 包含无关字段"
            }
            val normalizedDueAt = dueAt
                ?.requireText("actions[$index].due_at", MAX_DUE_AT_LENGTH)
                ?.requireRfc3339("actions[$index].due_at")
            CreateTodo(
                title = title.requireText("actions[$index].title", MAX_TITLE_LENGTH),
                dueAt = normalizedDueAt,
            )
        }

        "open_app" -> {
            require(title == null && dueAt == null && question == null) {
                "actions[$index] 的 open_app 包含无关字段"
            }
            val normalizedPackageName = packageName.requireText(
                name = "actions[$index].package_name",
                maxLength = MAX_PACKAGE_NAME_LENGTH,
            )
            require(PACKAGE_NAME_PATTERN.matches(normalizedPackageName)) {
                "actions[$index].package_name 不是合法包名"
            }
            OpenApp(normalizedPackageName)
        }

        "ask_user" -> {
            require(title == null && dueAt == null && packageName == null) {
                "actions[$index] 的 ask_user 包含无关字段"
            }
            AskUser(question.requireText("actions[$index].question", MAX_QUESTION_LENGTH))
        }

        else -> throw IllegalArgumentException("actions[$index].type 不支持：$type")
    }

    private fun String?.requireText(name: String, maxLength: Int): String {
        val normalized = this?.trim().orEmpty()
        require(normalized.isNotEmpty()) { "$name 不能为空" }
        require(normalized.length <= maxLength) { "$name 过长" }
        return normalized
    }

    private fun String.requireRfc3339(name: String): String {
        require(RFC3339_PATTERN.matches(this)) {
            "$name 必须是合法的 RFC 3339 时间"
        }
        require(runCatching { OffsetDateTime.parse(this, RFC3339_FORMATTER) }.isSuccess) {
            "$name 必须是合法的 RFC 3339 时间"
        }
        return this
    }

    private companion object {
        const val MAX_ACTION_COUNT = 10
        const val MAX_GOAL_LENGTH = 200
        const val MAX_TITLE_LENGTH = 100
        const val MAX_DUE_AT_LENGTH = 64
        const val MAX_PACKAGE_NAME_LENGTH = 255
        const val MAX_QUESTION_LENGTH = 200
        val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val RFC3339_PATTERN = Regex(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$",
        )
        val RFC3339_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
