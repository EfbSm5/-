package com.example.agent.rootpilot.input

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.RootScreenshotProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnicodeInputInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val environment = AndroidImeEnvironment(context)
    private val store = FileImeRestoreStore(context)
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }

    private fun openFixture(waitForLaunch: Boolean = true) {
        // The fixture belongs to the test APK, so launch it with the test shell identity.
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start ${if (waitForLaunch) "-W" else ""} --user current -f 0x10008000 -n $fixturePackage/${InputFixtureActivity::class.java.name}",
        )).bufferedReader().use { assertTrue(it.readText().contains(if (waitForLaunch) "Status: ok" else "Starting: Intent")) }
        node("android:id/edit")
    }

    private fun node(id: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.packageName?.toString() == fixturePackage }?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Fixture node not visible: $id")
    }

    private fun focus(id: String) {
        assertTrue(node(id).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!node(id).isFocused && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(node(id).isFocused)
    }

    @Test fun screenObservationReadsRealFixture() = runBlocking {
        requireScreenAcceptance()
        stage("fixture_start")
        openFixture(waitForLaunch = false)
        stage("fixture_focus")
        focus("android:id/edit")
        val keyboardDeadline = SystemClock.elapsedRealtime() + 5_000
        while (automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
            SystemClock.elapsedRealtime() < keyboardDeadline) SystemClock.sleep(50)
        assertTrue("Fixture keyboard must be visible before sampling", automation.windows.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        })
        stage("observation_start")
        val rootGranted = SuRootExecutor().checkRoot() is RootExecutionResult.Success
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("rootGranted", rootGranted) })
        assertTrue("Fixture app requires Root permission", rootGranted)
        val started = SystemClock.elapsedRealtime()
        val observation = SuRootExecutor().observeScreen()
        instrumentation.sendStatus(0, Bundle().apply {
            putLong("observationMillis", SystemClock.elapsedRealtime() - started)
            putBoolean("foregroundKnown", observation.foregroundPackage != null)
            putBoolean("activityKnown", observation.foregroundActivity != null)
            putBoolean("focusKnown", observation.focusedPackage != null)
            putBoolean("windowKnown", observation.focusedWindowId != null)
            putBoolean("keyboardKnown", observation.keyboardVisible != null)
            putBoolean("keyboardVisible", observation.keyboardVisible == true)
        })
        assertEquals(fixturePackage, observation.foregroundPackage)
        assertEquals(InputFixtureActivity::class.java.name, observation.foregroundActivity)
        assertEquals(fixturePackage, observation.focusedPackage)
        assertNotNull(observation.focusedWindowId)
        // Editor focus alone does not imply that a soft keyboard is visible.
        assertNotNull(observation.keyboardVisible)
        assertEquals(automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }, observation.keyboardVisible)
    }

    @Test fun screenCollectorWithoutUiAutomation() = runBlocking {
        requireScreenAcceptance()
        val root = SuRootExecutor()
        val granted = root.checkRoot() is RootExecutionResult.Success
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("rootGranted", granted) })
        assertTrue("Fixture app requires Root permission", granted)
        val observation = root.observeScreen()
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("foregroundKnown", observation.foregroundPackage != null)
            putBoolean("focusKnown", observation.focusedPackage != null)
            putBoolean("keyboardKnown", observation.keyboardVisible != null)
        })
        assertEquals(fixturePackage, observation.foregroundPackage)
        assertEquals(fixturePackage, observation.focusedPackage)
        assertNotNull(observation.keyboardVisible)
    }

    @Test fun screenGuardAllowsFixtureUnicodeAndRestoresIme() = runBlocking {
        requireScreenAcceptance()
        require(environment.isEnabled(environment.ownId))
        openFixture(waitForLaunch = false)
        focus("android:id/edit")
        val original = environment.currentId()
        val input = AndroidImeEnvironment.createInput(context)
        val root = SuRootExecutor(typeText = input::type)
        val events = mutableListOf<AgentLoopEvent>()
        AgentLoop(RootScreenshotProvider(root), fixtureModel(
            """{"action":"type","text":"屏幕上下文验收中文","reason":"fixture"}""",
        ), root).run(AgentLoopRequest(
            RootPilotConfig(task = "fixture", allowScreenUpload = true, manualConfirmation = true), 1, true,
        )) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }
        assertEquals(original, environment.currentId())
        assertNull(store.read())
        assertTrue(events.lastOrNull() is AgentLoopEvent.Completed)
        assertEquals("屏幕上下文验收中文", node("android:id/edit").text.toString())
    }

    @Test fun screenGuardRejectsChangedApplicationWithoutInjectingTap() = runBlocking {
        requireScreenAcceptance()
        openFixture(waitForLaunch = false)
        focus("android:id/edit")
        val actual = SuRootExecutor()
        var wouldExecute = 0
        val guarded = object : RootExecutor by actual {
            override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult {
                if (confirm(null)) wouldExecute++
                // This negative fixture must never inject a real tap, even if its assertion fails.
                return RootExecutionResult.Failure("fixture injection disabled")
            }
        }
        var approved = false
        val events = mutableListOf<AgentLoopEvent>()
        try {
            AgentLoop(RootScreenshotProvider(actual), fixtureModel(
                """{"action":"tap","x":500,"y":500,"reason":"fixture"}""",
            ), guarded).run(AgentLoopRequest(
                RootPilotConfig(task = "fixture", allowScreenUpload = true, manualConfirmation = true), 1, true,
            )) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                        "am start -n ${context.packageName}/com.example.agent.rootpilot.RootPilotActivity",
                    )).bufferedReader().use { output -> assertTrue(output.readText().contains("Starting: Intent")) }
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (automation.rootInActiveWindow?.packageName?.toString() != context.packageName &&
                        SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                    assertEquals(context.packageName, automation.rootInActiveWindow?.packageName?.toString())
                    approved = true
                    it.approval.approve()
                }
            }
            assertTrue(approved)
            assertEquals(0, wouldExecute)
            assertTrue((events.lastOrNull() as? AgentLoopEvent.Failed)?.message?.contains("未执行动作") == true)
        } finally {
            openFixture(waitForLaunch = false)
        }
    }

    private fun requireScreenAcceptance() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("screenContextAcceptance") == "true")
    }

    private fun stage(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("fixtureStage", value) })

    private fun fixtureModel(action: String) = object : DeepSeekClient {
        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
            assertEquals(fixturePackage, request.observation?.foregroundPackage)
            assertEquals(fixturePackage, request.observation?.focusedPackage)
            return DeepSeekActionResult.Success(action)
        }
    }

    @Test fun catalogLaunchesAnAppOutsideTheOldSettingsAllowlist() = runBlocking {
        val catalog = AndroidAppCatalog(context)
        val apps = catalog.listApps()
        assertEquals(apps.size, apps.map { it.packageName }.distinct().size)
        assertFalse(apps.any { it.packageName == context.packageName })
        val fixture = apps.single { it.packageName == fixturePackage }
        assertTrue(SuRootExecutor(appCatalog = catalog).execute(ExecutableRootAction.OpenApp(fixture)) is RootExecutionResult.Success)
        assertEquals(fixturePackage, node("android:id/edit").packageName.toString())
    }

    @Test fun unicodeInsertionSelectionAndImeRestoration() = runBlocking {
        require(environment.isEnabled(environment.ownId)) { "Enable RootPilot IME manually before device tests" }
        openFixture(); focus("android:id/edit")
        val original = environment.currentId()
        val input = AndroidImeEnvironment.createInput(context)
        val text = "中文 😀 ; ' $(id)\n第二行"
        assertTrue(input.type(text) { assertEquals(fixturePackage, it); true } is RootExecutionResult.Success)
        assertEquals(original, environment.currentId())
        assertNull(store.read())
        assertEquals(text, node("android:id/edit").text.toString())
        assertTrue(node("android:id/edit").performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
        }))
        assertTrue(input.type("替换") { true } is RootExecutionResult.Success)
        assertEquals("替换" + text.drop(2), node("android:id/edit").text.toString())
        assertEquals(original, environment.currentId())
    }

    @Test fun changedEditorAndPasswordNeverReceiveText() = runBlocking {
        require(environment.isEnabled(environment.ownId))
        openFixture(); focus("android:id/edit")
        val original = environment.currentId()
        val input = AndroidImeEnvironment.createInput(context)
        val result = input.type("不应输入") {
            focus("android:id/text2")
            withTimeout(5_000) {
                while (InputConnectionBridge.editor.value?.info?.fieldId != android.R.id.text2) kotlinx.coroutines.delay(25)
            }
            true
        }
        assertTrue(result is RootExecutionResult.Failure)
        assertEquals(original, environment.currentId())
        assertTrue(node("android:id/edit").text?.toString().let { it.isNullOrEmpty() || it == "第一个输入框" })
        assertTrue(node("android:id/text2").text?.toString().let { it.isNullOrEmpty() || it == "第二个输入框" })
        focus("android:id/input")
        var requestedApproval = false
        assertTrue(input.type("不应输入") { requestedApproval = true; true } is RootExecutionResult.Failure)
        assertFalse(requestedApproval)
        assertEquals(original, environment.currentId())
    }

    @Test fun cancellingPendingApprovalRestoresImeWithoutInput() = runBlocking {
        require(environment.isEnabled(environment.ownId))
        openFixture(); focus("android:id/edit")
        val original = environment.currentId()
        val ready = CompletableDeferred<Unit>()
        val job = async {
            AndroidImeEnvironment.createInput(context).type("不应输入") { ready.complete(Unit); awaitCancellation() }
        }
        withTimeout(8_000) { ready.await() }
        job.cancelAndJoin()
        assertEquals(original, environment.currentId())
        assertNull(store.read())
    }

    @Test fun changingApplicationDuringApprovalRejectsCommit() = runBlocking {
        require(environment.isEnabled(environment.ownId))
        openFixture(); focus("android:id/edit")
        val original = environment.currentId()
        val catalog = AndroidAppCatalog(context)
        val settings = catalog.listApps().single { it.packageName == "com.android.settings" }
        val result = AndroidImeEnvironment.createInput(context).type("不应输入") {
            assertTrue(SuRootExecutor(appCatalog = catalog).execute(ExecutableRootAction.OpenApp(settings)) is RootExecutionResult.Success)
            withTimeout(5_000) {
                while (InputConnectionBridge.editor.value?.info?.packageName == fixturePackage) kotlinx.coroutines.delay(25)
            }
            true
        }
        assertTrue(result is RootExecutionResult.Failure)
        assertEquals(original, environment.currentId())
    }
}
