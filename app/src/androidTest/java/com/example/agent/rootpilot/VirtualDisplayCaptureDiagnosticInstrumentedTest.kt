package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplaySession
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline diagnostics; no model, Service commands, input, screen upload or configuration writes. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayCaptureDiagnosticInstrumentedTest {
    @Test fun readsRetainedCaptureFailureWithoutDeviceActions() {
        assumeTrue(arguments.getString("readRetainedCaptureFailure") == "true")
        val state = RootPilotService.uiState.value
        check(!state.running && state.status == RootPilotStatus.FAILED, "failed_state_required")
        val latest = RootPilotService.historyState(context).value.records.maxByOrNull { it.startedAtEpochMs }
        check(latest?.id == "203ae342-37e4-4350-8a53-8c08212aa53c" &&
            latest.events.lastOrNull()?.let { it.event == TraceEvent.RUN_END && it.reason == TraceReason.SCREENSHOT_FAILED } == true,
            "prior_capture_failure_required")
        publish(buildJsonObject {
            put("retainedFailureCode", code(state.errorMessage)); put("frameRetained", state.frame != null)
            put("modelRequests", 0); put("deviceActions", 0)
        }.toString())
    }

    @Test fun capturesCalculatorOnceWithoutInputOrNetwork() = runBlocking {
        assumeTrue(arguments.getString("offlineSingleCapture") == "true")
        withRootPilotAcceptanceScreen { captureOnce() }
    }

    private suspend fun captureOnce() {
        val state = RootPilotService.uiState.value
        check(!state.running && state.pendingAction == null && state.status in setOf(RootPilotStatus.IDLE,
            RootPilotStatus.FAILED, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED), "idle_required")
        check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), "recovery_pending")
        check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, "device_locked")
        val manager = context.getSystemService(DisplayManager::class.java)
        val mainState = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state
        val powerProbe = arguments.getString("ownedDisplayPowerProbe") == "true"
        fun gone() = manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }
        check(gone(), "private_display_exists")
        val component = context.packageManager.getLaunchIntentForPackage("com.miui.calculator")?.component
        check(component?.packageName == "com.miui.calculator" &&
            component.className == "com.miui.calculator.cal.CalculatorActivity", "calculator_identity")
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val session = VirtualDisplaySession(context)
        var started = false
        var opened = false
        var captured = false
        var failureCode = "none"
        var elapsed = 0L
        var bytes = 0
        var displayState = -1
        var displayStateAfterStart = -1
        var unlockedAfterStart = false
        var unlockedAfterOpen = false
        var unlockedBeforeCapture = false
        var displayStateAfterPower = -1
        var powerRequests = 0
        var powerCommandExited = true
        var powerCommandExitCode = -1
        var mainStateBeforePower = -1
        var mainStateAfterPower = -1
        var mainStateBeforeClose = -1
        var cleaned = false
        var unchanged = false
        var failure: Throwable? = null
        try {
            withTimeout(35_000) {
                val start = session.start()
                if (start is RootExecutionResult.Failure) failureCode = code(start.message)
                check(start is RootExecutionResult.Success, "start_failed")
                started = true
                displayStateAfterStart = manager.getDisplay(session.displayId)?.state ?: -1
                unlockedAfterStart = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
                val open = session.execute(ExecutableRootAction.OpenApp(RootPilotApp(
                    component!!.packageName, "计算器", component.className)))
                if (open is RootExecutionResult.Failure) failureCode = code(open.message)
                check(open is RootExecutionResult.Success, "open_failed")
                opened = true
                unlockedAfterOpen = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
                delay(500)
                check(session.validate(), "session_invalid")
                displayState = manager.getDisplay(session.displayId)?.state ?: -1
                unlockedBeforeCapture = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
                if (!unlockedBeforeCapture) failureCode = "device_locked_before_capture"
                check(unlockedBeforeCapture, "device_locked_before_capture")
                if (powerProbe) {
                    val owned = manager.getDisplay(session.displayId)
                    check(session.displayId > Display.DEFAULT_DISPLAY &&
                        owned?.name == VirtualDisplayProtocol.DISPLAY_PREFIX + session.sessionId &&
                        session.validate(), "owned_display_required")
                    check(displayState == Display.STATE_OFF, "off_display_required")
                    mainStateBeforePower = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1
                    powerRequests++
                    powerCommandExited = false
                    val command = ProcessBuilder("su", "-c",
                        "exec /system/bin/timeout -s KILL 2 /system/bin/cmd display power-on ${session.displayId}")
                        .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
                    try {
                        powerCommandExited = command.waitFor(4, TimeUnit.SECONDS)
                        check(powerCommandExited, "power_request_timeout")
                        powerCommandExitCode = command.exitValue()
                        check(powerCommandExitCode == 0, "power_request_rejected")
                    }
                    finally { if (command.isAlive) command.destroyForcibly() }
                    displayStateAfterPower = manager.getDisplay(session.displayId)?.state ?: -1
                    mainStateAfterPower = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1
                }
                val at = SystemClock.elapsedRealtime()
                val result = session.capture()
                elapsed = SystemClock.elapsedRealtime() - at
                when (result) {
                    is RootScreenshotResult.Failure -> failureCode = code(result.message)
                    is RootScreenshotResult.Success -> {
                        VirtualDisplayProtocol.checkPng(result.pngBytes)
                        bytes = result.pngBytes.size
                        captured = true
                    }
                }
            }
        } catch (error: Throwable) { failure = error }
        finally {
            withContext(NonCancellable) {
                mainStateBeforeClose = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1
                cleaned = session.close() && gone() && powerCommandExited
                val imeUnchanged = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == ime
                val serviceUnchanged = RootPilotService.uiState.value === state
                val mainStateAfterClose = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1
                val unlocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
                unchanged = imeUnchanged && serviceUnchanged && mainStateAfterClose == mainState && unlocked
                publish(buildJsonObject {
                    put("started", started); put("opened", opened); put("captured", captured)
                    put("failureCode", failureCode); put("captureMs", elapsed); put("pngBytes", bytes)
                    put("displayStateBeforeCapture", displayState)
                    put("displayStateAfterStart", displayStateAfterStart)
                    put("unlockedAfterStart", unlockedAfterStart); put("unlockedAfterOpen", unlockedAfterOpen)
                    put("unlockedBeforeCapture", unlockedBeforeCapture)
                    put("ownedDisplayPowerRequests", powerRequests); put("displayStateAfterPower", displayStateAfterPower)
                    put("powerCommandExited", powerCommandExited); put("powerCommandExitCode", powerCommandExitCode)
                    put("cleanupConfirmed", cleaned); put("environmentUnchanged", unchanged)
                    put("imeUnchanged", imeUnchanged); put("serviceStateSame", serviceUnchanged); put("unlocked", unlocked)
                    put("mainStateAtStart", mainState ?: -1); put("mainStateBeforePower", mainStateBeforePower)
                    put("mainStateAfterPower", mainStateAfterPower); put("mainStateBeforeClose", mainStateBeforeClose)
                    put("mainStateAfterClose", mainStateAfterClose)
                    put("modelRequests", 0); put("tapCount", 0); put("configurationWrites", 0)
                }.toString())
            }
        }
        check(cleaned, "cleanup_unconfirmed")
        check(unchanged, "environment_changed")
        check(failure == null, "diagnostic_interrupted")
        check(captured, "capture_failed")
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun code(value: String?) = VirtualDisplayProtocol.Reason.entries.firstOrNull { it.code == value }?.code ?: "unknown"
    private fun check(value: Boolean, code: String) { if (!value) throw AssertionError(code) }
    private fun publish(report: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
        putString("captureDiagnostic", report)
    })
}
