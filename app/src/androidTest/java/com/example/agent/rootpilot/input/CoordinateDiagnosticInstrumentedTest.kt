package com.example.agent.rootpilot.input

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotApiConfigStore
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.action.ActionPolicy
import com.example.agent.rootpilot.action.ActionPolicyResult
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.ScreenSize
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.screen.RootScreenshotProvider
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in evidence probe, not a model accuracy pass/fail test. Never executes model text. */
@RunWith(AndroidJUnit4::class)
class CoordinateDiagnosticInstrumentedTest {
    @Test fun captureSameFrameCoordinatesAndFocus() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coordinateProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W --user current -f 0x10008000 -n $fixturePackage/${InputFixtureActivity::class.java.name}",
        )).bufferedReader().use { assertTrue(it.readText().contains("Status: ok")) }

        fun fields(): List<Pair<Rect, Boolean>> {
            val root = requireNotNull(automation.rootInActiveWindow) { "No active fixture window" }
            check(root.packageName?.toString() == fixturePackage) { "Fixture left foreground" }
            return listOf("android:id/edit", "android:id/text2").map { id ->
                val node = root.findAccessibilityNodeInfosByViewId(id).single {
                    it.packageName?.toString() == fixturePackage
                }
                Rect().also(node::getBoundsInScreen) to node.isFocused
            }
        }

        delay(500)
        fields()
        val saved = requireNotNull(RootPilotApiConfigStore.create(context).read()) { "Save API configuration first" }
        val config = saved.applyTo(RootPilotConfig(
            task = "在当前验收页的第一个输入框输入中文测试，不触碰密码框，不发送任何内容",
            allowScreenUpload = true,
        ))
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "coordinate-probe-").toFile()
        val report = directory.resolve("evidence.txt")
        report.writeText("Diagnostic only; success means evidence collected, not model accuracy.\n")
        val rootExecutor = SuRootExecutor()
        val screenshots = RootScreenshotProvider(rootExecutor)
        val client = HttpDeepSeekClient()
        val apps = AndroidAppCatalog(context).listApps()
        val history = mutableListOf<String>()
        try {
            // At most three model steps, and only taps inside the two non-password fixture fields.
            for (step in 0 until 3) {
                val before = fields()
                val captured = screenshots.capture()
                check(captured is ScreenshotCaptureResult.Success) { "Screenshot failed" }
                val frame = captured.frame
                check(fields() == before) { "Fixture changed during capture" }
                directory.resolve("step-$step.jpg").writeBytes(frame.bytes)
                report.appendText("step=$step uptime=${SystemClock.uptimeMillis()} image=${frame.width}x${frame.height} physical=${frame.physicalWidth}x${frame.physicalHeight} before=$before\n")
                val request = DeepSeekVisionRequest(config, frame, history.toList(), 3 - step, step, apps)
                var response = client.requestAction(request)
                if (response is DeepSeekActionResult.Failure &&
                    listOf("429", "超时", "网络").any { response.message.contains(it) }
                ) {
                    report.appendText("retry_once=${response.message}; reduced to fixture context\n")
                    delay(10_000)
                    response = client.requestAction(request.copy(
                        history = history.takeLast(1),
                        availableApps = apps.filter { it.packageName == fixturePackage },
                    ))
                }
                if (response is DeepSeekActionResult.Failure) {
                    report.appendText("request_failed=${response.message}\n")
                    error("Model request failed; see private diagnostic evidence")
                }
                response as DeepSeekActionResult.Success
                directory.resolve("step-$step-action.json").writeText(response.rawActionJson)
                val parsed = ActionParser().parse(response.rawActionJson)
                if (parsed !is ActionParseResult.Success || parsed.action !is RootPilotAction.Tap) {
                    report.appendText("stopped: non-tap response, not executed\n")
                    break
                }
                val action = parsed.action
                val allowed = ActionPolicy().toExecutable(action, ScreenSize(frame.physicalWidth, frame.physicalHeight))
                check(allowed is ActionPolicyResult.Allowed && allowed.action is ExecutableRootAction.Tap)
                val tap = allowed.action as ExecutableRootAction.Tap
                report.appendText("normalized=${action.x},${action.y} physical=${tap.x},${tap.y}\n")
                if (fields() != before || before.none { it.first.contains(tap.x, tap.y) }) {
                    report.appendText("stopped: changed field state or tap outside safe fields\n")
                    break
                }
                // A call or other overlay can cover a safe editor's screen coordinates.
                val windows = automation.windows
                val fixtureWindow = windows.singleOrNull { it.root?.packageName?.toString() == fixturePackage }
                if (fixtureWindow == null || windows.any { window ->
                    window.layer > fixtureWindow.layer &&
                        Rect().also(window::getBoundsInScreen).contains(tap.x, tap.y)
                }) {
                    report.appendText("stopped: fixture window missing or target covered by another window\n")
                    break
                }
                check(rootExecutor.execute(tap) is RootExecutionResult.Success) { "Tap execution failed" }
                delay(500)
                report.appendText("after=${fields()}\n")
                history += "step=$step action=tap(${action.x},${action.y}) result=success"
            }
        } finally {
            rootExecutor.cancel()
            // Only the artifact directory name is returned; no credentials or frame data in logs.
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("coordinateEvidence", "cache/${directory.name}")
            })
        }
    }
}
