package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.planning.TodoRepository
import com.example.agent.agent.planning.FileTodoRepository
import java.nio.file.Files
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin

class AgentLoopTest {
    private val todoJson = """{"action":"create_todo","title":" 买牛奶 ","due_at":null,"reason":"记录"}"""
    private val finishJson = """{"action":"finish","success":true,"message":"完成"}"""

    @Test
    fun createTodo_savesConfirmedPreviewOnceAndReportsHistory() = runTest {
        val directory = Files.createTempDirectory("rootpilot-todo-test").toFile()
        try {
            val repository = FileTodoRepository(java.io.File(directory, "agent_todos.json"))
            val root = RecordingRootExecutor()
            val client = QueueDeepSeekClient(todoJson, finishJson)
            val events = mutableListOf<AgentLoopEvent>()
            AgentLoop(RepeatedScreenshotProvider(), client, root, todoRepository = repository)
                .run(request(maxSteps = 2)) {
                    events += it
                    if (it is AgentLoopEvent.AwaitingConfirmation) {
                        assertEquals(RootPilotAction.CreateTodo("买牛奶", null, "记录"), it.action)
                        assertTrue(repository.list().isEmpty())
                        assertTrue(it.approval.approve(it.approval.token))
                        assertTrue(!it.approval.approve(it.approval.token))
                        it.approval.approve()
                    }
                }
            assertEquals(listOf(CreateTodo("买牛奶", null)), repository.list())
            assertTrue(root.actions.isEmpty())
            assertTrue(client.requests.last().history.single().contains("\"title\":\"买牛奶\""))
            assertTrue(client.requests.last().history.single().contains("\"due_at\":null"))
            assertTrue(client.requests.last().history.single().contains("result=success"))
            assertTrue(events.last() is AgentLoopEvent.Completed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun createTodo_rejectionDoesNotWrite() = runTest {
        val repository = RecordingTodoRepository()
        val events = mutableListOf<AgentLoopEvent>()
        todoLoop(repository, todoJson).run(request()) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                it.approval.reject()
                it.approval.approve()
            }
        }
        assertEquals(0, repository.attempts)
        assertTrue(events.last() is AgentLoopEvent.Stopped)
    }

    @Test
    fun createTodo_saveFailureStopsWithoutRetryOrLeakingException() = runTest {
        val repository = RecordingTodoRepository { throw java.io.IOException("private path and token") }
        val events = mutableListOf<AgentLoopEvent>()
        todoLoop(repository, todoJson).run(request()) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }
        assertEquals(1, repository.attempts)
        assertEquals("本地待办保存失败，已停止；请人工核对保存结果后再决定是否重试", (events.last() as AgentLoopEvent.Failed).message)
    }

    @Test
    fun createTodo_cancellationWhileAwaitingApprovalDoesNotWrite() = runTest {
        val repository = RecordingTodoRepository()
        val ready = CompletableDeferred<ActionApproval>()
        val job = async {
            todoLoop(repository, todoJson).run(request()) {
                if (it is AgentLoopEvent.AwaitingConfirmation) ready.complete(it.approval)
            }
        }
        val approval = ready.await()
        job.cancelAndJoin()
        approval.approve()
        assertEquals(0, repository.attempts)
    }

    @Test
    fun createTodo_repositoryCancellationPropagates() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = RecordingTodoRepository {
            entered.complete(Unit)
            awaitCancellation()
        }
        val events = mutableListOf<AgentLoopEvent>()
        val job = async {
            todoLoop(repository, todoJson).run(request()) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(events.none { it is AgentLoopEvent.Failed || it is AgentLoopEvent.Completed })
        assertEquals(1, repository.attempts)
    }

    @Test
    fun createTodo_sameTaskDeduplicatesAcrossInterveningActionsAndEquivalentOffsets() = runTest {
        val repository = RecordingTodoRepository()
        val first = todoJson.replace("null", "\"2026-09-22T09:00:00+08:00\"")
        val duplicate = todoJson.replace(" 买牛奶 ", "买牛奶").replace("记录", "再次记录")
            .replace("null", "\"2026-09-22T01:00:00Z\"")
        val events = mutableListOf<AgentLoopEvent>()
        todoLoop(repository, first, todoJson.replace("买牛奶", "买面包"), duplicate)
            .run(request(maxSteps = 3)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
        assertEquals(2, repository.attempts)
        assertEquals(2, events.filterIsInstance<AgentLoopEvent.AwaitingConfirmation>().size)
        assertEquals("本次任务已保存相同待办，已阻止重复写入", (events.last() as AgentLoopEvent.Failed).message)
    }

    @Test
    fun createTodo_multipleTodosCanFinishWithUnchangedScreenshot() = runTest {
        val repository = RecordingTodoRepository()
        val events = mutableListOf<AgentLoopEvent>()
        todoLoop(repository, todoJson, todoJson.replace("买牛奶", "买面包"), todoJson.replace("买牛奶", "买鸡蛋"), finishJson)
            .run(request(maxSteps = 4)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
        assertEquals(3, repository.attempts)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test
    fun createTodo_deduplicationDoesNotCrossRunsAndSingleStepCompletes() = runTest {
        val repository = RecordingTodoRepository()
        val loop = todoLoop(repository, todoJson, todoJson)
        repeat(2) {
            val events = mutableListOf<AgentLoopEvent>()
            loop.run(request().copy(singleStep = true)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
            assertTrue(events.last() is AgentLoopEvent.Completed)
        }
        assertEquals(2, repository.attempts)
    }

    private fun todoLoop(repository: TodoRepository, vararg actions: String) = AgentLoop(
        RepeatedScreenshotProvider(), QueueDeepSeekClient(*actions), RecordingRootExecutor(),
        todoRepository = repository,
    )

    private class RecordingTodoRepository(private val onWrite: suspend () -> Unit = {}) : TodoRepository {
        var attempts = 0
        private val todos = mutableListOf<CreateTodo>()
        override suspend fun addAll(todos: List<CreateTodo>) {
            attempts++
            onWrite()
            this.todos += todos
        }
        override suspend fun list(): List<CreateTodo> = todos.toList()
    }

    private val settingsApp = RootPilotApp("com.android.settings", "设置", "com.android.settings.Settings")
    @Test
    fun waitsForWindowRemovalBeforeCapturingAndExecuting() = runTest {
        val captureEvent = CompletableDeferred<Unit>()
        val allowCapture = CompletableDeferred<Unit>()
        val executeEvent = CompletableDeferred<Unit>()
        val allowExecute = CompletableDeferred<Unit>()
        var captures = 0
        val root = RecordingRootExecutor()
        val job = async {
            AgentLoop(
                screenshotProvider = object : ScreenshotProvider {
                    override suspend fun capture(): ScreenshotCaptureResult {
                        captures++
                        return RepeatedScreenshotProvider().capture()
                    }
                },
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
                ),
                rootExecutor = root,
            ).run(request()) {
                when (it) {
                    is AgentLoopEvent.Capturing -> {
                        captureEvent.complete(Unit)
                        allowCapture.await()
                    }
                    is AgentLoopEvent.Executing -> {
                        executeEvent.complete(Unit)
                        allowExecute.await()
                    }
                    else -> Unit
                }
            }
        }
        captureEvent.await()
        assertEquals(0, captures)
        allowCapture.complete(Unit)
        executeEvent.await()
        assertEquals(1, captures)
        assertTrue(root.actions.isEmpty())
        allowExecute.complete(Unit)
        job.await()
        assertEquals(1, root.actions.size)
    }

    @Test
    fun finishAction_completesWithoutRootExecution() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()
        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"finish","success":true,"message":"任务完成"}""",
            ),
            rootExecutor = root,
        ).run(request()) { events += it }

        assertTrue(events.last() is AgentLoopEvent.Completed)
        assertTrue(root.actions.isEmpty())
    }

    @Test
    fun manualConfirmation_waitsBeforeExecutingAndCanStop() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val loop = AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
                """{"action":"finish","success":true,"message":"完成"}""",
            ),
            rootExecutor = root,
        )
        val events = mutableListOf<AgentLoopEvent>()
        val job = async {
            loop.run(request(maxSteps = 2, manualConfirmation = true)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    approvalReady.complete(it.approval)
                }
            }
        }

        val approval = approvalReady.await()
        assertTrue(root.actions.isEmpty())
        approval.approve()
        job.await()

        assertEquals(1, root.actions.size)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test
    fun rejectingConfirmation_stopsWithoutExecutingAction() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val events = mutableListOf<AgentLoopEvent>()
        val job = async {
            AgentLoop(
                screenshotProvider = IncrementingScreenshotProvider(),
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
                ),
                rootExecutor = root,
            ).run(request(manualConfirmation = true)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) approvalReady.complete(it.approval)
            }
        }

        approvalReady.await().reject()
        job.await()

        assertTrue(root.actions.isEmpty())
        assertTrue(events.last() is AgentLoopEvent.Stopped)
    }

    @Test
    fun repeatedFrames_stopBeforeMaxSteps() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()
        AgentLoop(
            screenshotProvider = RepeatedScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
                """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
            ),
            rootExecutor = root,
        ).run(request(maxSteps = 20)) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }

        assertEquals(2, root.actions.size)
        assertEquals("连续截图没有变化，已停止避免死循环", (events.last() as AgentLoopEvent.Failed).message)
    }

    @Test
    fun invalidUnicodeTypePayload_isRejectedBeforeRootExecution() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()
        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"type","text":"hello\u0000","reason":"输入"}""",
                """{"action":"type","text":"hello\u0000","reason":"输入"}""",
            ),
            rootExecutor = root,
        ).run(request()) { events += it }

        assertTrue(root.actions.isEmpty())
        assertTrue(events.last() is AgentLoopEvent.Failed)
    }

    @Test
    fun invalidModelJson_isRetriedOnce() = runTest {
        val root = RecordingRootExecutor()
        val client = QueueDeepSeekClient(
            "not-json",
            "{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}",
        )

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = client,
            rootExecutor = root,
        ).run(request()) {}

        assertEquals(2, client.requestCount)
    }

    @Test
    fun invalidModelJsonTwice_failsWithoutRootExecution() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient("not-json", "still-not-json"),
            rootExecutor = root,
        ).run(request()) { events += it }

        assertTrue(root.actions.isEmpty())
        assertTrue(events.last() is AgentLoopEvent.Failed)
    }

    @Test
    fun scaledScreenshot_usesPhysicalScreenSizeForCoordinates() = runTest {
        val root = RecordingRootExecutor()

        AgentLoop(
            screenshotProvider = PhysicalSizeScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
            ),
            rootExecutor = root,
        ).run(request()) {
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }

        assertEquals(listOf(ExecutableRootAction.Tap(49, 99)), root.actions)
    }

    @Test
    fun automaticMode_requiresConfirmationForTyping() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val job = async {
            AgentLoop(
                screenshotProvider = IncrementingScreenshotProvider(),
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"type","text":"hello","reason":"输入"}""",
                ),
                rootExecutor = root,
            ).run(request(manualConfirmation = false)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) approvalReady.complete(it.approval)
            }
        }

        approvalReady.await().reject()
        job.await()

        assertTrue(root.actions.isEmpty())
    }

    @Test
    fun automaticMode_executesTapWithoutConfirmation() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
                """{"action":"finish","success":true,"message":"完成"}""",
            ),
            rootExecutor = root,
        ).run(request(maxSteps = 2, manualConfirmation = false)) {
            events += it
        }

        assertEquals(listOf(ExecutableRootAction.Tap(49, 49)), root.actions)
        assertTrue(events.none { it is AgentLoopEvent.AwaitingConfirmation })
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test
    fun automaticMode_executesSwipeWithoutConfirmation() = runTest {
        val root = RecordingRootExecutor()

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"swipe","x1":100,"y1":100,"x2":200,"y2":200,"duration_ms":300,"reason":"滑动"}""",
                """{"action":"finish","success":true,"message":"完成"}""",
            ),
            rootExecutor = root,
        ).run(request(maxSteps = 2, manualConfirmation = false)) {}

        assertEquals(
            listOf(ExecutableRootAction.Swipe(9, 9, 19, 19, 300)),
            root.actions,
        )
    }

    @Test
    fun automaticMode_executesCatalogAppOnlyAfterConfirmation() = runTest {
        val root = RecordingRootExecutor()

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"open_app","package_name":"com.android.settings","reason":"打开设置"}""",
                """{"action":"finish","success":true,"message":"完成"}""",
            ),
            rootExecutor = root,
                appCatalog = com.example.agent.rootpilot.apps.AppCatalog { listOf(settingsApp) },
        ).run(request(maxSteps = 2, manualConfirmation = false)) {
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                assertTrue(it.action is RootPilotAction.OpenApp)
                assertTrue(root.actions.isEmpty())
                it.approval.approve()
            }
        }

        assertEquals(
            listOf(ExecutableRootAction.OpenApp(settingsApp)),
            root.actions,
        )
    }

    @Test
    fun systemSettingsTask_usesModelActionFromFirstStep() = runTest {
        val root = RecordingRootExecutor()
        val client = QueueDeepSeekClient(
            """{"action":"tap","x":500,"y":500,"reason":"点击"}""",
        )

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = client,
            rootExecutor = root,
        ).run(
            request(
                maxSteps = 1,
                manualConfirmation = false,
                task = "打开系统设置",
            ),
        ) {}

        assertEquals(
            listOf(
                ExecutableRootAction.Tap(49, 49),
            ),
            root.actions,
        )
        assertEquals(1, client.requestCount)
    }

    @Test
    fun systemSettingsTask_modelChoosesLaunchThenNavigation() = runTest {
        val root = RecordingRootExecutor()
        val client = QueueDeepSeekClient(
            """{"action":"open_app","package_name":"com.android.settings","reason":"打开目标应用"}""",
            """{"action":"tap","x":500,"y":500,"reason":"进入显示"}""",
            """{"action":"finish","success":true,"message":"完成"}""",
        )

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = client,
            rootExecutor = root,
                appCatalog = com.example.agent.rootpilot.apps.AppCatalog { listOf(settingsApp) },
        ).run(
            request(
                maxSteps = 3,
                manualConfirmation = false,
                task = "打开系统设置，进入显示设置",
            ),
        ) {
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                assertTrue(it.action is RootPilotAction.OpenApp)
                assertTrue(root.actions.isEmpty())
                it.approval.approve()
            }
        }

        assertEquals(
            listOf(
                ExecutableRootAction.OpenApp(settingsApp),
                ExecutableRootAction.Tap(49, 49),
            ),
            root.actions,
        )
        assertEquals(listOf(0, 1, 2), client.requests.map { it.step })
    }

    @Test
    fun systemSettingsTask_canFinishImmediatelyWhenTargetAlreadyVisible() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()
        val client = QueueDeepSeekClient(
            """{"action":"finish","success":true,"message":"已在目标页面"}""",
        )

        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = client,
            rootExecutor = root,
        ).run(
            request(
                maxSteps = 2,
                manualConfirmation = false,
                task = "打开系统设置，进入显示设置",
            ),
        ) { events += it }

        assertEquals(
            emptyList<ExecutableRootAction>(),
            root.actions,
        )
        assertEquals(
            "已在目标页面",
            (events.last() as AgentLoopEvent.Completed).message,
        )
    }

    @Test
    fun manualConfirmation_requiresConfirmationForOpenApp() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val job = async {
            AgentLoop(
                screenshotProvider = IncrementingScreenshotProvider(),
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"open_app","package_name":"com.android.settings","reason":"打开设置"}""",
                ),
                rootExecutor = root,
                appCatalog = com.example.agent.rootpilot.apps.AppCatalog { listOf(settingsApp) },
            ).run(request(manualConfirmation = true)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) approvalReady.complete(it.approval)
            }
        }

        approvalReady.await().reject()
        job.await()

        assertTrue(root.actions.isEmpty())
    }

    @Test
    fun automaticMode_requiresConfirmationForSystemKey() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val job = async {
            AgentLoop(
                screenshotProvider = IncrementingScreenshotProvider(),
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"key","key":"HOME","reason":"返回桌面"}""",
                ),
                rootExecutor = root,
            ).run(request(manualConfirmation = false)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) approvalReady.complete(it.approval)
            }
        }

        approvalReady.await().reject()
        job.await()

        assertTrue(root.actions.isEmpty())
    }

    @Test
    fun askUser_pausesUntilUserConfirmsThenContinues() = runTest {
        val root = RecordingRootExecutor()
        val approvalReady = CompletableDeferred<ActionApproval>()
        val events = mutableListOf<AgentLoopEvent>()
        val job = async {
            AgentLoop(
                screenshotProvider = IncrementingScreenshotProvider(),
                deepSeekClient = QueueDeepSeekClient(
                    """{"action":"ask_user","message":"请打开系统设置"}""",
                    """{"action":"finish","success":true,"message":"完成"}""",
                ),
                rootExecutor = root,
            ).run(request(maxSteps = 2)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation && it.action is RootPilotAction.AskUser) {
                    approvalReady.complete(it.approval)
                }
            }
        }

        val approval = approvalReady.await()
        assertTrue(root.actions.isEmpty())
        approval.approve()
        job.await()

        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    private fun request(
        maxSteps: Int = 1,
        manualConfirmation: Boolean = false,
        task: String = "测试任务",
    ): AgentLoopRequest = AgentLoopRequest(
        config = RootPilotConfig(
            apiKey = "test-key",
            task = task,
            manualConfirmation = manualConfirmation,
            allowScreenUpload = true,
        ),
        maxSteps = maxSteps,
    )

    private class QueueDeepSeekClient(vararg private val responses: String) : DeepSeekClient {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<DeepSeekVisionRequest>()
        var requestCount: Int = 0

        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
            requestCount++
            requests += request
            return DeepSeekActionResult.Success(queue.removeFirst())
        }
    }

    private class IncrementingScreenshotProvider : ScreenshotProvider {
        private var count = 0

        override suspend fun capture(): ScreenshotCaptureResult = {
            count++
            ScreenshotCaptureResult.Success(
                ScreenshotFrame(
                    bytes = byteArrayOf(count.toByte()),
                    width = 100,
                    height = 100,
                    dataUrl = "data:image/jpeg;base64,test",
                ),
            )
        }()
    }

    private class RepeatedScreenshotProvider : ScreenshotProvider {
        override suspend fun capture(): ScreenshotCaptureResult = ScreenshotCaptureResult.Success(
            ScreenshotFrame(
                bytes = byteArrayOf(1),
                width = 100,
                height = 100,
                dataUrl = "data:image/jpeg;base64,test",
            ),
        )
    }

    private class PhysicalSizeScreenshotProvider : ScreenshotProvider {
        override suspend fun capture(): ScreenshotCaptureResult = ScreenshotCaptureResult.Success(
            ScreenshotFrame(
                bytes = byteArrayOf(1),
                width = 50,
                height = 100,
                dataUrl = "data:image/jpeg;base64,test",
                physicalWidth = 100,
                physicalHeight = 200,
            ),
        )
    }

    private class RecordingRootExecutor : RootExecutor {
        val actions = mutableListOf<ExecutableRootAction>()

        override suspend fun checkRoot(): RootExecutionResult = RootExecutionResult.Success()

        override suspend fun captureScreen() =
            com.example.agent.rootpilot.root.RootScreenshotResult.Failure("未配置")

        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            actions += action
            return RootExecutionResult.Success()
        }

        override fun cancel() = Unit
    }
}
