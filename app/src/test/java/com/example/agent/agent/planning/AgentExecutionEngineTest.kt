package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertThrows

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
    fun openAppWithoutLauncher_isRejectedBeforeTodoWrite() = runTest {
        val repository = RecordingTodoRepository()
        val plan = AgentPlan(
                goal = "打开应用并创建待办",
                actions = listOf(
                    CreateTodo("投递 Android 岗位", dueAt = null),
                    OpenApp("com.example.other"),
                ),
            )
        val result = AgentExecutionEngine(repository).execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure("OpenApp Tool 未配置"),
            result,
        )
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun openAppAction_isExecutedThroughLauncher() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Success(
                createdTodos = emptyList(),
                openedPackages = listOf("com.android.settings"),
            ),
            result,
        )
        assertEquals(listOf("com.android.settings"), launcher.requestedPackages)
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun deniedOpenApp_doesNotPersistEarlierTodos() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Denied("com.example.other"),
            launchResult = AppLaunchResult.Denied("com.example.other"),
        )
        val todo = CreateTodo("投递 Android 岗位", dueAt = null)
        val plan = AgentPlan(
            goal = "创建待办并打开应用",
            actions = listOf(todo, OpenApp("com.example.other")),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure("未授权打开应用：com.example.other"),
            result,
        )
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun preflightFailure_doesNotLaunchEarlierApp() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
            preflightResults = mapOf(
                "com.example.second" to AppLaunchPreflight.Denied("com.example.second"),
            ),
        )
        val plan = AgentPlan(
            goal = "打开两个应用",
            actions = listOf(
                OpenApp("com.android.settings"),
                OpenApp("com.example.second"),
            ),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure("未授权打开应用：com.example.second"),
            result,
        )
        assertTrue(launcher.requestedPackages.isEmpty())
    }

    @Test
    fun todoPersistenceFailure_reportsAlreadyOpenedApps() = runTest {
        val repository = FailingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val plan = AgentPlan(
            goal = "打开设置并创建待办",
            actions = listOf(
                OpenApp("com.android.settings"),
                CreateTodo("投递 Android 岗位", dueAt = null),
            ),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "待办保存失败",
                openedPackages = listOf("com.android.settings"),
            ),
            result,
        )
    }

    @Test
    fun laterLaunchFailure_reportsEarlierOpenedApp() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
            launchResults = mapOf(
                "com.example.second" to AppLaunchResult.Failure("启动失败"),
            ),
        )
        val plan = AgentPlan(
            goal = "打开两个应用",
            actions = listOf(
                OpenApp("com.android.settings"),
                OpenApp("com.example.second"),
            ),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "启动失败",
                openedPackages = listOf("com.android.settings"),
            ),
            result,
        )
    }

    @Test
    fun launcherException_reportsEarlierOpenedApp() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
            launchExceptions = mapOf("com.example.second" to IllegalStateException()),
        )
        val plan = AgentPlan(
            goal = "打开两个应用",
            actions = listOf(
                OpenApp("com.android.settings"),
                OpenApp("com.example.second"),
            ),
        )

        val result = AgentExecutionEngine(repository, launcher)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "打开应用失败",
                openedPackages = listOf("com.android.settings"),
            ),
            result,
        )
    }

    @Test
    fun cancellationBeforeLaunch_doesNotCallLauncher() = runTest {
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                AgentExecutionEngine(InMemoryTodoRepository(), launcher)
                    .execute(ExecutionConfirmation.issue(plan)) {
                        currentCoroutineContext().cancel()
                    }
            }
        }
        assertTrue(launcher.requestedPackages.isEmpty())
    }

    private class RecordingTodoRepository : TodoRepository {
        val todos = mutableListOf<CreateTodo>()

        override suspend fun addAll(todos: List<CreateTodo>) {
            this.todos += todos
        }

        override suspend fun list(): List<CreateTodo> = todos.toList()
    }

    private class RecordingAppLauncher(
        private val preflightResult: AppLaunchPreflight,
        private val launchResult: AppLaunchResult,
        private val preflightResults: Map<String, AppLaunchPreflight> = emptyMap(),
        private val launchResults: Map<String, AppLaunchResult> = emptyMap(),
        private val launchExceptions: Map<String, Exception> = emptyMap(),
    ) : AppLauncher {
        val requestedPackages = mutableListOf<String>()

        override fun preflight(packageName: String): AppLaunchPreflight =
            preflightResults[packageName] ?: preflightResult

        override suspend fun launch(packageName: String): AppLaunchResult {
            requestedPackages += packageName
            launchExceptions[packageName]?.let { throw it }
            return launchResults[packageName] ?: launchResult
        }
    }

    private class FailingTodoRepository : TodoRepository {
        override suspend fun addAll(todos: List<CreateTodo>) {
            error("写入失败")
        }

        override suspend fun list(): List<CreateTodo> = emptyList()
    }
}
