package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.DisplayRoutingRootExecutor
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real product executor/adapter, fixed seeds, no model, network or screenshot calls. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayPlainTextInstrumentedTest {
    @Test fun insertsAtKnownCaretWithProductionInput() = runBlocking {
        optIn("virtualPlainCursorAcceptance")
        runEdit(selection = false)
    }

    @Test fun replacesOnlyKnownSelectionWithProductionInput() = runBlocking {
        optIn("virtualPlainSelectionAcceptance")
        runEdit(selection = true)
    }

    @Test fun rejectsChangedSourceAfterProductionConfirmation() = runBlocking {
        optIn("virtualPlainChangedSourceAcceptance")
        runEdit(selection = false, changed = true)
    }

    @Test fun cancelsBeforeProductionSubmissionAndRestoresFlags() = runBlocking {
        optIn("virtualPlainCancelAcceptance")
        runEdit(selection = false, cancel = true)
    }

    private suspend fun runEdit(selection: Boolean, changed: Boolean = false, cancel: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        val main = RejectMain()
        val backend = DisplayRoutingRootExecutor(context, main, AppCatalog { listOf(APP) })
        val service = RootPilotAccessibilityService.connectedService
        val connection = service?.connectionIdentity
        val flags = service?.serviceInfo?.flags
        val originalConfig = RootPilotService.uiState.value.config
        val originalIme = setting(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        val originalServices = setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val originalImes = enabledImes(context)
        val originalRotation = manager.getDisplay(0).rotation
        var mainBaseline: String? = null
        var session: DisplaySession? = null
        var instance: String? = null
        var started = false
        var prepared = false
        var stage = "preflight"
        var failure: String? = null
        var typeApprovals = 0
        val evidence = Bundle().apply { putBoolean("networkUsed", false); putBoolean("pixelsExported", false) }

        suspend fun environment() {
            idle(context)
            check(service != null && RootPilotAccessibilityService.connectedService === service &&
                service.connectionIdentity === connection && service.serviceInfo.flags == flags &&
                setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme && enabledImes(context) == originalImes &&
                setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == originalServices &&
                RootPilotService.uiState.value.config === originalConfig && manager.getDisplay(0).rotation == originalRotation &&
                (mainBaseline == null || mainIdentity() == mainBaseline)) { "environment_changed" }
        }

        suspend fun sample(): Bundle {
            environment()
            val owned = session ?: error("session_missing")
            check(backend.validateSession()) { "session_invalid" }
            val observed = backend.observeScreen()
            check(observed.displayId == owned.displayId && observed.sessionId == owned.sessionId &&
                observed.foregroundPackage == PACKAGE && observed.foregroundActivity == APP.activityName &&
                observed.focusedPackage == PACKAGE && observed.focusedWindowId != null && observed.keyboardVisible == false) {
                "owned_target_changed"
            }
            val state = call(context, "virtual_state")
            check(state.getInt("displayId", -1) == owned.displayId && state.getBoolean("ready") && state.getBoolean("editorFocused")) {
                "fixture_target_changed"
            }
            if (instance == null) instance = state.getString("instance")
            check(instance != null && state.getString("instance") == instance && backend.validateSession()) { "fixture_instance_changed" }
            return state
        }

        try {
            withTimeout(60_000) {
                environment()
                check(manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) ||
                    it.name.startsWith("rootpilot-parity-") }) { "existing_owned_display" }
                check(context.packageManager.resolveContentProvider("$PACKAGE.state", 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) { "fixture_identity" }
                check(call(context, "virtual_state").isEmpty) { "fixture_already_open" }
                mainBaseline = mainIdentity()
                stage = "begin"
                started = true
                check(backend.beginRun(RootPilotConfig(task = "fixed offline plain-text acceptance", executionDisplay = ExecutionDisplay.VIRTUAL,
                    virtualDisplayStartPackage = PACKAGE)) is RootExecutionResult.Success) { "display_create" }
                val name = VirtualDisplayProtocol.DISPLAY_PREFIX + backend.sessionIdentity
                val display = manager.displays.singleOrNull { it.name == name } ?: error("display_identity")
                session = DisplaySession(display.displayId, backend.sessionIdentity ?: error("session_missing"))
                val binding = Bundle().apply { putInt("displayId", session!!.displayId); putString("sessionId", session!!.sessionId) }
                stage = "prepare"
                prepared = true
                val receipt = call(context, if (selection) "virtual_prepare_selection" else "virtual_prepare_cursor", binding)
                check(receipt.getBoolean("prepared") && receipt.getInt("displayId", -1) == session!!.displayId &&
                    receipt.getString("sessionId") == session!!.sessionId && backend.validateSession()) { "preparation_unconfirmed" }
                stage = "open"
                check(backend.executeConfirmed(ExecutableRootAction.OpenApp(APP)) { true } is RootExecutionResult.Success) { "open_failed" }
                withTimeout(5_000) { while (!call(context, "virtual_state").getBoolean("ready")) delay(50) }
                val seed = sample()
                check(seed.getString("preparedInputMode") == if (selection) "SELECTION" else "CURSOR") { "seed_preparation_missing" }
                check(seed.getBoolean(if (selection) "selectionSeedMatches" else "cursorSeedMatches") &&
                    seed.getInt("selectionStart", -1) == 1 && seed.getInt("selectionEnd", -1) == if (selection) 2 else 1) { "seed_unconfirmed" }
                stage = "type"
                var cancelled = false
                var cancelRequested = false
                val result = try {
                    backend.executeConfirmed(ExecutableRootAction.Type(if (selection) UNICODE else "🙂")) { target ->
                        check(target == PACKAGE) { "approval_target_changed" }
                        sample()
                        typeApprovals++
                        evidence.putBoolean("flagsRestoredBeforeConfirmation", true)
                        if (changed) call(context, "virtual_change_cursor_source", Bundle().apply {
                            putInt("displayId", session!!.displayId); putString("instance", instance)
                        })
                        if (cancel) {
                            cancelRequested = true
                            throw CancellationException("fixed_before_submit")
                        }
                        true
                    }
                } catch (error: CancellationException) {
                    if (!cancelRequested) throw error
                    cancelled = true
                    null
                }
                val final = sample()
                check(typeApprovals == 1 && main.calls == 0) { "unexpected_approval_or_main_call" }
                when {
                    changed -> {
                        check(result is RootExecutionResult.Failure && final.getBoolean("changedCursorSeedMatches") &&
                            final.getInt("selectionStart", -1) == 1 && final.getInt("selectionEnd", -1) == 1) { "changed_source_overwritten" }
                        evidence.putBoolean("changedSourcePreserved", true)
                    }
                    cancel -> {
                        check(cancelled && final.getBoolean("cursorSeedMatches") && final.getInt("selectionStart", -1) == 1 &&
                            final.getInt("selectionEnd", -1) == 1) { "cancelled_edit_changed" }
                        evidence.putBoolean("cancelledBeforeSubmit", true)
                    }
                    else -> {
                        val caret = if (selection) 1 + UNICODE.length else 3
                        check(result is RootExecutionResult.Success && final.getBoolean(if (selection) "selectionSampleMatches" else "cursorSampleMatches") &&
                            final.getInt("selectionStart", -1) == caret && final.getInt("selectionEnd", -1) == caret) { "edit_result_unconfirmed" }
                        val screen = backend.observeScreen()
                        val tree = backend.queryDeviceInfo(DeviceInfoTool.UI_TREE, screen)
                        val expected = if (selection) "甲${UNICODE}丙" else "甲🙂乙"
                        check(tree.unavailable == null && tree.data?.stringValues()?.contains(expected) == true) { "result_tree_unconfirmed" }
                        evidence.putBoolean("fixedTextAndCaretObserved", true)
                        evidence.putBoolean("resultTreeObserved", true)
                    }
                }
                stage = "complete"
            }
        } catch (e: Exception) {
            val detail = e.message?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.take(48)
            failure = "plain_edit_failed_$stage" + (detail?.takeIf { it.isNotEmpty() }?.let { "_$it" } ?: "")
        } finally {
            withContext(NonCancellable) {
                try {
                    check(!started || backend.endRun() is RootExecutionResult.Success) { "release_failed" }
                    if (prepared && session != null) {
                        val cleared = call(context, "virtual_clear_prepared_input", Bundle().apply {
                            putInt("displayId", session!!.displayId); putString("sessionId", session!!.sessionId)
                        })
                        check(!cleared.getBoolean("prepared")) { "prepared_seed_cleanup_failed" }
                        evidence.putBoolean("preparedSeedCleared", true)
                    }
                    check(manager.displays.none { it.name == VirtualDisplayProtocol.DISPLAY_PREFIX + session?.sessionId } &&
                        call(context, "virtual_state").isEmpty) { "owned_display_or_fixture_remaining" }
                    environment()
                    evidence.putBoolean("environmentUnchanged", true)
                    evidence.putBoolean("releaseConfirmed", true)
                    evidence.putBoolean("ownedDisplayGone", true)
                    if (started) {
                        var releasedApprovals = 0
                        check(backend.executeConfirmed(ExecutableRootAction.Type("🙂")) { releasedApprovals++; false } is
                            RootExecutionResult.Failure && releasedApprovals == 0) { "released_input_not_rejected" }
                        evidence.putBoolean("releasedInputRejected", true)
                    }
                } catch (_: Exception) { failure = "plain_edit_cleanup_unconfirmed" }
                evidence.putInt("typeApprovals", typeApprovals)
                evidence.putInt("mainExecutorCalls", main.calls)
                evidence.putString("stage", stage)
                evidence.putString("failure", failure ?: "none")
                evidence.putBoolean("passed", failure == null && stage == "complete")
                instrumentation.sendStatus(0, evidence)
            }
        }
        check(failure == null && stage == "complete") { failure ?: "plain_edit_unconfirmed" }
    }

    private fun optIn(name: String) = assumeTrue(InstrumentationRegistry.getArguments().getString(name) == "true")
    private fun JsonElement.stringValues(): List<String> = when (this) {
        is JsonPrimitive -> if (isString) listOf(content) else emptyList()
        is JsonObject -> values.flatMap { it.stringValues() }
        is JsonArray -> flatMap { it.stringValues() }
        else -> emptyList()
    }
    private fun setting(context: Context, name: String) = Settings.Secure.getString(context.contentResolver, name).orEmpty()
    private fun enabledImes(context: Context) = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.id }.sorted()
    private fun idle(context: Context) {
        val state = RootPilotService.uiState.value
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!state.running && state.pendingAction == null && state.status in setOf(RootPilotStatus.IDLE, RootPilotStatus.COMPLETED,
            RootPilotStatus.FAILED, RootPilotStatus.STOPPED) && !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists() && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive) { "not_idle_or_unlocked" }
    }
    private suspend fun call(context: Context, method: String, extras: Bundle? = null): Bundle = withContext(Dispatchers.IO) {
        context.contentResolver.call(Uri.parse("content://$PACKAGE.state"), method, null, extras) ?: error("fixture_unavailable")
    }

    private suspend fun mainIdentity(): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", "exec dumpsys activity activities").redirectError(File("/dev/null")).start()
        val dump = try {
            process.outputStream.close()
            val output = ByteArrayOutputStream()
            withTimeout(5_000) {
                while (process.isAlive || process.inputStream.available() > 0) {
                    val available = process.inputStream.available()
                    if (available == 0) { delay(10); continue }
                    val bytes = ByteArray(minOf(available, 4096))
                    val count = process.inputStream.read(bytes)
                    check(count > 0 && output.size() + count <= 512 * 1024) { "main_identity_limit" }
                    output.write(bytes, 0, count)
                }
            }
            check(process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0) { "main_identity_command" }
            output.toString("UTF-8")
        } finally {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
        }
        val lines = dump.lines().dropWhile { it.trim() != "Display #0 (activities from top to bottom):" }
            .drop(1).takeWhile { !it.trimStart().startsWith("Display #") }
        val pattern = Regex("(?:mResumedActivity: |topResumedActivity=|Resumed: )" +
            "(ActivityRecord\\{[0-9a-fA-F]+ u0 com\\.example\\.agent/\\.rootpilot\\.RootPilotActivity t[0-9]+\\})")
        val identities = lines.map(String::trim).filter { it.startsWith("mResumedActivity:") ||
            it.startsWith("topResumedActivity=") || it.startsWith("Resumed:") }.map { pattern.matchEntire(it)?.groupValues?.get(1) }
        check(identities.isNotEmpty() && identities.all { it != null } && identities.distinct().size == 1) { "main_identity_unavailable" }
        identities.first()!!
    }

    private class RejectMain : RootExecutor {
        var calls = 0
        override suspend fun checkRoot(): RootExecutionResult { calls++; error("main_forbidden") }
        override suspend fun captureScreen(): RootScreenshotResult { calls++; error("main_forbidden") }
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult { calls++; error("main_forbidden") }
        override fun cancel() { }
    }

    private companion object {
        const val PACKAGE = "com.example.rootpilot.fixture"
        const val UNICODE = "中文🙂\n第二行"
        val APP = RootPilotApp(packageName = PACKAGE, label = "RootPilot fixed fixture", activityName = "$PACKAGE.VirtualCapabilityActivity")
    }
}
