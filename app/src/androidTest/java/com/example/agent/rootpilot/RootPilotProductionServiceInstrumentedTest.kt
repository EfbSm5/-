package com.example.agent.rootpilot

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.information.AndroidUiTreeProvider
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.SuRootExecutor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Service, root full-screen capture and local overlay listener; fixed signed fixture only. */
@RunWith(AndroidJUnit4::class)
class RootPilotProductionServiceInstrumentedTest {
    @Test fun realServiceModelOverlayInputAndCompletion() = runBlocking {
        assumeTrue(arguments().getString("liveProductionServiceAcceptance") == "true")
        acceptance(stopAtApproval = false)
    }

    @Test fun realServiceStopAtInputConfirmationRestoresIme() = runBlocking {
        assumeTrue(arguments().getString("productionServiceStopAcceptance") == "true")
        acceptance(stopAtApproval = true)
    }

    @Test fun realServiceStopDuringActivityQuery() = runBlocking {
        assumeTrue(arguments().getString("productionQueryStopAcceptance") == "true")
        acceptance(stopAtApproval = false, stopAtQuery = true)
    }

    @Test fun realServiceContinuesAfterManuallyDisabledPageStructure() = runBlocking {
        assumeTrue(arguments().getString("productionServiceDisconnectedAcceptance") == "true")
        acceptance(stopAtApproval = false, disconnected = true)
    }

    private suspend fun acceptance(stopAtApproval: Boolean, stopAtQuery: Boolean = false, disconnected: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = FixtureClient(context)
        fixture.verifyIdentity()
        assertFalse("idle_service_required", RootPilotService.uiState.value.running)
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertTrue("overlay_permission_required", Settings.canDrawOverlays(context))
        assertEquals("manual_accessibility_state_required", !disconnected, RootPilotAccessibilityService.connected.value)
        val ime = AndroidImeEnvironment(context)
        val originalIme = ime.currentId()
        assertTrue("input_method_required", ime.isEnabled(ime.ownId))
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        val saved = try { RootPilotApiConfigStore.create(context).read() }
        catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertTrue("deepseek_endpoint_required", saved!!.baseUrl.trimEnd('/') == "https://api.deepseek.com")
        val originalConfig = RootPilotService.uiState.value.config
        val history = RootPilotService.historyState(context)
        val previousIds = history.value.records.map { it.id }.toSet()
        var commandRequestedAt = Long.MAX_VALUE
        fun currentRecord() = history.value.records.firstOrNull {
            it.id !in previousIds && it.startedAtEpochMs >= commandRequestedAt
        }
        var started = false
        var approved = false
        var approvedAction: RootPilotAction? = null
        var approvedStep = -1
        var queryStopSent = false
        var failed: Throwable? = null
        try {
            fixture.launch()
            assertTrue("fixture_not_empty", fixture.awaitReady().getBoolean("empty"))
            val observed = SuRootExecutor().observeScreen()
            assertEquals("fixture_foreground_required", FIXTURE_PACKAGE, observed.foregroundPackage)
            assertEquals("fixture_focus_required", FIXTURE_PACKAGE, observed.focusedPackage)
            if (disconnected) {
                val unavailable = AndroidUiTreeProvider(context.packageName).query(observed)
                assertEquals(DeviceInfoUnavailable.NOT_ENABLED, unavailable.unavailable)
                assertNull("disabled_page_structure_must_not_return_data", unavailable.data)
            }
            RootPilotService.updateApiConfig(saved)
            val config = saved.applyTo(RootPilotConfig(
                task = if (disconnected) "当前是 RootPilot 专用测试页。页面结构读取服务已由用户关闭。请仅调用一次 get_ui_tree 空参数只读工具，收到不可用回执后报告结束。不要重试，不要执行任何界面动作，不要输入、不点击、不切应用。"
                    else if (stopAtQuery) "当前是 RootPilot 专用测试页。请先调用 get_activity_stack 一个空参数只读工具，然后报告结束；不要执行任何界面动作。"
                    else "当前是 RootPilot 专用测试页，输入框已聚焦。只操作此页。先依次调用 get_screen_context、get_activity_stack、get_ui_tree 三个只读工具，每次一个空参数。然后输入“$EXPECTED”一次，不重复输入。输入后再调用 get_ui_tree 验证该文字并报告成功结束。不点击、不滑动、不启动应用、不按键、不保存、不发送。",
                manualConfirmation = true, allowScreenUpload = true,
            ))
            commandRequestedAt = System.currentTimeMillis()
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, config)
            started = true
            withTimeout(5000) {
                while (currentRecord() == null) delay(25)
            }
            withTimeout(180_000) {
                while (true) {
                    val state = RootPilotService.uiState.value
                    if (stopAtQuery && !queryStopSent && currentRecord()
                            ?.events?.any { it.event == TraceEvent.READ_ACTIVITY_STACK && it.status == TraceStatus.STARTED } == true) {
                        RootPilotService.send(context, RootPilotService.ACTION_STOP)
                        queryStopSent = true
                    }
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION) {
                        assertFalse("unexpected_confirmation_in_query_only_run", stopAtQuery || disconnected)
                        if (approved) {
                            assertTrue("unexpected_second_confirmation", state.pendingAction === approvedAction && state.step == approvedStep)
                            delay(50)
                            continue
                        }
                        assertTrue("action_outside_fixture_scope", (state.pendingAction as? RootPilotAction.Type)?.text == EXPECTED)
                        assertTrue("fixture_changed_before_confirmation", fixture.call("state").getBoolean("empty"))
                        val record = currentRecord()
                        assertNotNull("history_run_missing", record)
                        val queried = record!!.events.filter { it.stage == TraceStage.INFORMATION }.map { it.event }.toSet()
                        assertTrue("three_queries_required_before_confirmation", queried.containsAll(TOOLS))
                        assertEquals("input_target_bound_before_confirmation", ime.ownId, ime.currentId())
                        var clicked = false
                        instrumentation.runOnMainSync {
                            val current = RootPilotService.uiState.value
                            if (current.status != RootPilotStatus.WAITING_CONFIRMATION || current.pendingAction !== state.pendingAction) {
                                return@runOnMainSync
                            }
                            val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                                (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay" && it.isAttachedToWindow
                            }
                            // State is published before the queued main-thread window render.
                            if (panel == null) return@runOnMainSync
                            val params = panel.layoutParams as WindowManager.LayoutParams
                            assertTrue("secure_overlay_required", params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                            val button = descendants(panel).filterIsInstance<Button>().single {
                                it.text.toString() == if (stopAtApproval) "停止" else "确认"
                            }
                            if (!button.isEnabled || !button.isShown) return@runOnMainSync
                            assertTrue("overlay_listener_not_invoked", button.performClick())
                            clicked = true
                        }
                        if (clicked) {
                            approved = true
                            approvedAction = state.pendingAction
                            approvedStep = state.step
                        }
                    }
                    if (!state.running && state.status in TERMINAL) break
                    delay(if (stopAtQuery) 5 else 50)
                }
            }
            val state = RootPilotService.uiState.value
            assertTrue("acceptance_stop_or_confirmation_not_reached", disconnected || if (stopAtQuery) queryStopSent else approved)
            val stopped = stopAtApproval || stopAtQuery
            assertEquals("service_terminal_state_mismatch", if (stopped) RootPilotStatus.STOPPED else RootPilotStatus.COMPLETED, state.status)
            assertNull("pending_action_remaining", state.pendingAction)
            val fixtureState = fixture.call("state")
            assertTrue("fixture_result_mismatch", fixtureState.getBoolean(if (stopped || disconnected) "empty" else "matches"))
            withTimeout(5000) {
                while (currentRecord()?.status == RunHistoryStatus.RUNNING) delay(50)
            }
            val record = currentRecord() ?: throw AssertionError("history_run_missing")
            assertTrue("run_end_required", record.events.any { it.event == TraceEvent.RUN_END })
            assertEquals("execution_count_mismatch", if (stopped || disconnected) 0 else 1,
                record.events.count { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS })
            if (disconnected) {
                assertEquals("exactly_one_page_structure_query_required", 1,
                    record.events.count { it.event == TraceEvent.READ_UI_TREE && it.status == TraceStatus.STARTED })
                val unavailable = record.events.indexOfFirst {
                    it.stage == TraceStage.INFORMATION && it.event == TraceEvent.RESULT && it.reason == TraceReason.INFORMATION_UNAVAILABLE
                }
                assertTrue("unavailable_receipt_required", unavailable >= 0)
                assertTrue("model_followup_after_unavailable_required", record.events.drop(unavailable + 1)
                    .any { it.stage == TraceStage.MODEL && it.event == TraceEvent.START })
                assertFalse("disconnected_service_must_remain_disconnected", RootPilotAccessibilityService.connected.value)
                report("manually_disconnected_service_completed", false)
            } else if (stopAtQuery) {
                val queryStart = record.events.indexOfFirst { it.event == TraceEvent.READ_ACTIVITY_STACK && it.status == TraceStatus.STARTED }
                val stop = record.events.indexOfFirst { it.event == TraceEvent.STOP_REQUESTED }
                assertTrue("activity_query_not_started_before_stop", queryStart >= 0 && stop > queryStart)
                assertFalse("query_finished_before_stop_request", record.events.subList(queryStart + 1, stop)
                    .any { it.stage == TraceStage.INFORMATION && it.event == TraceEvent.RESULT })
                assertFalse("cancelled_query_result_consumed", record.events.drop(stop + 1)
                    .any { it.stage == TraceStage.MODEL && it.event == TraceEvent.START })
            } else if (!stopAtApproval) {
                assertTrue("post_input_query_required", record.events.any { it.step > 0 && it.event == TraceEvent.READ_UI_TREE })
                assertTrue("followup_capture_required", record.events.any { it.step > 0 && it.stage == TraceStage.SCREENSHOT && it.status == TraceStatus.SUCCESS })
            }
            if (!disconnected) report(if (stopAtQuery) "activity_query_stop_pass" else if (stopAtApproval) "confirmation_stop_pass" else "service_completed", approved)
        } catch (error: Throwable) {
            failed = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    try {
                        if (started && (failed != null || RootPilotService.uiState.value.running)) {
                            RootPilotService.send(context, RootPilotService.ACTION_STOP)
                            withTimeout(15_000) { while (RootPilotService.uiState.value.running) delay(50) }
                        }
                        assertEquals("original_ime_not_restored", originalIme, ime.currentId())
                        assertNull("ime_recovery_remaining", FileImeRestoreStore(context).read())
                        assertFalse("task_snapshot_remaining", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
                    } finally {
                        RootPilotService.updateConfig(originalConfig)
                        RootPilotService.updateApiConfig(saved)
                    }
                } catch (cleanup: Throwable) { if (failed != null) failed.addSuppressed(cleanup) else throw cleanup }
                finally { fixture.call("finish") }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun report(stage: String, confirmed: Boolean) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
        putString("productionServiceStage", stage)
        putBoolean("actualOverlayListener", confirmed)
        putString("confirmationSource", if (confirmed) "script_local_view_click"
            else if (stage == "activity_query_stop_pass") "script_service_stop_command" else "none")
    })
    private fun arguments() = InstrumentationRegistry.getArguments()
    private companion object {
        const val EXPECTED = "执行模式验收通过"
        const val FIXTURE_PACKAGE = "com.example.rootpilot.fixture"
        val TOOLS = setOf(TraceEvent.READ_SCREEN_CONTEXT, TraceEvent.READ_ACTIVITY_STACK, TraceEvent.READ_UI_TREE)
        val TERMINAL = setOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
    }
}
