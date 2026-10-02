package com.example.agent.rootpilot.information

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotApiConfigStore
import com.example.agent.rootpilot.RootPilotRunStore
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.deepseek.*
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.loop.*
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.*
import com.example.agent.rootpilot.screen.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in, signed fixed-content fixture only; no UiAutomation connection or positional actions. */
@RunWith(AndroidJUnit4::class)
class DeviceInformationInstrumentedTest {
    @Test fun readsAllThreeToolsFromFocusedFixture() = runBlocking {
        assumeTrue(arguments().getString("deviceInformationAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = FixtureClient(context)
        fixture.verifyIdentity()
        val actual = SuRootExecutor(uiTreeProvider = AndroidUiTreeProvider(context.packageName))
        try {
            assertTrue("manual_accessibility_enable_required", RootPilotAccessibilityService.connected.value)
            fixture.launch()
            assertTrue("fixture_not_ready", fixture.awaitReady().getBoolean("ready"))
            val before = actual.observeScreen()
            assertFixture(before)
            for (tool in DeviceInfoTool.entries) {
                val result = actual.queryDeviceInfo(tool, before)
                val after = actual.observeScreen()
                assertFixture(after)
                assertEquals("window_changed", before.focusedWindowId, after.focusedWindowId)
                assertNull("${tool.wireName}_unavailable:${result.unavailable}", result.unavailable)
                assertNotNull(result.data)
                assertTrue(result.finishedAtMillis >= result.startedAtMillis)
                when (tool) {
                    DeviceInfoTool.SCREEN_CONTEXT -> assertEquals(JsonPrimitive(FIXTURE_PACKAGE), result.data!!["foreground_package"])
                    DeviceInfoTool.ACTIVITY_STACK -> assertEquals(JsonPrimitive(FIXTURE_ACTIVITY), result.data!!["activities"]!!.jsonArray[0].jsonObject["activity"])
                    DeviceInfoTool.UI_TREE -> {
                        assertEquals(JsonPrimitive(FIXTURE_PACKAGE), result.data!!["package_name"])
                        val nodes = result.data["nodes"]!!.jsonArray
                        assertTrue("editor_semantics_missing", nodes.any { it.jsonObject["editable"] == JsonPrimitive(true) })
                        assertTrue("fixture_title_missing", nodes.any { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.contains("RootPilot 专用执行测试页") == true })
                        assertFalse("fixture_unexpected_truncation", result.truncated)
                    }
                }
                report(tool.wireName, 0, 0)
            }
            val protected = AndroidUiTreeProvider(context.packageName).query(before.copy(
                foregroundPackage = context.packageName, focusedPackage = context.packageName))
            assertEquals(DeviceInfoUnavailable.PROTECTED_APP, protected.unavailable)
        } finally {
            withContext(NonCancellable) { actual.cancel(); fixture.call("finish") }
        }
    }

    @Test fun disabledOptionalServiceReturnsUnavailableOnFixture() = runBlocking {
        assumeTrue(arguments().getString("deviceInformationDisabledAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = FixtureClient(context)
        fixture.verifyIdentity()
        val actual = SuRootExecutor()
        try {
            assertFalse("service_must_be_manually_disabled", RootPilotAccessibilityService.connected.value)
            fixture.launch()
            assertTrue("fixture_not_ready", fixture.awaitReady().getBoolean("ready"))
            val expected = actual.observeScreen()
            assertFixture(expected)
            val result = AndroidUiTreeProvider(context.packageName).query(expected)
            assertEquals(DeviceInfoUnavailable.NOT_ENABLED, result.unavailable)
            assertNull(result.data)
            report("not_enabled", 0, 0)
        } finally {
            withContext(NonCancellable) { actual.cancel(); fixture.call("finish") }
        }
    }

    @Test fun realModelConsumesToolsThenTypesAndObservesFixture() = runBlocking {
        assumeTrue(arguments().getString("liveDeviceInformationExecution") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = FixtureClient(context)
        fixture.verifyIdentity()
        val ime = AndroidImeEnvironment(context)
        val originalIme = ime.currentId()
        assertTrue("manual_accessibility_enable_required", RootPilotAccessibilityService.connected.value)
        assertTrue("input_method_required", ime.isEnabled(ime.ownId))
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertFalse("idle_service_required", RootPilotService.uiState.value.running)
        val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertEquals("deepseek_endpoint_required", "https://api.deepseek.com", saved!!.baseUrl.trimEnd('/'))
        val input = AndroidImeEnvironment.createInput(context)
        val actual = SuRootExecutor(typeText = input::type, uiTreeProvider = AndroidUiTreeProvider(context.packageName))
        val queryCounts = mutableMapOf<DeviceInfoTool, Int>()
        val consumed = mutableSetOf<DeviceInfoTool>()
        var observedTypedTree = false
        var typed = 0
        var captures = 0
        var complete = false
        var failure: Throwable? = null
        val trace = RunTrace(observer = { event ->
            if (event.status == com.example.agent.rootpilot.log.TraceStatus.FAILED ||
                event.event == com.example.agent.rootpilot.log.TraceEvent.RUN_END) {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("traceStage", event.stage.name)
                    putString("reasonCode", event.reason.name)
                    putString("traceOutcome", event.status.name)
                })
            }
        })
        val transport = HttpDeepSeekClient(requestTimeoutMillis = 60_000)
        val client = object : DeepSeekClient {
            override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult = error("native_protocol_required")
            override suspend fun requestDecision(request: DeepSeekVisionRequest, toolHistory: List<ToolChatTurn>,
                allowTools: Boolean, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
                toolHistory.filter { it.role == "tool" }.forEach { turn ->
                    val receipt = Json.parseToJsonElement(turn.content).jsonObject
                    val tool = DeviceInfoTool.fromWireName(receipt["tool"]!!.jsonPrimitive.content)!!
                    assertEquals("tool_receipt_unavailable", JsonPrimitive("available"), receipt["status"])
                    consumed += tool
                    if (typed == 1 && tool == DeviceInfoTool.UI_TREE) {
                        val nodes = receipt["data"]!!.jsonObject["nodes"]!!.jsonArray
                        if (nodes.any { it.jsonObject["text"] == JsonPrimitive(EXPECTED) }) observedTypedTree = true
                    }
                }
                var reasoningChars = 0
                var contentChars = 0
                return transport.requestDecision(request, toolHistory, allowTools) { snapshot ->
                    reasoningChars = snapshot.reasoning.length
                    contentChars = snapshot.content.length
                    onUpdate(snapshot)
                }.also { result ->
                    if (result is ToolChatResult.Failure) {
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                            putString("modelFailureCategory", result.diagnostic.category.name)
                            result.diagnostic.httpStatus?.let { putInt("modelHttpStatus", it) }
                            result.diagnostic.protocolReason?.let { putString("modelProtocolReason", it.name) }
                            putInt("modelStep", request.step)
                            putInt("toolHistoryTurns", toolHistory.size)
                            putInt("reasoningChars", reasoningChars)
                            putInt("contentChars", contentChars)
                        })
                    }
                }
            }
        }
        try {
            fixture.launch()
            assertTrue("fixture_not_empty", fixture.awaitReady().getBoolean("empty"))
            val screenshots = object : ScreenshotProvider {
                override suspend fun capture(): ScreenshotCaptureResult {
                    check(++captures <= 3) { "capture_budget_exceeded" }
                    return ScreenshotCaptureResult.Success(fixture.capture())
                }
            }
            val guarded = object : RootExecutor by actual {
                override suspend fun observeScreen() = actual.observeScreen().also(::assertFixture)
                override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult {
                    assertFixture(expected)
                    queryCounts[tool] = (queryCounts[tool] ?: 0) + 1
                    return actual.queryDeviceInfo(tool, expected)
                }
                override suspend fun captureScreen() = error("physical_capture_forbidden")
                override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = error("unconfirmed_action_forbidden")
                override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult {
                    if (action !is ExecutableRootAction.Type || action.text != EXPECTED || typed != 0 ||
                        consumed.size != DeviceInfoTool.entries.size) return RootExecutionResult.Failure("action_outside_fixture_scope")
                    return actual.executeConfirmed(action) { target ->
                        val state = fixture.call("state")
                        target == FIXTURE_PACKAGE && state.getBoolean("ready") && state.getBoolean("empty") && confirm(target)
                    }.also { if (it is RootExecutionResult.Success) typed++ }
                }
            }
            val config = saved.applyTo(RootPilotConfig(
                task = "当前是专用测试页，第一个输入框已聚焦。请先依次调用 get_screen_context、get_activity_stack、get_ui_tree 三个只读工具（每次一个空参数调用），读完后输入“$EXPECTED”，不要重复输入。输入后再次调用 get_ui_tree 确认该文本，再报告成功结束。不点击、不切换应用、不发送、不保存。截图仅是测试页 View，所有坐标动作禁止。",
                manualConfirmation = true, allowScreenUpload = true,
            ))
            withTimeout(180_000) {
                AgentLoop(screenshots, client, guarded).run(AgentLoopRequest(config, maxSteps = 3), trace) { event ->
                    when (event) {
                        is AgentLoopEvent.QueryingInformation -> report(event.tool.wireName, typed, captures)
                        is AgentLoopEvent.RequestingModel -> report("requesting_model", typed, captures)
                        is AgentLoopEvent.AwaitingConfirmation -> {
                            if ((event.action as? RootPilotAction.Type)?.text == EXPECTED && typed == 0 && consumed.size == 3) event.approval.approve()
                            else event.approval.reject()
                        }
                        is AgentLoopEvent.Completed -> { complete = true; report("completed", typed, captures) }
                        is AgentLoopEvent.Failed -> report("failed", typed, captures)
                        else -> Unit
                    }
                }
            }
            assertTrue("model_did_not_complete", complete)
            assertEquals("exactly_one_input_required", 1, typed)
            assertEquals(DeviceInfoTool.entries.toSet(), consumed)
            assertTrue("post_input_ui_tree_receipt_required", observedTypedTree && (queryCounts[DeviceInfoTool.UI_TREE] ?: 0) >= 2)
            assertTrue("followup_frame_required", captures >= 2)
            assertTrue("fixture_text_mismatch", fixture.call("state").getBoolean("matches"))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                actual.cancel()
                try {
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putBoolean("typedTreeObserved", observedTypedTree)
                        putBoolean("fixtureTextMatches", fixture.call("state").getBoolean("matches"))
                    })
                    assertEquals("original_ime_not_restored", originalIme, ime.currentId())
                    assertNull("ime_recovery_remaining", FileImeRestoreStore(context).read())
                    assertFalse("unexpected_task_snapshot", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
                } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
                finally { fixture.call("finish") }
            }
        }
    }

    private fun arguments() = InstrumentationRegistry.getArguments()
    private fun assertFixture(observation: ScreenObservation) {
        assertEquals("fixture_foreground_required", FIXTURE_PACKAGE, observation.foregroundPackage)
        assertEquals("fixture_activity_required", FIXTURE_ACTIVITY, observation.foregroundActivity)
        assertEquals("fixture_focus_required", FIXTURE_PACKAGE, observation.focusedPackage)
    }
    private fun report(stage: String, typed: Int, captures: Int) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("informationStage", stage); putInt("typedActions", typed); putInt("captures", captures) })

    private companion object {
        const val FIXTURE_PACKAGE = "com.example.rootpilot.fixture"
        const val FIXTURE_ACTIVITY = "$FIXTURE_PACKAGE.ExecutionFixtureActivity"
        const val EXPECTED = "执行模式验收通过"
    }
}
