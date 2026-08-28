package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionEngineTest {
    @Test
    fun planWithAskUser_isRejectedBeforeToolExecution() = runTest {
        val repository = RecordingTodoRepository()
        val plan = AgentPlan(
            goal = "准备投递",
            actions = listOf(AskUser("你准备投递哪个岗位？")),
        )

        val result = AgentExecutionEngine(repository)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Failure("计划仍需要用户补充信息"), result)
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun createTodoAction_isStagedAndCommittedAfterConfirmation() = runTest {
        val repository = RecordingTodoRepository()
        val todo = CreateTodo("投递 Android 岗位", dueAt = null)
        val plan = AgentPlan(goal = "准备投递", actions = listOf(todo))

        val result = AgentExecutionEngine(repository)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Success(
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.CREATE_TODO,
                            status = ActionExecutionStatus.SUCCEEDED,
                            detail = todo.title,
                        ),
                    ),
                ),
            ),
            result,
        )
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

        val result = AgentExecutionEngine(repository)
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Failure("OpenApp Tool 未配置"), result)
        assertTrue(repository.todos.isEmpty())
    }

    @Test
    fun openAppAction_isDispatchedThroughRegistry() = runTest {
        val repository = RecordingTodoRepository()
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )

        val result = AgentExecutionEngine(repository, registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Success(
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.SUCCEEDED,
                            detail = "com.android.settings",
                        ),
                    ),
                ),
            ),
            result,
        )
        assertEquals(listOf("com.android.settings"), launcher.requestedPackages)
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

        val result = AgentExecutionEngine(repository, registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Failure("未授权打开应用：com.example.other"), result)
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

        val result = AgentExecutionEngine(repository, registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(ToolExecutionResult.Failure("未授权打开应用：com.example.second"), result)
        assertTrue(launcher.requestedPackages.isEmpty())
    }

    @Test
    fun todoPersistenceFailure_reportsStagedAndOpenedActions() = runTest {
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

        val result = AgentExecutionEngine(FailingTodoRepository(), registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "待办保存失败",
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.SUCCEEDED,
                            detail = "com.android.settings",
                        ),
                        ActionExecutionRecord(
                            actionIndex = 1,
                            toolName = AgentToolNames.CREATE_TODO,
                            status = ActionExecutionStatus.STAGED,
                            detail = "投递 Android 岗位",
                        ),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun laterLaunchFailure_reportsEarlierOpenedApp() = runTest {
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

        val result = AgentExecutionEngine(RecordingTodoRepository(), registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "启动失败",
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.SUCCEEDED,
                            detail = "com.android.settings",
                        ),
                        ActionExecutionRecord(
                            actionIndex = 1,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.FAILED,
                            detail = "启动失败",
                        ),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun launcherException_isConvertedToToolFailure() = runTest {
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

        val result = AgentExecutionEngine(RecordingTodoRepository(), registry(launcher))
            .execute(ExecutionConfirmation.issue(plan))

        assertEquals(
            ToolExecutionResult.Failure(
                message = "open_app Tool 执行失败",
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.SUCCEEDED,
                            detail = "com.android.settings",
                        ),
                        ActionExecutionRecord(
                            actionIndex = 1,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.FAILED,
                            detail = "open_app Tool 执行失败",
                        ),
                    ),
                ),
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
            runBlocking {
                AgentExecutionEngine(InMemoryTodoRepository(), registry(launcher))
                    .execute(ExecutionConfirmation.issue(plan)) {
                        currentCoroutineContext().cancel()
                    }
            }
        }
        assertTrue(launcher.requestedPackages.isEmpty())
    }

    @Test
    fun rerunningSucceededConfirmation_skipsToolExecution() = runTest {
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val journal = InMemoryExecutionJournal()
        val engine = AgentExecutionEngine(
            todoRepository = RecordingTodoRepository(),
            toolRegistry = registry(launcher),
            executionJournal = journal,
        )
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )
        val confirmation = ExecutionConfirmation.issue(plan)

        assertTrue(engine.execute(confirmation) is ToolExecutionResult.Success)
        assertTrue(engine.execute(confirmation) is ToolExecutionResult.Success)

        assertEquals(listOf("com.android.settings"), launcher.requestedPackages)
    }

    @Test
    fun runningRecord_blocksAutomaticReplay() = runTest {
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val journal = InMemoryExecutionJournal()
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )
        val confirmation = ExecutionConfirmation.issue(plan)
        journal.write(
            ExecutionRecord(
                runId = confirmation.runId,
                status = ExecutionRunStatus.RUNNING,
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.RUNNING,
                        ),
                    ),
                ),
            ),
        )

        val result = AgentExecutionEngine(
            todoRepository = RecordingTodoRepository(),
            toolRegistry = registry(launcher),
            executionJournal = journal,
        ).execute(confirmation)

        assertEquals(
            ToolExecutionResult.Failure(
                message = "上一次执行状态不确定，请人工确认后再试",
                report = ToolExecutionReport(
                    actionResults = listOf(
                        ActionExecutionRecord(
                            actionIndex = 0,
                            toolName = AgentToolNames.OPEN_APP,
                            status = ActionExecutionStatus.RUNNING,
                        ),
                    ),
                ),
            ),
            result,
        )
        assertTrue(launcher.requestedPackages.isEmpty())
    }

    @Test
    fun recoveryConfirmation_replaysRunningActionAfterExplicitApproval() = runTest {
        val launcher = RecordingAppLauncher(
            preflightResult = AppLaunchPreflight.Ready,
            launchResult = AppLaunchResult.Launched,
        )
        val journal = InMemoryExecutionJournal()
        val plan = AgentPlan(
            goal = "打开设置",
            actions = listOf(OpenApp("com.android.settings")),
        )
        val confirmation = ExecutionConfirmation.issue(plan)
        val record = ExecutionRecord(
            runId = confirmation.runId,
            status = ExecutionRunStatus.RUNNING,
            report = ToolExecutionReport(
                actionResults = listOf(
                    ActionExecutionRecord(
                        actionIndex = 0,
                        toolName = AgentToolNames.OPEN_APP,
                        status = ActionExecutionStatus.RUNNING,
                    ),
                ),
            ),
            plan = PersistedAgentPlan.fromDomain(plan),
        )
        journal.write(record)

        val result = AgentExecutionEngine(
            todoRepository = RecordingTodoRepository(),
            toolRegistry = registry(launcher),
            executionJournal = journal,
        ).execute(
            ExecutionConfirmation.recover(
                RecoverableExecution(record = record, plan = plan),
            ),
        )

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(listOf("com.android.settings"), launcher.requestedPackages)
    }

    @Test
    fun recovery_skipsSuccessfulActionBeforeResolvingItsTool() = runTest {
        val repository = RecordingTodoRepository()
        val journal = InMemoryExecutionJournal()
        val plan = AgentPlan(
            goal = "打开设置并创建待办",
            actions = listOf(
                OpenApp("com.android.settings"),
                CreateTodo("投递 Android 岗位", dueAt = null),
            ),
        )
        val record = ExecutionRecord(
            runId = "run-skip-preflight",
            status = ExecutionRunStatus.FAILED,
            report = ToolExecutionReport(
                actionResults = listOf(
                    ActionExecutionRecord(
                        actionIndex = 0,
                        toolName = AgentToolNames.OPEN_APP,
                        status = ActionExecutionStatus.SUCCEEDED,
                        detail = "com.android.settings",
                    ),
                    ActionExecutionRecord(
                        actionIndex = 1,
                        toolName = AgentToolNames.CREATE_TODO,
                        status = ActionExecutionStatus.FAILED,
                        detail = "之前失败",
                    ),
                ),
            ),
            plan = PersistedAgentPlan.fromDomain(plan),
        )
        journal.write(record)

        val result = AgentExecutionEngine(
            todoRepository = repository,
            toolRegistry = ToolRegistry(listOf(CreateTodoTool())),
            executionJournal = journal,
        ).execute(
            ExecutionConfirmation.recover(
                RecoverableExecution(record = record, plan = plan),
            ),
        )

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(
            listOf(CreateTodo("投递 Android 岗位", dueAt = null)),
            repository.todos,
        )
    }

    private fun registry(launcher: AppLauncher): ToolRegistry = ToolRegistry(
        tools = listOf(CreateTodoTool(), OpenAppTool(launcher)),
    )

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
