package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.root.RootScreenObserver
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Service/API acceptance; only the signed, fixed virtual fixture may receive actions. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayCapabilityServiceInstrumentedTest {
    @Test fun realServiceConfirmsSwipeBackEnterAndObservesReceipts() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualCapabilityServiceAcceptance") == "true")
        Acceptance(null).run()
    }

    @Test fun realServiceReadsOwnedActivityStackAndCompletesCapabilities() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualStackServiceAcceptance") == "true")
        Acceptance(null, requireStack = true).run()
    }

    @Test fun realServiceCompletesCombinedStackSwipeKeysAndUnicode() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualCompositeServiceAcceptance") == "true")
        withRootPilotAcceptanceScreen {
            Acceptance(null, requireStack = true, unicode = true, composite = true).run()
        }
    }

    @Test fun realServiceReadsOwnedActivityStackWithoutInputAndFinishes() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualStackReadOnlyAcceptance") == "true")
        Acceptance(null, requireStack = true, readOnly = true).run()
    }

    @Test fun realServiceTypesFixedUnicodeAndObservesCompletion() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualUnicodeServiceAcceptance") == "true")
        Acceptance(null, unicode = true).run()
    }

    @Test fun realServiceStopsAtUnicodeConfirmationWithoutInput() = runBlocking {
        assumeTrue(arguments().getString("virtualUnicodeServiceStopAcceptance") == "true")
        Acceptance("TYPE", unicode = true).run()
    }

    @Test fun realServiceInsertsAtKnownNonemptyCaretAndFinishes() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualPlainCursorServiceAcceptance") == "true")
        Acceptance(null, unicode = true, editMode = "CURSOR").run()
    }

    @Test fun realServiceReplacesKnownSelectionAndFinishes() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualPlainSelectionServiceAcceptance") == "true")
        Acceptance(null, unicode = true, editMode = "SELECTION").run()
    }

    @Test fun realServiceStopsAtNonemptyInputConfirmation() = runBlocking {
        assumeTrue(arguments().getString("virtualPlainServiceStopAcceptance") == "true")
        Acceptance("TYPE", unicode = true, editMode = "SELECTION").run()
    }

    @Test fun realServiceSingleStepClosesAfterOnePlanningAction() = runBlocking {
        assumeTrue(arguments().getString("liveVirtualSingleStepServiceAcceptance") == "true")
        Acceptance(null, singleStep = true).run()
    }

    @Test fun realServiceStopsBeforeRequestedCapability() = runBlocking {
        assumeTrue(arguments().getString("virtualCapabilityServiceStopAcceptance") == "true")
        val stop = arguments().getString("stopAction")
        check(stop in listOf("SWIPE", "BACK", "ENTER")) { "fixed_stop_action_required" }
        Acceptance(stop).run()
    }

    @Test fun inspectRejectedSwipeWithoutActionsOrNetwork() = runBlocking {
        assumeTrue(arguments().getString("inspectRejectedVirtualCapabilitySwipe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = arguments().getString("failedCapabilityDirectory").orEmpty()
        val id = arguments().getString("failedCapabilityRunId").orEmpty()
        gate(name.matches(Regex("virtual-capability-service-[0-9a-f-]{36}")) &&
            UUID.fromString(id).toString() == id, "failed_receipt_identity")
        val directory = context.cacheDir.resolve(name)
        val file = directory.resolve("metadata.json")
        gate(file.isFile && file.length() in 1..16_384, "failed_receipt_missing")
        val metadata = Json.parseToJsonElement(file.readText()).jsonObject
        fun field(key: String) = metadata[key]?.jsonPrimitive?.content
        gate(field("runId") == id && field("failure") == "swipe_outside_scroll" &&
            field("failureStage") == "confirm_swipe" && field("approvals") == "1" &&
            field("executionSource") == "production_service" &&
            listOf("exitConfirmed", "cleanupConfirmed", "fixtureGone", "displayGone", "configRestored",
                "allowlistBytesRestored", "apiCiphertextUnchanged").all { field(it) == "true" }, "failed_receipt_invalid")
        val state = RootPilotService.uiState.value
        val record = RootPilotService.historyState(context).value.records.maxByOrNull { it.startedAtEpochMs }
        gate(record?.id == id && !record.eventsTruncated && record.status == RunHistoryStatus.STOPPED &&
            record.events.lastOrNull()?.event == TraceEvent.RUN_END &&
            record.events.filter { it.stage == TraceStage.EXECUTION }.let {
                it.size == 2 && it.all { event -> event.actionType == TraceActionType.OPEN_APP } &&
                    it.map { event -> event.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS)
            }, "failed_history_invalid")
        gate(!state.running && state.status == RootPilotStatus.STOPPED && state.step == 0 &&
            state.logs.lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject["runId"]?.jsonPrimitive?.content } == id,
            "retained_state_changed")
        val action = state.lastAction as? RootPilotAction.Swipe ?: fail("retained_swipe_missing")
        val frame = state.frame ?: fail("retained_frame_missing")
        gate(frame.physicalWidth == 1080 && frame.physicalHeight == 1920 &&
            record!!.events.any { it.stage == TraceStage.SCREENSHOT && it.event == TraceEvent.RESULT &&
                it.status == TraceStatus.SUCCESS && it.step == 0 }, "retained_frame_invalid")
        gate(RootPilotService.uiState.value === state, "retained_state_changed")
        val image = directory.resolve("rejected-swipe.jpg")
        gate(!image.exists(), "diagnostic_already_recorded")
        image.writeBytes(frame.bytes)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("runId", id); putString("artifactDirectory", name)
            putString("imageSource", "retained_owned_fixture_frame_not_live")
            putString("fixtureCounterAttribution", "not_collected")
            putBoolean("networkUsed", false); putBoolean("actionsExecuted", false)
            putInt("x1", action.x1); putInt("y1", action.y1)
            putInt("x2", action.x2); putInt("y2", action.y2)
            putInt("durationMillis", action.durationMillis)
        })
    }

    private class Acceptance(private val stopAt: String?, private val requireStack: Boolean = false,
        private val readOnly: Boolean = false, private val unicode: Boolean = false,
        private val singleStep: Boolean = false, private val composite: Boolean = false,
        private val editMode: String? = null) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val manager = context.getSystemService(DisplayManager::class.java)
        private val uri = Uri.parse("content://$PACKAGE.state")
        private val originalState = RootPilotService.uiState.value
        private val originalConfig = originalState.config
        private val history = RootPilotService.historyState(context)
        private val previousIds = history.value.records.map { it.id }.toSet()
        private val selection = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        private val apiFile = context.noBackupFilesDir.resolve(RootPilotApiConfigStore.FILE_NAME)
        private var originalSelection: ByteArray? = null
        private var installedSelection: ByteArray? = null
        private var originalCiphertext: ByteArray? = null
        private var originalIme: String? = null
        private var originalImes: List<String>? = null
        private var originalServices: String? = null
        private val originalAccessibilityService = RootPilotAccessibilityService.connectedService
        private var originalAccessibilityFlags: Int? = null
        private var mainBaseline: String? = null
        private var directory: File? = null
        private var testConfig: RootPilotConfig? = null
        private var preparedConfig: RootPilotConfig? = null
        private var configChanged = false
        private var selectionAttempted = false
        private var sent = false
        private var requestedAt = Long.MAX_VALUE
        private var runId: String? = null
        private var owned: DisplaySession? = null
        private var instance: String? = null
        private var approvedAction: RootPilotAction? = null
        private var approvedStep = -1
        private var approvals = 0
        private var stopped = false
        private var seedPreparationAttempted = false
        private var stage = "preflight"
        private var failure: String? = null
        private val evidence = Bundle()
        private val inputSample get() = if (editMode == "CURSOR") "🙂" else UNICODE_SAMPLE
        private val expectedInput get() = when (editMode) {
            "CURSOR" -> "甲🙂乙"
            "SELECTION" -> "甲${UNICODE_SAMPLE}丙"
            else -> UNICODE_SAMPLE
        }
        private val order = when {
            composite -> ORDER + "TYPE"
            readOnly -> ORDER.take(1)
            unicode -> listOf("OPEN", "TYPE")
            singleStep -> ORDER.take(2)
            else -> ORDER
        }

        suspend fun run() {
            try {
                withTimeout(180_000) {
                    preflight()
                    execute()
                }
            } catch (error: Throwable) {
                // Never export platform exceptions, model text, task text or configuration.
                failure = if (error is GateFailure) error.code else "acceptance_failed_$stage"
                evidence.putString("failureStage", stage)
            } finally {
                withContext(NonCancellable) {
                    cleanup()
                    publish()
                }
            }
            check(failure == null) { failure ?: "acceptance_unknown" }
        }

        private suspend fun preflight() {
            gate(context.packageName == "com.example.agent", "target_package")
            gate(listOf(readOnly, unicode, singleStep).count { it } <= 1 && (!readOnly || requireStack), "acceptance_mode")
            gate(!composite || requireStack && unicode && !readOnly && !singleStep && stopAt == null,
                "composite_mode")
            gate(editMode == null || editMode in listOf("CURSOR", "SELECTION") && unicode &&
                !composite && !readOnly && !singleStep && !requireStack, "plain_edit_mode")
            gate(!originalState.running && originalState.pendingAction == null && originalState.status in IDLE,
                "service_busy")
            gate(noRecovery(), "recovery_pending")
            gate(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, "device_locked")
            gate(Settings.canDrawOverlays(context), "overlay_permission")
            gate(RootPilotAccessibilityService.connectedService != null, "accessibility_unavailable")
            originalAccessibilityFlags = originalAccessibilityService?.serviceInfo?.flags
            gate(displays().isEmpty(), "existing_owned_display")
            gate(history.value.error == null, "history_unavailable")
            gate(context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH &&
                context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == PACKAGE, "fixture_identity")
            gate(AndroidAppCatalog(context).listApps().singleOrNull { it.packageName == PACKAGE }?.activityName == ACTIVITY,
                "fixture_launcher_identity")
            stage = "fixture_initial_state"
            gate(call().isEmpty, "fixture_already_open")
            stage = "preflight"
            originalIme = setting(Settings.Secure.DEFAULT_INPUT_METHOD)
            gate(!originalIme.isNullOrBlank(), "ime_unavailable")
            originalImes = enabledImes()
            originalServices = setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            mainBaseline = mainIdentity()
            val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) { null }
            gate(saved != null && saved.apiKey.isNotBlank() && saved.model.isNotBlank() &&
                saved.baseUrl.trimEnd('/') == "https://api.deepseek.com", "saved_deepseek_config_required")
            gate(originalState.apiConfigured || originalConfig.apiKey.isEmpty() &&
                originalConfig.baseUrl == RootPilotApiConfig().baseUrl && originalConfig.model == RootPilotApiConfig().model,
                "config_unrestorable")
            originalCiphertext = apiFile.readBytes()
            directory = File(context.cacheDir, "virtual-capability-service-${UUID.randomUUID()}")
                .also { gate(it.mkdir(), "artifact_write") }
            originalSelection = if (selection.exists()) selection.readBytes() else null
            selectionAttempted = true
            AppLaunchAllowlistStore.create(context).save(setOf(PACKAGE))
            installedSelection = selection.readBytes()
            configChanged = true
            RootPilotService.updateApiConfig(saved)
            preparedConfig = RootPilotService.uiState.value.config
            testConfig = saved!!.applyTo(RootPilotConfig(task = when {
                editMode == "CURSOR" -> PLAIN_CURSOR_TASK
                editMode == "SELECTION" -> PLAIN_SELECTION_TASK
                composite -> COMPOSITE_TASK
                unicode -> UNICODE_TASK
                singleStep -> SINGLE_STEP_TASK
                readOnly -> STACK_READ_ONLY_TASK
                requireStack -> STACK_TASK
                stopAt == null -> TASK
                else -> STOP_TASK
            }, manualConfirmation = false,
                allowScreenUpload = true, executionDisplay = ExecutionDisplay.VIRTUAL,
                virtualDisplayStartPackage = PACKAGE))
        }

        private suspend fun execute() {
            stage = "start"
            requestedAt = System.currentTimeMillis()
            sent = true
            RootPilotService.send(context, if (singleStep) RootPilotService.ACTION_SINGLE_STEP
                else RootPilotService.ACTION_AUTO_EXECUTE, testConfig)
            withTimeout(10_000) { while (record() == null) delay(25) }
            stage = "confirm"
            while (true) {
                currentCoroutineContext().ensureActive()
                val state = RootPilotService.uiState.value
                gate(state.config == testConfig, "config_changed")
                if (state.status == RootPilotStatus.WAITING_CONFIRMATION && !stopped &&
                    (state.pendingAction !== approvedAction || state.step != approvedStep)) {
                    val session = owned ?: bindDisplay().also { owned = it }
                    validateDisplay(session)
                    val action = state.pendingAction ?: fail("pending_action_missing")
                    gate(actionName(action) == order.getOrNull(approvals), "action_outside_scope")
                    stage = "confirm_${actionName(action).lowercase()}"
                    if (approvals == 0) {
                        gate(action is RootPilotAction.OpenApp && action.packageName == PACKAGE && call().isEmpty,
                            "bootstrap_scope")
                        if (editMode != null && !seedPreparationAttempted) {
                            seedPreparationAttempted = true
                            val receipt = context.contentResolver.call(uri,
                                if (editMode == "CURSOR") "virtual_prepare_cursor" else "virtual_prepare_selection", null,
                                Bundle().apply { putInt("displayId", session.displayId); putString("sessionId", session.sessionId) })
                                ?: fail("seed_preparation_unavailable")
                            gate(receipt.getBoolean("prepared") && receipt.getInt("displayId", -1) == session.displayId &&
                                receipt.getString("sessionId") == session.sessionId, "seed_preparation_identity")
                            validateDisplay(session)
                            evidence.putBoolean("seedPreparedBeforeOpen", true)
                        }
                    } else {
                        if (!captureReceiptReady(state)) { delay(25); continue }
                        checkFrame(state)
                        val fixture = sample(session)
                        verifyPrefix(fixture, approvals)
                        if (action is RootPilotAction.Swipe) verifySwipe(action, fixture)
                        if (unicode && action is RootPilotAction.Type) {
                            gate(action.text == inputSample &&
                                fixture.getBoolean("editorFocused"), "unicode_action_scope")
                        }
                        gate(mainIdentity() == mainBaseline, "main_activity_changed")
                    }
                    if (composite || unicode && action is RootPilotAction.Type) {
                        gate(RootPilotAccessibilityService.connectedService === originalAccessibilityService &&
                            originalAccessibilityService?.serviceInfo?.flags == originalAccessibilityFlags,
                            "flags_not_restored_before_confirmation")
                        evidence.putBoolean("flagsRestoredBeforeConfirmation", true)
                    }
                    val stopping = actionName(action) == stopAt
                    if (overlay(state, session, if (stopping) "停止" else "确认")) {
                        approvedAction = action
                        approvedStep = state.step
                        if (stopping) stopped = true else approvals++
                    }
                }
                if (readOnly && instance == null && approvals == 1 && state.running &&
                    state.status == RootPilotStatus.REQUESTING_MODEL && captureReceiptReady(state)) {
                    sample(owned ?: fail("display_identity"))
                }
                if (!state.running && state.status in TERMINAL) break
                delay(25)
            }
            val state = RootPilotService.uiState.value
            gate(state.status == if (stopAt == null) RootPilotStatus.COMPLETED else RootPilotStatus.STOPPED,
                "terminal_not_expected")
            gate(state.pendingAction == null && state.savedTodos.isEmpty(), "unexpected_side_effect")
            gate(if (stopAt == null) approvals == order.size else stopped,
                "confirmation_boundary_not_reached")
            withTimeout(5_000) {
                while (record()?.status == RunHistoryStatus.RUNNING ||
                    record()?.events?.lastOrNull()?.event != TraceEvent.RUN_END) delay(25)
            }
            verifyHistory(record() ?: fail("history_missing"))
            // This is an instance-bound retained effect receipt, not a fresh screen observation after release.
            val receipt = context.contentResolver.call(uri, "virtual_receipt", null, null)
                ?: fail("final_receipt_unavailable")
            gate(instance != null && receipt.getString("instance") == instance && owned != null &&
                receipt.getInt("displayId", -1) == owned!!.displayId, "final_receipt_identity")
            // The retained receipt is the decisive quantity of the gates below: record its fields so a
            // mismatch reads as "receipt says X" instead of a bare code (a stale fixture build once
            // looked identical to a production defect here).
            evidence.putString("retainedReceiptMode", receipt.getString("preparedInputMode"))
            evidence.putInt("retainedReceiptSelectionStart", receipt.getInt("selectionStart", -1))
            evidence.putInt("retainedReceiptSelectionEnd", receipt.getInt("selectionEnd", -1))
            evidence.putBoolean("retainedReceiptCursorSampleMatches", receipt.getBoolean("cursorSampleMatches"))
            evidence.putBoolean("retainedReceiptSelectionSampleMatches", receipt.getBoolean("selectionSampleMatches"))
            evidence.putBoolean("retainedReceiptCursorSeedMatches", receipt.getBoolean("cursorSeedMatches"))
            evidence.putBoolean("retainedReceiptSelectionSeedMatches", receipt.getBoolean("selectionSeedMatches"))
            evidence.putBoolean("retainedReceiptEmpty", receipt.getBoolean("empty"))
            evidence.putBoolean("retainedReceiptTyped", approvals >= 2)
            verifyPrefix(receipt, approvals)
            evidence.putBoolean("finalReceiptsObserved", true)
            evidence.putString("finalReceiptSource", "instance_bound_retained_fixture_counters")
            if (singleStep) {
                gate(!state.modelReportedResult, "single_step_completion_source")
                evidence.putBoolean("onePlanningActionCompleted", true)
            } else if (stopAt == null) {
                gate((readOnly || state.step > approvedStep) && captureReceiptReady(state), "final_capture_receipt_missing")
                checkFrame(state)
                directory!!.resolve("result.jpg").writeBytes(state.frame!!.bytes)
                val message = state.errorMessage.orEmpty().replace(Regex("\\s+"), "")
                gate(state.modelReportedResult && when {
                    readOnly -> message.contains("VirtualCapabilityActivity")
                    composite -> state.errorMessage.orEmpty().contains(UNICODE_SAMPLE) &&
                        message.contains("BACK=1") && message.contains("ENTER=1/1")
                    unicode -> state.errorMessage.orEmpty().contains(expectedInput)
                    else -> message.contains("BACK=1") && message.contains("ENTER=1/1")
                },
                    "model_receipts_not_reported")
                evidence.putBoolean(if (readOnly) "modelActivityReported" else "modelReceiptsReported", true)
            }
            evidence.putBoolean("bodyComplete", true)
        }

        private fun verifyHistory(record: RunHistoryRecord) {
            gate(!record.eventsTruncated && history.value.error == null && record.events.all { it.runId == runId },
                "history_incomplete")
            val events = record.events
            gate(record.status == if (stopAt == null) RunHistoryStatus.COMPLETED else RunHistoryStatus.STOPPED,
                "history_terminal_mismatch")
            val expected = order.take(approvals).map {
                when (it) {
                    "OPEN" -> TraceActionType.OPEN_APP
                    "SWIPE" -> TraceActionType.SWIPE
                    "TYPE" -> TraceActionType.TYPE
                    else -> TraceActionType.KEY
                }
            }
            val executions = events.filter { it.stage == TraceStage.EXECUTION }
            gate(executions.size == expected.size * 2, "execution_count")
            executions.chunked(2).zip(expected).forEach { (pair, type) ->
                gate(pair.all { it.actionType == type } && pair[0].step == pair[1].step &&
                    pair.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT) &&
                    pair.map { it.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS), "execution_receipt")
            }
            gate(events.filter { it.event == TraceEvent.CONFIRMED }.map { it.actionType } == expected, "approval_receipt")
            gate(events.none { it.event == TraceEvent.TODO_SAVED || (!requireStack && it.event == TraceEvent.READ_ACTIVITY_STACK) }, "unsupported_tool")
            if (requireStack) {
                if (readOnly) {
                    val queries = events.filter { it.stage == TraceStage.INFORMATION && it.event != TraceEvent.RESULT }
                    gate(queries.size == 1 && queries.single().event == TraceEvent.READ_ACTIVITY_STACK &&
                        queries.single().status == TraceStatus.STARTED, "readonly_stack_tools_not_exclusive")
                    evidence.putBoolean("singleExclusiveStackQuery", true)
                }
                val queryIndex = events.indexOfFirst { it.event == TraceEvent.READ_ACTIVITY_STACK && it.status == TraceStatus.STARTED }
                val query = events.getOrNull(queryIndex)
                val resultIndex = if (query == null) -1 else (queryIndex + 1 until events.size).firstOrNull {
                    events[it].stage == TraceStage.INFORMATION
                } ?: -1
                val result = events.getOrNull(resultIndex)
                val followup = if (resultIndex >= 0) events.drop(resultIndex + 1).filter {
                    it.stage == TraceStage.MODEL && it.step == query?.step
                } else emptyList()
                gate(query != null && result?.event == TraceEvent.RESULT && result.status == TraceStatus.SUCCESS &&
                    result.step == query.step && followup.any { it.event == TraceEvent.START } &&
                    followup.any { it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS }, "owned_stack_followup_missing")
                if (composite) {
                    val firstSwipe = events.indexOfFirst { it.stage == TraceStage.EXECUTION &&
                        it.event == TraceEvent.START && it.actionType == TraceActionType.SWIPE }
                    val initialTree = events.indexOfFirst { it.event == TraceEvent.READ_UI_TREE &&
                        it.status == TraceStatus.STARTED && it.step == 0 }
                    val initialTreeResult = if (initialTree >= 0) (initialTree + 1 until events.size).firstOrNull {
                        events[it].stage == TraceStage.INFORMATION
                    } ?: -1 else -1
                    gate(query?.step == 0 && initialTree > resultIndex && initialTreeResult > initialTree &&
                        firstSwipe > initialTreeResult && events[initialTreeResult].event == TraceEvent.RESULT &&
                        events[initialTreeResult].status == TraceStatus.SUCCESS && events[initialTreeResult].step == 0,
                        "initial_stack_tree_not_before_swipe")
                }
                evidence.putBoolean("ownedStackForwarded", true)
                evidence.putInt("stackQueries", events.count { it.event == TraceEvent.READ_ACTIVITY_STACK })
            }
            val tools = events.filter { it.event == TraceEvent.READ_UI_TREE }
            if (singleStep) {
                gate(events.none { it.stage == TraceStage.INFORMATION } &&
                    events.count { it.stage == TraceStage.MODEL && it.event == TraceEvent.START } == 1,
                    "single_step_planning_count")
            } else if (stopAt == null && !readOnly) {
                val queryIndex = events.indexOfLast { it.event == TraceEvent.READ_UI_TREE &&
                    it.status == TraceStatus.STARTED && it.step > approvedStep }
                val query = events.getOrNull(queryIndex)
                val resultIndex = if (query == null) -1 else (queryIndex + 1 until events.size).firstOrNull {
                    events[it].stage == TraceStage.INFORMATION
                } ?: -1
                val result = events.getOrNull(resultIndex)
                val followup = if (resultIndex >= 0) events.drop(resultIndex + 1).filter {
                    it.stage == TraceStage.MODEL && it.step == query?.step
                } else emptyList()
                gate(tools.any { it.step > approvedStep } && query != null && result != null &&
                    result.event == TraceEvent.RESULT && result.status == TraceStatus.SUCCESS && result.step == query.step &&
                    followup.any { it.event == TraceEvent.START } &&
                    followup.any { it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS },
                    "final_tree_followup_missing")
                evidence.putBoolean("finalTreeForwarded", true)
            } else if (stopAt != null) {
                val stop = events.indexOfFirst { it.event == TraceEvent.STOP_REQUESTED }
                gate(stop >= 0 && events.subList(0, stop).any { it.event == TraceEvent.WAITING && it.step == approvedStep } &&
                    events.drop(stop + 1).none { it.stage in listOf(TraceStage.EXECUTION, TraceStage.MODEL, TraceStage.SCREENSHOT) &&
                        it.event == TraceEvent.START }, "stop_boundary_receipt")
                evidence.putBoolean("zeroExecutionAfterStop", true)
            }
            evidence.putInt("modelRequests", events.count { it.stage == TraceStage.MODEL && it.event == TraceEvent.START })
            evidence.putInt("executions", expected.size)
            evidence.putBoolean("exactExecutionReceipts", true)
        }

        private suspend fun sample(session: DisplaySession): Bundle {
            validateDisplay(session)
            val observed = RootScreenObserver(session).observe()
            gate(observed.displayId == session.displayId && observed.sessionId == session.sessionId &&
                observed.foregroundPackage == PACKAGE && observed.foregroundActivity == ACTIVITY &&
                observed.focusedPackage == PACKAGE && !observed.focusedWindowId.isNullOrBlank() &&
                observed.keyboardVisible == false, "virtual_window_identity")
            val fixture = call()
            gate(fixture.getBoolean("ready") && fixture.getInt("displayId", -1) == session.displayId, "fixture_target")
            if (instance == null) instance = fixture.getString("instance")
            gate(instance != null && UUID.fromString(instance).toString() == instance &&
                fixture.getString("instance") == instance, "fixture_instance")
            validateDisplay(session)
            evidence.putBoolean("virtualTargetVerified", true)
            return fixture
        }

        private fun verifyPrefix(fixture: Bundle, next: Int) {
            if (unicode && !composite) {
                val typed = next >= 2
                val matches = when (editMode) {
                    "CURSOR" -> fixture.getBoolean(if (typed) "cursorSampleMatches" else "cursorSeedMatches")
                    "SELECTION" -> fixture.getBoolean(if (typed) "selectionSampleMatches" else "selectionSeedMatches")
                    else -> fixture.getBoolean(if (typed) "unicodeMatches" else "empty")
                }
                if (editMode != null) {
                    val caret = if (typed) 1 + inputSample.length else 1
                    gate(fixture.getString("preparedInputMode") == editMode && fixture.getInt("selectionStart", -1) == caret &&
                        fixture.getInt("selectionEnd", -1) == if (typed) caret else if (editMode == "SELECTION") 2 else 1,
                        "plain_edit_caret_or_seed")
                    evidence.putBoolean(if (typed) "plainTextAndCaretObserved" else "zeroPlainTextInput", true)
                }
                gate(matches &&
                    fixture.getInt("scrollY", -1) == 0 && fixture.getInt("backInvoked", -1) == 0 &&
                    fixture.getInt("enterDown", -1) == 0 && fixture.getInt("enterUp", -1) == 0, "unicode_fixture_prefix")
                if (editMode == null) evidence.putBoolean(if (typed) "unicodeMatches" else "zeroUnicodeInput", true)
                return
            }
            val unicodeComplete = composite && next == order.size
            gate((if (unicodeComplete) fixture.getBoolean("unicodeMatches") else fixture.getBoolean("empty")) &&
                fixture.getInt("scrollY", -1).let { if (next >= 2) it > 0 else it == 0 } &&
                fixture.getInt("backInvoked", -1) == if (next >= 3) 1 else 0, "fixture_prefix")
            gate(fixture.getInt("enterDown", -1) == if (next >= 4) 1 else 0, "enter_down_count")
            gate(fixture.getInt("enterUp", -1) == if (next >= 4) 1 else 0, "enter_up_count")
            if (unicodeComplete) evidence.putBoolean("unicodeMatches", true)
        }

        private fun verifySwipe(action: RootPilotAction.Swipe, fixture: Bundle) {
            fun x(value: Int) = value * 1079 / 1000
            fun y(value: Int) = value * 1919 / 1000
            val center = fixture.getInt("x", -1)
            val from = fixture.getInt("fromY", -1)
            val to = fixture.getInt("toY", -1)
            val top = fixture.getInt("rowsTop", -1)
            val bottom = fixture.getInt("rowsBottom", -1)
            evidence.putInt("swipeNormalizedFromY", action.y1)
            evidence.putInt("swipeNormalizedToY", action.y2)
            evidence.putInt("freshRowsTop", top)
            evidence.putInt("freshRowsBottom", bottom)
            gate(center > 0 && from > to && to > 0 && top >= 0 && bottom > top && bottom <= 1920 &&
                action.x1 in 0..1000 && action.x2 in 0..1000 &&
                action.y1 in 0..1000 && action.y2 in 0..1000 && action.durationMillis in 100..2000 &&
                x(action.x1) in center / 2..center * 3 / 2 && x(action.x2) in center / 2..center * 3 / 2 &&
                y(action.y1) in top until bottom && y(action.y2) in top until bottom &&
                y(action.y1) > y(action.y2), "swipe_outside_scroll")
        }

        private fun overlay(expected: RootPilotUiState, session: DisplaySession, label: String): Boolean {
            var clicked = false
            var rejected: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    val state = RootPilotService.uiState.value
                    if (!state.running || state.status != RootPilotStatus.WAITING_CONFIRMATION ||
                        state.pendingAction !== expected.pendingAction || state.step != expected.step ||
                        state.frame !== expected.frame) return@runOnMainSync
                    gate(state.config == testConfig && record()?.id == runId, "approval_identity")
                    // History writes are asynchronous; a queued receipt must be visible before approving the next action.
                    val executionReceipts = record()?.events?.filter {
                        it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS
                    }.orEmpty()
                    if (executionReceipts.size != approvals) return@runOnMainSync
                    validateDisplay(session)
                    val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                        it.isAttachedToWindow && (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay"
                    } ?: return@runOnMainSync
                    gate(panel.display?.displayId == 0 && (panel.layoutParams as WindowManager.LayoutParams).flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0, "overlay_identity")
                    val button = views(panel).filterIsInstance<Button>().singleOrNull { it.text.toString() == label }
                        ?: return@runOnMainSync
                    if (button.isEnabled && button.isShown) clicked = button.performClick()
                } catch (error: Throwable) { rejected = error }
            }
            rejected?.let { throw it }
            return clicked
        }

        private fun checkFrame(state: RootPilotUiState) {
            val frame = state.frame ?: fail("frame_missing")
            gate(frame.physicalWidth == 1080 && frame.physicalHeight == 1920 && frame.width > 0 && frame.height > 0 &&
                frame.bytes.size in 8..16 * 1024 * 1024, "frame_geometry")
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, dimensions)
            gate(dimensions.outMimeType == "image/jpeg" && dimensions.outWidth == frame.width &&
                dimensions.outHeight == frame.height, "frame_format")
        }

        private fun captureReceiptReady(state: RootPilotUiState) = record()?.events?.any {
            it.step == state.step && it.stage == TraceStage.SCREENSHOT && it.event == TraceEvent.RESULT &&
                it.status == TraceStatus.SUCCESS
        } == true

        private fun displays() = manager.displays.filter { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }
        private fun bindDisplay(): DisplaySession {
            val display = displays().singleOrNull() ?: fail("display_identity")
            val session = display.name.removePrefix(VirtualDisplayProtocol.DISPLAY_PREFIX)
            gate(UUID.fromString(session).toString() == session, "display_identity")
            return DisplaySession(display.displayId, session)
        }
        private fun validateDisplay(session: DisplaySession) {
            val display = displays().singleOrNull() ?: fail("display_identity")
            val size = Point().also(display::getRealSize)
            gate(display.isValid && display.rotation == Surface.ROTATION_0 && size.x == 1080 && size.y == 1920 &&
                display.displayId == session.displayId && display.name == VirtualDisplayProtocol.DISPLAY_PREFIX + session.sessionId,
                "display_identity")
        }
        private fun record(): RunHistoryRecord? {
            if (runId == null) {
                val fresh = history.value.records.filter { it.id !in previousIds && it.startedAtEpochMs >= requestedAt }
                gate(fresh.size <= 1, "run_identity")
                runId = fresh.singleOrNull()?.id ?: return null
                gate(UUID.fromString(runId).toString() == runId, "run_identity")
            }
            return history.value.records.singleOrNull { it.id == runId }
        }
        private fun call(): Bundle = context.contentResolver.call(uri, "virtual_state", null, null)
            ?: fail("fixture_unavailable")
        private fun noRecovery() = !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()
        private fun setting(name: String) = Settings.Secure.getString(context.contentResolver, name)
        private fun enabledImes() = context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.id }.sorted()

        private suspend fun cleanup() {
            stage = "cleanup"
            var settled = !sent
            var invariants = true
            try {
                if (sent) {
                    if (RootPilotService.uiState.value.running || record()?.events?.lastOrNull()?.event != TraceEvent.RUN_END) {
                        RootPilotService.send(context, RootPilotService.ACTION_STOP)
                    }
                    withTimeout(20_000) {
                        while (RootPilotService.uiState.value.running || displays().isNotEmpty() ||
                            record()?.events?.lastOrNull()?.event != TraceEvent.RUN_END) delay(25)
                    }
                    settled = true
                    if (seedPreparationAttempted && owned != null) {
                        val session = owned!!
                        val receipt = context.contentResolver.call(uri, "virtual_clear_prepared_input", null,
                            Bundle().apply { putInt("displayId", session.displayId); putString("sessionId", session.sessionId) })
                            ?: fail("seed_cleanup_unavailable")
                        gate(!receipt.getBoolean("prepared") && receipt.getInt("displayId", -1) == session.displayId &&
                            receipt.getString("sessionId") == session.sessionId, "seed_cleanup_identity")
                        evidence.putBoolean("preparedSeedCleared", true)
                    }
                    gate(call().isEmpty && noRecovery(), "fixture_or_recovery_remaining")
                    evidence.putBoolean("fixtureGone", true)
                    evidence.putBoolean("runEnd", true)
                    evidence.putBoolean("displayGone", true)
                }
                if (originalIme != null) {
                    gate(setting(Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme && enabledImes() == originalImes &&
                        setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices &&
                        RootPilotAccessibilityService.connectedService === originalAccessibilityService &&
                        originalAccessibilityService?.serviceInfo?.flags == originalAccessibilityFlags, "environment_changed")
                    evidence.putBoolean("imeAndServicesUnchanged", true)
                }
                if (mainBaseline != null) {
                    gate(mainIdentity() == mainBaseline, "main_activity_changed_after_release")
                    evidence.putBoolean("mainActivityUnchangedAfterRelease", true)
                }
            } catch (_: Throwable) {
                invariants = false
                if (failure == null) failure = if (settled) "environment_assertion_failed" else "cleanup_unconfirmed"
            }
            evidence.putBoolean("exitConfirmed", settled)
            evidence.putBoolean("cleanupConfirmed", settled && invariants)
            if (!settled) return
            try {
                if (selectionAttempted) {
                    val installed = installedSelection ?: fail("allowlist_restore_uncertain")
                    gate(selection.exists() && selection.readBytes().contentEquals(installed), "allowlist_changed")
                    val original = originalSelection
                    if (original == null) gate(selection.delete(), "allowlist_restore_failed")
                    else {
                        val temporary = Files.createTempFile(selection.parentFile!!.toPath(), "capability-restore-", ".tmp")
                        try {
                            temporary.toFile().outputStream().use { it.write(original); it.fd.sync() }
                            Files.move(temporary, selection.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(temporary) }
                        gate(selection.readBytes().contentEquals(original), "allowlist_restore_failed")
                    }
                    evidence.putBoolean("allowlistBytesRestored", true)
                }
            } catch (_: Throwable) {
                evidence.putBoolean("cleanupConfirmed", false)
                if (failure == null) failure = "allowlist_restore_failed"
            }
            try {
                if (configChanged) {
                    var rejected: Throwable? = null
                    // Service dispatch and normal UI edits share this main-thread boundary.
                    instrumentation.runOnMainSync {
                        try {
                            val state = RootPilotService.uiState.value
                            gate(!state.running && (state.config == testConfig || state.config == preparedConfig),
                                "config_restore_conflict")
                            RootPilotService.updateConfig(originalConfig)
                            RootPilotService.updateApiConfig(if (originalState.apiConfigured)
                                RootPilotApiConfig(originalConfig.apiKey, originalConfig.baseUrl, originalConfig.model) else null)
                            gate(RootPilotService.uiState.value.config == originalConfig &&
                                RootPilotService.uiState.value.apiConfigured == originalState.apiConfigured, "config_restore_failed")
                        } catch (error: Throwable) { rejected = error }
                    }
                    rejected?.let { throw it }
                    evidence.putBoolean("configRestored", true)
                }
            } catch (_: Throwable) {
                evidence.putBoolean("cleanupConfirmed", false)
                if (failure == null) failure = "config_restore_failed"
            }
            try {
                if (originalCiphertext != null) {
                    gate(apiFile.readBytes().contentEquals(originalCiphertext!!), "api_ciphertext_changed")
                    evidence.putBoolean("apiCiphertextUnchanged", true)
                }
            } catch (_: Throwable) {
                evidence.putBoolean("cleanupConfirmed", false)
                if (failure == null) failure = "api_ciphertext_changed"
            }
        }

        private suspend fun mainIdentity(): String {
            val process = ProcessBuilder("su", "-c", "exec dumpsys activity activities")
                .redirectError(File("/dev/null")).start()
            val dump = try {
                process.outputStream.close()
                val output = ByteArrayOutputStream()
                withTimeout(5_000) {
                    while (process.isAlive || process.inputStream.available() > 0) {
                        val available = process.inputStream.available()
                        if (available == 0) { delay(10); continue }
                        val bytes = ByteArray(minOf(available, 4096))
                        val count = process.inputStream.read(bytes)
                        gate(count > 0 && output.size() + count <= 512 * 1024, "main_identity_limit")
                        output.write(bytes, 0, count)
                    }
                }
                gate(process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0, "main_identity_command")
                output.toString("UTF-8")
            } finally {
                process.destroyForcibly()
                process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            }
            val lines = dump.lines().dropWhile { it.trim() != "Display #0 (activities from top to bottom):" }
                .drop(1).takeWhile { !it.trimStart().startsWith("Display #") }
            val pattern = Regex("(?:mResumedActivity: |topResumedActivity=|Resumed: )" +
                "(ActivityRecord\\{[0-9a-fA-F]+ u[0-9]+ com\\.example\\.agent/\\.rootpilot\\.RootPilotActivity t[0-9]+\\})")
            val identities = lines.map(String::trim).filter { it.startsWith("mResumedActivity:") ||
                it.startsWith("topResumedActivity=") || it.startsWith("Resumed:") }.map { pattern.matchEntire(it)?.groupValues?.get(1) }
            gate(identities.isNotEmpty() && identities.all { it != null } && identities.distinct().size == 1,
                "main_identity_unavailable")
            return identities.singleOrNull() ?: identities.first()!!
        }

        private fun publish() {
            evidence.putString("stage", stage)
            evidence.putString("failure", failure ?: "none")
            evidence.putString("runId", runId.orEmpty())
            evidence.putString("fixtureInstance", instance.orEmpty())
            evidence.putInt("displayId", owned?.displayId ?: -1)
            evidence.putString("sessionId", owned?.sessionId.orEmpty())
            evidence.putString("stopAction", stopAt ?: "none")
            evidence.putBoolean("requiresStack", requireStack)
            evidence.putBoolean("readOnly", readOnly)
            evidence.putBoolean("unicode", unicode)
            evidence.putString("plainEditMode", editMode ?: "none")
            evidence.putBoolean("singleStep", singleStep)
            evidence.putBoolean("composite", composite)
            evidence.putInt("approvals", approvals)
            evidence.putBoolean("stopOverlayInvoked", stopped)
            evidence.putString("executionSource", "production_service")
            evidence.putString("confirmationSource", "script_local_view_click")
            evidence.putBoolean("passed", failure == null && evidence.getBoolean("bodyComplete") &&
                evidence.getBoolean("cleanupConfirmed") && evidence.getBoolean("configRestored") &&
                evidence.getBoolean("allowlistBytesRestored"))
            try {
                record()?.let { directory?.resolve("history.json")?.writeText(Json.encodeToString(it)) }
                directory?.resolve("metadata.json")?.writeText(Json.encodeToString(evidence.keySet().sorted().associateWith {
                    @Suppress("DEPRECATION") evidence.get(it).toString()
                }))
            } catch (_: Exception) { if (failure == null) failure = "artifact_write"; evidence.putBoolean("passed", false) }
            evidence.putString("artifactDirectory", directory?.name ?: "none")
            instrumentation.sendStatus(0, evidence)
        }
    }

    private class GateFailure(val code: String) : RuntimeException(code)
    private companion object {
        const val PACKAGE = "com.example.rootpilot.fixture"
        const val ACTIVITY = "$PACKAGE.VirtualCapabilityActivity"
        const val UNICODE_SAMPLE = "中文🙂\n第二行"
        const val PLAIN_CURSOR_TASK = "只验收本次独立副屏的签名固定测试页，不读取主屏。普通输入框已聚焦，完整旧文是甲乙，光标位于甲和乙之间。仅执行一次 type，text精确为🙂，不可再次输入，不点击、滑动、按键、再次打开应用、等待或创建待办。输入后必须调用get_ui_tree确认完整结果甲🙂乙，再finish success=true并在message中原样报告甲🙂乙。"
        const val PLAIN_SELECTION_TASK = "只验收本次独立副屏的签名固定测试页，不读取主屏。普通输入框已聚焦，完整旧文是甲乙丙，只有乙被选中。仅执行一次type，内容为中文🙂、一个换行、第二行；text精确为中文🙂\n第二行，不添加空格或其他字符。不重复输入，不点击、滑动、按键、再次打开应用、等待或创建待办。输入后必须调用get_ui_tree确认完整结果甲中文🙂\n第二行丙，再finish success=true并在message中报告这个完整结果，保留真实换行。"
        const val UNICODE_TASK = "只操作本次独立副屏中的签名固定测试页，不读取主屏。当前普通输入框已聚焦且为空。只执行一次 type，内容为中文🙂，接一个换行，再接第二行。完整内容必须精确为 中文🙂\n第二行，不添加空格或其他字符。不要点击、滑动、按键、再次启动应用、等待或创建待办，不重复输入。输入后必须调用 get_ui_tree 核对输入框中的完整文字，再 finish success=true，并在 message 中报告完整输入内容。"
        const val COMPOSITE_TASK = "只操作本次独立副屏中的签名固定测试页，不读取主屏。先用 get_activity_stack 核对当前 Activity，再用 get_ui_tree 读取本页；每次模型响应只调用一个工具，参数必须是空对象 {}。然后依次执行一次向上滑动、一次 BACK、一次 ENTER、一次 type，每个动作仅一次，不重复。滑动仅在固定行列表可见区域内，从下半部到上半部，不触碰顶部固定计数栏和输入框。BACK 仅更新测试计数，ENTER 仅更新按下与抬起计数，不导航、不编辑。type 只能填写顶部已聚焦空普通文本框，完整内容为 中文🙂\n第二行，必须保留一个真实换行，不添加其他字符。输入后调用一次 get_ui_tree，核对完整两行文本及 BACK=1 ENTER=1/1，再 finish success=true，message 同时报告完整输入内容和这两项计数。禁止点击、HOME、等待、再次启动、创建待办或其他输入，不查询其他屏幕。"
        const val SINGLE_STEP_TASK = "只操作本次独立副屏中的签名固定测试页。只规划一次向上滑动，位于固定测试行列表中央，从页面下半部到上半部，依据当前截图，不触碰上方计数栏和输入框。不调用任何信息工具，不点击、不输入、不按键、不等待、不再次启动、不创建待办。不要返回 finish，直接返回这一次 swipe 动作。"
        const val TASK = "当前是 RootPilot 签名专用副屏测试页，仅固定测试内容。只操作此页。先读取一次 get_ui_tree。然后依次执行一次向上滑动、一次 BACK、一次 ENTER；每个动作只做一次，不重复。滑动只在固定测试行列表中央，从页面下半部向上半部，坐标依据当前副屏截图；不触碰上方计数栏或输入框。BACK 由此测试页回调计数，不会导航离开；ENTER 由此页统计按下和抬起。每个动作后观察画面。ENTER 后必须再调用 get_ui_tree 确认最上方固定回执 BACK=1 ENTER=1/1，最后用 finish success=true 结束并在 message 中原样报告 BACK=1 ENTER=1/1。禁止点击、输入文字、HOME、再次启动应用、创建待办和 Activity 栈查询。"
        const val STACK_TASK = "当前是 RootPilot 签名专用副屏测试页，仅固定非敏感测试内容。只操作本次副屏的此页，不读取主屏。先调用一次 get_activity_stack 核对当前前台 Activity，再调用 get_ui_tree 读取控件。然后依次执行一次向上滑动、一次 BACK、一次 ENTER，每个动作只做一次，不重复。滑动只在固定测试行列表中央，从页面下半部向上半部，依据当前副屏截图；不触碰上方计数栏或输入框。BACK 由此测试页回调计数，不会离开；ENTER 只统计按下和抬起。ENTER 后再调用 get_ui_tree 确认顶部固定回执 BACK=1 ENTER=1/1，最后 finish success=true，并在 message 中报告 BACK=1 ENTER=1/1。禁止点击、输入文字、HOME、再次启动应用、创建待办，不查询其他屏幕或历史任务。"
        const val STACK_READ_ONLY_TASK = "仅验收本次独立副屏中已打开的 RootPilot 签名固定测试页。调用一次 get_activity_stack，读取其当前前台 Activity。只调用这个工具，参数为空对象 {}；不要查询主屏或其他任务，不调用其他工具。读取成功后直接返回 finish success=true，并在 message 中报告实际 Activity 的组件名。禁止所有点击、滑动、按键、文字输入、再次启动应用、等待动作和创建待办。"
        const val STOP_TASK = "只操作当前 RootPilot 签名专用副屏测试页。依次向上滑动一次、BACK 一次、ENTER 一次，每次一个动作，不重复。滑动仅在固定测试行列表中央，从页面下半部到上半部，以当前截图判断坐标，不触碰上方计数栏或输入框。BACK 与 ENTER 仅更新固定计数。不要调用任何信息工具，不点击、不输入、不按 HOME、不再次启动应用、不创建待办。只依据每步新截图提出下一个动作，完成后结束。"
        val ORDER = listOf("OPEN", "SWIPE", "BACK", "ENTER")
        val IDLE = setOf(RootPilotStatus.IDLE, RootPilotStatus.COMPLETED, RootPilotStatus.STOPPED, RootPilotStatus.FAILED)
        val TERMINAL = IDLE + RootPilotStatus.RECOVERY_REQUIRED
        fun arguments(): Bundle = InstrumentationRegistry.getArguments()
        fun gate(value: Boolean, code: String) { if (!value) fail(code) }
        fun fail(code: String): Nothing = throw GateFailure(code)
        fun actionName(action: RootPilotAction): String = when (action) {
            is RootPilotAction.OpenApp -> "OPEN"
            is RootPilotAction.Swipe -> "SWIPE"
            is RootPilotAction.Type -> "TYPE"
            is RootPilotAction.Key -> action.key.name
            else -> "FORBIDDEN"
        }
        fun views(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }
}
