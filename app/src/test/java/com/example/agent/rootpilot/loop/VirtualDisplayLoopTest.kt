package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.ChatToolCall
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.information.*
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.*
import com.example.agent.rootpilot.screen.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayLoopTest {
    private val app = RootPilotApp("com.example.target", "Target", "com.example.target.Main")
    private val config = RootPilotConfig(task = "fixed test", allowScreenUpload = true,
        manualConfirmation = false, executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = app.packageName)
    private inner class Root : RootExecutor {
        var valid = true
        var identity = "first"
        var window = "window"
        var display = 9
        var queries = 0
        var queryMutation: () -> Unit = {}
        val actions = mutableListOf<ExecutableRootAction>()
        override val initialApp get() = app
        override val sessionIdentity get() = identity
        override suspend fun validateSession() = valid
        override suspend fun checkRoot() = RootExecutionResult.Success()
        override suspend fun captureScreen(): RootScreenshotResult = error("not used")
        override suspend fun observeScreen() = ScreenObservation(app.packageName, app.activityName, app.packageName,
            window, false, 1, display, identity)
        override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult {
            assertEquals(DeviceInfoTool.UI_TREE, tool)
            queries++
            val result = DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 1, 2,
                buildJsonObject { put("display_id", display); put("text", "private-tree-marker") })
            queryMutation()
            return result
        }
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            actions += action
            return RootExecutionResult.Success()
        }
        override fun supports(action: ExecutableRootAction) = action is ExecutableRootAction.Tap ||
            action is ExecutableRootAction.OpenApp || action is ExecutableRootAction.Wait ||
            action is ExecutableRootAction.Swipe || (action is ExecutableRootAction.Key && action.key != RootPilotKey.HOME)
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

    @Test fun virtualSwipeBackAndEnterRequireConfirmationInAutoMode() = runTest {
        val root = Root()
        val approvals = mutableListOf<RootPilotAction>()
        val events = mutableListOf<AgentLoopEvent>()
        loop(root, listOf(
            """{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration_ms":400,"reason":"fixed"}""",
            """{"action":"key","key":"BACK","reason":"fixed"}""",
            """{"action":"key","key":"ENTER","reason":"fixed"}""",
            """{"action":"finish","success":true,"message":"fixed"}""",
        )).run(AgentLoopRequest(config, 4)) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) { approvals += it.action; it.approval.approve() }
        }
        assertEquals(4, approvals.size)
        assertTrue(approvals[1] is RootPilotAction.Swipe)
        assertEquals(RootPilotKey.BACK, (approvals[2] as RootPilotAction.Key).key)
        assertEquals(RootPilotKey.ENTER, (approvals[3] as RootPilotAction.Key).key)
        assertEquals(listOf(ExecutableRootAction.OpenApp(app), ExecutableRootAction.Swipe(539, 1535, 539, 383, 400),
            ExecutableRootAction.Key(RootPilotKey.BACK), ExecutableRootAction.Key(RootPilotKey.ENTER)), root.actions)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test fun changedSessionAtSwipeOrKeyConfirmationPreventsNewAction() = runTest {
        for (response in listOf(
            """{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration_ms":400,"reason":"fixed"}""",
            """{"action":"key","key":"BACK","reason":"fixed"}""",
        )) {
            val root = Root()
            loop(root, listOf(response)).run(AgentLoopRequest(config, 1)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    if (it.action !is RootPilotAction.OpenApp) root.identity = "replacement"
                    it.approval.approve()
                }
            }
            assertEquals(listOf(ExecutableRootAction.OpenApp(app)), root.actions)
        }
    }

    @Test fun homeIsRejectedWithoutNewConfirmationOrExecution() = runTest {
        val root = Root()
        var approvals = 0
        loop(root, listOf("""{"action":"key","key":"HOME","reason":"fixed"}"""))
            .run(AgentLoopRequest(config, 1)) {
                if (it is AgentLoopEvent.AwaitingConfirmation) { approvals++; it.approval.approve() }
            }
        assertEquals(1, approvals)
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

    @Test fun virtualTreeReceiptReachesNextModelRequestWithoutAnExtraActionOrApproval() = runTest {
        val root = Root()
        val histories = mutableListOf<List<ToolChatTurn>>()
        val traceLines = mutableListOf<String>()
        var approvals = 0
        val events = treeLoop(root, histories).let { loop ->
            val collected = mutableListOf<AgentLoopEvent>()
            loop.run(AgentLoopRequest(config, 1), RunTrace(sink = { traceLines += it })) {
                collected += it
                if (it is AgentLoopEvent.AwaitingConfirmation) { approvals++; it.approval.approve() }
            }
            collected
        }
        assertEquals(1, root.queries)
        assertEquals(2, histories.size)
        val receipt = Json.parseToJsonElement(histories[1].single { it.role == "tool" }.content).jsonObject
        assertEquals(JsonPrimitive("available"), receipt["status"])
        assertEquals(JsonPrimitive(9), receipt.getValue("data").jsonObject["display_id"])
        assertEquals(1, approvals)
        assertEquals(listOf(ExecutableRootAction.OpenApp(app)), root.actions)
        assertTrue(events.last() is AgentLoopEvent.Completed)
        assertFalse(traceLines.joinToString().contains("private-tree-marker"))
    }

    @Test fun displaySessionOrWindowChangeDuringVirtualQueryNeverReturnsDataToModel() = runTest {
        val changes = listOf<(Root) -> Unit>({ it.identity = "replacement" }, { it.display = 10 }, { it.window = "other" })
        changes.forEach { change ->
            val root = Root().also { it.queryMutation = { change(it) } }
            val histories = mutableListOf<List<ToolChatTurn>>()
            val trace = RunTrace()
            treeLoop(root, histories).run(AgentLoopRequest(config, 1), trace) {
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
            assertEquals(1, root.queries)
            assertEquals(1, histories.size)
            assertEquals(TraceReason.SCREEN_CONTEXT_CHANGED, trace.reason)
            assertEquals(listOf(ExecutableRootAction.OpenApp(app)), root.actions)
        }
    }

    private fun treeLoop(root: Root, histories: MutableList<List<ToolChatTurn>>) = AgentLoop(
        object : ScreenshotProvider {
            override suspend fun capture() = ScreenshotCaptureResult.Success(ScreenshotFrame(byteArrayOf(1),
                1080, 1920, "fixture"))
        },
        object : DeepSeekClient {
            override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult = error("native path required")
            override suspend fun requestDecision(request: DeepSeekVisionRequest, toolHistory: List<ToolChatTurn>, allowTools: Boolean,
                onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
                histories += toolHistory.toList()
                return if (histories.size == 1) ToolChatResult.Success("", "", listOf(ChatToolCall("tree", "get_ui_tree", "{}")))
                else ToolChatResult.Success("""{"action":"finish","success":true,"message":"fixed"}""", "", emptyList())
            }
        }, root, appCatalog = AppCatalog { listOf(app) },
    )
}
