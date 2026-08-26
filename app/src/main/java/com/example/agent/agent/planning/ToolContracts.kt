package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.CreateTodo
import kotlinx.serialization.Serializable

object AgentToolNames {
    const val CREATE_TODO = "create_todo"
    const val OPEN_APP = "open_app"
}

data class ToolParameterDescriptor(
    val name: String,
    val description: String,
    val required: Boolean,
)

data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParameterDescriptor> = emptyList(),
    val constraints: List<String> = emptyList(),
)

@Serializable
enum class ActionExecutionStatus {
    RUNNING,
    STAGED,
    SUCCEEDED,
    FAILED,
}

@Serializable
data class ActionExecutionRecord(
    val actionIndex: Int,
    val toolName: String,
    val status: ActionExecutionStatus,
    val detail: String? = null,
)

@Serializable
data class ToolExecutionReport(
    val actionResults: List<ActionExecutionRecord> = emptyList(),
)

sealed interface ToolPreflightResult {
    data object Ready : ToolPreflightResult

    data class Rejected(val message: String) : ToolPreflightResult
}

sealed interface ToolActionOutcome {
    data class Staged(val detail: String? = null) : ToolActionOutcome

    data class Succeeded(val detail: String? = null) : ToolActionOutcome

    data class Failed(val message: String) : ToolActionOutcome
}

class ToolExecutionContext internal constructor() {
    private val pendingTodos = mutableListOf<CreateTodo>()

    internal fun stageTodo(todo: CreateTodo) {
        pendingTodos += todo
    }

    internal fun pendingTodos(): List<CreateTodo> = pendingTodos.toList()
}

interface AgentTool {
    val descriptor: ToolDescriptor

    val name: String
        get() = descriptor.name

    fun supports(action: AgentAction): Boolean

    fun preflight(action: AgentAction): ToolPreflightResult

    suspend fun execute(action: AgentAction, context: ToolExecutionContext): ToolActionOutcome
}

class ToolRegistry(tools: List<AgentTool>) {
    private val registeredTools = tools.toList()

    init {
        require(registeredTools.isNotEmpty()) { "ToolRegistry 不能没有 Tool" }
        require(registeredTools.map { it.name }.toSet().size == registeredTools.size) {
            "Tool 名称不能重复"
        }
    }

    fun resolve(action: AgentAction): AgentTool? =
        registeredTools.firstOrNull { it.supports(action) }

    fun describeForModel(): String = registeredTools.joinToString(separator = "\n\n") { tool ->
        buildString {
            append("- ")
            append(tool.descriptor.name)
            append(": ")
            append(tool.descriptor.description)
            if (tool.descriptor.parameters.isNotEmpty()) {
                append("\n  parameters:")
                tool.descriptor.parameters.forEach { parameter ->
                    append("\n  - ")
                    append(parameter.name)
                    append(if (parameter.required) " (required): " else " (optional): ")
                    append(parameter.description)
                }
            }
            if (tool.descriptor.constraints.isNotEmpty()) {
                append("\n  constraints:")
                tool.descriptor.constraints.forEach { constraint ->
                    append("\n  - ")
                    append(constraint)
                }
            }
        }
    }

    companion object {
        fun default(): ToolRegistry = ToolRegistry(
            tools = listOf(
                CreateTodoTool(),
                OpenAppTool(UnconfiguredAppLauncher),
            ),
        )
    }
}

sealed interface ToolExecutionResult {
    data class Success(val report: ToolExecutionReport) : ToolExecutionResult

    data class Failure(
        val message: String,
        val report: ToolExecutionReport = ToolExecutionReport(),
    ) : ToolExecutionResult
}
