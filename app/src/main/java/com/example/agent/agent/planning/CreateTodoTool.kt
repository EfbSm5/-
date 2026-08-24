package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.CreateTodo

class CreateTodoTool : AgentTool {
    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = AgentToolNames.CREATE_TODO,
        description = "创建一个待办事项。",
        parameters = listOf(
            ToolParameterDescriptor(
                name = "title",
                description = "待办标题。",
                required = true,
            ),
            ToolParameterDescriptor(
                name = "due_at",
                description = "RFC 3339 时间；没有截止时间时省略。",
                required = false,
            ),
        ),
    )

    override fun supports(action: AgentAction): Boolean = action is CreateTodo

    override fun preflight(action: AgentAction): ToolPreflightResult =
        if (action is CreateTodo) {
            ToolPreflightResult.Ready
        } else {
            ToolPreflightResult.Rejected("$name Tool 收到了不支持的 Action")
        }

    override suspend fun execute(
        action: AgentAction,
        context: ToolExecutionContext,
    ): ToolActionOutcome {
        val todo = action as? CreateTodo
            ?: return ToolActionOutcome.Failed("$name Tool 收到了不支持的 Action")
        context.stageTodo(todo)
        return ToolActionOutcome.Staged(detail = todo.title)
    }
}
