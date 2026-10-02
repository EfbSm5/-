package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.deepseek.*
import com.example.agent.rootpilot.information.*
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.*
import com.example.agent.rootpilot.screen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentLoopInformationTest {
    private val initial = ScreenObservation("com.test.app", "com.test.app.Main", "com.test.app", "abcd", false, 10)
    private val tap = ToolChatResult.Success("""{"action":"tap","x":500,"y":500,"reason":"fixture"}""", "", emptyList())
    private val finish = ToolChatResult.Success("""{"action":"finish","success":true,"message":"done"}""", "", emptyList())
    private fun query(name: String = "get_ui_tree", args: String = "{}") = ToolChatResult.Success("", "private-reasoning",
        listOf(ChatToolCall("call-1", name, args)))

    @Test fun typedModelFailureReachesResultAndRunEndWithoutToolData() = runTest {
        val diagnostic = ModelFailure(ModelFailureCategory.HTTP, 429)
        val fixture = Fixture(listOf(query(), ToolChatResult.Failure("fixed failure", diagnostic)))
        fixture.run()
        assertEquals(TraceReason.MODEL_FAILED, fixture.trace.reason)
        assertEquals(0, fixture.executions)
        val failed = fixture.traceEvents.filter { it.reason == TraceReason.MODEL_FAILED }
        assertEquals(listOf(TraceEvent.RESULT, TraceEvent.RUN_END), failed.map { it.event })
        assertTrue(failed.all { it.modelFailure == diagnostic })
        assertFalse(fixture.traceLines.joinToString().contains("private-content"))
        assertFalse(fixture.traceLines.joinToString().contains("private-reasoning"))
    }

    @Test fun nativeRoundTripKeepsReasoningAndResultIdentityAndDoesNotConsumeActionStep() = runTest {
        val fixture = Fixture(listOf(query(), tap))
        fixture.run()
        assertEquals(1, fixture.queries)
        assertEquals(1, fixture.executions)
        assertEquals(1, fixture.captures)
        assertEquals(listOf(1, 1), fixture.remaining)
        assertEquals(listOf("observe", "capture", "observe", "model", "hide-query", "observe", "query", "observe",
            "model", "confirm", "hide-action", "observe", "execute"), fixture.order)
        assertTrue(fixture.histories[0].isEmpty())
        val history = fixture.histories[1]
        assertEquals("private-reasoning", history[0].reasoningContent)
        assertEquals("call-1", history[1].toolCallId)
        assertEquals(JsonPrimitive("available"), Json.parseToJsonElement(history[1].content).jsonObject["status"])
        assertTrue(fixture.events.last() is AgentLoopEvent.Completed)
    }

    @Test fun queryCannotBypassActionConfirmation() = runTest {
        val fixture = Fixture(listOf(query(), tap))
        fixture.run(approve = false)
        assertEquals(1, fixture.queries)
        assertEquals(0, fixture.executions)
        assertTrue(fixture.events.last() is AgentLoopEvent.Stopped)
    }

    @Test fun unavailableReceiptIsReturnedWithoutPretendingEmptyTree() = runTest {
        val fixture = Fixture(listOf(query(), finish), unavailable = true)
        fixture.run()
        val receipt = Json.parseToJsonElement(fixture.histories[1][1].content).jsonObject
        assertEquals(JsonPrimitive("unavailable"), receipt["status"])
        assertEquals(JsonPrimitive("not_enabled"), receipt["reason"])
        assertEquals(JsonNull, receipt["data"])
        assertTrue(fixture.events.last() is AgentLoopEvent.Completed)
    }

    @Test fun unknownMutatingExtraArgumentMalformedAndParallelCallsAreRejected() = runTest {
        val invalid = listOf(query("shell"), query("click_node"), query(args = "{\"node_id\":\"n0\"}"),
            query(args = "[]"), query(args = "{"), query().copy(toolCalls = query().toolCalls + ChatToolCall("call-2", "get_screen_context", "{}")))
        invalid.forEach { response ->
            val fixture = Fixture(listOf(response))
            fixture.run()
            assertEquals(TraceReason.INFORMATION_CALL_INVALID, fixture.trace.reason)
            assertEquals(0, fixture.queries)
            assertEquals(0, fixture.executions)
        }
    }

    @Test fun perStepBudgetDisablesCallsAndRejectsModelThatIgnoresIt() = runTest {
        val fixture = Fixture(List(4) { query() })
        fixture.run()
        assertEquals(3, fixture.queries)
        assertEquals(listOf(true, true, true, false), fixture.allowTools)
        assertEquals(TraceReason.INFORMATION_LIMIT, fixture.trace.reason)
        assertEquals(0, fixture.executions)
    }

    @Test fun finalActionIsAcceptedWhenPerStepBudgetIsExhausted() = runTest {
        val fixture = Fixture(List(3) { query() } + tap)
        fixture.run()
        assertEquals(3, fixture.queries)
        assertEquals(false, fixture.allowTools.last())
        assertEquals(1, fixture.executions)
    }

    @Test fun totalBudgetPersistsButToolResultsDoNotCrossPlanningSteps() = runTest {
        val responses = (0..3).flatMap { step -> List(3) { query() } + ToolChatResult.Success(
            """{"action":"wait","duration_ms":${300 + step},"reason":"fixture"}""", "", emptyList()) } + query()
        val fixture = Fixture(responses)
        fixture.run(steps = 5, single = false)
        assertEquals(12, fixture.queries)
        assertEquals(4, fixture.executions)
        assertEquals(false, fixture.allowTools.last())
        assertEquals(TraceReason.INFORMATION_LIMIT, fixture.trace.reason)
        assertTrue(listOf(0, 4, 8, 12, 16).all { fixture.histories[it].isEmpty() })
    }

    @Test fun windowChangeBeforeQueryPreventsCollection() = runTest {
        val fixture = Fixture(listOf(query()), samples = listOf(initial, initial, initial.copy(focusedWindowId = "other")))
        fixture.run()
        assertEquals(0, fixture.queries)
        assertEquals(TraceReason.SCREEN_CONTEXT_CHANGED, fixture.trace.reason)
    }

    @Test fun windowOrKeyboardChangeDuringQueryPreventsReturningReceipt() = runTest {
        listOf(initial.copy(focusedWindowId = "other"), initial.copy(focusedPackage = null), initial.copy(keyboardVisible = true)).forEach { changed ->
            val fixture = Fixture(listOf(query()), samples = listOf(initial, initial, initial, changed))
            fixture.run()
            assertEquals(1, fixture.queries)
            assertEquals(1, fixture.histories.size)
            assertEquals(TraceReason.SCREEN_CONTEXT_CHANGED, fixture.trace.reason)
        }
    }

    @Test fun oversizedReceiptNeverReturnsToModel() = runTest {
        val fixture = Fixture(listOf(query()), oversized = true)
        fixture.run()
        assertEquals(1, fixture.histories.size)
        assertEquals(TraceReason.INFORMATION_LIMIT, fixture.trace.reason)
    }

    @Test fun cancellationInQueryPropagatesWithoutAnotherModelRequestOrAction() = runTest {
        val fixture = Fixture(listOf(query()), cancelQuery = true)
        try { fixture.run(); fail("cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(TraceStatus.CANCELLED, fixture.trace.outcome)
        assertEquals(1, fixture.histories.size)
        assertEquals(0, fixture.executions)
    }

    @Test fun queryContentNeverEntersFixedFieldTrace() = runTest {
        val fixture = Fixture(listOf(query(), finish))
        fixture.run()
        assertFalse(fixture.traceLines.joinToString().contains("private-content"))
        assertFalse(fixture.traceLines.joinToString().contains("private-reasoning"))
        assertTrue(fixture.traceEvents.any { it.event == TraceEvent.READ_UI_TREE })
    }

    @Test fun noUploadConsentMeansNoScreenshotQueryOrModelRequest() = runTest {
        val fixture = Fixture(listOf(query()))
        fixture.run(consent = false)
        assertTrue(fixture.order.isEmpty())
    }

    private inner class Fixture(
        private val responses: List<ToolChatResult>,
        private val samples: List<ScreenObservation> = listOf(initial),
        private val unavailable: Boolean = false,
        private val oversized: Boolean = false,
        private val cancelQuery: Boolean = false,
    ) {
        val traceLines = mutableListOf<String>()
        val traceEvents = mutableListOf<RunTraceEvent>()
        val trace = RunTrace(observer = { traceEvents += it }, sink = { traceLines += it })
        val order = mutableListOf<String>()
        val events = mutableListOf<AgentLoopEvent>()
        val histories = mutableListOf<List<ToolChatTurn>>()
        val remaining = mutableListOf<Int>()
        val allowTools = mutableListOf<Boolean>()
        var queries = 0
        var executions = 0
        var captures = 0
        private var sampleIndex = 0
        private val root = object : RootExecutor {
            override suspend fun observeScreen(): ScreenObservation {
                order += "observe"
                return samples[(sampleIndex++).coerceAtMost(samples.lastIndex)]
            }
            override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult {
                order += "query"; queries++
                if (cancelQuery) throw CancellationException("fixture")
                return if (unavailable) DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 10, 20,
                    unavailable = DeviceInfoUnavailable.NOT_ENABLED)
                else DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, 10, 20,
                    buildJsonObject { put("text", if (oversized) "x".repeat(256 * 1024) else "private-content") })
            }
            override suspend fun checkRoot() = RootExecutionResult.Success()
            override suspend fun captureScreen() = RootScreenshotResult.Failure("fixture")
            override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
                order += "execute"; executions++
                return RootExecutionResult.Success()
            }
            override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean) =
                if (confirm(null)) execute(action) else RootExecutionResult.Failure("rejected")
            override fun cancel() = Unit
        }
        suspend fun run(approve: Boolean = true, consent: Boolean = true, steps: Int = 1, single: Boolean = true) {
            val screenshots = object : ScreenshotProvider {
                override suspend fun capture(): ScreenshotCaptureResult {
                    order += "capture"
                    return ScreenshotCaptureResult.Success(ScreenshotFrame(byteArrayOf((++captures).toByte()), 100, 100, "fixture"))
                }
            }
            val model = object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult = error("native path required")
                override suspend fun requestDecision(request: DeepSeekVisionRequest, toolHistory: List<ToolChatTurn>, allowTools: Boolean,
                    onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
                    order += "model"
                    histories += toolHistory.toList()
                    remaining += request.remainingSteps
                    this@Fixture.allowTools += allowTools
                    return responses[histories.lastIndex]
                }
            }
            AgentLoop(screenshots, model, root).run(AgentLoopRequest(RootPilotConfig(allowScreenUpload = consent,
                manualConfirmation = true), steps, single), trace) { event ->
                events += event
                when (event) {
                    is AgentLoopEvent.QueryingInformation -> order += "hide-query"
                    is AgentLoopEvent.Executing -> order += "hide-action"
                    is AgentLoopEvent.AwaitingConfirmation -> {
                        order += "confirm"
                        if (approve) event.approval.approve() else event.approval.reject()
                    }
                    else -> Unit
                }
            }
        }
    }
}
