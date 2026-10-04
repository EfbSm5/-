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
import com.example.agent.rootpilot.information.AndroidUiTreeProvider
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.SuRootExecutor
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, fixed arithmetic case on the separately installed system calculator. */
@RunWith(AndroidJUnit4::class)
class CalculatorLiveAcceptanceInstrumentedTest {
    @Test fun inspectCalculatorWithoutActionsOrNetwork() = runBlocking {
        assumeTrue(arguments().getString("calculatorPreflight") == "true")
        assertTrue("accessibility_connection_required", RootPilotAccessibilityService.connected.value)
        val tree = calculatorTree()
        val controls = tree.nodes.filter { it["resource_id"]?.jsonPrimitive?.contentOrNull != null }.map { node ->
            buildJsonObject {
                put("id", node["resource_id"]!!)
                put("label", labels(node).firstOrNull { it in SAFE_LABELS } ?: "other")
                put("clickable", node["clickable"]!!)
                put("bounds", node["bounds"]!!)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("calculatorControls", JsonArray(controls).toString())
            putBoolean("calculatorTreeTruncated", tree.truncated)
        })
    }

    @Test fun realServiceOpensCalculatorAndReadsProduct() = runBlocking {
        assumeTrue(arguments().getString("liveCalculatorAcceptance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = FixtureClient(context).also { it.verifyIdentity() }
        assertFalse("idle_service_required", RootPilotService.uiState.value.running)
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertTrue("overlay_permission_required", Settings.canDrawOverlays(context))
        assertTrue("accessibility_connection_required", RootPilotAccessibilityService.connected.value)
        val ime = AndroidImeEnvironment(context)
        val originalIme = ime.currentId()
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        val saved = try { RootPilotApiConfigStore.create(context).read() }
        catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertEquals("deepseek_endpoint_required", "https://api.deepseek.com", saved!!.baseUrl.trimEnd('/'))
        val originalConfig = RootPilotService.uiState.value.config
        val selectionFile = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        // Preserve exact bytes, including an absent or noncanonical prior selection.
        val originalSelection = if (selectionFile.exists()) selectionFile.readBytes() else null
        var installedSelection: ByteArray? = null
        val history = RootPilotService.historyState(context)
        val previousIds = history.value.records.map { it.id }.toSet()
        var commandRequestedAt = Long.MAX_VALUE
        fun record() = history.value.records.firstOrNull {
            it.id !in previousIds && it.startedAtEpochMs >= commandRequestedAt
        }
        var started = false
        var approvedAction: RootPilotAction? = null
        var approvedStep = -1
        var opened = false
        var keyIndex = 0
        var failed: Throwable? = null
        try {
            AppLaunchAllowlistStore.create(context).save(setOf(CALCULATOR))
            installedSelection = selectionFile.readBytes()
            fixture.launch()
            assertTrue("fixture_not_empty", fixture.awaitReady().getBoolean("empty"))
            val initial = SuRootExecutor().observeScreen()
            assertEquals("fixture_foreground_required", FIXTURE, initial.foregroundPackage)
            assertEquals("fixture_focus_required", FIXTURE, initial.focusedPackage)
            RootPilotService.updateApiConfig(saved)
            commandRequestedAt = System.currentTimeMillis()
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, saved.applyTo(RootPilotConfig(
                task = "当前是专用测试页。请先打开系统计算器，然后只点击数字和运算符计算 123×45，依次按 1、2、3、×、4、5、=。不要清除历史，不操作其他功能，不输入文本，不切换到其他应用。打开计算器后先用 get_ui_tree 观察当前页面，初始算式应为 0；若不是 0 请报告失败并停止。按等号后再次用 get_ui_tree 读取最终结果，然后报告读到的结果并成功结束。",
                manualConfirmation = true, allowScreenUpload = true,
            )))
            started = true
            withTimeout(5_000) { while (record() == null) delay(25) }
            withTimeout(180_000) {
                while (true) {
                    val state = RootPilotService.uiState.value
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION &&
                        (state.pendingAction !== approvedAction || state.step != approvedStep)) {
                        val action = state.pendingAction
                        when (action) {
                            is RootPilotAction.OpenApp -> {
                                assertFalse("second_launch_not_allowed", opened)
                                assertEquals("only_calculator_launch_allowed", CALCULATOR, action.packageName)
                                val screen = SuRootExecutor().observeScreen()
                                assertEquals(FIXTURE, screen.foregroundPackage)
                                assertEquals(FIXTURE, screen.focusedPackage)
                            }
                            is RootPilotAction.Tap -> {
                                assertTrue("calculator_must_be_opened_first", opened)
                                assertTrue("extra_tap_not_allowed", keyIndex < KEYS.size)
                                val tree = calculatorTree()
                                val expectedText = EXPRESSIONS[keyIndex]
                                val expressions = tree.nodes.filter { resourceId(it) == "$CALCULATOR:id/expression" }
                                assertEquals("single_current_expression_required", 1, expressions.size)
                                assertTrue("calculator_expression_mismatch", labels(expressions.single()).any { normalize(it) == expectedText })
                                val frame = state.frame ?: throw AssertionError("source_frame_required")
                                val x = (action.x * (frame.physicalWidth - 1) / 1000).coerceIn(0, frame.physicalWidth - 1)
                                val y = (action.y * (frame.physicalHeight - 1) / 1000).coerceIn(0, frame.physicalHeight - 1)
                                val targets = tree.nodes.filter { node ->
                                    node["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                                        node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                                        node["text_redacted"]?.jsonPrimitive?.booleanOrNull == false &&
                                        resourceId(node) == "$CALCULATOR:id/${KEY_IDS[keyIndex]}" &&
                                        labels(node).any { it in KEYS[keyIndex] } && contains(node, x, y)
                                }
                                assertEquals("tap_must_hit_expected_visible_key", 1, targets.size)
                            }
                            else -> throw AssertionError("action_outside_fixed_calculator_scope")
                        }
                        if (confirmOverlay(state)) {
                            approvedAction = action
                            approvedStep = state.step
                            if (action is RootPilotAction.OpenApp) opened = true else keyIndex++
                            report("approved", keyIndex)
                        }
                    }
                    if (!state.running && state.status in TERMINAL) break
                    delay(50)
                }
            }
            val state = RootPilotService.uiState.value
            assertEquals("service_terminal_state_mismatch", RootPilotStatus.COMPLETED, state.status)
            assertTrue("calculator_open_required", opened)
            assertEquals("all_arithmetic_keys_required", KEYS.size, keyIndex)
            assertNull("pending_action_remaining", state.pendingAction)
            val finalTree = calculatorTree()
            val displayed = finalTree.nodes.filter { node ->
                var parent = node
                var inDisplay = false
                repeat(finalTree.nodes.size) {
                    if (resourceId(parent) == "$CALCULATOR:id/listView") inDisplay = true
                    val parentId = parent["parent_id"]?.jsonPrimitive?.contentOrNull
                    parent = finalTree.nodes.firstOrNull { it["node_id"]?.jsonPrimitive?.contentOrNull == parentId }
                        ?: return@repeat
                }
                inDisplay && node["text_redacted"]?.jsonPrimitive?.booleanOrNull == false
            }
            assertTrue("calculator_result_mismatch", displayed.any { node -> labels(node).any { normalize(it) == "5535" } })
            assertTrue("model_must_report_observed_product", state.modelReportedResult && normalize(state.errorMessage.orEmpty()).contains("5535"))
            withTimeout(5_000) { while (record()?.status == RunHistoryStatus.RUNNING) delay(50) }
            val events = record()?.events ?: throw AssertionError("history_run_missing")
            assertTrue("run_end_required", events.any { it.event == TraceEvent.RUN_END })
            val executions = events.filter { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS }
            assertEquals("exact_execution_count_required", KEYS.size + 1, executions.size)
            assertEquals(TraceActionType.OPEN_APP, executions.first().actionType)
            assertTrue(executions.drop(1).all { it.actionType == TraceActionType.TAP })
            assertTrue("post_equals_model_tree_receipt_required", events.indices.any { index ->
                val start = events[index]
                if (start.step <= approvedStep || start.event != TraceEvent.READ_UI_TREE || start.status != TraceStatus.STARTED) false
                else {
                    val receipt = events.drop(index + 1).firstOrNull { it.stage == TraceStage.INFORMATION }
                    receipt?.step == start.step && receipt.event == TraceEvent.RESULT && receipt.status == TraceStatus.SUCCESS &&
                        events.drop(index + 1).any { it.step == start.step && it.stage == TraceStage.MODEL && it.event == TraceEvent.START }
                }
            })
            report("completed_result_5535", keyIndex)
        } catch (error: Throwable) {
            failed = error
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                fun retain(error: Throwable) {
                    if (failed != null) failed.addSuppressed(error)
                    else if (cleanupFailure == null) cleanupFailure = error
                    else cleanupFailure!!.addSuppressed(error)
                }
                var settled = !started
                try {
                    if (started) {
                        val ended = record()?.events?.any { it.event == TraceEvent.RUN_END } == true
                        val stopSent = !ended || RootPilotService.uiState.value.running
                        if (stopSent) RootPilotService.send(context, RootPilotService.ACTION_STOP)
                        withTimeout(15_000) {
                            while (true) {
                                val events = record()?.events.orEmpty()
                                val state = RootPilotService.uiState.value
                                val stopHandled = !stopSent || state.status == RootPilotStatus.STOPPED ||
                                    events.any { it.event == TraceEvent.STOP_REQUESTED }
                                if (events.any { it.event == TraceEvent.RUN_END } && !state.running && stopHandled) break
                                delay(50)
                            }
                        }
                        settled = true
                    }
                } catch (cleanup: Throwable) { retain(cleanup) }
                // Without a run-end/stop receipt, keep the known screen and selection in place.
                if (settled) {
                    try {
                        assertEquals("original_ime_not_preserved", originalIme, ime.currentId())
                        assertNull("ime_recovery_remaining", FileImeRestoreStore(context).read())
                        assertFalse("task_snapshot_remaining", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
                    } catch (cleanup: Throwable) { retain(cleanup) }
                    try {
                        RootPilotService.updateConfig(originalConfig)
                        RootPilotService.updateApiConfig(saved)
                        installedSelection?.let { expected ->
                            assertTrue("selection_changed_externally_not_overwritten", selectionFile.exists() && selectionFile.readBytes().contentEquals(expected))
                            if (originalSelection == null) assertTrue("temporary_selection_cleanup_failed", selectionFile.delete())
                            else {
                                val temporary = Files.createTempFile(selectionFile.parentFile!!.toPath(), "calculator-restore-", ".tmp")
                                try {
                                    Files.write(temporary, originalSelection)
                                    Files.move(temporary, selectionFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                } finally { Files.deleteIfExists(temporary) }
                                assertTrue("selection_not_restored", selectionFile.readBytes().contentEquals(originalSelection))
                            }
                        }
                    } catch (cleanup: Throwable) { retain(cleanup) }
                    try { fixture.call("finish") } catch (cleanup: Throwable) { retain(cleanup) }
                } else report("cleanup_unconfirmed_fixture_retained", keyIndex)
                if (failed == null) cleanupFailure?.let { throw it }
            }
        }
    }

    private fun confirmOverlay(expected: RootPilotUiState): Boolean {
        var clicked = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = RootPilotService.uiState.value
            if (current.status != RootPilotStatus.WAITING_CONFIRMATION || current.pendingAction !== expected.pendingAction || current.step != expected.step) return@runOnMainSync
            val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay" && it.isAttachedToWindow
            } ?: return@runOnMainSync
            assertTrue("secure_overlay_required", (panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val button = descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "确认" }
            if (button.isEnabled && button.isShown) clicked = button.performClick()
        }
        return clicked
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun contains(node: JsonObject, x: Int, y: Int): Boolean {
        val bounds = node.getValue("bounds").jsonObject
        return x >= bounds.getValue("left").jsonPrimitive.int && x < bounds.getValue("right").jsonPrimitive.int &&
            y >= bounds.getValue("top").jsonPrimitive.int && y < bounds.getValue("bottom").jsonPrimitive.int
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() || it == ',' }
    private fun report(stage: String, keys: Int) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
        putString("calculatorStage", stage)
        putInt("approvedArithmeticKeys", keys)
        putString("confirmationSource", "script_local_view_click")
    })

    private data class CalculatorTree(val nodes: List<JsonObject>, val truncated: Boolean)
    private suspend fun calculatorTree(): CalculatorTree {
        val observation = SuRootExecutor().observeScreen()
        assertEquals("calculator_foreground_required", CALCULATOR, observation.foregroundPackage)
        assertEquals("calculator_focus_required", CALCULATOR, observation.focusedPackage)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidUiTreeProvider(context.packageName).query(observation)
        assertNull("calculator_tree_unavailable", result.unavailable)
        val data = result.data ?: throw AssertionError("calculator_tree_missing")
        assertEquals(CALCULATOR, data["package_name"]?.jsonPrimitive?.contentOrNull)
        return CalculatorTree(data.getValue("nodes").jsonArray.map { it.jsonObject }, result.truncated)
    }

    private fun labels(node: JsonObject) = listOf("text", "content_description")
        .mapNotNull { node[it]?.jsonPrimitive?.contentOrNull }
    private fun resourceId(node: JsonObject) = node["resource_id"]?.jsonPrimitive?.contentOrNull
    private fun arguments() = InstrumentationRegistry.getArguments()
    private companion object {
        const val CALCULATOR = "com.miui.calculator"
        const val FIXTURE = "com.example.rootpilot.fixture"
        val KEYS = listOf(setOf("1"), setOf("2"), setOf("3"), setOf("×", "乘", "乘号"), setOf("4"), setOf("5"), setOf("=", "等于"))
        val KEY_IDS = listOf("digit_1", "digit_2", "digit_3", "op_mul", "digit_4", "digit_5", "btn_equal_s")
        val EXPRESSIONS = listOf("0", "1", "12", "123", "123×", "123×4", "123×45")
        val TERMINAL = setOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
        val SAFE_LABELS = setOf("0", "1", "2", "3", "4", "5", "×", "乘", "乘号", "=", "等于", "AC", "C", "清除", "全部清除")
    }
}
