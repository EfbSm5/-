package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.*
import com.example.agent.rootpilot.screen.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayLoopTest {
    private val app = RootPilotApp("com.example.target", "Target", "com.example.target.Main")
    private val config = RootPilotConfig(task = "fixed test", allowScreenUpload = true,
        manualConfirmation = false, executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = app.packageName)
    private inner class Root : RootExecutor {
        var valid = true
        var identity = "first"
        val actions = mutableListOf<ExecutableRootAction>()
        override val initialApp get() = app
        override val sessionIdentity get() = identity
        override suspend fun validateSession() = valid
        override suspend fun checkRoot() = RootExecutionResult.Success()
        override suspend fun captureScreen(): RootScreenshotResult = error("not used")
        override suspend fun observeScreen() = ScreenObservation(app.packageName, app.activityName, app.packageName,
            "window", false, 1, 9, identity)
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            actions += action
            return RootExecutionResult.Success()
        }
        override fun supports(action: ExecutableRootAction) = action is ExecutableRootAction.Tap ||
            action is ExecutableRootAction.OpenApp || action is ExecutableRootAction.Wait
        override fun cancel() { valid = false }
    }
    private fun loop(root: Root, responses: List<String>, modelCalled: () -> Unit = {}): AgentLoop {
        var calls = 0
        var frames = 0
        return AgentLoop(object : ScreenshotProvider {
            override suspend fun capture() = ScreenshotCaptureResult.Success(ScreenshotFrame(byteArrayOf((++frames).toByte()),
                1080, 1920, "fixed"))
        }, object : DeepSeekClient {
            override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                modelCalled()
                return DeepSeekActionResult.Success(responses[calls++])
            }
        }, root, appCatalog = AppCatalog { listOf(app) })
    }
    @Test fun initialAppAndTapBothRequireConfirmationEvenIfMainAutoModeWasSelected() = runTest {
        val root = Root()
        var approvals = 0
        val events = mutableListOf<AgentLoopEvent>()
        loop(root, listOf("""{"action":"tap","x":500,"y":500,"reason":"fixed"}""",
            """{"action":"finish","success":true,"message":"fixed"}"""))
            .run(AgentLoopRequest(config, 2)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) { approvals++; it.approval.approve() }
            }
        assertEquals(2, approvals)
        assertEquals(listOf(ExecutableRootAction.OpenApp(app), ExecutableRootAction.Tap(539, 959)), root.actions)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }
    @Test fun rejectionAtBootstrapMakesNoModelRequestOrDeviceAction() = runTest {
        val root = Root()
        var modelCalls = 0
        loop(root, emptyList()) { modelCalls++ }.run(AgentLoopRequest(config, 2)) {
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.reject()
        }
        assertEquals(0, modelCalls)
        assertTrue(root.actions.isEmpty())
    }
    @Test fun expiredBootstrapConfirmationCannotOpenOnAnotherSession() = runTest {
        val root = Root()
        loop(root, emptyList()).run(AgentLoopRequest(config, 2)) {
            if (it is AgentLoopEvent.AwaitingConfirmation) { root.identity = "replacement"; it.approval.approve() }
        }
        assertTrue(root.actions.isEmpty())
    }
    @Test fun changedSessionAtTapConfirmationRejectsExecution() = runTest {
        val root = Root()
        loop(root, listOf("""{"action":"tap","x":500,"y":500,"reason":"fixed"}"""))
            .run(AgentLoopRequest(config, 1)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    if (it.action is RootPilotAction.Tap) root.identity = "replacement"
                    it.approval.approve()
                }
            }
        assertEquals(listOf(ExecutableRootAction.OpenApp(app)), root.actions)
    }
    @Test fun unsupportedTextNeverTouchesImeOrRequestsTextConfirmation() = runTest {
        val root = Root()
        val approvals = mutableListOf<RootPilotAction>()
        loop(root, listOf("""{"action":"type","text":"fixed","reason":"fixed"}"""))
            .run(AgentLoopRequest(config, 1)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) { approvals += it.action; it.approval.approve() }
            }
        assertEquals(1, approvals.size)
        assertEquals(listOf(ExecutableRootAction.OpenApp(app)), root.actions)
    }
}
