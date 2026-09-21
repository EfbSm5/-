package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingAgentLoopTest {
    @Test
    fun streamPreviewsNeverExecuteAndFinalFailureNeverRequestsApproval() = runTest {
        val client = StreamingClient()
        val root = RecordingRoot()
        val events = mutableListOf<AgentLoopEvent>()
        val job = async { loop(client, root).run(request()) { events += it } }
        try {
            runCurrent()
            assertEquals(listOf(PARTIAL, COMPLETE), events.filterIsInstance<AgentLoopEvent.ModelOutput>().map { it.snapshot.content })
            assertTrue(root.actions.isEmpty())
            assertTrue(events.none { it is AgentLoopEvent.AwaitingConfirmation || it is AgentLoopEvent.Executing })
            assertFalse(job.isCompleted)
            client.result.complete(DeepSeekActionResult.Failure("stream interrupted"))
            advanceUntilIdle()
            job.await()
            assertTrue(root.actions.isEmpty())
            assertTrue(events.none { it is AgentLoopEvent.AwaitingConfirmation || it is AgentLoopEvent.Executing })
            assertEquals(1, client.calls)
            assertEquals("stream interrupted", (events.last() as AgentLoopEvent.Failed).message)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun finalSuccessWaitsForExistingApprovalBeforeExecutingExactlyOnce() = runTest {
        val client = StreamingClient()
        val root = RecordingRoot()
        val approval = CompletableDeferred<ActionApproval>()
        val events = mutableListOf<AgentLoopEvent>()
        val job = async {
            loop(client, root).run(request()) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    assertEquals(RootPilotAction.Tap(500, 250, "test"), it.action)
                    approval.complete(it.approval)
                }
            }
        }
        try {
            runCurrent()
            assertFalse(approval.isCompleted)
            assertTrue(root.actions.isEmpty())
            client.result.complete(DeepSeekActionResult.Success(COMPLETE))
            runCurrent()
            assertTrue(approval.isCompleted)
            assertTrue(root.actions.isEmpty())
            approval.await().approve()
            advanceUntilIdle()
            job.await()
            assertEquals(1, root.actions.size)
            assertTrue(root.actions.single() is ExecutableRootAction.Tap)
            assertEquals(1, events.filterIsInstance<AgentLoopEvent.Executing>().size)
            assertEquals(1, client.calls)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun rejectingFinalSuccessStillPreventsExecution() = runTest {
        val client = StreamingClient().apply { result.complete(DeepSeekActionResult.Success(COMPLETE)) }
        val root = RecordingRoot()
        val events = mutableListOf<AgentLoopEvent>()
        loop(client, root).run(request()) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.reject()
        }
        assertEquals(1, events.filterIsInstance<AgentLoopEvent.AwaitingConfirmation>().size)
        assertTrue(root.actions.isEmpty())
        assertTrue(events.last() is AgentLoopEvent.Stopped)
    }

    private fun request() = AgentLoopRequest(
        config = RootPilotConfig(task = "fixture", manualConfirmation = true, allowScreenUpload = true),
        maxSteps = 1,
        singleStep = true,
    )

    private fun loop(client: DeepSeekClient, root: RootExecutor) = AgentLoop(
        screenshotProvider = object : ScreenshotProvider {
            override suspend fun capture() = ScreenshotCaptureResult.Success(
                ScreenshotFrame(byteArrayOf(1), 100, 200, "fixture"),
            )
        },
        deepSeekClient = client,
        rootExecutor = root,
    )

    private class StreamingClient : DeepSeekClient {
        val result = CompletableDeferred<DeepSeekActionResult>()
        var calls = 0
        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult =
            error("Expected streaming overload")

        override suspend fun requestAction(
            request: DeepSeekVisionRequest,
            onUpdate: suspend (ModelStreamSnapshot) -> Unit,
        ): DeepSeekActionResult {
            calls++
            onUpdate(ModelStreamSnapshot(reasoning = "preview", content = PARTIAL))
            // Even parseable preview JSON is not an authoritative final response.
            onUpdate(ModelStreamSnapshot(content = COMPLETE))
            return result.await()
        }
    }

    private class RecordingRoot : RootExecutor {
        val actions = mutableListOf<ExecutableRootAction>()
        override suspend fun checkRoot() = RootExecutionResult.Success()
        override suspend fun captureScreen(): RootScreenshotResult = error("Use fixture screenshot")
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            actions += action
            return RootExecutionResult.Success()
        }
        override fun cancel() = Unit
    }

    private companion object {
        const val PARTIAL = "{\"action\":\"tap\",\"x\":"
        const val COMPLETE = """{"action":"tap","x":500,"y":250,"reason":"test"}"""
    }
}
