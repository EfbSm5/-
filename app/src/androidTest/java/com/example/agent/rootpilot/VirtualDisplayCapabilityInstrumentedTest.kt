package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootScreenObserver
import com.example.agent.rootpilot.root.DisplayRoutingRootExecutor
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotKey
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.screen.RootScreenshotProvider
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.sameTarget
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.information.AndroidUiTreeProvider
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplaySession
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in offline platform probe, not a production model/Service or physical-touch acceptance. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayCapabilityInstrumentedTest {
    @Test fun closesOnlyExplicitlyLaunchedMainDisplayFixtureWithoutNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("closeExplicitVirtualFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.resolveContentProvider("$PACKAGE.state", 0)?.packageName == PACKAGE &&
            context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) { "fixture_identity" }
        val uri = Uri.parse("content://$PACKAGE.state")
        var complete = false
        try {
            check(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null) { "service_busy" }
            val state = call(context, uri, "virtual_state")
            check(state.getInt("displayId", -1) == 0 && state.getBoolean("ready") &&
                state.getBoolean("empty") && state.getString("instance") != null) { "explicit_fixture_unavailable" }
            call(context, uri, "virtual_finish")
            withTimeout(5_000) {
                while (!call(context, uri, "virtual_state").isEmpty) delay(50)
            }
            complete = true
        } catch (_: Exception) {
            // Only fixed fixture state may leave this preparation step.
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("fixtureGone", complete); putBoolean("networkUsed", false)
            putString("preparationSource", "explicit_main_display_fixture_launch")
        })
        check(complete) { "fixture_preparation_unconfirmed" }
    }
    @Test fun platformDeliversSwipeKeysAndAsciiWithHiddenIme() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualCapabilityPlatformProbe") == "true")
        runBlocking { probe() }
    }

    @Test fun productionLoopConfirmsSwipeBackAndEnterWithoutNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualCapabilityLoopAcceptance") == "true")
        runBlocking { acceptLoop() }
    }

    @Test fun productionLoopReadsOnlyOwnedActivityStackWithoutNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualActivityStackAcceptance") == "true")
        runBlocking { acceptLoop(verifyStack = true) }
    }

    @Test fun twoIndependentSingleStepsUseFreshOwnedSessionsWithoutNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualSingleStepLoopAcceptance") == "true")
        val first = acceptLoop(singleStep = true)
        val second = acceptLoop(singleStep = true)
        check(first != second) { "single_step_session_reused" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("twoFreshSessions", true)
            putBoolean("networkUsed", false)
        })
    }

    @Test fun productionLoopTypesFixedUnicodeAndObservesCompletionWithoutNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualUnicodeLoopAcceptance") == "true")
        acceptLoop(unicode = true)
        Unit
    }

    @Test fun platformHomeTargetsOwnedDisplayWithoutChangingMain() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualHomePlatformProbe") == "true")
        runBlocking { probeHome() }
    }

    @Test fun platformSetsFixedUnicodeInEmptyOwnedEditorWithoutImeChange() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualUnicodePlatformProbe") == "true")
        runBlocking { probeUnicode() }
    }

    private suspend fun acceptLoop(verifyStack: Boolean = false, singleStep: Boolean = false,
        unicode: Boolean = false): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://$PACKAGE.state")
        val mainExecutor = RejectMain()
        val catalog = AppCatalog { listOf(APP) }
        val backend = DisplayRoutingRootExecutor(context, mainExecutor, catalog)
        val originalIme = setting(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalEnabledImes = enabledImes(context)
        val originalServices = setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        var mainBaseline: String? = null
        var started = false
        var stage = "preflight"
        var failure: String? = null
        val evidence = Bundle()
        val trace = RunTrace()
        var modelCalls = 0
        var confirmations = 0
        var executions = 0
        var stackChecks = 0
        var acceptedSession: String? = null
        try {
            withTimeout(60_000) {
                check(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null &&
                    RootPilotService.uiState.value.status in setOf(
                        com.example.agent.rootpilot.model.RootPilotStatus.IDLE,
                        com.example.agent.rootpilot.model.RootPilotStatus.COMPLETED,
                        com.example.agent.rootpilot.model.RootPilotStatus.FAILED,
                        com.example.agent.rootpilot.model.RootPilotStatus.STOPPED)) { "service_busy" }
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                    !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "recovery_pending" }
                check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) { "device_locked" }
                check(context.getSystemService(DisplayManager::class.java).displays.none {
                    it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX)
                }) { "existing_owned_display" }
                check(context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) {
                    "fixture_identity"
                }
                check(call(context, uri, "virtual_state").isEmpty) { "fixture_already_open" }
                mainBaseline = mainIdentity()
                val config = RootPilotConfig(task = "fixed offline acceptance", allowScreenUpload = true,
                    manualConfirmation = false, executionDisplay = ExecutionDisplay.VIRTUAL,
                    virtualDisplayStartPackage = PACKAGE)
                stage = "begin"
                started = true
                check(backend.beginRun(config) is RootExecutionResult.Success) { "display_create" }
                acceptedSession = backend.sessionIdentity ?: error("session_missing")
                evidence.putString("sessionId", acceptedSession)
                check(backend.execute(ExecutableRootAction.Type("RootPilot42")) is RootExecutionResult.Failure &&
                    !backend.supports(ExecutableRootAction.Key(RootPilotKey.HOME)) &&
                    backend.execute(ExecutableRootAction.Key(RootPilotKey.HOME)) is RootExecutionResult.Failure) { "unsupported_accepted" }
                var instance: String? = null
                var displayId = -1
                var completed = false
                val client = object : DeepSeekClient {
                    override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                        stage = "local_model_$modelCalls"
                        check(backend.validateSession()) { "session_invalid" }
                        val screen = backend.observeScreen()
                        check(screen.foregroundPackage == PACKAGE && screen.foregroundActivity == APP.activityName &&
                            screen.focusedPackage == PACKAGE && screen.keyboardVisible == false &&
                            screen.sessionId == backend.sessionIdentity && screen.displayId > 0) { "virtual_focus" }
                        val state = call(context, uri, "virtual_state")
                        check(state.getBoolean("ready") && state.getInt("displayId", -1) == screen.displayId) { "fixture_target" }
                        if (instance == null) {
                            instance = state.getString("instance") ?: error("fixture_instance")
                            displayId = screen.displayId
                        }
                        check(state.getString("instance") == instance && screen.displayId == displayId &&
                            mainIdentity() == mainBaseline) { "target_changed" }
                        if (verifyStack) {
                            stage = "owned_stack_$modelCalls"
                            val stack = backend.queryDeviceInfo(DeviceInfoTool.ACTIVITY_STACK, screen)
                            val data = stack.data ?: error("owned_stack_unavailable")
                            check(stack.unavailable == null && data["display_id"]?.jsonPrimitive?.content == displayId.toString()) {
                                "stack_display_mismatch"
                            }
                            val entries = data["activities"]?.jsonArray ?: error("stack_entries_unavailable")
                            check(entries.size == 1 && entries.single().jsonObject["package_name"]?.jsonPrimitive?.content == PACKAGE &&
                                entries.single().jsonObject["activity"]?.jsonPrimitive?.content == APP.activityName &&
                                entries.single().jsonObject["top_resumed"]?.jsonPrimitive?.content == "true") { "stack_scope_mismatch" }
                            check(backend.queryDeviceInfo(DeviceInfoTool.ACTIVITY_STACK,
                                screen.copy(sessionId = "wrong-session")).unavailable == DeviceInfoUnavailable.TARGET_NOT_READY &&
                                backend.queryDeviceInfo(DeviceInfoTool.ACTIVITY_STACK,
                                    screen.copy(displayId = 0, sessionId = null)).unavailable == DeviceInfoUnavailable.TARGET_NOT_READY) {
                                "stack_session_guard_failed"
                            }
                            stackChecks++
                        }
                        val response = if (unicode) when (modelCalls++) {
                            0 -> {
                                check(state.getBoolean("empty") && state.getBoolean("editorFocused")) { "fixture_initial" }
                                """{"action":"type","text":"中文🙂\n第二行","reason":"fixed"}"""
                            }
                            1 -> {
                                check(state.getBoolean("unicodeMatches") && state.getInt("scrollY") == 0 &&
                                    state.getInt("backInvoked") == 0 && state.getInt("enterDown") == 0 &&
                                    state.getInt("enterUp") == 0) { "unicode_not_observed" }
                                val tree = backend.queryDeviceInfo(DeviceInfoTool.UI_TREE, screen)
                                check(tree.unavailable == null && tree.data != null &&
                                    tree.data.toString().contains("中文🙂\\n第二行")) { "unicode_tree_missing" }
                                evidence.putBoolean("unicodeMatches", true)
                                evidence.putBoolean("inputTreeObserved", true)
                                var repeatApprovals = 0
                                check(backend.executeConfirmed(ExecutableRootAction.Type("a".repeat(129))) {
                                    repeatApprovals++; false
                                } is RootExecutionResult.Failure && repeatApprovals == 0) { "invalid_nonempty_input_not_rejected" }
                                check(call(context, uri, "virtual_state").getBoolean("unicodeMatches")) { "nonempty_input_changed" }
                                evidence.putBoolean("invalidNonemptyInputRejectedBeforeApproval", true)
                                """{"action":"finish","success":true,"message":"fixed"}"""
                            }
                            else -> error("unexpected_model_call")
                        } else when (modelCalls++) {
                            0 -> {
                                check(state.getBoolean("empty") && state.getInt("scrollY") == 0) { "fixture_initial" }
                                val x = state.getInt("x", -1); val from = state.getInt("fromY", -1); val to = state.getInt("toY", -1)
                                VirtualDisplayProtocol.swipe(x, from, x, to, 400)
                                fun normalized(pixel: Int, size: Int) = pixel * 1000 / (size - 1)
                                """{"action":"swipe","x1":${normalized(x,1080)},"y1":${normalized(from,1920)},"x2":${normalized(x,1080)},"y2":${normalized(to,1920)},"duration_ms":400,"reason":"fixed"}"""
                            }
                            1 -> {
                                check(state.getInt("scrollY") > 0) { "swipe_not_observed" }
                                evidence.putBoolean("swipeObserved", true)
                                """{"action":"key","key":"BACK","reason":"fixed"}"""
                            }
                            2 -> {
                                check(state.getInt("backInvoked") == 1) { "back_not_observed" }
                                evidence.putBoolean("backObserved", true)
                                """{"action":"key","key":"ENTER","reason":"fixed"}"""
                            }
                            3 -> {
                                check(state.getInt("enterDown") == 1 && state.getInt("enterUp") == 1) { "enter_not_observed" }
                                evidence.putBoolean("enterObserved", true)
                                """{"action":"finish","success":true,"message":"fixed"}"""
                            }
                            else -> error("unexpected_model_call")
                        }
                        return DeepSeekActionResult.Success(response)
                    }
                }
                stage = "loop"
                AgentLoop(RootScreenshotProvider(backend), client, backend, appCatalog = catalog)
                    .run(AgentLoopRequest(config, 4, singleStep = singleStep), trace) { event ->
                        if (event is AgentLoopEvent.AwaitingConfirmation) {
                            stage = "confirmation_$confirmations"
                            check(when (confirmations) {
                                0 -> event.action is RootPilotAction.OpenApp && event.action.packageName == PACKAGE
                                1 -> if (unicode) event.action is RootPilotAction.Type && event.action.text == UNICODE_SAMPLE
                                    else event.action is RootPilotAction.Swipe
                                2 -> event.action is RootPilotAction.Key && event.action.key == RootPilotKey.BACK
                                3 -> event.action is RootPilotAction.Key && event.action.key == RootPilotKey.ENTER
                                else -> false
                            }) { "unexpected_confirmation" }
                            confirmations++
                            event.approval.approve()
                        }
                        if (event is AgentLoopEvent.Executing) executions++
                        if (event is AgentLoopEvent.Completed) completed = true
                    }
                val expectedModels = if (singleStep) 1 else if (unicode) 2 else 4
                val expectedActions = if (singleStep || unicode) 2 else 4
                check(completed && modelCalls == expectedModels && confirmations == expectedActions &&
                    executions == expectedActions && mainExecutor.calls == 0) {
                    "loop_incomplete"
                }
                if (singleStep) {
                    val receipt = call(context, uri, "virtual_state")
                    check(receipt.getString("instance") == instance && receipt.getInt("displayId", -1) == displayId &&
                        receipt.getInt("scrollY", -1) > 0 && receipt.getBoolean("empty") &&
                        receipt.getInt("backInvoked", -1) == 0 && receipt.getInt("enterDown", -1) == 0 &&
                        receipt.getInt("enterUp", -1) == 0 && mainIdentity() == mainBaseline) { "single_step_receipt" }
                    evidence.putBoolean("onePlanningActionObserved", true)
                }
                evidence.putInt("confirmations", confirmations); evidence.putInt("executions", executions)
                evidence.putInt("localModelCalls", modelCalls); evidence.putInt("mainExecutorCalls", mainExecutor.calls)
                evidence.putBoolean("loopCompleted", true)
                if (verifyStack) {
                    check(stackChecks == 4) { "stack_acceptance_incomplete" }
                    evidence.putInt("ownedStackChecks", stackChecks)
                    evidence.putBoolean("wrongStackSessionRejected", true)
                    evidence.putBoolean("mainStackFallbackRejected", true)
                }
                stage = "complete"
            }
        } catch (_: Exception) {
            failure = "capability_loop_failed_$stage"
        } finally {
            withContext(NonCancellable) {
                val closed = if (started) backend.endRun() is RootExecutionResult.Success else true
                evidence.putBoolean("cleanupConfirmed", closed)
                if (closed && started && unicode) {
                    var releasedApprovals = 0
                    val rejected = try {
                        backend.executeConfirmed(ExecutableRootAction.Type(UNICODE_SAMPLE)) {
                            releasedApprovals++; false
                        } is RootExecutionResult.Failure && releasedApprovals == 0
                    } catch (_: Exception) { false }
                    evidence.putBoolean("releasedInputRejected", rejected)
                    if (!rejected) failure = "released_input_not_rejected"
                }
                if (started) {
                    evidence.putBoolean("fixtureGone", if (closed) {
                        try { call(context, uri, "virtual_state").isEmpty } catch (_: Exception) { false }
                    } else false)
                    evidence.putBoolean("mainActivityUnchangedAfterRelease", if (closed && mainBaseline != null) {
                        try { mainIdentity() == mainBaseline } catch (_: Exception) { false }
                    } else false)
                }
                evidence.putBoolean("imeUnchanged", setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme)
                evidence.putBoolean("enabledImesUnchanged", enabledImes(context) == originalEnabledImes)
                evidence.putBoolean("servicesUnchanged", setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices)
                evidence.putBoolean("networkUsed", false); evidence.putString("stage", stage)
                evidence.putInt("confirmations", confirmations); evidence.putInt("executions", executions)
                evidence.putInt("localModelCalls", modelCalls); evidence.putInt("mainExecutorCalls", mainExecutor.calls)
                evidence.putString("traceReason", trace.reason.name); evidence.putString("traceStage", trace.stage.name)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
                if (!closed || (started && (!evidence.getBoolean("fixtureGone") ||
                        !evidence.getBoolean("mainActivityUnchangedAfterRelease"))) || !evidence.getBoolean("imeUnchanged") ||
                    !evidence.getBoolean("enabledImesUnchanged") || !evidence.getBoolean("servicesUnchanged")) {
                    failure = "capability_loop_cleanup_unconfirmed"
                }
            }
        }
        check(failure == null) { failure ?: "capability_unknown" }
        return acceptedSession ?: error("session_missing")
    }

    private class RejectMain : RootExecutor {
        var calls = 0
        private fun reject(): Nothing { calls++; error("main_executor_forbidden") }
        override suspend fun checkRoot(): RootExecutionResult = reject()
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = reject()
        override suspend fun captureScreen(): RootScreenshotResult = reject()
        override suspend fun observeScreen(): com.example.agent.rootpilot.screen.ScreenObservation = reject()
        override fun cancel() = Unit
    }

    private suspend fun probeHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://$PACKAGE.state")
        val session = VirtualDisplaySession(context)
        val originalIme = setting(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalEnabledImes = enabledImes(context)
        val originalServices = setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        var baseline: String? = null
        var started = false
        var stage = "preflight"
        var failure: String? = null
        val evidence = Bundle()
        val preflightOnly = InstrumentationRegistry.getArguments().getString("virtualHomePreflightOnly") == "true"
        try {
            withTimeout(60_000) {
                stage = "service_idle"
                check(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null &&
                    RootPilotService.uiState.value.status in setOf(
                        com.example.agent.rootpilot.model.RootPilotStatus.IDLE,
                        com.example.agent.rootpilot.model.RootPilotStatus.COMPLETED,
                        com.example.agent.rootpilot.model.RootPilotStatus.FAILED,
                        com.example.agent.rootpilot.model.RootPilotStatus.STOPPED)) { "service_busy" }
                stage = "recovery_absent"
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                    !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "recovery_pending" }
                stage = "device_unlocked"
                check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) { "device_locked" }
                stage = "no_owned_display"
                check(context.getSystemService(DisplayManager::class.java).displays.none {
                    it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX)
                }) { "existing_owned_display" }
                stage = "fixture_identity"
                check(context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) {
                    "fixture_identity"
                }
                stage = "fixture_state_read"
                val existingFixture = call(context, uri, "virtual_state")
                evidence.putBoolean("fixturePresentBeforeRun", !existingFixture.isEmpty)
                if (!existingFixture.isEmpty) {
                    evidence.putInt("fixtureDisplayBeforeRun", existingFixture.getInt("displayId", -1))
                    evidence.putBoolean("fixtureReadyBeforeRun", existingFixture.getBoolean("ready"))
                }
                stage = "fixture_absent"
                check(existingFixture.isEmpty) { "fixture_already_open" }
                stage = "home_resolver"
                val homeTargets = listOf("HOME", "SECONDARY_HOME").mapNotNull { category ->
                    val lines = command("exec cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                        "-c android.intent.category.$category", 16 * 1024).lineSequence().map(String::trim)
                        .filter { it.contains('/') }.toList()
                    check(lines.size <= 1) { "home_component_ambiguous" }
                    val component = lines.singleOrNull() ?: return@mapNotNull null
                    val packageName = component.substringBefore('/')
                    val className = component.substringAfter('/').let { if (it.startsWith('.')) packageName + it else it }
                    VirtualDisplayProtocol.validateApp(packageName, className)
                    packageName to className
                }.toSet()
                check(homeTargets.isNotEmpty()) { "home_component_unavailable" }
                evidence.putInt("homeCandidates", homeTargets.size)
                stage = "main_identity"
                val main = mainIdentity().also { baseline = it }
                if (preflightOnly) {
                    stage = "preflight_complete"
                    return@withTimeout
                }
                stage = "create"
                started = true
                check(session.start() is RootExecutionResult.Success) { "display_create" }
                check(session.execute(ExecutableRootAction.OpenApp(APP)) is RootExecutionResult.Success) { "fixture_open" }
                val initial = awaitState(context, uri, session.displayId)
                check(initial.getBoolean("empty") && initial.getBoolean("editorFocused")) { "fixture_initial_state" }
                val instance = initial.getString("instance") ?: error("fixture_instance")
                evidence.putString("fixtureInstance", instance)
                evidence.putInt("displayId", session.displayId)
                evidence.putString("sessionId", session.sessionId)
                val observer = RootScreenObserver(DisplaySession(session.displayId, session.sessionId))
                val before = observer.observe()
                check(session.validate() && before.foregroundPackage == PACKAGE &&
                    before.foregroundActivity == APP.activityName && before.focusedPackage == PACKAGE &&
                    before.displayId == session.displayId && before.sessionId == session.sessionId &&
                    before.focusedWindowId != null && before.keyboardVisible == false && mainIdentity() == main) {
                    "owned_target_unavailable"
                }
                stage = "home_delivery"
                // This fixed platform probe is intentionally outside the production HOME whitelist.
                input(session.displayId, listOf("keyevent", "3"))
                evidence.putBoolean("homeCommandCompleted", true)
                stage = "home_target"
                var homeObserved = false
                repeat(5) {
                    if (!homeObserved) {
                        check(session.validate()) { "session_invalid" }
                        val unchanged = runCatching { mainIdentity() == main }.getOrDefault(false)
                        evidence.putBoolean("mainActivityUnchanged", unchanged)
                        check(unchanged) { "main_activity_changed" }
                        val after = observer.observe()
                        homeObserved = after.displayId == session.displayId && after.sessionId == session.sessionId &&
                            (after.foregroundPackage to after.foregroundActivity) in homeTargets &&
                            after.focusedPackage == after.foregroundPackage && after.focusedWindowId != null &&
                            after.keyboardVisible == false
                        if (!homeObserved) delay(1_000)
                    }
                }
                evidence.putBoolean("ownedHomeObserved", homeObserved)
                check(homeObserved) { "owned_home_not_observed" }
                stage = "complete"
            }
        } catch (error: Exception) {
            evidence.putString("failureKind", when (error) {
                is SecurityException -> "permission_denied"
                is IllegalArgumentException -> "invalid_arguments"
                is IllegalStateException -> "state_check"
                else -> "other"
            })
            failure = "home_platform_failed_$stage"
        } finally {
            withContext(NonCancellable) {
                val closed = if (started) runCatching { session.close() }.getOrDefault(false) else true
                evidence.putBoolean("cleanupConfirmed", closed)
                evidence.putString("releaseStatus", if (!started) "not_started" else if (closed) "confirmed" else "unconfirmed")
                val gone = if (started && closed) {
                    runCatching { call(context, uri, "virtual_state").isEmpty }.getOrDefault(false)
                } else null
                val mainUnchanged = if (started && closed && baseline != null) {
                    runCatching { mainIdentity() == baseline }.getOrDefault(false)
                } else null
                val imeUnchanged = runCatching { setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme }.getOrDefault(false)
                val enabledUnchanged = runCatching { enabledImes(context) == originalEnabledImes }.getOrDefault(false)
                val servicesUnchanged = runCatching { setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices }.getOrDefault(false)
                if (gone != null) evidence.putBoolean("fixtureGone", gone)
                else evidence.putString("fixtureExitCheck", "not_collected")
                if (mainUnchanged != null) evidence.putBoolean("mainActivityUnchangedAfterRelease", mainUnchanged)
                else evidence.putString("mainExitCheck", "not_collected")
                evidence.putBoolean("imeUnchanged", imeUnchanged)
                evidence.putBoolean("enabledImesUnchanged", enabledUnchanged)
                evidence.putBoolean("servicesUnchanged", servicesUnchanged)
                if (!closed || (started && (gone != true || mainUnchanged != true)) ||
                    !imeUnchanged || !enabledUnchanged || !servicesUnchanged) {
                    evidence.putString("cleanupFailure", if (!closed) "release_unconfirmed" else "environment_assertion_failed")
                    if (failure == null) failure = "home_platform_cleanup_unconfirmed"
                }
                evidence.putBoolean("networkUsed", false)
                evidence.putBoolean("screenshotsUsed", false)
                evidence.putBoolean("preflightOnly", preflightOnly)
                evidence.putString("stage", stage)
                evidence.putString("failure", failure ?: "none")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
            }
        }
        check(failure == null && stage == if (preflightOnly) "preflight_complete" else "complete") {
            failure ?: "home_platform_unknown"
        }
    }

    private suspend fun probeUnicode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://$PACKAGE.state")
        val session = VirtualDisplaySession(context)
        val originalIme = setting(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalEnabledImes = enabledImes(context)
        val originalServices = setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        var originalAccessibilityService: RootPilotAccessibilityService? = null
        var originalAccessibilityFlags: Int? = null
        var baseline: String? = null
        var started = false
        var stage = "preflight"
        var failure: String? = null
        val evidence = Bundle()
        try {
            withTimeout(60_000) {
                check(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null &&
                    RootPilotService.uiState.value.status in setOf(
                        com.example.agent.rootpilot.model.RootPilotStatus.IDLE,
                        com.example.agent.rootpilot.model.RootPilotStatus.COMPLETED,
                        com.example.agent.rootpilot.model.RootPilotStatus.FAILED,
                        com.example.agent.rootpilot.model.RootPilotStatus.STOPPED)) { "service_busy" }
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                    !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "recovery_pending" }
                check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) { "device_locked" }
                check(context.getSystemService(DisplayManager::class.java).displays.none {
                    it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX)
                }) { "existing_owned_display" }
                check(context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) {
                    "fixture_identity"
                }
                check(call(context, uri, "virtual_state").isEmpty) { "fixture_already_open" }
                val service = RootPilotAccessibilityService.connectedService ?: error("semantics_disabled")
                val flags = service.serviceInfo?.flags ?: error("service_flags_unavailable")
                originalAccessibilityService = service
                originalAccessibilityFlags = flags
                val main = mainIdentity().also { baseline = it }
                stage = "create"
                started = true
                check(session.start() is RootExecutionResult.Success) { "display_create" }
                check(session.execute(ExecutableRootAction.OpenApp(APP)) is RootExecutionResult.Success) { "fixture_open" }
                val initial = awaitState(context, uri, session.displayId)
                check(initial.getBoolean("empty") && initial.getBoolean("editorFocused")) { "fixture_initial" }
                val instance = initial.getString("instance") ?: error("fixture_instance")
                evidence.putString("fixtureInstance", instance)
                evidence.putInt("displayId", session.displayId)
                evidence.putString("sessionId", session.sessionId)
                val observer = RootScreenObserver(DisplaySession(session.displayId, session.sessionId))
                val before = observer.observe()
                check(session.validate() && before.foregroundPackage == PACKAGE && before.foregroundActivity == APP.activityName &&
                    before.focusedPackage == PACKAGE && before.displayId == session.displayId &&
                    before.sessionId == session.sessionId && before.focusedWindowId != null && before.keyboardVisible == false &&
                    mainIdentity() == main) { "owned_target_unavailable" }
                stage = "tree"
                val tree = AndroidUiTreeProvider(context.packageName, DisplaySession(session.displayId, session.sessionId))
                    .query(before)
                check(tree.unavailable == null && tree.data != null) { "owned_tree_unavailable" }
                evidence.putBoolean("ownedTreeAvailable", true)
                stage = "unicode_delivery"
                withContext(Dispatchers.IO) {
                    service.queryTree(virtual = true) {
                        suspend fun targetRoot(): AccessibilityNodeInfo {
                            check(RootPilotAccessibilityService.connectedService === service && session.validate()) { "binding_changed" }
                            val window = service.windowsOnAllDisplays[session.displayId]?.singleOrNull {
                                it.displayId == session.displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
                            } ?: error("owned_window_unavailable")
                            return window.root?.takeIf {
                                it.windowId == window.id && it.packageName?.toString() == PACKAGE && it.isVisibleToUser
                            } ?: error("owned_root_unavailable")
                        }
                        stage = "unicode_window_binding"
                        val root = targetRoot()
                        stage = "unicode_editor_binding"
                        val editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("editor_unavailable")
                        stage = "unicode_editor_eligibility"
                        val refreshed = editor.refresh()
                        evidence.putBoolean("editorRefreshed", refreshed)
                        evidence.putBoolean("editorPackageMatches", editor.packageName?.toString() == PACKAGE)
                        evidence.putBoolean("editorWindowMatches", editor.windowId == root.windowId)
                        evidence.putBoolean("editorIdMatches", editor.viewIdResourceName == "android:id/edit")
                        check(refreshed && editor.packageName?.toString() == PACKAGE && editor.windowId == root.windowId &&
                            editor.viewIdResourceName == "android:id/edit") { "editor_identity_mismatch" }
                        evidence.putBoolean("editorEditable", editor.isEditable)
                        evidence.putBoolean("editorFocused", editor.isFocused)
                        evidence.putBoolean("editorVisible", editor.isVisibleToUser)
                        evidence.putBoolean("editorEnabled", editor.isEnabled)
                        evidence.putBoolean("editorEmpty", editor.text.isNullOrEmpty())
                        evidence.putBoolean("editorTextPresent", editor.text != null)
                        evidence.putInt("selectionStart", editor.textSelectionStart)
                        evidence.putInt("selectionEnd", editor.textSelectionEnd)
                        evidence.putBoolean("setTextSupported", editor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                        check(refreshed && editor.packageName?.toString() == PACKAGE &&
                            editor.windowId == root.windowId && editor.viewIdResourceName == "android:id/edit" &&
                            editor.isEditable && editor.isFocused && editor.isVisibleToUser && editor.isEnabled &&
                            editor.text.isNullOrEmpty() &&
                            editor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) { "empty_editor_unavailable" }
                        var ancestor: AccessibilityNodeInfo? = editor
                        var rootReached = false
                        stage = "unicode_ancestry"
                        repeat(33) {
                            if (!rootReached) {
                                val node = ancestor ?: error("incomplete_ancestry")
                                check(node.windowId == root.windowId && node.packageName?.toString() == PACKAGE &&
                                    !node.isPassword && !node.isAccessibilityDataSensitive) { "sensitive_or_changed_ancestry" }
                                rootReached = node == root
                                if (!rootReached) ancestor = node.parent
                            }
                        }
                        stage = "unicode_final_binding"
                        check(rootReached && targetRoot() == root &&
                            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) == editor &&
                            before.sameTarget(observer.observe()) && mainIdentity() == main) { "input_binding_changed" }
                        val arguments = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, UNICODE_SAMPLE)
                        }
                        currentCoroutineContext().ensureActive()
                        stage = "unicode_send"
                        evidence.putInt("setTextCalls", 1)
                        val accepted = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        evidence.putBoolean("setTextAccepted", accepted)
                        check(accepted) { "unicode_not_accepted" }
                    }
                }
                evidence.putBoolean("serviceFlagsRestored", service.serviceInfo?.flags == flags &&
                    RootPilotAccessibilityService.connectedService === service)
                check(evidence.getBoolean("serviceFlagsRestored")) { "flags_not_restored" }
                stage = "unicode_effect"
                withTimeout(5_000) {
                    while (true) {
                        check(session.validate() && before.sameTarget(observer.observe()) && mainIdentity() == main) { "target_changed" }
                        val state = call(context, uri, "virtual_state")
                        check(state.getString("instance") == instance && state.getInt("displayId", -1) == session.displayId &&
                            state.getBoolean("ready")) { "fixture_changed" }
                        if (state.getBoolean("unicodeMatches")) break
                        delay(50)
                    }
                }
                check(observer.observe().keyboardVisible == false) { "ime_visible" }
                evidence.putBoolean("unicodeMatches", true)
                evidence.putBoolean("imeHidden", true)
                evidence.putBoolean("mainActivityUnchanged", true)
                stage = "complete"
            }
        } catch (_: Exception) {
            failure = "unicode_platform_failed_$stage"
        } finally {
            withContext(NonCancellable) {
                val closed = if (started) runCatching { session.close() }.getOrDefault(false) else true
                evidence.putBoolean("cleanupConfirmed", closed)
                evidence.putString("releaseStatus", if (!started) "not_started" else if (closed) "confirmed" else "unconfirmed")
                val gone = if (started && closed) runCatching { call(context, uri, "virtual_state").isEmpty }.getOrDefault(false) else null
                val mainUnchanged = if (started && closed && baseline != null) runCatching { mainIdentity() == baseline }.getOrDefault(false) else null
                if (gone != null) evidence.putBoolean("fixtureGone", gone) else evidence.putString("fixtureExitCheck", "not_collected")
                if (mainUnchanged != null) evidence.putBoolean("mainActivityUnchangedAfterRelease", mainUnchanged)
                else evidence.putString("mainExitCheck", "not_collected")
                val imeUnchanged = runCatching { setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme }.getOrDefault(false)
                val enabledUnchanged = runCatching { enabledImes(context) == originalEnabledImes }.getOrDefault(false)
                val servicesUnchanged = runCatching { setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices }.getOrDefault(false)
                val flagsRestored = originalAccessibilityService?.let { original ->
                    runCatching { RootPilotAccessibilityService.connectedService === original &&
                        originalAccessibilityFlags != null && original.serviceInfo?.flags == originalAccessibilityFlags }.getOrDefault(false)
                }
                if (flagsRestored != null) evidence.putBoolean("serviceFlagsRestored", flagsRestored)
                else evidence.putString("serviceFlagsCheck", "not_collected")
                evidence.putBoolean("imeUnchanged", imeUnchanged)
                evidence.putBoolean("enabledImesUnchanged", enabledUnchanged)
                evidence.putBoolean("servicesUnchanged", servicesUnchanged)
                if (!closed || (started && (gone != true || mainUnchanged != true || flagsRestored != true)) ||
                    !imeUnchanged || !enabledUnchanged || !servicesUnchanged || flagsRestored == false) {
                    evidence.putString("cleanupFailure", if (!closed) "release_unconfirmed" else "environment_assertion_failed")
                    if (failure == null) failure = "unicode_platform_cleanup_unconfirmed"
                }
                evidence.putBoolean("networkUsed", false)
                evidence.putBoolean("screenshotsUsed", false)
                evidence.putString("stage", stage)
                evidence.putString("failure", failure ?: "none")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
            }
        }
        check(failure == null && stage == "complete") { failure ?: "unicode_platform_unknown" }
    }

    private suspend fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://$PACKAGE.state")
        val session = VirtualDisplaySession(context)
        val originalIme = setting(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalEnabledImes = enabledImes(context)
        val originalServices = setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        var mainBaseline: String? = null
        var started = false
        var failure: String? = null
        var stage = "preflight"
        val evidence = Bundle()
        try {
            withTimeout(60_000) {
                check(context.packageName == "com.example.agent") { "target_package" }
                check(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null &&
                    RootPilotService.uiState.value.status in setOf(
                        com.example.agent.rootpilot.model.RootPilotStatus.IDLE,
                        com.example.agent.rootpilot.model.RootPilotStatus.COMPLETED,
                        com.example.agent.rootpilot.model.RootPilotStatus.FAILED,
                        com.example.agent.rootpilot.model.RootPilotStatus.STOPPED)) { "service_busy" }
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                    !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "recovery_pending" }
                check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) { "device_locked" }
                check(originalIme.isNotBlank()) { "ime_unavailable" }
                check(context.getSystemService(DisplayManager::class.java).displays.none {
                    it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX)
                }) { "existing_owned_display" }
                check(context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) {
                    "fixture_identity"
                }
                check(call(context, uri, "virtual_state").isEmpty) { "fixture_already_open" }
                val main = mainIdentity().also { mainBaseline = it }
                stage = "create"
                started = true
                check(session.start() is RootExecutionResult.Success) { "display_create" }
                check(session.execute(ExecutableRootAction.OpenApp(APP)) is RootExecutionResult.Success) { "fixture_open" }
                val observer = RootScreenObserver(DisplaySession(session.displayId, session.sessionId))
                val initial = awaitState(context, uri, session.displayId)
                val instance = initial.getString("instance") ?: error("fixture_instance")
                suspend fun guard(): Bundle {
                    check(session.validate()) { "session_invalid" }
                    val screen = observer.observe()
                    check(screen.displayId == session.displayId && screen.sessionId == session.sessionId &&
                        screen.foregroundPackage == PACKAGE && screen.foregroundActivity == APP.activityName &&
                        screen.focusedPackage == PACKAGE && screen.keyboardVisible == false && screen.focusedWindowId != null) {
                        "virtual_focus_or_ime"
                    }
                    val state = call(context, uri, "virtual_state")
                    check(state.getBoolean("ready") && state.getInt("displayId", -1) == session.displayId &&
                        state.getString("instance") == instance) { "fixture_changed" }
                    check(mainIdentity() == main) { "main_activity_changed" }
                    check(setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme) { "ime_changed" }
                    return state
                }
                check(initial.getBoolean("empty") && initial.getBoolean("editorFocused") && initial.getInt("scrollY") == 0) {
                    "fixture_initial_state"
                }
                stage = "ascii"
                guard()
                input(session.displayId, listOf("text", "RootPilot42"))
                delay(500)
                evidence.putBoolean("asciiObserved", guard().getBoolean("asciiMatches"))
                // Text is a capability observation only; failed ASCII input does not bypass the swipe/key checks.
                stage = "back"
                input(session.displayId, listOf("keyevent", "4"))
                delay(500)
                val back = guard()
                check(back.getInt("backInvoked") == 1) { "back_not_delivered" }
                evidence.putBoolean("backObserved", true)
                stage = "enter"
                input(session.displayId, listOf("keyevent", "66"))
                delay(500)
                val enter = guard()
                check(enter.getInt("enterDown") == 1 && enter.getInt("enterUp") == 1) { "enter_not_delivered" }
                evidence.putBoolean("enterObserved", true)
                stage = "swipe"
                val before = guard()
                val x = before.getInt("x", -1)
                val from = before.getInt("fromY", -1)
                val to = before.getInt("toY", -1)
                VirtualDisplayProtocol.tap(x, from); VirtualDisplayProtocol.tap(x, to)
                check(from > to && before.getInt("scrollY") == 0) { "swipe_geometry" }
                input(session.displayId, listOf("swipe", "$x", "$from", "$x", "$to", "400"))
                delay(500)
                val after = guard()
                check(after.getInt("scrollY") > before.getInt("scrollY")) { "swipe_not_observed" }
                evidence.putBoolean("swipeObserved", true)
                evidence.putBoolean("mainActivityUnchanged", true)
                evidence.putBoolean("keyboardHidden", true)
                stage = "complete"
            }
        } catch (_: Exception) {
            failure = "capability_failed_$stage"
        } finally {
            withContext(NonCancellable) {
                val closed = if (started) session.close() else true
                evidence.putBoolean("cleanupConfirmed", closed)
                if (started) {
                    evidence.putBoolean("fixtureGone", if (closed) {
                        try { call(context, uri, "virtual_state").isEmpty } catch (_: Exception) { false }
                    } else false)
                    evidence.putBoolean("mainActivityUnchangedAfterRelease", if (closed && mainBaseline != null) {
                        try { mainIdentity() == mainBaseline } catch (_: Exception) { false }
                    } else false)
                }
                evidence.putBoolean("imeUnchanged", setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme)
                evidence.putBoolean("enabledImesUnchanged", enabledImes(context) == originalEnabledImes)
                evidence.putBoolean("servicesUnchanged", setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices)
                evidence.putBoolean("networkUsed", false)
                evidence.putString("stage", stage)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
                if (!closed || (started && (!evidence.getBoolean("fixtureGone") ||
                        !evidence.getBoolean("mainActivityUnchangedAfterRelease"))) || !evidence.getBoolean("imeUnchanged") ||
                    !evidence.getBoolean("enabledImesUnchanged") || !evidence.getBoolean("servicesUnchanged")) {
                    failure = "capability_cleanup_unconfirmed"
                }
            }
        }
        check(failure == null) { failure ?: "capability_unknown" }
    }

    private suspend fun awaitState(context: Context, uri: Uri, displayId: Int): Bundle = withTimeout(5_000) {
        while (true) {
            val state = call(context, uri, "virtual_state")
            if (state.getBoolean("ready") && state.getInt("displayId", -1) == displayId) return@withTimeout state
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("fixture_not_ready")
    }

    private suspend fun call(context: Context, uri: Uri, method: String): Bundle = withContext(Dispatchers.IO) {
        context.contentResolver.call(uri, method, null, null) ?: error("fixture_unavailable")
    }

    private fun setting(context: Context, name: String) = Settings.Secure.getString(context.contentResolver, name).orEmpty()

    private fun enabledImes(context: Context): List<String> =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.id }.sorted()

    private suspend fun input(displayId: Int, args: List<String>) {
        check(displayId > 0 && args.all { Regex("[A-Za-z0-9]+").matches(it) }) { "probe_arguments" }
        command("exec /system/bin/cmd input -d $displayId ${args.joinToString(" ")}", 16 * 1024)
    }

    private suspend fun mainIdentity(): String {
        val dump = command("exec dumpsys activity activities", 512 * 1024)
        val section = dump.lines().dropWhile { it.trim() != "Display #0 (activities from top to bottom):" }
            .drop(1).takeWhile { !it.trimStart().startsWith("Display #") }
        val pattern = Regex("(?:mResumedActivity: |topResumedActivity=|Resumed: )" +
            "(ActivityRecord\\{[0-9a-fA-F]+ u[0-9]+ com\\.example\\.agent/\\.rootpilot\\.RootPilotActivity t[0-9]+\\})")
        val candidates = section.map(String::trim).filter {
            it.startsWith("mResumedActivity:") || it.startsWith("topResumedActivity=") || it.startsWith("Resumed:")
        }.map { pattern.matchEntire(it)?.groupValues?.get(1) }
        check(candidates.isNotEmpty() && candidates.all { it != null }) { "main_identity_unavailable" }
        return candidates.filterNotNull().distinct().singleOrNull() ?: error("main_identity_ambiguous")
    }

    private suspend fun command(value: String, limit: Int): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", value).redirectError(File("/dev/null")).start()
        try {
            process.outputStream.close()
            val output = java.io.ByteArrayOutputStream()
            withTimeout(5_000) {
                while (process.isAlive || process.inputStream.available() > 0) {
                    val count = process.inputStream.available()
                    if (count == 0) { delay(10); continue }
                    val buffer = ByteArray(minOf(count, 4096))
                    val read = process.inputStream.read(buffer)
                    check(read > 0 && output.size() + read <= limit) { "probe_output_limit" }
                    output.write(buffer, 0, read)
                }
            }
            check(process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0) { "probe_command" }
            output.toString("UTF-8")
        } finally {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
        }
    }

    private companion object {
        const val PACKAGE = "com.example.rootpilot.fixture"
        const val UNICODE_SAMPLE = "中文🙂\n第二行"
        val APP = RootPilotApp(PACKAGE, "RootPilot offline fixture", "$PACKAGE.VirtualCapabilityActivity")
    }
}
