package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AgentExecutionEngineTest {
    @Test
    fun planWithAskUser_isRejectedBeforeToolExecution() = runTest {
        val repository = RecordingTodoRepository()
        val plan = AgentPlan(
                goal = "准备投递",
                actions = listOf(AskUser("你准备投递哪个岗位？")),
            )
        val result = AgentExecutionEngine(repository).execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Failure("计划仍需要用户补充信息"), result)
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun createTodoAction_isExecutedAfterConfirmation() = runTest {
        val repository = RecordingTodoRepository()
        val todo = CreateTodo("投递 Android 岗位", dueAt = null)
        val plan = AgentPlan(goal = "准备投递", actions = listOf(todo))
        val result = AgentExecutionEngine(repository).execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Success(listOf(todo)), result)
        assertEquals(listOf(todo), repository.todos)
    }

    @Test
    fun unsupportedAction_isRejectedBeforeAnyActionRuns() = runTest {
        val repository = RecordingTodoRepository()
        val plan = AgentPlan(
                goal = "打开应用并创建待办",
                actions = listOf(
                    CreateTodo("投递 Android 岗位", dueAt = null),
                    com.example.agent.agent.model.OpenApp("com.example.other"),
                ),
            )
        val result = AgentExecutionEngine(repository).execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure("当前只允许执行 create_todo Tool"),
            result,
        )
        assertTrue(repository.todos.isEmpty())
    }

    private class RecordingTodoRepository : TodoRepository {
        val todos = mutableListOf<CreateTodo>()

        override suspend fun addAll(todos: List<CreateTodo>) {
            this.todos += todos
        }

        override suspend fun list(): List<CreateTodo> = todos.toList()
    }
}
