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
import com.example.agent.rootpilot.input.InputConnectionBridge
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

/** Opt-in checks on the public city picker; never selects a city or changes an alarm. */
@RunWith(AndroidJUnit4::class)
class ClockCitySearchAcceptanceInstrumentedTest {
    @Test fun timezoneReportRequiresExactEightHourOffset() {
        for (value in listOf("GMT+8", "UTC+08:00", "中国 GMT+8:00。")) {
            assertTrue("exact_eight_hour_offset_rejected", BEIJING_TIMEZONE.containsMatchIn(value))
        }
        for (value in listOf("UTC+08:30", "GMT+80", "GMT+8.5", "GMT+8:00:30", "UTC+7:00")) {
            assertFalse("different_offset_accepted", BEIJING_TIMEZONE.containsMatchIn(value))
        }
    }

    @Test fun inspectPublicClockPageWithoutActionsOrNetwork() = runBlocking {
        assumeTrue(arguments().getString("clockCityPreflight") == "true")
        assertTrue("accessibility_connection_required", RootPilotAccessibilityService.connected.value)
        val tree = clockTree()
        val pageLabelRecognized = tree.nodes.any {
            labels(it).any { value -> value == "世界时钟" || value == "选择城市" || value == "输入国家或城市名搜索" }
        }
        val controls = tree.nodes.map { node ->
            buildJsonObject {
                for (field in listOf("node_id", "parent_id", "resource_id", "class", "bounds",
                    "clickable", "editable", "enabled", "focused", "selected", "text_redacted")) {
                    node[field]?.let { put(field, it) }
                }
                put("safeLabels", JsonArray(labels(node).filter { it in SAFE_LABELS }.map(::JsonPrimitive)))
                put("textEmpty", node["text"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("clockControls", JsonArray(controls).toString())
            putBoolean("clockTreeTruncated", tree.truncated)
            putString("clockActivity", tree.activity)
            putBoolean("clockPageLabelRecognized", pageLabelRecognized)
        })
    }

    /** Requires the public world-clock tab prepared before bringing RootPilot to the foreground. */
    @Test fun realServiceOpensClockSearchesCityAndReportsResult() = runBlocking {
        assumeTrue(arguments().getString("liveClockCitySearch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = FixtureClient(context).also { it.verifyIdentity() }
        val original = RootPilotService.uiState.value
        assertFalse("idle_service_required", original.running)
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertTrue("overlay_permission_required", Settings.canDrawOverlays(context))
        assertTrue("accessibility_connection_required", RootPilotAccessibilityService.connected.value)
        val ime = AndroidImeEnvironment(context)
        val originalIme = ime.currentId()
        assertNotNull("original_ime_required", originalIme)
        assertTrue("external_ime_required", originalIme != ime.ownId)
        assertTrue("rootpilot_ime_required", ime.isEnabled(ime.ownId))
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        val saved = try { RootPilotApiConfigStore.create(context).read() }
        catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertTrue("deepseek_endpoint_required", saved!!.baseUrl.trimEnd('/') == "https://api.deepseek.com")
        assertTrue("restorable_api_state_required",
            (if (original.apiConfigured) saved else RootPilotApiConfig()).applyTo(original.config) == original.config)
        val apiFile = context.noBackupFilesDir.resolve(RootPilotApiConfigStore.FILE_NAME)
        val originalApiBytes = apiFile.readBytes()
        val selectionFile = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        val originalSelection = if (selectionFile.exists()) selectionFile.readBytes() else null
        var installedSelection: ByteArray? = null
        val history = RootPilotService.historyState(context)
        assertTrue("history_must_be_idle", history.value.records.none { it.status == RunHistoryStatus.RUNNING })
        val previousIds = history.value.records.map { it.id }.toSet()
        var requestedAt = Long.MAX_VALUE
        fun record() = history.value.records.singleOrNull {
            it.id !in previousIds && it.startedAtEpochMs >= requestedAt
        }
        var started = false
        var approvedAction: RootPilotAction? = null
        var approvedStep = -1
        var approved = 0
        var failed: Throwable? = null
        try {
            AppLaunchAllowlistStore.create(context).save(setOf(CLOCK))
            installedSelection = selectionFile.readBytes()
            fixture.launch()
            assertTrue("fixture_not_empty", fixture.awaitReady().getBoolean("empty"))
            val initial = SuRootExecutor().observeScreen()
            assertEquals("fixture_foreground_required", FIXTURE, initial.foregroundPackage)
            assertEquals("fixture_focus_required", FIXTURE, initial.focusedPackage)
            RootPilotService.updateApiConfig(saved)
            requestedAt = System.currentTimeMillis()
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, saved.applyTo(RootPilotConfig(
                task = "当前是专用测试页。请先打开系统时钟，已预先停留在世界时钟页。只打开添加城市的选择页，点击搜索框，输入一次北京。随后从当前搜索结果读取城市名、国家和显示的时区，报告实际读到的内容并成功结束。不要选择或添加任何城市，不要操作闹钟、计时器、菜单或设置，不切换其他应用，不重复输入，不按回车。若不是世界时钟页或看不到目标，请报告失败并停止。控件树可能不完整，结果也可依据当前截图读取。",
                manualConfirmation = true, allowScreenUpload = true,
            )))
            started = true
            withTimeout(5_000) { while (record() == null) delay(25) }
            report("started", approved, record()!!.id)
            withTimeout(180_000) {
                while (true) {
                    val state = RootPilotService.uiState.value
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION &&
                        (state.pendingAction !== approvedAction || state.step != approvedStep)) {
                        val action = state.pendingAction
                        when (action) {
                            is RootPilotAction.OpenApp -> {
                                assertEquals("launch_must_be_first", 0, approved)
                                assertEquals("only_clock_launch_allowed", CLOCK, action.packageName)
                                val screen = SuRootExecutor().observeScreen()
                                assertEquals(FIXTURE, screen.foregroundPackage)
                                assertEquals(FIXTURE, screen.focusedPackage)
                            }
                            is RootPilotAction.Tap -> {
                                assertTrue("only_two_entry_taps_allowed", approved in 1..2)
                                val tree = clockTree()
                                val frame = state.frame ?: throw AssertionError("source_frame_required")
                                val x = (action.x * (frame.physicalWidth - 1) / 1000).coerceIn(0, frame.physicalWidth - 1)
                                val y = (action.y * (frame.physicalHeight - 1) / 1000).coerceIn(0, frame.physicalHeight - 1)
                                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                    putInt("clockEntryIndex", approved)
                                    putInt("clockEntryNodeCount", tree.nodes.size)
                                    putBoolean("clockEntryTreeTruncated", tree.truncated)
                                    putBoolean("clockPickerTitleVisible", tree.nodes.any { labels(it).contains("选择城市") })
                                    putBoolean("clockPickerContainerVisible", tree.nodes.any { resourceId(it) == "$CLOCK:id/timezone_search_bs_container" })
                                    putBoolean("clockSearchEntryVisible", tree.nodes.any { resourceId(it) == "$CLOCK:id/search_view" })
                                })
                                if (approved == 1) assertTrue("world_clock_page_required",
                                    tree.nodes.any { labels(it).contains("世界时钟") })
                                val targets = tree.nodes.filter { node ->
                                    val parent = tree.nodes.firstOrNull {
                                        it["node_id"] == node["parent_id"]
                                    }
                                    val correctControl = if (approved == 1) {
                                        resourceId(parent) == "$CLOCK:id/action_bar_container" &&
                                            resourceId(node) == null &&
                                            node["class"]?.jsonPrimitive?.contentOrNull == "android.widget.Button"
                                    } else resourceId(node) == "$CLOCK:id/search_view" &&
                                        hasAncestor(node, tree.nodes, "$CLOCK:id/timezone_search_bs_container") &&
                                        tree.nodes.any { child ->
                                            resourceId(child) == "android:id/input" &&
                                                child["parent_id"] == node["node_id"] &&
                                                child["text_redacted"]?.jsonPrimitive?.booleanOrNull == false &&
                                                labels(child).contains("输入国家或城市名搜索")
                                        }
                                    correctControl && node["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                                        node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                                        node["text_redacted"]?.jsonPrimitive?.booleanOrNull == false && contains(node, x, y)
                                }
                                assertEquals("tap_must_hit_expected_public_entry", 1, targets.size)
                            }
                            is RootPilotAction.Type -> {
                                assertEquals("type_requires_both_entry_taps", 3, approved)
                                assertTrue("only_fixed_city_query_allowed", action.text == "北京")
                                val screen = SuRootExecutor().observeScreen()
                                assertEquals("clock_input_foreground_required", CLOCK, screen.foregroundPackage)
                                assertEquals("clock_input_focus_required", CLOCK, screen.focusedPackage)
                                assertTrue("clock_input_activity_required", screen.foregroundActivity in CLOCK_ACTIVITIES)
                                assertEquals("own_ime_required", ime.ownId, ime.currentId())
                                assertEquals("original_ime_record_required", originalIme, FileImeRestoreStore(context).read())
                            }
                            else -> throw AssertionError("action_outside_clock_search_scope")
                        }
                        if (confirmOverlay(state, action is RootPilotAction.Type)) {
                            approvedAction = action
                            approvedStep = state.step
                            approved++
                            report("approved", approved, record()!!.id)
                        }
                    }
                    if (!state.running && state.status in TERMINAL) break
                    delay(50)
                }
            }
            val state = RootPilotService.uiState.value
            assertEquals("service_terminal_state_mismatch", RootPilotStatus.COMPLETED, state.status)
            assertEquals("exactly_four_actions_required", 4, approved)
            assertNull("pending_action_remaining", state.pendingAction)
            val result = state.errorMessage.orEmpty().replace(" ", "").uppercase()
            assertTrue("model_city_report_required", state.modelReportedResult && result.contains("北京"))
            assertTrue("model_country_report_required", result.contains("中国"))
            assertTrue("model_timezone_report_required", BEIJING_TIMEZONE.containsMatchIn(result))
            withTimeout(5_000) { while (record()?.status == RunHistoryStatus.RUNNING) delay(50) }
            val events = record()?.events ?: throw AssertionError("history_run_missing")
            assertTrue("run_end_required", events.any { it.event == TraceEvent.RUN_END })
            val executions = events.filter { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS }
            assertEquals("exact_execution_sequence_required", listOf(TraceActionType.OPEN_APP,
                TraceActionType.TAP, TraceActionType.TAP, TraceActionType.TYPE), executions.map { it.actionType })
            assertTrue("post_input_screenshot_required", events.any {
                it.step > approvedStep && it.stage == TraceStage.SCREENSHOT && it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS
            })
            assertTrue("post_input_model_request_required", events.any {
                it.step > approvedStep && it.stage == TraceStage.MODEL && it.event == TraceEvent.START
            })
            // The public result must additionally be checked against a fresh host screenshot.
            report("model_report_verified_visual_check_pending", approved, record()!!.id)
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
                if (settled) {
                    try {
                        assertEquals("original_ime_not_restored", originalIme, ime.currentId())
                        assertNull("ime_recovery_remaining", FileImeRestoreStore(context).read())
                        assertFalse("task_snapshot_remaining", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
                    } catch (cleanup: Throwable) { retain(cleanup) }
                    try {
                        RootPilotService.updateConfig(original.config)
                        RootPilotService.updateApiConfig(if (original.apiConfigured) saved else null)
                        assertTrue("config_not_restored", RootPilotService.uiState.value.config == original.config &&
                            RootPilotService.uiState.value.apiConfigured == original.apiConfigured)
                        assertTrue("saved_api_changed", apiFile.readBytes().contentEquals(originalApiBytes))
                        installedSelection?.let { expected ->
                            assertTrue("selection_changed_externally_not_overwritten", selectionFile.exists() && selectionFile.readBytes().contentEquals(expected))
                            if (originalSelection == null) assertTrue("temporary_selection_cleanup_failed", selectionFile.delete())
                            else {
                                val temporary = Files.createTempFile(selectionFile.parentFile!!.toPath(), "clock-restore-", ".tmp")
                                try {
                                    Files.write(temporary, originalSelection)
                                    Files.move(temporary, selectionFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                } finally { Files.deleteIfExists(temporary) }
                                assertTrue("selection_not_restored", selectionFile.readBytes().contentEquals(originalSelection))
                            }
                        }
                    } catch (cleanup: Throwable) { retain(cleanup) }
                    try { fixture.call("finish") } catch (cleanup: Throwable) { retain(cleanup) }
                    report(if (cleanupFailure == null && failed?.suppressed?.isEmpty() != false)
                        "baseline_restored" else "cleanup_failed", approved, record()?.id)
                } else report("cleanup_unconfirmed_fixture_retained", approved, record()?.id)
                if (failed == null) cleanupFailure?.let { throw it }
            }
        }
    }

    private fun confirmOverlay(expected: RootPilotUiState, typing: Boolean): Boolean {
        var clicked = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val current = RootPilotService.uiState.value
            if (current.status != RootPilotStatus.WAITING_CONFIRMATION || current.pendingAction !== expected.pendingAction || current.step != expected.step) return@runOnMainSync
            if (typing) {
                val editor = InputConnectionBridge.editor.value ?: throw AssertionError("clock_editor_required")
                assertTrue("clock_editor_connection_changed", editor.owner.currentInputConnection === editor.connection)
                assertTrue("clock_search_editor_required", editor.info.packageName == CLOCK &&
                    editor.info.fieldId == android.R.id.input && editor.info.inputType == 0x200001 &&
                    !InputConnectionBridge.isPassword(editor.info.inputType))
                assertTrue("empty_city_query_required", editor.connection.getTextBeforeCursor(1, 0)?.isEmpty() == true &&
                    editor.connection.getTextAfterCursor(1, 0)?.isEmpty() == true)
            }
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

    private fun resourceId(node: JsonObject?) = node?.get("resource_id")?.jsonPrimitive?.contentOrNull

    private fun hasAncestor(node: JsonObject, nodes: List<JsonObject>, resource: String): Boolean {
        var current = node
        repeat(nodes.size) {
            current = nodes.firstOrNull { it["node_id"] == current["parent_id"] } ?: return false
            if (resourceId(current) == resource) return true
        }
        return false
    }

    private fun report(stage: String, actions: Int, runId: String?) =
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("clockCityStage", stage)
            putInt("approvedActions", actions)
            putString("clockCityRunId", runId)
            putString("confirmationSource", "script_local_view_click")
        })

    private data class ClockTree(val nodes: List<JsonObject>, val truncated: Boolean, val activity: String)

    private suspend fun clockTree(): ClockTree {
        val observer = SuRootExecutor()
        val before = observer.observeScreen()
        assertEquals("clock_foreground_required", CLOCK, before.foregroundPackage)
        assertEquals("clock_focus_required", CLOCK, before.focusedPackage)
        assertTrue("clock_activity_required", before.foregroundActivity in CLOCK_ACTIVITIES)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidUiTreeProvider(context.packageName).query(before)
        assertNull("clock_tree_unavailable", result.unavailable)
        val after = observer.observeScreen()
        assertEquals("clock_window_changed", before.focusedWindowId, after.focusedWindowId)
        assertEquals("clock_foreground_changed", before.foregroundPackage, after.foregroundPackage)
        assertEquals("clock_focus_changed", before.focusedPackage, after.focusedPackage)
        assertEquals("clock_activity_changed", before.foregroundActivity, after.foregroundActivity)
        val data = result.data ?: throw AssertionError("clock_tree_missing")
        assertEquals(CLOCK, data["package_name"]?.jsonPrimitive?.contentOrNull)
        return ClockTree(data.getValue("nodes").jsonArray.map { it.jsonObject }, result.truncated,
            before.foregroundActivity!!)
    }

    private fun labels(node: JsonObject) = listOf("text", "content_description", "hint")
        .mapNotNull { node[it]?.jsonPrimitive?.contentOrNull }

    private fun arguments() = InstrumentationRegistry.getArguments()

    private companion object {
        const val CLOCK = "com.android.deskclock"
        const val FIXTURE = "com.example.rootpilot.fixture"
        val BEIJING_TIMEZONE = Regex("(?:GMT|UTC)\\+0?8(?::00)?(?![0-9:.])")
        val TERMINAL = setOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED,
            RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
        val CLOCK_ACTIVITIES = setOf("$CLOCK.DeskClockTabActivity", "$CLOCK.worldclock.TimezoneSearchActivity")
        val SAFE_LABELS = setOf("世界时钟", "选择城市", "添加", "添加城市", "搜索", "输入国家或城市名搜索",
            "北京", "中国", "中国 GMT+8:00", "取消", "关闭")
    }
}
