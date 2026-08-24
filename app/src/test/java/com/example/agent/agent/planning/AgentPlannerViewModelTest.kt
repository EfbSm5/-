package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentPlannerViewModelTest {
    @Test
    fun planWithAskUser_exposesClarificationState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(
                dispatcher = dispatcher,
                modelResult = AgentModelResult.Success(
                    """{"goal":"测试","actions":[{"type":"ask_user","question":"确认？"}]}""",
                ),
            )

            viewModel.submit("测试")
            advanceUntilIdle()

            assertEquals(
                AgentRunState.NeedsClarification(
                    request = "测试",
                    plan = AgentPlan(
                        goal = "测试",
                        actions = listOf(AskUser("确认？")),
                    ),
                    question = "确认？",
                ),
                viewModel.uiState.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun blankRequest_exposesNonRetryableFailure() {
        val viewModel = createViewModel(
            dispatcher = StandardTestDispatcher(),
            modelResult = AgentModelResult.Success("不应被调用"),
        )

        viewModel.submit("   ")

        assertEquals(
            AgentRunState.Failure(
                message = "请输入你想让助手完成的目标",
                canRetry = false,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun decodeFailure_exposesRetryableUserMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(
                dispatcher = dispatcher,
                modelResult = AgentModelResult.Success("不是 JSON"),
            )

            viewModel.submit("测试")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is AgentRunState.Failure)
            assertEquals(true, (state as AgentRunState.Failure).canRetry)
            assertEquals("助手返回的计划无法理解，请重试", state.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun authenticationFailure_exposesSafeNonRetryableMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(
                dispatcher = dispatcher,
                modelResult = AgentModelResult.Failure(
                    AgentModelFailure.Authentication("internal token detail"),
                ),
            )

            viewModel.submit("测试")
            advanceUntilIdle()

            assertEquals(
                AgentRunState.Failure(
                    message = "助手服务配置异常，请联系开发者",
                    canRetry = false,
                ),
                viewModel.uiState.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun answerClarification_replansWithOriginalRequestAndAnswer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val requests = mutableListOf<String>()
            val responses = listOf(
                """{"goal":"准备投递","actions":[{"type":"ask_user","question":"你准备投递哪个岗位？"}]}""",
                """{"goal":"准备投递","actions":[{"type":"create_todo","title":"投递 Android 岗位"}]}""",
            )
            var responseIndex = 0
            val viewModel = AgentPlannerViewModel(
                planner = AgentPlanner(
                    object : AgentModelClient {
                        override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
                            requests += userRequest
                            return AgentModelResult.Success(responses[responseIndex++])
                        }
                    },
                ),
                dispatcher = dispatcher,
            )

            viewModel.submit("准备投递")
            advanceUntilIdle()
            viewModel.answerClarification("Android 客户端工程师岗位")
            advanceUntilIdle()

            assertTrue(requests[1].contains("准备投递"))
            assertTrue(requests[1].contains("Android 客户端工程师岗位"))
            val state = viewModel.uiState.value
            assertTrue(state is AgentRunState.AwaitingConfirmation)
            assertEquals(
                AgentPlan(
                    goal = "准备投递",
                    actions = listOf(CreateTodo("投递 Android 岗位", dueAt = null)),
                ),
                (state as AgentRunState.AwaitingConfirmation).plan,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledOldRequestCannotOverwriteNewRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()
            var requestIndex = 0
            val viewModel = AgentPlannerViewModel(
                planner = AgentPlanner(
                    object : AgentModelClient {
                        override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
                            return if (requestIndex++ == 0) {
                                withContext(NonCancellable) { firstGate.await() }
                                AgentModelResult.Success(
                                    """{"goal":"旧请求","actions":[{"type":"create_todo","title":"旧待办"}]}""",
                                )
                            } else {
                                secondGate.await()
                                AgentModelResult.Success(
                                    """{"goal":"新请求","actions":[{"type":"create_todo","title":"新待办"}]}""",
                                )
                            }
                        }
                    },
                ),
                dispatcher = dispatcher,
            )

            viewModel.submit("旧请求")
            runCurrent()
            viewModel.submit("新请求")
            runCurrent()
            firstGate.complete(Unit)
            runCurrent()

            assertEquals(AgentRunState.Planning("新请求"), viewModel.uiState.value)

            secondGate.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is AgentRunState.AwaitingConfirmation)
            assertEquals(
                AgentPlan(
                    goal = "新请求",
                    actions = listOf(CreateTodo("新待办", dueAt = null)),
                ),
                (state as AgentRunState.AwaitingConfirmation).plan,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmation_executesCreateTodoAndExposesCompletedState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val plan = AgentPlan(
                goal = "准备投递",
                actions = listOf(CreateTodo("投递 Android 岗位", dueAt = null)),
            )
            val viewModel = createViewModel(
                dispatcher = dispatcher,
                modelResult = AgentModelResult.Success(
                    """{"goal":"准备投递","actions":[{"type":"create_todo","title":"投递 Android 岗位"}]}""",
                ),
            )

            viewModel.submit("准备投递")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is AgentRunState.AwaitingConfirmation)
            assertEquals(
                plan,
                (viewModel.uiState.value as AgentRunState.AwaitingConfirmation).plan,
            )

            viewModel.confirmExecution()
            advanceUntilIdle()

            assertEquals(
                AgentRunState.Completed(
                    plan = plan,
                    report = ToolExecutionReport(
                        actionResults = listOf(
                            ActionExecutionRecord(
                                actionIndex = 0,
                                toolName = AgentToolNames.CREATE_TODO,
                                status = ActionExecutionStatus.SUCCEEDED,
                                detail = "投递 Android 岗位",
                            ),
                        ),
                    ),
                ),
                viewModel.uiState.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun executionFailure_exposesAlreadyOpenedApps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = AgentPlannerViewModel(
                planner = AgentPlanner(
                    FakeAgentModelClient(
                        AgentModelResult.Success(
                            """{"goal":"打开设置并创建待办","actions":[{"type":"open_app","package_name":"com.android.settings"},{"type":"create_todo","title":"投递 Android 岗位"}]}""",
                        ),
                    ),
                ),
                executionEngine = AgentExecutionEngine(
                    todoRepository = FailingTodoRepository(),
                    toolRegistry = ToolRegistry(
                        tools = listOf(
                            CreateTodoTool(),
                            OpenAppTool(ReadyAppLauncher()),
                        ),
                    ),
                ),
                dispatcher = dispatcher,
            )

            viewModel.submit("打开设置并创建待办")
            advanceUntilIdle()
            viewModel.confirmExecution()
            advanceUntilIdle()

            assertEquals(
                AgentRunState.Failure(
                    message = "待办保存失败",
                    canRetry = false,
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
                viewModel.uiState.value,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        modelResult: AgentModelResult,
    ): AgentPlannerViewModel = AgentPlannerViewModel(
        planner = AgentPlanner(FakeAgentModelClient(modelResult)),
        dispatcher = dispatcher,
    )

    private class FakeAgentModelClient(
        private val result: AgentModelResult,
    ) : AgentModelClient {
        override suspend fun generatePlanJson(userRequest: String): AgentModelResult = result
    }

    private class FailingTodoRepository : TodoRepository {
        override suspend fun addAll(todos: List<CreateTodo>) {
            error("写入失败")
        }

        override suspend fun list(): List<CreateTodo> = emptyList()
    }

    private class ReadyAppLauncher : AppLauncher {
        override fun preflight(packageName: String): AppLaunchPreflight = AppLaunchPreflight.Ready

        override suspend fun launch(packageName: String): AppLaunchResult = AppLaunchResult.Launched
    }
}
