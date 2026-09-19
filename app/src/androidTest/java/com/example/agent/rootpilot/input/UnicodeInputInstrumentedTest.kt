package com.example.agent.rootpilot.input

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.SuRootExecutor
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
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        }
    }

    private fun openFixture() {
        // The fixture belongs to the test APK, so launch it with the test shell identity.
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W --user current -f 0x10008000 -n $fixturePackage/${InputFixtureActivity::class.java.name}",
        )).bufferedReader().use { assertTrue(it.readText().contains("Status: ok")) }
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
