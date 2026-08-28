package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
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

class AgentLoopTest {
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
    fun maliciousTypePayload_isRejectedBeforeRootExecution() = runTest {
        val root = RecordingRootExecutor()
        val events = mutableListOf<AgentLoopEvent>()
        AgentLoop(
            screenshotProvider = IncrementingScreenshotProvider(),
            deepSeekClient = QueueDeepSeekClient(
                """{"action":"type","text":"hello;rm","reason":"输入"}""",
                """{"action":"type","text":"hello;rm","reason":"输入"}""",
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
    ): AgentLoopRequest = AgentLoopRequest(
        config = RootPilotConfig(
            apiKey = "test-key",
            task = "测试任务",
            manualConfirmation = manualConfirmation,
            allowScreenUpload = true,
        ),
        maxSteps = maxSteps,
    )

    private class QueueDeepSeekClient(vararg private val responses: String) : DeepSeekClient {
        private val queue = ArrayDeque(responses.toList())
        var requestCount: Int = 0

        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
            requestCount++
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
