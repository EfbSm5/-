package com.example.agent.rootpilot

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.CommitBoundaryGate
import com.example.agent.rootpilot.input.TypeCommitRecoveryGate
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.InputConnectionBridge
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.SuRootExecutor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production Service / saved API / IME; the test never injects text directly.
 * realNotificationApprovesTypeOnce has its own typeNotificationConfirmation=true gate. It
 * reuses prepare's guards, runs ACTION_SINGLE_STEP to avoid another screenshot/model request,
 * clicks only the exact own SystemUI row's action, then verifies the retained old real
 * PendingIntent is cancelled and has no effect during a one-second quiet window. It does not
 * test forged tokens, a new pending action, physical touch, or process/Service death.
 * realServiceDestroyedAtUnapprovedType is separately gated by typeServiceDestroy=true, runs once
 * without process death, and reuses prepare's ownership/privacy guards. It retains the waiting
 * snapshot, permits only cancellation's RUN_END (plus the reject/cancel race), checks a retained
 * real confirmation listener, then uses restoreIfNeeded to rebuild without replay. The process-
 * death phases and unchanged-history assertions below do not apply to that method.
 * Unapproved methods leave Type pending. Submitted methods require typeSubmittedRecovery=true,
 * click the actual secure overlay confirmation once, and require the same target to contain
 * exactly EXPECTED with one commitText call after IME restoration. This is after-submit recovery,
 * not death inside the sender's commitText or recovery during an unfinished IME switch.
 * Invoke each method separately with --no-restart, recoveryReceiptId=<fresh UUID>,
 * expectedRootPilotPid=<host-verified current main PID>. Process-death methods also require
 * typeProcessRecovery=true; standalone notification/Service-destroy methods use only their own gate.
 * Prepare also requires recoveryBaseline=empty_task_saved_api_or_defaults and a normally
 * foreground RootPilotActivity. Prepare opens the signed fixture once; neither phase reopens
 * it after binding its identity, and verify never launches any Activity.
 *
 * After ready_for_main_process_kill, the host has at most 45 seconds (400 ms for submitted Type,
 * additionally bounded by the unchanged WAITING_SCREEN snapshot/history) to kill only the old main
 * PID, normally reopen RootPilotActivity, then invoke the matching verify method within 120
 * seconds of readyAt. Prepare dying with that process is expected and is not a test PASS.
 * The fixture Activity instance and process must survive; a newly empty page is not evidence.
 *
 * Private receipts contain identities, flags and digests, never credentials or model/task text.
 * historyDigest hashes the actual ready-time runs.json bytes; interruptedHistoryDigest hashes
 * the same document with only this run's status changed to INTERRUPTED. Current production
 * recovery adds no trace event. Zero post-reopen activity uses complete history and fresh
 * process logs, not network interception. Failed verification retains the one-shot claim and
 * snapshot. Prepare revokes its receipt before normal STOP; uncertain ownership forbids cleanup.
 */
@RunWith(AndroidJUnit4::class)
class TypeProcessRecoveryInstrumentedTest {
    /** Fixed local model; only the external operator presses the real A/B controls. */
    @Test
    fun noModelTwoStageTypeRequiresNotificationAndImeConfirmation() = twoStageType(realModel = false)

    /**
     * Same gates with the saved real API and one fixed fixture task. The armed session uploads only the
     * fixture's View export without tools; the model's reason is compared in-process only, and output
     * carries fixed codes and counts, never model or task text.
     */
    @Test
    fun realModelTwoStageTypeRequiresNotificationAndImeConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("twoStageTypeRealModelAcceptance") == "true")
        twoStageType(realModel = true)
    }

    private fun twoStageType(realModel: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val gate = if (realModel) "twoStageTypeRealModelAcceptance" else "twoStageTypeNoModelAcceptance"
        val keys = setOf("class", gate, "twoStageActor", "testNonce",
            "expectedMainPid", "expectedFreshSessionId", "expectedFixtureInstanceId", "expectedFixturePid",
            "expectedFixtureTaskId", "expectedFixtureDisplayId", "expectedFixtureProcessStartTicks")
        check(args.keySet() == keys && args.getString(gate) == "true" &&
            args.getString("twoStageActor") == "manual") { "two_stage_parameters" }
        fun id(key: String): String = checkNotNull(args.getString(key)).also {
            check(it.length == 36 && UUID.fromString(it).toString() == it) { "two_stage_identity" }
        }
        fun number(key: String): Long = checkNotNull(args.getString(key)).also {
            check(it.matches(Regex("0|[1-9][0-9]*"))) { "two_stage_number" }
        }.toLong()
        fun integer(key: String): Int = number(key).also { check(it <= Int.MAX_VALUE) }.toInt()
        val nonce = id("testNonce")
        check(Application.getProcessName() == context.packageName &&
            Process.myUid() == context.applicationInfo.uid) { "two_stage_main_process" }
        val pid = if (args.getString("expectedMainPid") == "instrumentation-main") Process.myPid()
            else integer("expectedMainPid")
        check(pid == Process.myPid() && pid > 0) { "two_stage_process" }
        val identity = RootPilotModelClientFactory.FixtureIdentity(id("expectedFreshSessionId"),
            id("expectedFixtureInstanceId"), integer("expectedFixturePid"), integer("expectedFixtureTaskId"),
            integer("expectedFixtureDisplayId"), number("expectedFixtureProcessStartTicks"))
        check(identity.pid > 0 && identity.pid != pid && identity.task > 0 && identity.display == 0 &&
            identity.startTicks > 0) { "two_stage_fixture_identity" }
        val fixture = FixtureClient(context)
        val result = org.json.JSONObject().put("schemaVersion", 1)
            .put("diagnosticKind", if (realModel) "TWO_STAGE_REAL_MODEL" else "TWO_STAGE_LOCAL_MODEL")
            .put("nonce", nonce).put("mainPid", pid).put("freshSessionId", identity.session)
            .put("fixtureInstanceId", identity.instance).put("fixturePid", identity.pid)
            .put("fixtureTaskId", identity.task).put("fixtureDisplayId", identity.display)
            .put("fixtureProcessStartTicks", identity.startTicks)
            .put("imeShowInputRequested", org.json.JSONObject.NULL)
            .put("imeInputViewShown", org.json.JSONObject.NULL)
        fun stage(value: String, target: Pair<android.graphics.Rect, Long>? = null) =
            instrumentation.sendStatus(0, Bundle().apply {
                putString("twoStageTypeStage", value)
                putString("twoStageTypeNonce", nonce)
                putInt("twoStageTypeMainPid", pid)
                target?.let { (bounds, observedAt) ->
                    putBoolean("twoStageImePreviewMatches", true)
                    putInt("twoStageImeLeft", bounds.left)
                    putInt("twoStageImeTop", bounds.top)
                    putInt("twoStageImeRight", bounds.right)
                    putInt("twoStageImeBottom", bounds.bottom)
                    putLong("twoStageImeObservedElapsed", observedAt)
                }
            })
        fun secure(key: String) = Settings.Secure.getString(context.contentResolver, key)
        fun imeMarkersAbsent() = listOf("rootpilot_original_ime", "rootpilot_original_ime.bak",
            "rootpilot_original_ime.new").none { File(context.noBackupFilesDir, it).exists() }
        fun runMarkersAbsent() = context.filesDir.listFiles()?.none {
            it.name == "rootpilot_run.json" || (it.name.startsWith("rootpilot_run.json.") && it.name.endsWith(".tmp"))
        } == true
        @Suppress("DEPRECATION")
        suspend fun state(): Bundle = fixture.call("state").also { b ->
            check(b.keySet() == setOf("freshSessionId", "instanceId", "processId", "fixtureTaskId",
                "fixtureDisplayId", "fixtureProcessStartTicks", "ready", "empty", "matches", "fieldId", "commitCalls"))
            check(b["freshSessionId"] == identity.session && b["instanceId"] == identity.instance &&
                b["processId"] is Int && b["processId"] == identity.pid &&
                b["fixtureTaskId"] is Int && b["fixtureTaskId"] == identity.task &&
                b["fixtureDisplayId"] is Int && b["fixtureDisplayId"] == identity.display &&
                b["fixtureProcessStartTicks"] is Long && b["fixtureProcessStartTicks"] == identity.startTicks)
            check(listOf("ready", "empty", "matches").all { b[it] is Boolean } &&
                b["fieldId"] is Int && b["fieldId"] == 16908291 &&
                b["commitCalls"] is Int && b.getInt("commitCalls") >= 0)
        }
        val originalIme = secure(Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalA11y = secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val originalA11yEnabled = secure(Settings.Secure.ACCESSIBILITY_ENABLED)
        val ownIme = "${context.packageName}/.rootpilot.input.RootPilotInputMethodService"
        val normalizedOwnIme = "${context.packageName}/${context.packageName}.rootpilot.input.RootPilotInputMethodService"
        fun isOwnIme(value: String?) = value == ownIme || value == normalizedOwnIme
        val initial = RootPilotService.uiState.value
        // Restore only the five non-API fields; do not inspect or serialize API fields.
        val originalConfig = RootPilotConfig(task = initial.config.task,
            manualConfirmation = initial.config.manualConfirmation, allowScreenUpload = initial.config.allowScreenUpload,
            executionDisplay = initial.config.executionDisplay,
            virtualDisplayStartPackage = initial.config.virtualDisplayStartPackage)
        var armed = false
        var apiLoaded = false
        var sentAt: Long? = null
        fun ownerActive() = RootPilotModelClientFactory.evidence(nonce).ownerActive == true
        var observedA = false
        var observedB = false
        var completed = false
        var cleaned = false
        var reason = "ADMISSION"
        try {
            reason = "ADMISSION_IDLE"
            check(!initial.running && initial.pendingAction == null && initial.confirmation == null &&
                initial.status != RootPilotStatus.RECOVERY_REQUIRED)
            reason = "ADMISSION_API_CONFIG"
            // A fresh process has no Service yet, so the armed factory builds the client at first start.
            val api = if (realModel) {
                check(initial.status == RootPilotStatus.IDLE && !initial.apiConfigured &&
                    ServiceDestroyObservation.current == null)
                checkNotNull(RootPilotApiConfigStore.create(context).read()).also {
                    check(it.apiKey.isNotBlank() && it.model.isNotBlank() &&
                        it.baseUrl.trimEnd('/') == "https://api.deepseek.com")
                }
            } else null
            reason = "ADMISSION_RECOVERY_MARKERS"
            check(!originalIme.isNullOrBlank() && !isOwnIme(originalIme) && imeMarkersAbsent() && runMarkersAbsent())
            reason = "ADMISSION_ENABLED_IME"
            val enabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .enabledInputMethodList.map { it.id }
            check(originalIme in enabled && enabled.any(::isOwnIme))
            reason = "ADMISSION_UNLOCKED_INTERACTIVE"
            check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
                context.getSystemService(PowerManager::class.java)?.isInteractive == true)
            reason = "ADMISSION_NOTIFICATION_OVERLAY"
            check(context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true &&
                Settings.canDrawOverlays(context))
            reason = "ADMISSION_HISTORY"
            val history = RootPilotService.historyState(context).value
            check(history.error == null && history.records.size < 50 &&
                history.records.none { it.status == RunHistoryStatus.RUNNING })
            reason = "ADMISSION_FIXTURE_IDENTITY"
            fixture.verifyIdentity()
            val before = state()
            reason = "ADMISSION_FIXTURE_READY_EMPTY"
            check(before.getBoolean("ready") && before.getBoolean("empty") && !before.getBoolean("matches") &&
                before.getInt("commitCalls") == 0)
            reason = "ADMISSION_ROOT_OBSERVATION"
            val observation = SuRootExecutor().observeScreen()
            check(observation?.foregroundPackage == "com.example.rootpilot.fixture" &&
                observation.focusedPackage == "com.example.rootpilot.fixture" && observation.displayId == 0 &&
                observation.sessionId == null && observation.foregroundActivity in setOf(
                    "com.example.rootpilot.fixture.ExecutionFixtureActivity", ".ExecutionFixtureActivity",
                    "com.example.rootpilot.fixture/.ExecutionFixtureActivity"))
            if (realModel) {
                reason = "ADMISSION_API_LOAD"
                withContext(Dispatchers.Main.immediate) { RootPilotService.updateApiConfig(api) }
                apiLoaded = true
                check(RootPilotService.uiState.value.apiConfigured)
            }
            reason = "ADMISSION_FACTORY_ARM"
            withContext(Dispatchers.Main.immediate) {
                RootPilotModelClientFactory.arm(nonce, pid, identity, real = realModel)
            }
            armed = true
            reason = "ADMISSION_SERVICE_START"
            sentAt = System.currentTimeMillis()
            RootPilotService.send(context, RootPilotService.ACTION_SINGLE_STEP, RootPilotConfig(
                task = if (realModel) RootPilotModelClientFactory.REAL_TASK else RootPilotModelClientFactory.task(nonce),
                manualConfirmation = true, allowScreenUpload = true, executionDisplay = ExecutionDisplay.MAIN))
            withTimeout(if (realModel) 240_000 else 120_000) {
                reason = "PREPARATION"
                // Confirmation and status are separate writes; any other pending action fails fast.
                val a = RootPilotService.uiState.first {
                    (it.status == RootPilotStatus.WAITING_CONFIRMATION && it.pendingAction != null) ||
                        it.status in setOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED,
                            RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
                }
                check(a.status == RootPilotStatus.WAITING_CONFIRMATION)
                reason = "PREPARATION_ACTION"
                check(a.confirmation?.stage == com.example.agent.rootpilot.model.ConfirmationStage.TYPE_PREPARE)
                val prepared = checkNotNull(a.pendingAction as? RootPilotAction.Type)
                check(prepared.text == EXPECTED && (realModel || prepared.reason == "fixture"))
                // AgentLoop prefixes the final-confirmation reason with the bound target package.
                val finalAction = prepared.copy(reason = "输入到 com.example.rootpilot.fixture：${prepared.reason}")
                reason = "PREPARATION"
                check(secure(Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme && imeMarkersAbsent())
                check(state().getInt("commitCalls") == 0)
                check(RootPilotModelClientFactory.evidence(nonce).let {
                    it.responseReturned && it.ownerActive == true && it.registeredHttpConstructions == 0 &&
                        (if (realModel) it.delegatedRequests in 1..it.fixedRequests &&
                            it.fixedRequests <= RootPilotModelClientFactory.MAX_REAL_REQUESTS
                        else it.fixedRequests == 1 && it.delegatedRequests == 0) })
                observedA = true
                stage("WAITING_NOTIFICATION_PREPARE")
                reason = "FINAL_CONFIRMATION"
                val b = RootPilotService.uiState.first {
                    (it.confirmation?.stage == com.example.agent.rootpilot.model.ConfirmationStage.TYPE_COMMIT &&
                        it.pendingAction == finalAction) ||
                        it.status in setOf(RootPilotStatus.FAILED, RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
                }
                check(b.confirmation?.stage == com.example.agent.rootpilot.model.ConfirmationStage.TYPE_COMMIT)
                check(isOwnIme(secure(Settings.Secure.DEFAULT_INPUT_METHOD)) && state().getInt("commitCalls") == 0)
                check(ownerActive())
                observedB = true
                // The model's reason is compared in-process only and never reported.
                val expectedPreview = "目标应用：com.example.rootpilot.fixture\n${finalAction.reason}\n" +
                    "待输入全文（仅本地预览）：\n$EXPECTED"
                fun secureImeRoots() = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { view ->
                    val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                    view.isAttachedToWindow && view.isShown && view.display?.displayId == 0 &&
                        params?.type == android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD &&
                        params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
                }
                reason = "IME_LOCATION_MAIN_ENTRY"
                // The panel request is asynchronous and the root is shown before its first window relayout;
                // bounded wait for one laid-out root, then locate exactly once.
                fun settledImeRoot() = secureImeRoots().singleOrNull()?.takeIf {
                    it.isLaidOut && !it.isLayoutRequested && it.width > 0 && it.height > 0
                }
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    while (withContext(Dispatchers.Main.immediate) {
                            settledImeRoot() == null && RootPilotService.uiState.value.confirmation === b.confirmation
                        }) delay(100)
                }
                val target = withContext(Dispatchers.Main.immediate) {
                    val imeOwner = com.example.agent.rootpilot.input.InputConnectionBridge.editor.value?.owner
                    result.put("imeShowInputRequested", runCatching { imeOwner?.isShowInputRequested }.getOrNull()
                        ?: org.json.JSONObject.NULL)
                    result.put("imeInputViewShown", runCatching { imeOwner?.isInputViewShown }.getOrNull()
                        ?: org.json.JSONObject.NULL)
                    reason = "IME_LOCATION_SCOPE_BEFORE"
                    check(Process.myPid() == pid && RootPilotService.uiState.value.confirmation === b.confirmation &&
                        ownerActive())
                    reason = "IME_LOCATION_ROOT_INSPECTION"
                    val roots = secureImeRoots()
                    reason = if (roots.isEmpty()) "IME_LOCATION_ROOT_NONE" else "IME_LOCATION_ROOT_MULTIPLE"
                    check(roots.size == 1)
                    reason = "IME_LOCATION_NODE_LIMIT"
                    val queue = java.util.ArrayDeque<android.view.View>().apply { add(roots.single()) }
                    val buttons = mutableListOf<android.widget.Button>()
                    var previewMatches = 0
                    var inspected = 0
                    while (queue.isNotEmpty()) {
                        check(++inspected <= 128)
                        val view = queue.removeFirst()
                        if (view.isShown && view is android.widget.TextView && view.text.toString() == expectedPreview)
                            previewMatches++
                        if (view is android.widget.Button && view.isShown && view.isEnabled && view.isClickable &&
                            view.text.toString() == "最终确认输入 8 字符") buttons.add(view)
                        if (view is android.view.ViewGroup) {
                            check(queue.size + view.childCount <= 128)
                            repeat(view.childCount) { queue.add(view.getChildAt(it)) }
                        }
                    }
                    reason = "IME_LOCATION_PREVIEW_NOT_UNIQUE"
                    check(previewMatches == 1)
                    reason = "IME_LOCATION_BUTTON_NOT_UNIQUE"
                    check(buttons.size == 1)
                    val button = buttons.single()
                    val visible = android.graphics.Rect()
                    reason = "IME_LOCATION_BUTTON_NOT_FULLY_VISIBLE"
                    check(button.isAttachedToWindow && button.getLocalVisibleRect(visible) &&
                        visible == android.graphics.Rect(0, 0, button.width, button.height) &&
                        button.width > 0 && button.height > 0)
                    reason = "IME_LOCATION_OUTSIDE_DISPLAY"
                    val location = IntArray(2).also(button::getLocationOnScreen)
                    val bounds = android.graphics.Rect(location[0], location[1],
                        location[0] + button.width, location[1] + button.height)
                    val screen = android.graphics.Point().also { button.display.getRealSize(it) }
                    check(android.graphics.Rect(0, 0, screen.x, screen.y).contains(bounds))
                    reason = "IME_LOCATION_SCOPE_AFTER"
                    check(RootPilotService.uiState.value.confirmation === b.confirmation && ownerActive())
                    bounds to android.os.SystemClock.elapsedRealtime()
                }
                stage("WAITING_IME_FINAL", target)
                reason = "COMMIT"
                val terminal = RootPilotService.uiState.first {
                    !it.running && it.status in setOf(RootPilotStatus.COMPLETED, RootPilotStatus.FAILED,
                        RootPilotStatus.STOPPED, RootPilotStatus.RECOVERY_REQUIRED)
                }
                check(terminal.status == RootPilotStatus.COMPLETED && terminal.pendingAction == null)
                val after = state()
                check(after.getInt("commitCalls") == 1 && after.getBoolean("matches") && !after.getBoolean("empty"))
                completed = true
            }
        } catch (_: Throwable) {
            // Fixed stage only; no model/task/error text enters instrumentation output.
        } finally {
            withContext(NonCancellable) {
                val joined = if (armed) runCatching { withTimeout(10_000) {
                    RootPilotModelClientFactory.sealAndJoin(nonce)
                } }.getOrDefault(false) else false
                // Only the run this session admitted is cancelled; a rejected admission never stops other work.
                val started = sentAt
                val record = if (realModel && joined && started != null) runCatching { withTimeout(5_000) {
                    RootPilotService.historyState(context).first { h ->
                        h.error != null || h.records.filter { it.startedAtEpochMs >= started }.let {
                            it.size == 1 && it.single().status != RunHistoryStatus.RUNNING
                        }
                    }.takeIf { it.error == null }?.records?.single { it.startedAtEpochMs >= started }
                } }.getOrNull() else null
                val evidence = if (armed) runCatching { RootPilotModelClientFactory.evidence(nonce) }.getOrNull() else null
                if (realModel) result
                    .put("modelRequests", evidence?.delegatedRequests ?: org.json.JSONObject.NULL)
                    .put("modelUsageReported", record?.events?.any {
                        it.stage == TraceStage.MODEL && it.event == TraceEvent.RESULT &&
                            it.status == TraceStatus.SUCCESS && (it.modelUsage?.totalTokens ?: 0L) > 0L
                    } ?: org.json.JSONObject.NULL)
                // A real run has no fixed local response; its admitted uploads are reported as modelRequests.
                result.put("fixedRequests", evidence?.takeIf { !realModel }?.fixedRequests ?: org.json.JSONObject.NULL)
                    .put("registeredHttpConstructions", evidence?.registeredHttpConstructions ?: org.json.JSONObject.NULL)
                    .put("ownerCompleted", evidence?.ownerCompleted ?: org.json.JSONObject.NULL)
                val finalState = runCatching { state() }.getOrNull()
                val ui = RootPilotService.uiState.value
                val restored = runCatching {
                    secure(Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme && imeMarkersAbsent() &&
                        secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalA11y &&
                        secure(Settings.Secure.ACCESSIBILITY_ENABLED) == originalA11yEnabled
                }.getOrDefault(false)
                if (apiLoaded && !ui.running) withContext(Dispatchers.Main.immediate) {
                    RootPilotService.updateApiConfig(null)
                }
                val owner = joined && evidence?.ownerCompleted == true &&
                    (!realModel || (record != null && !RootPilotService.uiState.value.apiConfigured))
                cleaned = owner && !ui.running && ui.pendingAction == null &&
                    ui.confirmation == null && restored && finalState != null && runMarkersAbsent()
                if (cleaned) withContext(Dispatchers.Main.immediate) { RootPilotService.updateConfig(originalConfig) }
                result.put("commitCalls", finalState?.getInt("commitCalls") ?: org.json.JSONObject.NULL)
                    .put("matches", finalState?.getBoolean("matches") ?: org.json.JSONObject.NULL)
                    .put("fixtureRetained", finalState != null).put("resourcesRestored", restored)
                    .put("cleanupConfirmed", cleaned).put("observedPreparation", observedA)
                    .put("observedFinalConfirmation", observedB).put("passed", completed && cleaned)
                    .put("failureStage", if (completed && cleaned) "NONE" else reason)
                    .put("clickLedgerExternal", true)
                instrumentation.sendStatus(0, Bundle().apply { putString("twoStageTypeReport", result.toString()) })
            }
        }
        check(completed && cleaned) { if (realModel) "TWO_STAGE_REAL_MODEL_NOT_PASSED" else "TWO_STAGE_LOCAL_MODEL_NOT_PASSED" }
    }

    @Test fun prepareServiceAtUnapprovedType() = invoke(prepare = true)

    @Test fun prepareServiceAfterSubmittedType() = invoke(prepare = true, submitted = true)

    @Test fun verifyAfterHostKillAndNormalActivityReopen() = invoke(prepare = false)

    @Test fun verifySubmittedTypeAfterHostKillAndNormalActivityReopen() = invoke(prepare = false, submitted = true)

    @Test fun prepareBeforeCommitBoundary() = invoke(prepare = true, boundary = CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
    @Test fun prepareAfterCommitBoundary() = invoke(prepare = true, boundary = CommitBoundaryGate.Boundary.AFTER_SUBMIT)
    @Test fun verifyBeforeCommitBoundary() = invoke(prepare = false, boundary = CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
    @Test fun verifyAfterCommitBoundary() = invoke(prepare = false, boundary = CommitBoundaryGate.Boundary.AFTER_SUBMIT)

    @Test fun realServiceDestroyedAtUnapprovedType() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typeServiceDestroy") == "true")
        runBlocking {
            val test = Recovery(InstrumentationRegistry.getInstrumentation().targetContext, false, null)
            var failure: Reason? = null
            try {
                test.checkInvocation()
                test.prepare(serviceDestroy = true)
            } catch (error: Throwable) { failure = reason(error) }
            finally {
                withContext(NonCancellable) {
                    if (failure != null) try { test.invalidate(failure!!) }
                    catch (_: Throwable) { test.cleanupReason = Reason.RECEIPT_INVALIDATION_FAILED }
                    try { test.cleanupServiceDestroy() }
                    catch (error: Throwable) { test.cleanupReason = reason(error) }
                    if (failure == null) failure = test.cleanupReason
                    try { test.reportServiceDestroy(failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    /** First approval is a SystemUI action click, never a direct PendingIntent/controller call. */
    @Test fun realNotificationApprovesTypeOnce() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typeNotificationConfirmation") == "true")
        runBlocking {
            val test = Recovery(InstrumentationRegistry.getInstrumentation().targetContext, false, null, notification = true)
            var failure: Reason? = null
            try {
                test.checkInvocation()
                test.prepare()
            } catch (error: Throwable) { failure = reason(error) }
            finally {
                withContext(NonCancellable) {
                    if (failure != null) try { test.invalidate(failure!!) }
                    catch (_: Throwable) { test.cleanupReason = Reason.RECEIPT_INVALIDATION_FAILED }
                    try { test.cleanup(prepare = true) }
                    catch (error: Throwable) { test.cleanupReason = reason(error) }
                    try { test.finishNotificationObservation() }
                    catch (error: Throwable) { if (test.cleanupReason == null) test.cleanupReason = reason(error) }
                    try { test.finishNotificationUi() }
                    catch (error: Throwable) { if (test.cleanupReason == null) test.cleanupReason = reason(error) }
                    if (failure == null) failure = test.cleanupReason
                    try { test.reportNotification(failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    /** Human cooperation plus the real Activity/confirmation chain, not automated click evidence. */
    @Test fun realNotificationManuallyApprovesTypeOnce() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typeNotificationManualConfirmation") == "true")
        runBlocking {
            val test = Recovery(InstrumentationRegistry.getInstrumentation().targetContext, false, null,
                notification = true, manualNotification = true)
            var failure: Reason? = null
            try {
                test.checkInvocation()
                test.prepare()
            } catch (error: Throwable) { failure = reason(error) }
            finally {
                withContext(NonCancellable) {
                    if (failure != null) try { test.invalidate(failure!!) }
                    catch (_: Throwable) { test.cleanupReason = Reason.RECEIPT_INVALIDATION_FAILED }
                    try {
                        withTimeout(35_000) {
                            try { test.cleanup(prepare = true) }
                            catch (error: Throwable) { if (test.cleanupReason == null) test.cleanupReason = reason(error) }
                            finally { test.finishManualNotificationObservation() }
                        }
                    } catch (error: Throwable) { if (test.cleanupReason == null) test.cleanupReason = reason(error) }
                    try { test.finishNotificationUi() }
                    catch (error: Throwable) { if (test.cleanupReason == null) test.cleanupReason = reason(error) }
                    if (failure == null) failure = test.cleanupReason
                    try { test.reportNotification(failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    private fun invoke(prepare: Boolean, submitted: Boolean = false, boundary: CommitBoundaryGate.Boundary? = null) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typeProcessRecovery") == "true")
        if (submitted) assumeTrue(InstrumentationRegistry.getArguments().getString("typeSubmittedRecovery") == "true")
        if (boundary != null) assumeTrue(InstrumentationRegistry.getArguments().getString("typeCommitBoundaryRecovery") == "true")
        runBlocking {
            val test = Recovery(InstrumentationRegistry.getInstrumentation().targetContext, submitted, boundary)
            var failure: Reason? = null
            try {
                test.checkInvocation()
                if (prepare) test.prepare() else test.verify()
            } catch (error: Throwable) {
                failure = reason(error)
            } finally {
                withContext(NonCancellable) {
                    var cleanupAllowed = true
                    if (prepare && failure != null) {
                        try { test.invalidate(failure!!) }
                        catch (_: Throwable) {
                            test.cleanupReason = Reason.RECEIPT_INVALIDATION_FAILED
                            cleanupAllowed = false
                        }
                    }
                    if (cleanupAllowed) {
                        try { test.cleanup(prepare) }
                        catch (error: Throwable) { test.cleanupReason = reason(error) }
                    }
                    if (failure == null) failure = test.cleanupReason
                    try { test.report(if (failure == null) "verified" else "failed", failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    private class NotificationUiSession(private val context: Context) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private var configurator: Configurator? = null
        private var originalFlags: Int? = null
        private var originalAccessibility: Pair<String?, String?>? = null
        private var automation: UiAutomation? = null
        lateinit var device: UiDevice
            private set
        private var finished = false
        val cleanupReasons = mutableListOf<Reason>()
        val restored get() = finished && originalAccessibility != null && cleanupReasons.isEmpty()

        fun begin() {
            originalAccessibility = fixed(Reason.NOTIFICATION_ACCESSIBILITY_BASELINE) { accessibility() }
            fixed(Reason.NOTIFICATION_UI_CONFIGURATOR) {
                val config = Configurator.getInstance()
                configurator = config
                originalFlags = config.uiAutomationFlags
                config.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
                check(config.uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
                    Reason.NOTIFICATION_UI_CONFIGURATOR)
            }
            automation = fixed(Reason.NOTIFICATION_UI_AUTOMATION) {
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    ?: fail(Reason.NOTIFICATION_UI_AUTOMATION)
            }
            device = fixed(Reason.NOTIFICATION_UI_DEVICE) { UiDevice.getInstance(instrumentation) }
        }

        fun finish() {
            fun attempt(code: Reason, block: () -> Unit) {
                try { fixed(code, block) }
                catch (error: Throwable) { cleanupReasons += reason(error) }
            }
            // This boundary follows all UI/observer cleanup. Never acquire UiDevice afterwards:
            // its configured flags would reconnect accessibility and invalidate the readback.
            if (automation != null) attempt(Reason.NOTIFICATION_UI_AUTOMATION_RELEASE) {
                check(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or
                    UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) != null, Reason.NOTIFICATION_UI_AUTOMATION_RELEASE)
            }
            originalFlags?.let { flags -> attempt(Reason.NOTIFICATION_UI_CONFIGURATOR_RESTORE) {
                val config = configurator ?: fail(Reason.NOTIFICATION_UI_CONFIGURATOR_RESTORE)
                config.uiAutomationFlags = flags
                check(config.uiAutomationFlags == flags, Reason.NOTIFICATION_UI_CONFIGURATOR_RESTORE)
            } }
            originalAccessibility?.let { before -> attempt(Reason.NOTIFICATION_ACCESSIBILITY_RESTORE) {
                check(accessibility() == before, Reason.NOTIFICATION_ACCESSIBILITY_RESTORE)
            } }
            finished = true
        }

        private fun accessibility() = Pair(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED),
        )
    }

    private class Recovery(
        private val context: Context,
        private val submitted: Boolean,
        private val boundary: CommitBoundaryGate.Boundary?,
        private val notification: Boolean = false,
        private val manualNotification: Boolean = false,
    ) {
        private var approved = false
        private var submittedReady = false
        private var boundaryReady = false
        private var boundaryReceipt: CommitBoundaryGate.Receipt? = null
        private val expectedTrace get() = if (boundaryReady) BOUNDARY_TRACE else if (submittedReady) SUBMITTED_TRACE else EXPECTED_TRACE
        private val expectedStatus get() = if (boundaryReady) RootPilotStatus.EXECUTING else if (submittedReady) RootPilotStatus.WAITING_SCREEN else RootPilotStatus.WAITING_CONFIRMATION
        private val expectedSummary get() = if (submittedReady) null else "type(length=${EXPECTED.length})"
        private val killWindow get() = if (boundary != null) 5_000L else if (submitted) 400L else HOST_KILL_WINDOW
        private val state get() = RootPilotService.uiState.value
        private val root = context.noBackupFilesDir.resolve("rootpilot-type-process-recovery-test")
        private val apiFile = context.noBackupFilesDir.resolve(RootPilotApiConfigStore.FILE_NAME)
        private val allowlistFile = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        private val snapshotFile = context.filesDir.resolve(RootPilotRunStore.FILE_NAME)
        private val imeFile = context.noBackupFilesDir.resolve("rootpilot_original_ime")
        private val historyFile = context.noBackupFilesDir.resolve("rootpilot_history/runs.json")
        private val ime = AndroidImeEnvironment(context)
        private val fixture = FixtureClient(context)
        private val history by lazy { RootPilotService.historyState(context) }
        private lateinit var id: String
        private val directory get() = root.resolve(id)
        private var directoryOwned = false
        private var baseline: Baseline? = null
        private var ready: Ready? = null
        private var loadedApi: RootPilotApiConfig? = null
        private var previousRecords = emptyList<RunHistoryRecord>()
        private var previousLogIds = emptySet<String>()
        private var requestedAt = Long.MAX_VALUE
        private var runId: String? = null
        private var configChanged = false
        private var startSent = false
        private var boundEditor: InputConnectionBridge.Editor? = null
        private var boundAction: RootPilotAction.Type? = null
        private var claimed = false
        private var verified = false
        private var restored = false
        var cleanupReason: Reason? = null

        fun checkInvocation() {
            val args = InstrumentationRegistry.getArguments()
            check(context.packageName == TARGET && Application.getProcessName() == TARGET, Reason.TARGET_PROCESS)
            check(args.getString("expectedRootPilotPid")?.toIntOrNull() == Process.myPid(), Reason.NO_RESTART_REQUIRED)
            id = args.getString("recoveryReceiptId").orEmpty()
            check(validId(id), Reason.RECEIPT_ID)
        }

        suspend fun prepare(serviceDestroy: Boolean = false) {
            check(InstrumentationRegistry.getArguments().getString("recoveryBaseline") ==
                "empty_task_saved_api_or_defaults", Reason.BASELINE_ACK_REQUIRED)
            val original = state
            check(!original.running && original.pendingAction == null && original.status in IDLE_STATES,
                Reason.SERVICE_BUSY)
            check(!exists(snapshotFile), Reason.EXISTING_RECOVERY)
            checkImeFileAbsent()
            check(original.config.task.isEmpty() && original.config.executionDisplay == ExecutionDisplay.MAIN &&
                original.config.virtualDisplayStartPackage.isEmpty(), Reason.BASELINE_UNRESTORABLE)
            checkMainUnlocked()
            check(Settings.canDrawOverlays(context), Reason.OVERLAY_PERMISSION)
            fixed(Reason.FIXTURE_IDENTITY) { fixture.verifyIdentity() }
            fixed(Reason.FIXTURE_STATE) { fixture.launch() }
            val fixtureState = try { fixture.awaitReady() } catch (_: Throwable) { fail(Reason.FIXTURE_STATE) }
            val fixtureId = fixtureState.getString("instanceId").orEmpty()
            val fixturePid = fixtureState.getInt("processId")
            check(validId(fixtureId) && fixturePid > 0 && fixturePid != Process.myPid(), Reason.FIXTURE_IDENTITY)
            check(fixtureState.getBoolean("ready") && fixtureState.getBoolean("empty") &&
                fixtureState.getInt("fieldId") == android.R.id.edit, Reason.FIXTURE_STATE)
            val observation = SuRootExecutor().observeScreen()
            check(observation.displayId == Display.DEFAULT_DISPLAY && observation.sessionId == null &&
                observation.foregroundPackage == FIXTURE && observation.focusedPackage == FIXTURE &&
                observation.foregroundActivity == "$FIXTURE.ExecutionFixtureActivity", Reason.FIXTURE_FOREGROUND)
            val originalIme = ime.currentId().orEmpty()
            check(originalIme.isNotBlank() && originalIme.length <= 512 && originalIme != ime.ownId &&
                ime.isEnabled(originalIme) && ime.isEnabled(ime.ownId), Reason.IME_UNAVAILABLE)
            val saved = savedApi().also { loadedApi = it }
            val rebuilt = (if (original.apiConfigured) saved else RootPilotApiConfig()).applyTo(original.config)
            check(rebuilt == original.config, Reason.BASELINE_UNRESTORABLE)
            val boot = bootCount()
            check(boot >= 0, Reason.BOOT_IDENTITY)
            check(!Files.isSymbolicLink(root.toPath()) && (!exists(root) || root.isDirectory), Reason.RECEIPT_IO)
            if (exists(root)) {
                val entries = root.listFiles() ?: fail(Reason.RECEIPT_IO)
                check(entries.all { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && validId(it.name) &&
                    it.resolve("completed").isFile && !exists(it.resolve("invalidated")) &&
                    !exists(it.resolve("invalidated-ready.json")) }, Reason.UNFINISHED_RECEIPT)
            }
            check(!exists(directory), Reason.RECEIPT_REUSED)
            withTimeout(5_000) {
                while (history.value.error == null && history.value.records != diskHistory().document.records) delay(POLL)
            }
            check(history.value.error == null && history.value.records.none { it.status == RunHistoryStatus.RUNNING },
                Reason.HISTORY_UNAVAILABLE)
            // Production retains 50 runs; do not evict someone else's oldest record for this test.
            check(history.value.records.size < 50, Reason.HISTORY_CAPACITY)
            previousRecords = history.value.records
            previousLogIds = logs().map { it.runId }.toSet()
            val base = Baseline(id, Process.myPid(), Process.myUid(), boot, original.apiConfigured,
                original.config.manualConfirmation, original.config.allowScreenUpload, originalIme,
                fileDigest(apiFile, Reason.API_CHANGED), optionalDigest(allowlistFile, Reason.ALLOWLIST_CHANGED),
                fixtureId, fixturePid)
            check(state === original && !exists(snapshotFile), Reason.ENVIRONMENT_CHANGED)
            if (!exists(root)) {
                check(root.mkdir(), Reason.RECEIPT_IO)
                syncDirectory(root.parentFile!!)
            }
            check(directory.mkdir(), Reason.RECEIPT_IO)
            directoryOwned = true
            syncDirectory(root)
            write(directory.resolve("baseline.json"), JSON.encodeToString(base).encodeToByteArray())
            baseline = base
            checkUnchangedFiles(base)
            checkMainUnlocked()
            checkFixture(base, foreground = true)
            check(state === original && !exists(snapshotFile) && ime.currentId() == originalIme, Reason.ENVIRONMENT_CHANGED)
            checkImeFileAbsent()
            if (notification) {
                val session = NotificationUiSession(context)
                notificationUiSession = session
                session.begin()
            }
            configChanged = true
            RootPilotService.updateApiConfig(saved)
            if (manualNotification) {
                val observer = ManualNotificationActivityObservation()
                manualActivityObservation = observer
                withContext(Dispatchers.Main.immediate) {
                    fixed(Reason.NOTIFICATION_OBSERVER_REGISTER) {
                        (context.applicationContext as Application).registerActivityLifecycleCallbacks(observer)
                    }
                }
                // Even a premature human approval can already have effects after START.
                approved = true
            }
            requestedAt = System.currentTimeMillis()
            startSent = true
            RootPilotService.send(context, if (notification) RootPilotService.ACTION_SINGLE_STEP else RootPilotService.ACTION_AUTO_EXECUTE,
                testConfig())
            withTimeout(10_000) {
                while (bindRun() == null) {
                    check(!state.running || state.config == testConfig(), Reason.RUN_IDENTITY)
                    delay(POLL)
                }
            }
            withTimeout(180_000) {
                while (true) {
                    if (manualNotification) checkManualEvidence(manualObserver().evidence.snapshot(SystemClock.elapsedRealtime()))
                    val owned = bindRun() ?: fail(Reason.RUN_IDENTITY)
                    check(state.config == testConfig() && state.status in PREPARING_STATES && state.step == 0,
                        Reason.UNEXPECTED_ACTIVITY)
                    checkMainUnlocked()
                    checkFixture(base, foreground = true)
                    checkLogPrefix(owned)
                    val disk = diskHistory()
                    val record = disk.document.records.singleOrNull { it.id == owned }
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION) {
                        checkPendingType()
                        if (record != null && record.events.size == EXPECTED_TRACE.size &&
                            history.value.records == disk.document.records &&
                            ownSnapshot().status == RootPilotStatus.WAITING_CONFIRMATION.name) break
                    }
                    delay(POLL)
                }
            }
            if (notification) {
                if (manualNotification) awaitManualNotificationAndVerify(base) else approveNotificationAndVerify(base)
                return
            }
            if (serviceDestroy) {
                destroyServiceAndVerify(base)
                return
            }
            if (submitted || boundary != null) approveAndAwaitSubmission(base)
            val owned = runId ?: fail(Reason.RUN_IDENTITY)
            val disk = diskHistory()
            checkCompleteHistory(disk.document.records.single { it.id == owned }, RunHistoryStatus.RUNNING)
            val interrupted = disk.document.copy(records = disk.document.records.map {
                if (it.id == owned) it.copy(status = RunHistoryStatus.INTERRUPTED) else it
            })
            val receipt = Ready(id, owned, Process.myPid(), Process.myUid(), boot, SystemClock.elapsedRealtime(),
                fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED), disk.digest,
                digest(JSON.encodeToString(interrupted).encodeToByteArray()),
                if (submitted) digest(byteArrayOf()) else fileDigest(imeFile, Reason.IME_RECOVERY), fixtureId, fixturePid,
                fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO), submitted,
                boundary?.name, boundaryReceipt?.reachedAt, boundaryReceipt?.deadline)
            ready = receipt
            checkPrepared(base, receipt)
            write(directory.resolve("ready.json"), JSON.encodeToString(receipt).encodeToByteArray())
            checkReceiptValid()
            checkPrepared(base, receipt)
            report("ready_for_main_process_kill", null)
            while (SystemClock.elapsedRealtime() - receipt.readyAt < killWindow) {
                checkReceiptValid()
                checkPrepared(base, receipt)
                delay(POLL)
            }
            fail(Reason.HOST_KILL_TIMEOUT)
        }

        private var notificationUiSession: NotificationUiSession? = null
        private var notificationUiClicked = false
        private var notificationActivityObserved = false
        private var oldConfirmationInvalid = false
        private var targetSameInstance = false
        private var targetTextMatches = false
        private var originalImeRestored = false
        private var historyFinalized = false
        private var snapshotCleared = false
        private var confirmedActions = -1
        private var targetCommitCalls = -1
        private var duplicateExecutions = -1
        private var notificationRecords: List<RunHistoryRecord>? = null
        private var notificationLogs: List<String>? = null
        private var activityObservation: NotificationActivityObservation? = null
        private val notificationUi = NotificationUiGuards<Reason>(::fail)
        private var manualActivityObservation: ManualNotificationActivityObservation? = null
        private var manualWaitArmed = false

        private fun manualObserver() = manualActivityObservation ?: fail(Reason.NOTIFICATION_ACTIVITY_UNOBSERVED)

        private fun checkManualEvidence(value: NotificationManualConfirmationEvidence.Snapshot) {
            value.failure?.let {
                fail(when (it) {
                    NotificationManualConfirmationEvidence.Failure.EARLY_ACTIVITY -> Reason.MANUAL_NOTIFICATION_EARLY_ACTIVITY
                    NotificationManualConfirmationEvidence.Failure.TIMEOUT -> Reason.MANUAL_NOTIFICATION_TIMEOUT
                    NotificationManualConfirmationEvidence.Failure.AUTOMATIC_APPROVAL -> Reason.MANUAL_NOTIFICATION_AUTOMATIC_APPROVAL
                    NotificationManualConfirmationEvidence.Failure.ACTIVITY -> Reason.NOTIFICATION_ACTIVITY_UNOBSERVED
                    NotificationManualConfirmationEvidence.Failure.STATE -> Reason.MANUAL_NOTIFICATION_STATE
                })
            }
            check(value.noAutomaticApproval && !notificationUiClicked, Reason.MANUAL_NOTIFICATION_AUTOMATIC_APPROVAL)
        }

        private suspend fun awaitManualNotificationAndVerify(base: Baseline) {
            val owned = runId ?: fail(Reason.RUN_IDENTITY)
            val observer = manualObserver()
            val manager = fixed(Reason.NOTIFICATION_MANAGER_ACCESS) {
                context.getSystemService(NotificationManager::class.java).also {
                    check(it.areNotificationsEnabled() && (it.getNotificationChannel(NOTIFICATION_CHANNEL)?.importance
                        ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE, Reason.NOTIFICATION_UNAVAILABLE)
                }
            }
            var retained: PendingIntent? = null
            withTimeout(5_000) {
                while (retained == null) {
                    checkManualEvidence(observer.evidence.snapshot(SystemClock.elapsedRealtime()))
                    checkPendingType()
                    retained = fixed(Reason.NOTIFICATION_QUERY) { ownNotificationConfirmation(manager) }
                    if (retained == null) delay(POLL)
                }
            }
            val oldConfirmation = retained ?: fail(Reason.NOTIFICATION_UNAVAILABLE)
            val device = notificationUiSession?.device ?: fail(Reason.NOTIFICATION_UI_DEVICE)
            checkPendingType()
            checkFixtureZero(base)
            check(ownSnapshot().status == RootPilotStatus.WAITING_CONFIRMATION.name, Reason.SNAPSHOT_IDENTITY)
            check(ime.currentId() == ime.ownId && FileImeRestoreStore(context).read() == base.originalIme,
                Reason.IME_RECOVERY)
            // The callbacks and final pending/zero-approval check share the main queue with
            // notification dispatch. No lifecycle event is discarded between arm and shade return.
            withContext(Dispatchers.Main.immediate) {
                checkPendingType()
                checkLogPrefix(owned, complete = true)
                val disk = diskHistory().document.records
                check(history.value.records == disk, Reason.HISTORY_CHANGED)
                checkCompleteHistory(disk.single { it.id == owned }, RunHistoryStatus.RUNNING)
                check(fixed(Reason.NOTIFICATION_QUERY) { ownNotificationConfirmation(manager) } == oldConfirmation,
                    Reason.TYPE_IDENTITY)
                checkManualEvidence(observer.evidence.arm(SystemClock.elapsedRealtime()))
                manualWaitArmed = true
            }
            // This is the only automated UI action in manual mode: a human start signal,
            // not row discovery, expansion, an action click, or a first PendingIntent send.
            fixed(Reason.NOTIFICATION_UI_OPEN) {
                check(device.openNotification(), Reason.NOTIFICATION_UI_OPEN_REJECTED)
            }
            checkManualEvidence(observer.evidence.beginWait(SystemClock.elapsedRealtime()))
            while (true) {
                check(bindRun() == owned && state.config == testConfig() && state.step == 0 && state.status in
                    setOf(RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.EXECUTING,
                        RootPilotStatus.WAITING_SCREEN, RootPilotStatus.COMPLETED), Reason.NOTIFICATION_EXECUTION_FAILED)
                checkMainUnlocked()
                checkUnchangedFiles(base)
                val trace = logs().filter { it.runId == owned }
                check(trace.map { it.shape } == NOTIFICATION_TRACE.take(trace.size) &&
                    trace.all { it.reason == "none" } &&
                    logs().none { it.runId !in previousLogIds && it.runId != owned }, Reason.UNEXPECTED_ACTIVITY)
                val disk = diskHistory().document.records
                val record = disk.singleOrNull { it.id == owned } ?: fail(Reason.RUN_IDENTITY)
                check(disk.filter { it.id != owned } == previousRecords && !record.eventsTruncated &&
                    record.events.map(::shape) == NOTIFICATION_TRACE.take(record.events.size) &&
                    record.events.all { it.runId == owned && it.reason == TraceReason.NONE }, Reason.UNEXPECTED_ACTIVITY)
                val value = fixtureState()
                check(value.getString("instanceId") == base.fixtureInstanceId && value.getInt("processId") == base.fixturePid,
                    Reason.FIXTURE_IDENTITY)
                // Sample evidence after asynchronous readbacks: a legitimate destruction/
                // approval between the reads must not be mistaken for an alternate entry.
                val evidence = observer.evidence.snapshot(SystemClock.elapsedRealtime())
                checkManualEvidence(evidence)
                if (evidence.complete) break
                check(trace.none { it.shape !in EXPECTED_TRACE } &&
                    record.events.none { it.stage == TraceStage.EXECUTION || it.event == TraceEvent.CONFIRMED },
                    Reason.MANUAL_NOTIFICATION_WRONG_ENTRY)
                check(value.getBoolean("empty") && !value.getBoolean("matches") &&
                    value.getInt("commitCalls", -1) == 0 && value.getInt("fieldId") == android.R.id.edit,
                    Reason.FIXTURE_STATE)
                delay(POLL)
            }
            notificationActivityObserved = true
            withTimeout(20_000) {
                while (true) {
                    checkManualEvidence(observer.evidence.snapshot(SystemClock.elapsedRealtime()))
                    check(bindRun() == owned && state.config == testConfig() && state.step == 0 && state.status in
                        setOf(RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.EXECUTING,
                            RootPilotStatus.WAITING_SCREEN, RootPilotStatus.COMPLETED), Reason.NOTIFICATION_EXECUTION_FAILED)
                    val disk = diskHistory().document.records
                    if (!state.running && state.status == RootPilotStatus.COMPLETED && history.value.records == disk &&
                        disk.singleOrNull { it.id == owned }?.status == RunHistoryStatus.COMPLETED) break
                    delay(POLL)
                }
            }
            checkNotificationTerminal(base)
            notificationRecords = diskHistory().document.records
            notificationLogs = state.logs.toList()
            // Post-terminal negative control only; never used to obtain the first approval.
            try {
                oldConfirmation.send()
                fail(Reason.OLD_CONFIRMATION_ACCEPTED)
            } catch (_: PendingIntent.CanceledException) { oldConfirmationInvalid = true }
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkNotificationTerminal(base)
                checkManualEvidence(observer.evidence.snapshot(SystemClock.elapsedRealtime()))
                check(diskHistory().document.records == notificationRecords && state.logs == notificationLogs,
                    Reason.UNEXPECTED_ACTIVITY)
                delay(POLL)
            }
            checkManualEvidence(observer.evidence.snapshot(SystemClock.elapsedRealtime()))
            verified = true
        }

        private suspend fun approveNotificationAndVerify(base: Baseline) {
            val owned = runId ?: fail(Reason.RUN_IDENTITY)
            checkPendingType()
            checkLogPrefix(owned, complete = true)
            checkCompleteHistory(diskHistory().document.records.single { it.id == owned }, RunHistoryStatus.RUNNING)
            checkFixtureZero(base)
            check(ownSnapshot().status == RootPilotStatus.WAITING_CONFIRMATION.name, Reason.SNAPSHOT_IDENTITY)
            check(ime.currentId() == ime.ownId && FileImeRestoreStore(context).read() == base.originalIme,
                Reason.IME_RECOVERY)
            val manager = fixed(Reason.NOTIFICATION_MANAGER_ACCESS) {
                context.getSystemService(NotificationManager::class.java).also {
                    check(it.areNotificationsEnabled() && (it.getNotificationChannel(NOTIFICATION_CHANNEL)?.importance
                        ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE, Reason.NOTIFICATION_UNAVAILABLE)
                }
            }
            var retained: PendingIntent? = null
            withTimeout(5_000) {
                while (retained == null) {
                    retained = fixed(Reason.NOTIFICATION_QUERY) { ownNotificationConfirmation(manager) }
                    if (retained == null) delay(POLL)
                }
            }
            val oldConfirmation = retained ?: fail(Reason.NOTIFICATION_UNAVAILABLE)
            val observer = NotificationActivityObservation()
            activityObservation = observer
            withContext(Dispatchers.Main.immediate) {
                fixed(Reason.NOTIFICATION_OBSERVER_REGISTER) {
                    (context.applicationContext as Application).registerActivityLifecycleCallbacks(observer)
                }
            }
            val device = notificationUiSession?.device ?: fail(Reason.NOTIFICATION_UI_DEVICE)
            fixed(Reason.NOTIFICATION_UI_OPEN) {
                notificationUi.require(device.openNotification(), Reason.NOTIFICATION_UI_OPEN_REJECTED)
            }
            val initial = fixed(Reason.NOTIFICATION_UI_ROW_QUERY) { notificationRow(device, waitForTitle = true) }
            val identity = fixed(Reason.NOTIFICATION_UI_ROW_QUERY) { notificationIdentity(initial) }
            val actions = fixed(Reason.NOTIFICATION_UI_ACTION_QUERY) {
                initial.row.findObjects(By.pkg(SYSTEM_UI).text(NOTIFICATION_ACTION))
            }
            val identityDeferred = NotificationTypeApprovalUi.mayExpandWithoutIdentity(identity.result,
                identity.namedCount, identity.iconCount, actions.size)
            if (!identityDeferred) requireNotificationIdentity(identity.result)
            if (actions.isEmpty()) {
                fixed(Reason.NOTIFICATION_UI_EXPAND) {
                    val expected = notificationExpand(initial)
                    notificationUi.require(expected.enabled, Reason.NOTIFICATION_UI_EXPAND_DISABLED)
                    notificationUi.require(expected.clickable, Reason.NOTIFICATION_UI_EXPAND_NOT_CLICKABLE)
                    checkNotificationApprovalIdentity(owned, manager, oldConfirmation)
                    val rebound = notificationRow(device)
                    notificationUi.require(NotificationTypeApprovalUi.sameBinding(initial.title, initial.row,
                        rebound.title, rebound.row), Reason.NOTIFICATION_UI_ROW_CHANGED)
                    val reboundIdentity = notificationIdentity(rebound)
                    val reboundActions = rebound.row.findObjects(By.pkg(SYSTEM_UI).text(NOTIFICATION_ACTION))
                    if (!identityDeferred || !NotificationTypeApprovalUi.mayExpandWithoutIdentity(reboundIdentity.result,
                            reboundIdentity.namedCount, reboundIdentity.iconCount, reboundActions.size))
                        requireNotificationIdentity(reboundIdentity.result)
                    notificationUi.require(reboundActions.isEmpty(), Reason.NOTIFICATION_UI_EXPAND_CHANGED)
                    val expand = try {
                        NotificationIdentityExpand.fresh(expected, notificationExpandCandidates(rebound))
                    } catch (error: NotificationIdentityExpand.Failure) {
                        fail(if (error.code == NotificationIdentityExpand.Code.AMBIGUOUS)
                            Reason.NOTIFICATION_UI_EXPAND_AMBIGUOUS else Reason.NOTIFICATION_UI_EXPAND_CHANGED)
                    }
                    checkNotificationApprovalIdentity(owned, manager, oldConfirmation)
                    expand.node.click()
                    notificationUi.require(device.wait(object : Condition<UiDevice, Boolean> {
                        override fun apply(container: UiDevice): Boolean {
                            val titles = container.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(NOTIFICATION_TITLE))
                            if (titles.isEmpty()) return false
                            val title = notificationUi.single(titles,
                                Reason.NOTIFICATION_UI_TITLE_MISSING, Reason.NOTIFICATION_UI_TITLE_AMBIGUOUS)
                            val expandedRow = notificationRowAncestor(title, required = false) ?: return false
                            return expandedRow.findObjects(By.pkg(SYSTEM_UI).text(NOTIFICATION_ACTION)).isNotEmpty()
                        }
                    }, 5_000), Reason.NOTIFICATION_UI_EXPAND_WAIT_TIMEOUT)
                }
            }
            checkNotificationApprovalIdentity(owned, manager, oldConfirmation)
            val binding = fixed(Reason.NOTIFICATION_UI_ROW_QUERY) {
                notificationRow(device).also {
                    if (actions.isNotEmpty()) notificationUi.require(NotificationTypeApprovalUi.sameBinding(
                        initial.title, initial.row, it.title, it.row), Reason.NOTIFICATION_UI_ROW_CHANGED)
                    requireNotificationIdentity(notificationIdentity(it).result)
                }
            }
            // Identity is mandatory here, including after an identity-deferred expand. Never
            // query approval text globally or reuse the pre-expand row/action wrappers.
            val action = fixed(Reason.NOTIFICATION_UI_ACTION_QUERY) {
                val candidate = notificationUi.single(binding.row.findObjects(By.pkg(SYSTEM_UI).text(NOTIFICATION_ACTION)),
                    Reason.NOTIFICATION_UI_ACTION_MISSING, Reason.NOTIFICATION_UI_ACTION_AMBIGUOUS)
                notificationUi.require(candidate.isEnabled, Reason.NOTIFICATION_UI_ACTION_DISABLED)
                notificationUi.require(candidate.isClickable, Reason.NOTIFICATION_UI_ACTION_NOT_CLICKABLE)
                candidate
            }
            checkNotificationApprovalIdentity(owned, manager, oldConfirmation)
            approved = true // A throwing UI click can already have dispatched; cleanup must allow that side effect.
            fixed(Reason.NOTIFICATION_UI_CLICK) { action.click() }
            notificationUiClicked = true
            withTimeout(20_000) {
                while (true) {
                    check(bindRun() == owned && state.config == testConfig() && state.step == 0 && state.status in
                        setOf(RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.EXECUTING,
                            RootPilotStatus.WAITING_SCREEN, RootPilotStatus.COMPLETED), Reason.NOTIFICATION_EXECUTION_FAILED)
                    val disk = diskHistory().document.records
                    if (!state.running && state.status == RootPilotStatus.COMPLETED && history.value.records == disk &&
                        disk.singleOrNull { it.id == owned }?.status == RunHistoryStatus.COMPLETED) break
                    delay(POLL)
                }
            }
            check(observer.created == 1 && observer.resumed == 1 && observer.destroyed == 1,
                Reason.NOTIFICATION_ACTIVITY_UNOBSERVED)
            notificationActivityObserved = true
            checkNotificationTerminal(base)
            notificationRecords = diskHistory().document.records
            notificationLogs = state.logs.toList()
            // This is only the retained real old notification's cancellation boundary, not a
            // forged intent/token test and not an attempt to approve a later pending action.
            try {
                oldConfirmation.send()
                fail(Reason.OLD_CONFIRMATION_ACCEPTED)
            } catch (_: PendingIntent.CanceledException) { oldConfirmationInvalid = true }
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkNotificationTerminal(base)
                check(diskHistory().document.records == notificationRecords && state.logs == notificationLogs &&
                    observer.created == 1 && observer.resumed == 1 && observer.destroyed == 1,
                    Reason.UNEXPECTED_ACTIVITY)
                delay(POLL)
            }
            verified = true
        }

        private fun ownNotificationConfirmation(manager: NotificationManager): PendingIntent? {
            val own = manager.activeNotifications.filter { it.packageName == TARGET && it.id == NOTIFICATION_ID &&
                it.tag == null && it.notification.channelId == NOTIFICATION_CHANNEL }
            check(own.size <= 1, Reason.NOTIFICATION_UNAVAILABLE)
            val notification = own.singleOrNull()?.notification ?: return null
            check(notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == NOTIFICATION_TITLE,
                Reason.NOTIFICATION_UNAVAILABLE)
            val action = notification.actions.orEmpty().filter { it.title?.toString() == NOTIFICATION_ACTION }
            check(action.size <= 1, Reason.NOTIFICATION_UNAVAILABLE)
            return action.singleOrNull()?.actionIntent?.also {
                check(it.creatorPackage == TARGET && it.creatorUid == Process.myUid() && it.isActivity,
                    Reason.NOTIFICATION_UNAVAILABLE)
            }
        }

        private data class NotificationRowBinding(val title: UiObject2, val row: UiObject2)
        private data class NotificationRowIdentity(val result: NotificationAppIdentity.Result,
            val namedCount: Int, val iconCount: Int)

        private fun checkNotificationApprovalIdentity(owned: String, manager: NotificationManager, expected: PendingIntent) {
            check(bindRun() == owned && state.running && state.config == testConfig() && state.step == 0 &&
                state.status == RootPilotStatus.WAITING_CONFIRMATION && state.pendingAction === boundAction &&
                fixed(Reason.NOTIFICATION_QUERY) { ownNotificationConfirmation(manager) } == expected, Reason.TYPE_IDENTITY)
        }

        private fun notificationRow(device: UiDevice, waitForTitle: Boolean = false): NotificationRowBinding {
            if (waitForTitle) notificationUi.require(device.wait(Until.hasObject(By.pkg(SYSTEM_UI)
                .res(NotificationIdentityEvidence.titleResourcePattern).text(NOTIFICATION_TITLE)), 5_000),
                Reason.NOTIFICATION_UI_TITLE_WAIT_TIMEOUT)
            val title = notificationUi.single(device.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(NOTIFICATION_TITLE)),
                Reason.NOTIFICATION_UI_TITLE_MISSING, Reason.NOTIFICATION_UI_TITLE_AMBIGUOUS)
            val row = notificationRowAncestor(title, required = true) ?: fail(Reason.NOTIFICATION_UI_ROW_ANCESTOR_LIMIT)
            notificationUi.require(row.applicationPackage == SYSTEM_UI && row.className == "android.widget.FrameLayout" &&
                row.isEnabled && row.isClickable && !row.isScrollable, Reason.NOTIFICATION_UI_ROW_STRUCTURE)
            val rowTitle = notificationUi.single(row.findObjects(By.pkg(SYSTEM_UI)
                .res(NotificationIdentityEvidence.titleResourcePattern).text(NOTIFICATION_TITLE)),
                Reason.NOTIFICATION_UI_ROW_TITLE_MISSING, Reason.NOTIFICATION_UI_ROW_TITLE_AMBIGUOUS)
            notificationUi.require(rowTitle == title, Reason.NOTIFICATION_UI_ROW_CHANGED)
            return NotificationRowBinding(title, row)
        }

        private fun notificationIdentity(binding: NotificationRowBinding): NotificationRowIdentity {
            val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
            notificationUi.require(appLabel.isNotBlank(), Reason.NOTIFICATION_UI_APP_LABEL_EMPTY)
            val names = binding.row.findObjects(By.res(NotificationAppIdentity.namedResourcePattern))
            var iconCount = 0
            val result = NotificationAppIdentity.verify(
                namedCandidates = names,
                iconCandidates = { binding.row.findObjects(By.res(SYSTEM_UI, "app_icon")).also { iconCount = it.size } },
                describe = { NotificationAppIdentity.Node(it.resourceName, it.applicationPackage, it.className,
                    it.contentDescription == appLabel, it.text == appLabel, it.isClickable, it.isLongClickable,
                    it.isEnabled, it.isScrollable) },
                parent = { it.parent },
                isBoundRow = { it == binding.row },
                title = binding.title,
                sameNode = { first, second -> first == second },
            )
            return NotificationRowIdentity(result, names.size, iconCount)
        }

        private fun requireNotificationIdentity(result: NotificationAppIdentity.Result) {
            when (result) {
                NotificationAppIdentity.Result.VERIFIED -> Unit
                NotificationAppIdentity.Result.MISSING -> fail(Reason.NOTIFICATION_UI_APP_LABEL_MISSING)
                NotificationAppIdentity.Result.AMBIGUOUS -> fail(Reason.NOTIFICATION_UI_APP_LABEL_AMBIGUOUS)
            }
        }

        private fun notificationExpandCandidates(binding: NotificationRowBinding) =
            binding.row.findObjects(By.res(NotificationIdentityExpand.resourcePattern)).map {
                NotificationIdentityExpand.Candidate(it, it.applicationPackage, it.resourceName, it.isEnabled, it.isClickable)
            }

        private fun notificationExpand(binding: NotificationRowBinding): NotificationIdentityExpand.Candidate<UiObject2> {
            val found = notificationExpandCandidates(binding)
            val candidate = notificationUi.single(found,
                Reason.NOTIFICATION_UI_EXPAND_MISSING, Reason.NOTIFICATION_UI_EXPAND_AMBIGUOUS)
            return try {
                NotificationIdentityExpand.initial(listOf(candidate)) ?: fail(Reason.NOTIFICATION_UI_EXPAND_MISSING)
            } catch (_: NotificationIdentityExpand.Failure) { fail(Reason.NOTIFICATION_UI_EXPAND_CHANGED) }
        }

        private fun notificationRowAncestor(title: UiObject2, required: Boolean): UiObject2? {
            var node: UiObject2? = title
            var child: UiObject2? = null
            repeat(12) {
                val current = node ?: if (required) fail(Reason.NOTIFICATION_UI_ROW_ANCESTOR_MISSING) else return null
                val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = current.resourceName))
                val parent = if (named) null else current.parent
                if (named || NotificationRowStructure.isRow(
                        NotificationRowStructure.Node(current.resourceName, current.applicationPackage, current.className,
                            current.isEnabled, current.isClickable, current.isScrollable),
                        child?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage) },
                        parent?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage, it.className) },
                    )) return current
                child = current
                node = parent
            }
            if (required) fail(Reason.NOTIFICATION_UI_ROW_ANCESTOR_LIMIT)
            return null
        }

        private suspend fun checkNotificationTerminal(base: Baseline) {
            if (manualNotification) {
                // Service notification approval runs on Main. Queue behind that callback so a
                // completed decision cannot be mistaken for missing evidence before its hook ran.
                val (success, expectedDigest) = withContext(Dispatchers.Main.immediate) {
                    NotificationApprovalObservation.lastSuccess to manualObserver().approvalTokenDigest
                }
                check(success?.runId == runId && expectedDigest != null && success?.approvalTokenDigest == expectedDigest,
                    Reason.MANUAL_NOTIFICATION_WRONG_ENTRY)
            }
            check(Process.myPid() == base.mainPid && Process.myUid() == base.appUid && bootCount() == base.bootCount,
                Reason.TARGET_PROCESS)
            checkMainUnlocked()
            check(!state.running && state.status == RootPilotStatus.COMPLETED && state.pendingAction == null &&
                state.config == testConfig() && state.savedTodos.isEmpty() && !state.modelReportedResult,
                Reason.NOTIFICATION_EXECUTION_FAILED)
            val value = fixtureState()
            check(value.getString("instanceId") == base.fixtureInstanceId && value.getInt("processId") == base.fixturePid,
                Reason.FIXTURE_IDENTITY)
            targetSameInstance = true
            targetCommitCalls = value.getInt("commitCalls", -1)
            check(value.getBoolean("matches") && !value.getBoolean("empty") && targetCommitCalls == 1 &&
                value.getInt("fieldId") == android.R.id.edit, Reason.FIXTURE_STATE)
            targetTextMatches = true
            check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
            checkImeFileAbsent()
            originalImeRestored = true
            checkUnchangedFiles(base)
            check(!exists(snapshotFile), Reason.SNAPSHOT_CHANGED)
            snapshotCleared = true
            val records = diskHistory().document.records
            check(history.value.error == null && history.value.records == records &&
                records.filter { it.id != runId } == previousRecords, Reason.HISTORY_CHANGED)
            val record = records.singleOrNull { it.id == runId } ?: fail(Reason.RUN_IDENTITY)
            check(record.status == RunHistoryStatus.COMPLETED && record.reason == TraceReason.NONE &&
                !record.eventsTruncated && record.stepCount == 1 && record.events.map(::shape) == NOTIFICATION_TRACE &&
                record.events.all { it.runId == runId && it.reason == TraceReason.NONE && it.modelFailure == null },
                Reason.UNEXPECTED_ACTIVITY)
            confirmedActions = record.events.count { it.stage == TraceStage.APPROVAL && it.event == TraceEvent.CONFIRMED &&
                it.actionType == TraceActionType.TYPE && it.status == TraceStatus.SUCCESS }
            val executions = record.events.count { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.START &&
                it.actionType == TraceActionType.TYPE }
            val results = record.events.count { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT &&
                it.actionType == TraceActionType.TYPE && it.status == TraceStatus.SUCCESS }
            duplicateExecutions = maxOf(confirmedActions - 1, executions - 1, results - 1, targetCommitCalls - 1)
            check(confirmedActions == 1 && executions == 1 && results == 1 && duplicateExecutions == 0,
                Reason.UNEXPECTED_ACTIVITY)
            val current = logs().filter { it.runId == runId }
            check(current.map { it.shape } == NOTIFICATION_TRACE && current.all { it.reason == "none" } &&
                logs().none { it.runId !in previousLogIds && it.runId != runId }, Reason.UNEXPECTED_ACTIVITY)
            historyFinalized = true
        }

        suspend fun finishNotificationObservation() {
            withContext(Dispatchers.Main.immediate) {
                activityObservation?.let { (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(it) }
                activityObservation = null
            }
            if (verified && restored && cleanupReason == null) {
                val base = baseline ?: fail(Reason.CLEANUP_UNCONFIRMED)
                check(state.config == originalConfig(base) && state.apiConfigured == base.apiConfigured && !state.running &&
                    state.pendingAction == null && !exists(snapshotFile), Reason.RESTORE_FAILED)
                check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
                checkImeFileAbsent()
                checkUnchangedFiles(base)
                checkFixture(base, foreground = false, allowApproved = true)
                check(diskHistory().document.records == notificationRecords && state.logs == notificationLogs,
                    Reason.HISTORY_CHANGED)
            }
        }

        suspend fun finishManualNotificationObservation() {
            val observer = manualActivityObservation ?: return
            try {
                if (verified && restored && cleanupReason == null) {
                    val base = baseline ?: fail(Reason.CLEANUP_UNCONFIRMED)
                    check(state.config == originalConfig(base) && state.apiConfigured == base.apiConfigured && !state.running &&
                        state.pendingAction == null && !exists(snapshotFile), Reason.RESTORE_FAILED)
                    check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
                    checkImeFileAbsent()
                    checkUnchangedFiles(base)
                    checkFixture(base, foreground = false, allowApproved = true)
                    val value = fixtureState()
                    check(value.getBoolean("matches") && !value.getBoolean("empty") && value.getInt("commitCalls", -1) == 1,
                        Reason.FIXTURE_STATE)
                    check(diskHistory().document.records == notificationRecords && state.logs == notificationLogs,
                        Reason.HISTORY_CHANGED)
                }
            } finally {
                // Unregister even when the bounded cleanup coroutine has timed out. Main-queue
                // closure keeps the observer active through the final restoration readbacks.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try { checkManualEvidence(observer.evidence.finish(SystemClock.elapsedRealtime(), requireComplete = verified)) }
                    finally { (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(observer) }
                }
            }
        }

        fun finishNotificationUi() {
            val session = notificationUiSession ?: return
            session.finish()
            if (cleanupReason == null) cleanupReason = session.cleanupReasons.firstOrNull()
            if (verified && restored && session.restored && cleanupReason == null) marker("completed")
        }

        fun reportNotification(failure: Reason?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                val baselineRestored = restored && notificationUiSession?.restored == true && cleanupReason == null
                val passed = verified && baselineRestored && failure == null && cleanupReason == null
                putString("notificationUiCleanupReasonCodes", notificationUiSession?.cleanupReasons
                    ?.joinToString(",") { it.name }.orEmpty())
                putString(if (manualNotification) "manualNotificationStage" else "notificationStage", if (passed) "verified" else "failed")
                if (manualNotification) {
                    val evidence = manualActivityObservation?.evidence?.snapshot(SystemClock.elapsedRealtime())
                    putBoolean("manualMode", true)
                    putBoolean("manualWaitArmed", manualWaitArmed)
                    putBoolean("noAutomaticApproval", !notificationUiClicked && (evidence?.noAutomaticApproval ?: true))
                    putInt("automaticApprovalAttempts", evidence?.automaticApprovalAttempts ?: 0)
                }
                putString("reasonCode", failure?.name ?: "NONE")
                putString("cleanupReasonCode", cleanupReason?.name ?: "NONE")
                if (::id.isInitialized && validId(id)) putString("recoveryReceiptId", id)
                runId?.let { putString("runId", it) }
                putInt("mainPid", Process.myPid())
                putBoolean("passed", passed)
                putBoolean("baselineRestored", baselineRestored)
                putBoolean("notificationUiClicked", notificationUiClicked)
                putBoolean("notificationActivityObserved", notificationActivityObserved)
                putBoolean("oldConfirmationInvalid", oldConfirmationInvalid)
                putBoolean("targetSameInstance", targetSameInstance)
                putBoolean("targetTextMatches", targetTextMatches)
                putBoolean("originalImeRestored", originalImeRestored)
                putBoolean("historyFinalized", historyFinalized)
                putBoolean("snapshotCleared", snapshotCleared)
                putInt("confirmedActions", confirmedActions)
                putInt("targetCommitCalls", targetCommitCalls)
                putInt("duplicateExecutions", duplicateExecutions)
            })
        }

        private var destroyedInstance: ServiceDestroyObservation.Instance? = null
        private var serviceSnapshotDigest: String? = null
        private var serviceHistoryBefore: RunHistoryRecord? = null
        private var serviceHistoryAfter: List<RunHistoryRecord>? = null
        private var serviceLogsAfter: List<String>? = null

        private suspend fun destroyServiceAndVerify(base: Baseline) {
            val owned = runId ?: fail(Reason.RUN_IDENTITY)
            checkPendingType()
            checkLogPrefix(owned, complete = true)
            checkFixtureZero(base)
            val before = diskHistory().document.records.single { it.id == owned }
            checkCompleteHistory(before, RunHistoryStatus.RUNNING)
            serviceHistoryBefore = before
            serviceSnapshotDigest = fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED)
            checkServiceSnapshot()
            check(ime.currentId() == ime.ownId && FileImeRestoreStore(context).read() == base.originalIme,
                Reason.IME_RECOVERY)
            val old = ServiceDestroyObservation.current ?: fail(Reason.SERVICE_OBSERVATION)
            check(!old.destroyed && old.service.get() != null, Reason.SERVICE_OBSERVATION)
            destroyedInstance = old
            val oldButton = withContext(Dispatchers.Main.immediate) {
                checkPendingType()
                val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay" && it.isAttachedToWindow
                } ?: fail(Reason.APPROVAL_UNAVAILABLE)
                check((panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                    Reason.APPROVAL_UNAVAILABLE)
                descendants(panel).filterIsInstance<Button>().singleOrNull { it.text.toString() == "确认" }
                    ?.also { check(it.isEnabled && it.isShown, Reason.APPROVAL_UNAVAILABLE) }
                    ?: fail(Reason.APPROVAL_UNAVAILABLE)
            }
            check(context.stopService(android.content.Intent(context, RootPilotService::class.java)), Reason.SERVICE_STOP_REJECTED)
            withTimeout(20_000) {
                while (!old.destroyed || state.running || history.value.records != diskHistory().document.records ||
                    history.value.records.singleOrNull { it.id == owned }?.status != RunHistoryStatus.INTERRUPTED) delay(POLL)
            }
            check(!oldButton.isAttachedToWindow, Reason.SERVICE_OBSERVATION)
            checkServiceInterrupted(base)
            serviceHistoryAfter = diskHistory().document.records
            serviceLogsAfter = state.logs.toList()
            // Exercise the retained real listener, which still addresses the destroyed controller.
            withContext(Dispatchers.Main.immediate) { check(oldButton.performClick(), Reason.APPROVAL_UNAVAILABLE) }
            checkServiceQuiet(base, recovered = false)
            RootPilotService.restoreIfNeeded(context)
            withTimeout(5_000) {
                while (ServiceDestroyObservation.current === old || state.config.allowScreenUpload) delay(POLL)
            }
            val rebuilt = ServiceDestroyObservation.current ?: fail(Reason.SERVICE_OBSERVATION)
            // ACTION_RESTORE can idle-stop before this poll; created proves onCreate completed.
            check(rebuilt !== old, Reason.SERVICE_OBSERVATION)
            checkServiceQuiet(base, recovered = true)
            withContext(Dispatchers.Main.immediate) { check(oldButton.performClick(), Reason.APPROVAL_UNAVAILABLE) }
            check(state.errorMessage != UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
            RootPilotService.send(context, RootPilotService.ACTION_RECOVER)
            withTimeout(5_000) {
                while (state.errorMessage != UPLOAD_REJECTION) {
                    checkServiceQuiet(base, recovered = true)
                    delay(POLL)
                }
            }
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkServiceQuiet(base, recovered = true)
                check(state.errorMessage == UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
                delay(POLL)
            }
            verified = true
        }

        private fun checkServiceSnapshot() {
            check(fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED) == serviceSnapshotDigest, Reason.SNAPSHOT_CHANGED)
            val snapshot = ownSnapshot()
            check(snapshot.status == RootPilotStatus.WAITING_CONFIRMATION.name && snapshot.step == 0 &&
                snapshot.allowScreenUpload && snapshot.actionSummary == "type(length=${EXPECTED.length})", Reason.SNAPSHOT_IDENTITY)
        }

        private suspend fun checkFixtureZero(base: Baseline) {
            checkFixture(base, foreground = false)
            check(fixtureState().getInt("commitCalls", -1) == 0, Reason.FIXTURE_STATE)
        }

        private suspend fun checkServiceInterrupted(base: Baseline) {
            check(Process.myPid() == base.mainPid && Process.myUid() == base.appUid && bootCount() == base.bootCount,
                Reason.TARGET_PROCESS)
            check(destroyedInstance?.destroyed == true && !state.running && state.pendingAction == null &&
                state.status == RootPilotStatus.RECOVERY_REQUIRED && state.step == 0 && state.savedTodos.isEmpty() &&
                !state.modelReportedResult, Reason.RECOVERY_STATE)
            checkMainUnlocked()
            checkFixtureZero(base)
            check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
            checkImeFileAbsent()
            checkUnchangedFiles(base)
            checkServiceSnapshot()
            val records = diskHistory().document.records
            check(history.value.error == null && history.value.records == records &&
                records.filter { it.id != runId } == previousRecords, Reason.HISTORY_CHANGED)
            val record = records.singleOrNull { it.id == runId } ?: fail(Reason.RUN_IDENTITY)
            val before = serviceHistoryBefore ?: fail(Reason.HISTORY_CHANGED)
            val tail = record.events.drop(before.events.size)
            check(record.status == RunHistoryStatus.INTERRUPTED && record.reason == TraceReason.CANCELLED &&
                record.durationMs >= before.durationMs && record.copy(status = before.status, reason = before.reason,
                    durationMs = before.durationMs, events = before.events) == before &&
                record.events.take(before.events.size) == before.events, Reason.HISTORY_CHANGED)
            // reject() can resume await before cancellation; only that documented race is optional.
            val end = "APPROVAL/RUN_END/CANCELLED/TYPE/0"
            val rejection = "APPROVAL/REJECTED/CANCELLED/TYPE/0"
            check(tail.map(::shape) == listOf(end) || tail.map(::shape) == listOf(rejection, end), Reason.UNEXPECTED_ACTIVITY)
            check(tail.all { it.runId == runId && it.modelFailure == null && it.modelUsage == null &&
                it.reason == if (it.event == TraceEvent.RUN_END) TraceReason.CANCELLED else TraceReason.USER_REJECTED },
                Reason.UNEXPECTED_ACTIVITY)
            val currentLogs = logs().filter { it.runId == runId }
            check(currentLogs.map { it.shape } == record.events.map(::shape) &&
                currentLogs.map { it.reason } == record.events.map { it.reason.name.lowercase(Locale.ROOT) } &&
                logs().none { it.runId !in previousLogIds && it.runId != runId }, Reason.UNEXPECTED_ACTIVITY)
        }

        private suspend fun checkServiceQuiet(base: Baseline, recovered: Boolean) {
            checkServiceInterrupted(base)
            check(state.config == testConfig().copy(allowScreenUpload = !recovered) && state.apiConfigured,
                Reason.UPLOAD_CONSENT)
            check(diskHistory().document.records == serviceHistoryAfter && state.logs == serviceLogsAfter,
                Reason.UNEXPECTED_ACTIVITY)
        }

        suspend fun cleanupServiceDestroy() {
            val base = baseline ?: return
            if (!verified) {
                // Never discard an unverified snapshot. Only cancel an owned, still-active Service.
                if (startSent && state.running) {
                    val owned = bindRun() ?: fail(Reason.CLEANUP_UNCONFIRMED)
                    check(state.config == testConfig() && logs().lastOrNull()?.runId == owned, Reason.CLEANUP_UNCONFIRMED)
                    if (exists(snapshotFile)) ownSnapshot()
                    val instance = ServiceDestroyObservation.current ?: fail(Reason.SERVICE_OBSERVATION)
                    check(context.stopService(android.content.Intent(context, RootPilotService::class.java)), Reason.SERVICE_STOP_REJECTED)
                    withTimeout(20_000) { while (!instance.destroyed || state.running) delay(POLL) }
                }
                check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
                checkImeFileAbsent()
                checkFixtureZero(base)
                checkUnchangedFiles(base)
                if (startSent) fail(Reason.CLEANUP_UNCONFIRMED)
            } else {
                checkServiceQuiet(base, recovered = true)
                RootPilotService.send(context, RootPilotService.ACTION_DISCARD_RECOVERY)
                withTimeout(5_000) {
                    while (state.status != RootPilotStatus.IDLE || exists(snapshotFile)) {
                        check(!state.running && state.config == testConfig().copy(allowScreenUpload = false), Reason.DISCARD_FAILED)
                        delay(POLL)
                    }
                }
                check(!state.running && state.pendingAction == null && state.config == testConfig().copy(allowScreenUpload = false),
                    Reason.CLEANUP_UNCONFIRMED)
                checkFixtureZero(base)
                check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
                checkImeFileAbsent()
                checkUnchangedFiles(base)
                check(diskHistory().document.records == serviceHistoryAfter, Reason.HISTORY_CHANGED)
            }
            if (configChanged) {
                val original = originalConfig(base)
                RootPilotService.updateConfig(original)
                RootPilotService.updateApiConfig(if (base.apiConfigured) savedApi() else null)
                check(state.config == original && state.apiConfigured == base.apiConfigured, Reason.RESTORE_FAILED)
            }
            restored = true
            if (verified) marker("completed")
        }

        fun reportServiceDestroy(failure: Reason?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                val passed = verified && restored && failure == null && cleanupReason == null
                putString("serviceDestroyStage", if (passed) "verified" else "failed")
                putString("reasonCode", failure?.name ?: "NONE")
                putString("cleanupReasonCode", cleanupReason?.name ?: "NONE")
                if (::id.isInitialized && validId(id)) putString("recoveryReceiptId", id)
                runId?.let { putString("runId", it) }
                putInt("mainPid", Process.myPid())
                putBoolean("passed", passed)
                putBoolean("baselineRestored", restored)
                if (passed) {
                    listOf("serviceDestroyed", "asyncCleanupComplete", "oldConfirmationInvalid", "serviceRecreated",
                        "snapshotRetained", "historyInterrupted", "targetSameInstance", "originalImeRestored",
                        "recoveryRequired", "uploadConsentRevoked", "unapprovedRecoverRejected").forEach { putBoolean(it, true) }
                    listOf("confirmedActions", "targetCommitCalls", "postReopenModelRequests", "postReopenScreenshots",
                        "postReopenExecutions").forEach { putInt(it, 0) }
                }
            })
        }

        private fun bindRun(): String? {
            check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
            val newRecords = history.value.records.filter { candidate -> previousRecords.none { it.id == candidate.id } }
            check(newRecords.size <= 1 && newRecords.all { it.startedAtEpochMs >= requestedAt }, Reason.RUN_IDENTITY)
            val logIds = logs().map { it.runId }.filterNot { it in previousLogIds }.distinct()
            val ids = (logIds + newRecords.map { it.id }).distinct()
            check(ids.size <= 1, Reason.RUN_IDENTITY)
            val found = ids.singleOrNull() ?: return null
            check(validId(found) && (runId == null || runId == found), Reason.RUN_IDENTITY)
            check(history.value.records.filter { it.id != found } == previousRecords, Reason.RUN_IDENTITY)
            runId = found
            return found
        }

        private suspend fun checkPendingType() {
            val current = state
            val action = current.pendingAction as? RootPilotAction.Type ?: fail(Reason.TYPE_IDENTITY)
            check(action.text == EXPECTED && action.reason.startsWith("输入到 $FIXTURE：") &&
                (boundAction == null || action === boundAction), Reason.TYPE_IDENTITY)
            check(current.running && current.status == RootPilotStatus.WAITING_CONFIRMATION && current.step == 0 &&
                current.config == testConfig() && current.frame != null && current.savedTodos.isEmpty() &&
                !current.modelReportedResult, Reason.WAITING_IDENTITY)
            withContext(Dispatchers.Main.immediate) {
                val editor = InputConnectionBridge.editor.value ?: fail(Reason.EDITOR_IDENTITY)
                check(editor.info.packageName == FIXTURE && editor.info.fieldId == android.R.id.edit &&
                    editor.owner.currentInputConnection === editor.connection &&
                    !InputConnectionBridge.isPassword(editor.info.inputType) && editor.info.inputType != 0 &&
                    (boundEditor == null || editor === boundEditor), Reason.EDITOR_IDENTITY)
                boundEditor = editor
            }
            boundAction = action
        }

        private suspend fun approveAndAwaitSubmission(base: Baseline) {
            checkPendingType()
            checkFixture(base, foreground = true)
            withContext(Dispatchers.Main.immediate) {
                check(state.pendingAction === boundAction && state.status == RootPilotStatus.WAITING_CONFIRMATION,
                    Reason.TYPE_IDENTITY)
                val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay" && it.isAttachedToWindow
                } ?: fail(Reason.APPROVAL_UNAVAILABLE)
                check((panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                    Reason.APPROVAL_UNAVAILABLE)
                val button = descendants(panel).filterIsInstance<Button>().singleOrNull { it.text.toString() == "确认" }
                    ?: fail(Reason.APPROVAL_UNAVAILABLE)
                check(button.isEnabled && button.isShown, Reason.APPROVAL_UNAVAILABLE)
                boundary?.let { TypeCommitRecoveryGate.arm(id, Process.myPid(), it) }
                approved = true
                check(button.performClick(), Reason.APPROVAL_UNAVAILABLE)
            }
            withTimeout(10_000) {
                while (true) {
                    check(state.running && state.config == testConfig() && state.step == 0 && state.status in
                        setOf(RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN),
                        Reason.UNEXPECTED_ACTIVITY)
                    val value = fixtureState()
                    check(value.getString("instanceId") == base.fixtureInstanceId && value.getInt("processId") == base.fixturePid,
                        Reason.FIXTURE_IDENTITY)
                    check(value.getInt("commitCalls") in 0..1, Reason.FIXTURE_STATE)
                    if (boundary != null) {
                        val paused = TypeCommitRecoveryGate.receipt(id)
                        val expectedCalls = if (boundary == CommitBoundaryGate.Boundary.BEFORE_SUBMIT) 0 else 1
                        val expectedText = if (expectedCalls == 0) value.getBoolean("empty") && !value.getBoolean("matches")
                            else value.getBoolean("matches") && !value.getBoolean("empty")
                        if (paused != null && state.status == RootPilotStatus.EXECUTING && expectedText &&
                            value.getInt("commitCalls") == expectedCalls) {
                            val disk = diskHistory()
                            val record = disk.document.records.singleOrNull { it.id == runId }
                            if (record?.events?.map(::shape) == BOUNDARY_TRACE && history.value.records == disk.document.records &&
                                ownSnapshot().status == RootPilotStatus.EXECUTING.name) {
                                check(paused.pid == Process.myPid() && paused.boundary == boundary &&
                                    paused.deadline - SystemClock.elapsedRealtime() > killWindow + 1_000, Reason.BOUNDARY_INVALID)
                                boundaryReceipt = paused
                                boundaryReady = true
                                break
                            }
                        }
                    }
                    if (boundary == null && state.status == RootPilotStatus.WAITING_SCREEN && value.getBoolean("matches") &&
                        value.getInt("commitCalls") == 1 && ime.currentId() == base.originalIme && !exists(imeFile)) {
                        val disk = diskHistory()
                        val record = disk.document.records.singleOrNull { it.id == runId }
                        if (record?.events?.map(::shape) == SUBMITTED_TRACE && history.value.records == disk.document.records &&
                            ownSnapshot().status == RootPilotStatus.WAITING_SCREEN.name) break
                    }
                    delay(10)
                }
            }
            if (boundary == null) submittedReady = true
        }

        private suspend fun checkPrepared(base: Baseline, receipt: Ready) {
            checkMainUnlocked()
            checkFixture(base, foreground = true)
            if (submittedReady) {
                // The controller retains the preview value while executing; only the approval token is cleared.
                // The exact trace and snapshot below, not that display field, bind this post-submit state.
                check(state.running && state.status == RootPilotStatus.WAITING_SCREEN && state.step == 0 &&
                    state.config == testConfig(), Reason.WAITING_IDENTITY)
                check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
                checkImeFileAbsent()
            } else {
                if (boundaryReady) {
                    val paused = TypeCommitRecoveryGate.receipt(id) ?: fail(Reason.BOUNDARY_INVALID)
                    check(paused == boundaryReceipt && paused.boundary.name == receipt.boundary &&
                        paused.reachedAt == receipt.boundaryReachedAt && paused.deadline == receipt.boundaryDeadline &&
                        state.running && state.status == RootPilotStatus.EXECUTING && state.step == 0 &&
                        state.config == testConfig(), Reason.BOUNDARY_INVALID)
                } else checkPendingType()
                check(ime.currentId() == ime.ownId && fixed(Reason.IME_RECOVERY) { FileImeRestoreStore(context).read() } ==
                    base.originalIme && !imeSidecarsExist(), Reason.IME_RECOVERY)
                check(fileDigest(imeFile, Reason.IME_RECOVERY) == receipt.imeRecoveryDigest, Reason.IME_RECOVERY)
            }
            val snapshot = ownSnapshot()
            check(snapshot.status == expectedStatus.name && snapshot.step == 0 &&
                snapshot.actionSummary == expectedSummary && snapshot.allowScreenUpload,
                Reason.SNAPSHOT_IDENTITY)
            checkSnapshotDigest(receipt)
            checkUnchangedFiles(base)
            val disk = diskHistory()
            val record = disk.document.records.singleOrNull { it.id == receipt.runId } ?: fail(Reason.RUN_IDENTITY)
            checkCompleteHistory(record, RunHistoryStatus.RUNNING)
            check(history.value.error == null && history.value.records == disk.document.records &&
                disk.digest == receipt.historyDigest, Reason.HISTORY_CHANGED)
            checkLogPrefix(receipt.runId, complete = true)
        }

        suspend fun verify() {
            checkReceiptValid()
            check(!exists(directory.resolve("claimed")) && !exists(directory.resolve("completed")), Reason.RECEIPT_REUSED)
            val base = fixed(Reason.RECEIPT_IO) {
                JSON.decodeFromString<Baseline>(readBounded(directory.resolve("baseline.json"), MAX_FILE).decodeToString())
            }
            val receipt = fixed(Reason.RECEIPT_IO) {
                JSON.decodeFromString<Ready>(readBounded(directory.resolve("ready.json"), MAX_FILE).decodeToString())
            }
            check(receipt.submitted == submitted && receipt.boundary == boundary?.name, Reason.RECEIPT_ID)
            submittedReady = submitted
            boundaryReady = boundary != null
            if (boundaryReady) check(receipt.boundaryReachedAt != null && receipt.boundaryDeadline != null &&
                receipt.boundaryReachedAt <= receipt.readyAt && receipt.boundaryDeadline - receipt.readyAt > killWindow,
                Reason.BOUNDARY_INVALID)
            else check(receipt.boundaryReachedAt == null && receipt.boundaryDeadline == null, Reason.RECEIPT_ID)
            check(base.id == id && receipt.id == id && validId(receipt.runId) &&
                validId(base.fixtureInstanceId) && receipt.fixtureInstanceId == base.fixtureInstanceId &&
                receipt.fixturePid == base.fixturePid && base.fixturePid > 0 && base.fixturePid != base.mainPid &&
                receipt.mainPid == base.mainPid && receipt.appUid == base.appUid && base.appUid == Process.myUid() &&
                receipt.bootCount == base.bootCount &&
                listOf(receipt.snapshotDigest, receipt.historyDigest, receipt.interruptedHistoryDigest,
                    receipt.imeRecoveryDigest, receipt.baselineDigest, base.apiDigest).all(::validDigest) &&
                (base.allowlistDigest == null || validDigest(base.allowlistDigest)), Reason.RECEIPT_ID)
            check(fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO) == receipt.baselineDigest,
                Reason.RECEIPT_CHANGED)
            check(base.mainPid > 0 && base.mainPid != Process.myPid() && pidAbsent(base.mainPid), Reason.MAIN_NOT_DEAD)
            check(bootCount() == receipt.bootCount && SystemClock.elapsedRealtime() - receipt.readyAt in 0..VERIFY_WINDOW,
                Reason.RECEIPT_EXPIRED)
            // Claim before assertions: an unsuccessful verification cannot later be replayed as PASS.
            marker("claimed")
            claimed = true
            baseline = base
            ready = receipt
            runId = receipt.runId
            fixed(Reason.FIXTURE_IDENTITY) { fixture.verifyIdentity() }
            checkUnchangedFiles(base)
            loadedApi = savedApi()
            checkSnapshotDigest(receipt)
            withTimeout(8_000) {
                while (true) {
                    check(!state.running && state.logs.isEmpty() && state.pendingAction == null && state.frame == null,
                        Reason.RECOVERY_STATE)
                    check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
                    checkFixture(base, foreground = false)
                    checkMainUnlocked()
                    if (state.status == RootPilotStatus.RECOVERY_REQUIRED && ime.currentId() == base.originalIme &&
                        !exists(imeFile) && history.value.records == diskHistory().document.records &&
                        history.value.records.singleOrNull { it.id == receipt.runId }?.status == RunHistoryStatus.INTERRUPTED) break
                    delay(POLL)
                }
            }
            checkRecovered(base, receipt)
            val before = state
            check(before.errorMessage != UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
            RootPilotService.send(context, RootPilotService.ACTION_RECOVER)
            withTimeout(5_000) {
                while (state.errorMessage != UPLOAD_REJECTION) {
                    checkRecovered(base, receipt)
                    delay(POLL)
                }
            }
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkRecovered(base, receipt)
                check(state.errorMessage == UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
                delay(POLL)
            }
            verified = true
        }

        private suspend fun checkRecovered(base: Baseline, receipt: Ready) {
            checkReceiptValid()
            checkMainUnlocked()
            checkFixture(base, foreground = false)
            val current = state
            check(!current.running && current.status == RootPilotStatus.RECOVERY_REQUIRED && current.step == 0 &&
                current.pendingAction == null && current.lastAction == null && current.frame == null &&
                current.savedTodos.isEmpty() && !current.modelReportedResult && current.logs.isEmpty(), Reason.RECOVERY_STATE)
            check(current.apiConfigured && !current.config.allowScreenUpload &&
                current.config == testConfig().copy(allowScreenUpload = false), Reason.UPLOAD_CONSENT)
            check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
            checkImeFileAbsent()
            checkSnapshotDigest(receipt)
            checkUnchangedFiles(base)
            val disk = diskHistory()
            val record = disk.document.records.singleOrNull { it.id == receipt.runId } ?: fail(Reason.RUN_IDENTITY)
            checkCompleteHistory(record, RunHistoryStatus.INTERRUPTED)
            check(history.value.error == null && history.value.records == disk.document.records &&
                disk.digest == receipt.interruptedHistoryDigest, Reason.HISTORY_CHANGED)
        }

        fun invalidate(failure: Reason) {
            if (!directoryOwned) return
            try {
                write(directory.resolve("invalidated"), failure.name.encodeToByteArray())
                check(readBounded(directory.resolve("invalidated"), MAX_FILE).decodeToString() == failure.name,
                    Reason.RECEIPT_INVALIDATION_FAILED)
            } catch (_: Throwable) {
                // A durable rename also revokes a ready receipt when allocating a marker fails.
                check(exists(directory.resolve("ready.json")), Reason.RECEIPT_INVALIDATION_FAILED)
                fixed(Reason.RECEIPT_INVALIDATION_FAILED) {
                    Files.move(directory.resolve("ready.json").toPath(), directory.resolve("invalidated-ready.json").toPath(),
                        StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(directory)
                }
            }
        }

        suspend fun cleanup(prepare: Boolean) {
            val base = baseline ?: return
            if (!prepare && (!claimed || !verified)) return
            var failed: Reason? = null
            suspend fun attempt(block: suspend () -> Unit): Boolean = try { block(); true } catch (error: Throwable) {
                if (failed == null) failed = reason(error)
                false
            }
            val stopped = attempt {
                if (prepare) stopOwnedPrepare()
                else {
                    val receipt = ready ?: fail(Reason.RECEIPT_ID)
                    checkRecovered(base, receipt)
                    RootPilotService.send(context, RootPilotService.ACTION_DISCARD_RECOVERY)
                    withTimeout(5_000) {
                        while (state.status != RootPilotStatus.IDLE || exists(snapshotFile)) {
                            check(!state.running && state.config == testConfig().copy(allowScreenUpload = false),
                                Reason.DISCARD_FAILED)
                            delay(POLL)
                        }
                    }
                    check(!state.running && state.pendingAction == null && state.frame == null && state.logs.isEmpty(),
                        Reason.DISCARD_FAILED)
                }
            }
            // Independent readbacks still run if STOP or one resource's restoration failed.
            attempt { check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED); checkImeFileAbsent() }
            attempt { checkMainUnlocked() }
            attempt { checkFixture(base, foreground = false, allowApproved = prepare && approved) }
            attempt { checkUnchangedFiles(base) }
            if (stopped) attempt {
                check(!state.running && !exists(snapshotFile) && state.pendingAction == null, Reason.CLEANUP_UNCONFIRMED)
                checkUnchangedFiles(base)
                val expected = if (startSent || claimed) testConfig().copy(allowScreenUpload = prepare)
                    else savedApi().applyTo(originalConfig(base))
                check(!configChanged && !claimed || state.config == expected, Reason.ENVIRONMENT_CHANGED)
                if (configChanged || claimed) {
                    val original = originalConfig(base)
                    RootPilotService.updateConfig(original)
                    RootPilotService.updateApiConfig(if (base.apiConfigured) savedApi() else null)
                    check(state.config == original && state.apiConfigured == base.apiConfigured, Reason.RESTORE_FAILED)
                }
                restored = true
            }
            if (failed != null) fail(failed!!)
            if (!prepare) {
                check(restored && verified, Reason.CLEANUP_UNCONFIRMED)
                marker("completed")
            }
        }

        private suspend fun stopOwnedPrepare() {
            if (!startSent) return
            val owned = try {
                val owned = bindRun() ?: fail(Reason.CLEANUP_UNCONFIRMED)
                check(state.config == testConfig() && logs().lastOrNull()?.runId == owned, Reason.CLEANUP_UNCONFIRMED)
                if (exists(snapshotFile)) ownSnapshot()
                owned
            } finally {
                // Cancel only this receipt, even if ownership checks reject sending STOP.
                // Keep snapshot validation first: cancellation may advance the run and clear it.
                if (boundary != null) TypeCommitRecoveryGate.cancel(id)
            }
            if (state.running) RootPilotService.send(context, RootPilotService.ACTION_STOP)
            withTimeout(20_000) {
                while (true) {
                    check(state.config == testConfig() && bindRun() == owned, Reason.CLEANUP_UNCONFIRMED)
                    val disk = diskHistory().document.records
                    val record = disk.singleOrNull { it.id == owned }
                    if (!state.running && state.status in TERMINAL_STATES && !exists(snapshotFile) &&
                        history.value.records == disk && record != null && record.status != RunHistoryStatus.RUNNING &&
                        record.events.any { it.event == TraceEvent.RUN_END }) break
                    delay(POLL)
                }
            }
        }

        private fun originalConfig(base: Baseline) =
            (if (base.apiConfigured) savedApi() else RootPilotApiConfig()).applyTo(RootPilotConfig(
                manualConfirmation = base.manualConfirmation, allowScreenUpload = base.allowScreenUpload,
            ))

        private fun testConfig() = (loadedApi ?: fail(Reason.SAVED_CONFIG)).applyTo(RootPilotConfig(
            task = "RootPilot 固定输入验收 $id。当前专用测试页的第一个输入框已经聚焦。只提出一次输入“$EXPECTED”的 type 动作，等待人工确认，不重复输入。不调用工具，不点击、不滑动、不按键、不等待、不启动应用、不保存、不发送，不提前报告完成。",
            manualConfirmation = true, allowScreenUpload = true,
        ))

        private fun ownSnapshot(): RootPilotRunSnapshot {
            val snapshot = fixed(Reason.SNAPSHOT_IDENTITY) {
                JSON.decodeFromString<RootPilotRunSnapshot>(readBounded(snapshotFile, MAX_FILE).decodeToString())
            }
            val config = testConfig()
            check(snapshot.task == config.task && snapshot.baseUrl == config.baseUrl && snapshot.model == config.model &&
                snapshot.manualConfirmation && snapshot.executionDisplay == ExecutionDisplay.MAIN &&
                snapshot.virtualDisplayStartPackage.isEmpty(), Reason.SNAPSHOT_IDENTITY)
            return snapshot
        }

        private fun checkSnapshotDigest(receipt: Ready) {
            check(fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED) == receipt.snapshotDigest, Reason.SNAPSHOT_CHANGED)
            val snapshot = ownSnapshot()
            check(snapshot.status == expectedStatus.name && snapshot.step == 0 &&
                snapshot.allowScreenUpload && snapshot.actionSummary == expectedSummary, Reason.SNAPSHOT_IDENTITY)
        }

        private suspend fun fixtureState(): Bundle = try { fixture.call("state") }
        catch (_: Throwable) { fail(Reason.FIXTURE_STATE) }

        private suspend fun checkFixture(base: Baseline, foreground: Boolean, allowApproved: Boolean = false) {
            val value = fixtureState()
            check(value.getString("instanceId") == base.fixtureInstanceId && value.getInt("processId") == base.fixturePid,
                Reason.FIXTURE_IDENTITY)
            val once = !value.getBoolean("empty") && value.getBoolean("matches") && value.getInt("commitCalls", -1) == 1
            val untouched = value.getBoolean("empty") && !value.getBoolean("matches")
            val expectedContent = if (allowApproved) once || untouched && value.getInt("commitCalls", -1) == 0
                else if (submittedReady || boundaryReady && boundary == CommitBoundaryGate.Boundary.AFTER_SUBMIT) once
                else untouched && (!boundaryReady || value.getInt("commitCalls", -1) == 0)
            check(expectedContent && value.getInt("fieldId") == android.R.id.edit &&
                (!foreground || value.getBoolean("ready")), Reason.FIXTURE_STATE)
        }

        private fun checkMainUnlocked() {
            val displays = context.getSystemService(DisplayManager::class.java)?.displays ?: fail(Reason.DISPLAY_IDENTITY)
            check(displays.size == 1 && displays.single().displayId == Display.DEFAULT_DISPLAY && displays.single().isValid,
                Reason.DISPLAY_IDENTITY)
            check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
                context.getSystemService(PowerManager::class.java)?.isInteractive == true, Reason.LOCKED)
        }

        private fun checkImeFileAbsent() {
            check(!exists(imeFile) && !imeSidecarsExist() &&
                fixed(Reason.IME_RECOVERY) { FileImeRestoreStore(context).read() } == null, Reason.IME_RECOVERY)
        }

        private fun imeSidecarsExist() = listOf(".bak", ".new").any { exists(File(imeFile.path + it)) }
        private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        private fun savedApi() = fixed(Reason.SAVED_CONFIG) {
            (RootPilotApiConfigStore.create(context).read() ?: fail(Reason.SAVED_CONFIG)).also {
                check(it.apiKey.isNotBlank() && it.model.isNotBlank() &&
                    it.baseUrl.trimEnd('/') == "https://api.deepseek.com", Reason.SAVED_CONFIG)
            }
        }

        private fun checkUnchangedFiles(base: Baseline) {
            check(fileDigest(apiFile, Reason.API_CHANGED) == base.apiDigest, Reason.API_CHANGED)
            check(optionalDigest(allowlistFile, Reason.ALLOWLIST_CHANGED) == base.allowlistDigest, Reason.ALLOWLIST_CHANGED)
        }

        private fun diskHistory(): DiskHistory = fixed(Reason.HISTORY_UNAVAILABLE) {
            if (!exists(historyFile)) return@fixed DiskHistory(HistoryDocument(), digest(byteArrayOf()))
            val bytes = readBounded(historyFile, 8 * 1024 * 1024)
            val document = JSON.decodeFromString<HistoryDocument>(bytes.decodeToString())
            check(document.version == 1 && document.records.size <= 50 &&
                document.records.map { it.id }.distinct().size == document.records.size, Reason.HISTORY_UNAVAILABLE)
            DiskHistory(document, digest(bytes))
        }

        private fun checkCompleteHistory(record: RunHistoryRecord, status: RunHistoryStatus) {
            check(record.status == status && !record.eventsTruncated && record.stepCount == 1 &&
                record.events.all { it.runId == record.id && it.reason == TraceReason.NONE } &&
                record.events.map(::shape) == expectedTrace && record.reason == TraceReason.NONE,
                Reason.UNEXPECTED_ACTIVITY)
            if (!submittedReady && !boundaryReady) check(record.events.none { it.stage == TraceStage.EXECUTION || it.event == TraceEvent.CONFIRMED } &&
                record.events.last().let { it.event == TraceEvent.WAITING && it.actionType == TraceActionType.TYPE &&
                    it.status == TraceStatus.WAITING }, Reason.UNEXPECTED_ACTIVITY)
        }

        private fun logs(): List<LogEntry> = fixed(Reason.TRACE_UNAVAILABLE) {
            state.logs.map { line ->
                val value = JSON.parseToJsonElement(line).jsonObject
                fun field(key: String) = value[key]?.jsonPrimitive?.content ?: fail(Reason.TRACE_UNAVAILABLE)
                LogEntry(field("runId"), listOf("stage", "event", "status", "actionType", "step")
                    .joinToString("/") { field(it).uppercase(Locale.ROOT) }, field("reasonCode"))
            }
        }

        private fun checkLogPrefix(owned: String, complete: Boolean = false) {
            val all = logs()
            val start = all.indexOfFirst { it.runId == owned }
            check(start >= 0, Reason.TRACE_UNAVAILABLE)
            val current = all.drop(start)
            check(current.all { it.runId == owned && it.reason == "none" } && current.size <= expectedTrace.size &&
                current.map { it.shape } == expectedTrace.take(current.size) &&
                (!complete || current.size == expectedTrace.size), Reason.UNEXPECTED_ACTIVITY)
        }

        private fun checkReceiptValid() {
            check(root.isDirectory && directory.isDirectory && !Files.isSymbolicLink(root.toPath()) &&
                !Files.isSymbolicLink(directory.toPath()) && !exists(directory.resolve("invalidated")) &&
                !exists(directory.resolve("invalidated-ready.json")) && directory.resolve("ready.json").isFile,
                Reason.RECEIPT_INVALIDATED)
            check(!exists(directory.resolve("completed")) && (claimed || !exists(directory.resolve("claimed"))),
                Reason.RECEIPT_REUSED)
            ready?.let { receipt ->
                check(readBounded(directory.resolve("ready.json"), MAX_FILE).contentEquals(
                    JSON.encodeToString(receipt).encodeToByteArray()), Reason.RECEIPT_CHANGED)
                check(fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO) == receipt.baselineDigest,
                    Reason.RECEIPT_CHANGED)
            }
        }

        private fun marker(name: String) = fixed(Reason.RECEIPT_REUSED) {
            val fd = Os.open(directory.resolve(name).path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384)
            try { check(Os.write(fd, byteArrayOf(1), 0, 1) == 1, Reason.RECEIPT_IO); Os.fsync(fd) }
            finally { Os.close(fd) }
            syncDirectory(directory)
        }

        fun report(stage: String, failure: Reason?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("typeRecoveryStage", stage)
                putString("reasonCode", failure?.name ?: "NONE")
                putString("cleanupReasonCode", cleanupReason?.name ?: "NONE")
                if (::id.isInitialized && validId(id)) putString("recoveryReceiptId", id)
                putInt("mainPid", Process.myPid())
                putBoolean("passed", verified && restored && failure == null && cleanupReason == null)
                putBoolean("baselineRestored", restored)
                putBoolean("verifyClaimed", claimed)
                ready?.let {
                    putString("runId", it.runId)
                    putLong("readyAt", it.readyAt)
                    it.boundary?.let { boundary -> putString("commitBoundary", boundary) }
                    it.boundaryDeadline?.let { deadline -> putLong("boundaryDeadline", deadline) }
                }
                if (stage == "ready_for_main_process_kill") putLong("hostKillWindowMs", killWindow)
                if (verified && failure == null && cleanupReason == null) {
                    putInt("postReopenModelRequests", 0)
                    putInt("postReopenScreenshots", 0)
                    putInt("postReopenExecutions", 0)
                    putInt("confirmedActions", if (submitted || boundary != null) 1 else 0)
                    if (submitted || boundary != null) putInt("targetCommitCalls",
                        if (boundary == CommitBoundaryGate.Boundary.BEFORE_SUBMIT) 0 else 1)
                }
            })
        }
    }

    /** Own-component lifecycle and token digest only; no plaintext token is retained or reported. */
    private class ManualNotificationActivityObservation : Application.ActivityLifecycleCallbacks {
        val evidence = NotificationManualConfirmationEvidence()
        @Volatile var approvalTokenDigest: String? = null
            private set
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            evidence.onCreated(activity, activity is RootPilotNotificationActivity, SystemClock.elapsedRealtime())
            if (activity is RootPilotNotificationActivity) {
                approvalTokenDigest = runCatching {
                    activity.intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN)?.takeIf { it.isNotBlank() }
                        ?.let { digest(it.toByteArray(Charsets.UTF_8)) }
                }.getOrNull()
            }
        }
        override fun onActivityStarted(activity: Activity) {
            if (activity !is RootPilotNotificationActivity) evidence.onOtherActivityStarted(SystemClock.elapsedRealtime())
        }
        override fun onActivityResumed(activity: Activity) {
            evidence.onResumed(activity, activity is RootPilotNotificationActivity, SystemClock.elapsedRealtime())
        }
        override fun onActivityDestroyed(activity: Activity) {
            evidence.onDestroyed(activity, activity is RootPilotNotificationActivity, SystemClock.elapsedRealtime())
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    /** Observe only this in-process component; never inspect notification extras or system events. */
    private class NotificationActivityObservation : Application.ActivityLifecycleCallbacks {
        @Volatile var created = 0
        @Volatile var resumed = 0
        @Volatile var destroyed = 0
        private var instance: Activity? = null
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is RootPilotNotificationActivity) { created++; instance = activity }
        }
        override fun onActivityResumed(activity: Activity) { if (activity === instance) resumed++ }
        override fun onActivityDestroyed(activity: Activity) { if (activity === instance) destroyed++ }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    @Serializable
    private data class Baseline(
        val id: String, val mainPid: Int, val appUid: Int, val bootCount: Int, val apiConfigured: Boolean,
        val manualConfirmation: Boolean, val allowScreenUpload: Boolean, val originalIme: String,
        val apiDigest: String, val allowlistDigest: String?, val fixtureInstanceId: String, val fixturePid: Int,
    )

    @Serializable
    private data class Ready(
        val id: String, val runId: String, val mainPid: Int, val appUid: Int, val bootCount: Int, val readyAt: Long,
        val snapshotDigest: String, val historyDigest: String, val interruptedHistoryDigest: String,
        val imeRecoveryDigest: String, val fixtureInstanceId: String, val fixturePid: Int, val baselineDigest: String,
        val submitted: Boolean = false,
        val boundary: String? = null, val boundaryReachedAt: Long? = null, val boundaryDeadline: Long? = null,
    )

    @Serializable
    private data class HistoryDocument(val version: Int = 1, val records: List<RunHistoryRecord> = emptyList())
    private data class DiskHistory(val document: HistoryDocument, val digest: String)
    private data class LogEntry(val runId: String, val shape: String, val reason: String)
    private class RecoveryFailure(val reason: Reason) : AssertionError(reason.name)
    private enum class Reason {
        TARGET_PROCESS, NO_RESTART_REQUIRED, RECEIPT_ID, BASELINE_ACK_REQUIRED, SERVICE_BUSY, EXISTING_RECOVERY,
        BASELINE_UNRESTORABLE, DISPLAY_IDENTITY, LOCKED, OVERLAY_PERMISSION, FIXTURE_IDENTITY, FIXTURE_STATE,
        FIXTURE_FOREGROUND, IME_UNAVAILABLE, IME_RECOVERY, IME_NOT_RESTORED, SAVED_CONFIG, BOOT_IDENTITY,
        RECEIPT_IO, RECEIPT_REUSED, RECEIPT_CHANGED, UNFINISHED_RECEIPT, HISTORY_UNAVAILABLE, HISTORY_CAPACITY,
        ENVIRONMENT_CHANGED, RUN_IDENTITY, TYPE_IDENTITY, EDITOR_IDENTITY, WAITING_IDENTITY, UNEXPECTED_ACTIVITY,
        SNAPSHOT_IDENTITY, SNAPSHOT_CHANGED, API_CHANGED, ALLOWLIST_CHANGED, TRACE_UNAVAILABLE, HISTORY_CHANGED,
        HOST_KILL_TIMEOUT, RECEIPT_INVALIDATION_FAILED, RECEIPT_INVALIDATED, MAIN_NOT_DEAD, PID_QUERY,
        RECEIPT_EXPIRED, RECOVERY_STATE, UPLOAD_CONSENT, RECOVER_NOT_REJECTED, CLEANUP_UNCONFIRMED,
        DISCARD_FAILED, RESTORE_FAILED, REPORT_FAILED, APPROVAL_UNAVAILABLE, BOUNDARY_INVALID, TIMEOUT, UNEXPECTED,
        SERVICE_OBSERVATION, SERVICE_STOP_REJECTED,
        NOTIFICATION_UNAVAILABLE, NOTIFICATION_UI_UNAVAILABLE, NOTIFICATION_ACTIVITY_UNOBSERVED,
        NOTIFICATION_EXECUTION_FAILED, OLD_CONFIRMATION_ACCEPTED,
        MANUAL_NOTIFICATION_EARLY_ACTIVITY, MANUAL_NOTIFICATION_TIMEOUT, MANUAL_NOTIFICATION_AUTOMATIC_APPROVAL,
        MANUAL_NOTIFICATION_STATE, MANUAL_NOTIFICATION_WRONG_ENTRY,
        NOTIFICATION_MANAGER_ACCESS, NOTIFICATION_QUERY, NOTIFICATION_OBSERVER_REGISTER,
        NOTIFICATION_ACCESSIBILITY_BASELINE, NOTIFICATION_UI_CONFIGURATOR, NOTIFICATION_UI_AUTOMATION,
        NOTIFICATION_UI_AUTOMATION_RELEASE, NOTIFICATION_UI_CONFIGURATOR_RESTORE, NOTIFICATION_ACCESSIBILITY_RESTORE,
        NOTIFICATION_UI_DEVICE, NOTIFICATION_UI_OPEN, NOTIFICATION_UI_ROW_QUERY,
        NOTIFICATION_UI_EXPAND, NOTIFICATION_UI_ACTION_QUERY, NOTIFICATION_UI_CLICK,
        NOTIFICATION_UI_OPEN_REJECTED, NOTIFICATION_UI_TITLE_WAIT_TIMEOUT,
        NOTIFICATION_UI_TITLE_MISSING, NOTIFICATION_UI_TITLE_AMBIGUOUS,
        NOTIFICATION_UI_ROW_ANCESTOR_MISSING, NOTIFICATION_UI_ROW_ANCESTOR_LIMIT,
        NOTIFICATION_UI_APP_LABEL_EMPTY, NOTIFICATION_UI_APP_LABEL_MISSING, NOTIFICATION_UI_APP_LABEL_AMBIGUOUS,
        NOTIFICATION_UI_ROW_TITLE_MISSING, NOTIFICATION_UI_ROW_TITLE_AMBIGUOUS,
        NOTIFICATION_UI_ROW_STRUCTURE, NOTIFICATION_UI_ROW_CHANGED, NOTIFICATION_UI_EXPAND_CHANGED,
        NOTIFICATION_UI_EXPAND_MISSING, NOTIFICATION_UI_EXPAND_AMBIGUOUS,
        NOTIFICATION_UI_EXPAND_DISABLED, NOTIFICATION_UI_EXPAND_NOT_CLICKABLE, NOTIFICATION_UI_EXPAND_WAIT_TIMEOUT,
        NOTIFICATION_UI_ACTION_MISSING, NOTIFICATION_UI_ACTION_AMBIGUOUS,
        NOTIFICATION_UI_ACTION_DISABLED, NOTIFICATION_UI_ACTION_NOT_CLICKABLE,
    }

    private companion object {
        const val TARGET = "com.example.agent"
        const val FIXTURE = "com.example.rootpilot.fixture"
        const val EXPECTED = "执行模式验收通过"
        const val SYSTEM_UI = "com.android.systemui"
        const val NOTIFICATION_TITLE = "RootPilot · 第 1 步"
        const val NOTIFICATION_ACTION = "确认当前动作"
        const val NOTIFICATION_CHANNEL = "rootpilot_agent"
        const val NOTIFICATION_ID = 2001
        const val UPLOAD_REJECTION = "发送截图前请先打开上传确认"
        const val MAX_FILE = 64 * 1024
        const val POLL = 50L
        const val HOST_KILL_WINDOW = 45_000L
        const val VERIFY_WINDOW = 120_000L
        const val QUIET_WINDOW = 1_000L
        val JSON = Json { encodeDefaults = true }
        val TERMINAL_STATES = setOf(RootPilotStatus.STOPPED, RootPilotStatus.FAILED, RootPilotStatus.COMPLETED)
        val IDLE_STATES = TERMINAL_STATES + RootPilotStatus.IDLE
        val PREPARING_STATES = setOf(RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.WAITING_CONFIRMATION)
        val EXPECTED_TRACE = listOf(
            "RUN/RUN_START/STARTED/NONE/-1", "SCREENSHOT/START/STARTED/NONE/0", "SCREENSHOT/RESULT/SUCCESS/NONE/0",
            "MODEL/START/STARTED/NONE/0", "MODEL/RESULT/SUCCESS/NONE/0", "APPROVAL/WAITING/WAITING/TYPE/0",
        )

        val BOUNDARY_TRACE = EXPECTED_TRACE + listOf(
            "APPROVAL/CONFIRMED/SUCCESS/TYPE/0", "EXECUTION/START/STARTED/TYPE/0",
        )

        val SUBMITTED_TRACE = EXPECTED_TRACE + listOf(
            "APPROVAL/CONFIRMED/SUCCESS/TYPE/0", "EXECUTION/START/STARTED/TYPE/0", "EXECUTION/RESULT/SUCCESS/TYPE/0",
        )

        val NOTIFICATION_TRACE = SUBMITTED_TRACE + "SETTLE/RUN_END/SUCCESS/TYPE/0"

        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }

        fun fail(reason: Reason): Nothing = throw RecoveryFailure(reason)
        fun check(value: Boolean, reason: Reason) { if (!value) fail(reason) }
        fun reason(error: Throwable) = when (error) {
            is RecoveryFailure -> error.reason
            is TimeoutCancellationException -> Reason.TIMEOUT
            else -> Reason.UNEXPECTED
        }
        inline fun <T> fixed(reason: Reason, block: () -> T): T = try { block() }
        catch (error: RecoveryFailure) { throw error }
        catch (_: Throwable) { fail(reason) }
        fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        fun validId(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun validDigest(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        fun shape(event: RunTraceEvent) = "${event.stage}/${event.event}/${event.status}/${event.actionType}/${event.step}"
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun fileDigest(file: File, reason: Reason) = fixed(reason) { digest(readBounded(file, MAX_FILE)) }
        fun optionalDigest(file: File, reason: Reason) = if (exists(file)) fileDigest(file, reason) else null
        fun readBounded(file: File, limit: Int): ByteArray = fixed(Reason.RECEIPT_IO) {
            check(file.isFile && !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT_IO)
            file.inputStream().use { it.readNBytes(limit + 1) }.also { check(it.size <= limit, Reason.RECEIPT_IO) }
        }
        fun pidAbsent(pid: Int): Boolean = try {
            // Signal zero only probes existence; it cannot terminate the old process.
            Os.kill(pid, 0)
            false
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ESRCH -> true
                OsConstants.EPERM -> false
                else -> fail(Reason.PID_QUERY)
            }
        }
        fun write(file: File, bytes: ByteArray) = fixed(Reason.RECEIPT_IO) {
            val parent = file.parentFile ?: fail(Reason.RECEIPT_IO)
            val temporary = Files.createTempFile(parent.toPath(), "type-recovery-", ".tmp")
            try {
                temporary.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(parent)
            } finally { Files.deleteIfExists(temporary) }
        }
        fun syncDirectory(directory: File) {
            check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()), Reason.RECEIPT_IO)
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
