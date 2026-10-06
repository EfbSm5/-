package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in offline platform probe, not a production model/Service or physical-touch acceptance. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayCapabilityInstrumentedTest {
    @Test fun platformDeliversSwipeKeysAndAsciiWithHiddenIme() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualCapabilityPlatformProbe") == "true")
        runBlocking { probe() }
    }

    @Test fun productionLoopConfirmsSwipeBackAndEnterWithoutNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualCapabilityLoopAcceptance") == "true")
        runBlocking { acceptLoop() }
    }

    private suspend fun acceptLoop() {
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
                check(!backend.supports(ExecutableRootAction.Type("RootPilot42")) &&
                    backend.execute(ExecutableRootAction.Type("RootPilot42")) is RootExecutionResult.Failure &&
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
                        val response = when (modelCalls++) {
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
                    .run(AgentLoopRequest(config, 4), trace) { event ->
                        if (event is AgentLoopEvent.AwaitingConfirmation) {
                            stage = "confirmation_$confirmations"
                            check(when (confirmations) {
                                0 -> event.action is RootPilotAction.OpenApp && event.action.packageName == PACKAGE
                                1 -> event.action is RootPilotAction.Swipe
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
                check(completed && modelCalls == 4 && confirmations == 4 && executions == 4 && mainExecutor.calls == 0) {
                    "loop_incomplete"
                }
                evidence.putInt("confirmations", confirmations); evidence.putInt("executions", executions)
                evidence.putInt("localModelCalls", modelCalls); evidence.putInt("mainExecutorCalls", mainExecutor.calls)
                evidence.putBoolean("loopCompleted", true)
                stage = "complete"
            }
        } catch (_: Exception) {
            failure = "capability_loop_failed_$stage"
        } finally {
            withContext(NonCancellable) {
                val closed = if (started) backend.endRun() is RootExecutionResult.Success else true
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
        val APP = RootPilotApp(PACKAGE, "RootPilot offline fixture", "$PACKAGE.VirtualCapabilityActivity")
    }
}
