package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo

interface TodoRepository {
    suspend fun add(todo: CreateTodo) = addAll(listOf(todo))

    suspend fun addAll(todos: List<CreateTodo>)

    suspend fun list(): List<CreateTodo>
}

class InMemoryTodoRepository : TodoRepository {
    private val todos = mutableListOf<CreateTodo>()

    override suspend fun addAll(todos: List<CreateTodo>) {
        synchronized(this.todos) {
            this.todos += todos
        }
    }

    override suspend fun list(): List<CreateTodo> = synchronized(todos) { todos.toList() }
}

sealed interface ToolExecutionResult {
    data class Success(val createdTodos: List<CreateTodo>) : ToolExecutionResult

    data class Failure(val message: String) : ToolExecutionResult
}

class AgentExecutionEngine(
    private val todoRepository: TodoRepository = InMemoryTodoRepository(),
) {
    suspend fun execute(
        confirmation: ExecutionConfirmation,
        onActionStarted: suspend (Int) -> Unit = {},
    ): ToolExecutionResult {
        val plan = confirmation.plan
        if (plan.assessReadiness() != PlanReadiness.ReadyToExecute) {
            return ToolExecutionResult.Failure("计划仍需要用户补充信息")
        }
        if (plan.actions.isEmpty()) {
            return ToolExecutionResult.Failure("计划没有可执行 Action")
        }
        if (plan.actions.any { it !is CreateTodo }) {
            return ToolExecutionResult.Failure("当前只允许执行 create_todo Tool")
        }

        val createdTodos = plan.actions.map { it as CreateTodo }
        createdTodos.forEachIndexed { index, _ ->
            onActionStarted(index)
        }
        todoRepository.addAll(createdTodos)
        return ToolExecutionResult.Success(createdTodos)
    }
}
