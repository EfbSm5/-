package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.CreateTodo

class CreateTodoTool : AgentTool {
    override val name: String = AgentToolNames.CREATE_TODO

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
