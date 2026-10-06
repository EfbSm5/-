package com.example.agent.rootpilot.input

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import android.os.SystemClock
import android.os.TransactionTooLargeException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotRunStore
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotKey
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.root.RootExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, one close request on a signed empty fixture; no model, screenshots or text input. */
@RunWith(AndroidJUnit4::class)
class FixtureCloseInstrumentedTest {
    @Test fun classifySingleCloseWithoutRetry() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("fixtureCloseProbeMode")
        assumeTrue(mode == "foreground" || mode == "background")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("idle_service_required", RootPilotService.uiState.value.running)
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        val homePackage = if (mode == "background") {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            val resolved = requireNotNull(context.packageManager.resolveActivity(homeIntent, flags)?.activityInfo) {
                "home_component_required"
            }
            check(context.packageManager.queryIntentActivities(homeIntent, flags).any {
                it.activityInfo.packageName == resolved.packageName && it.activityInfo.name == resolved.name
            }) { "default_home_required" }
            resolved.packageName
        } else null
        val fixture = FixtureClient(context).also { it.verifyIdentity() }
        fixture.launch()
        val state = fixture.awaitReady()
        assertTrue("empty_fixture_required", state.getBoolean("empty"))
        assertTrue("fixture_process_identity_required", state.getInt("processId") > 0)
        if (mode == "background") {
            assertTrue("home_action_failed", SuRootExecutor().execute(
                ExecutableRootAction.Key(RootPilotKey.HOME),
            ) is RootExecutionResult.Success)
            var backgrounded = false
            for (attempt in 0 until 5) {
                val screen = SuRootExecutor().observeScreen()
                if (screen.foregroundPackage == homePackage && screen.focusedPackage == homePackage) {
                    backgrounded = true
                    break
                }
                if (attempt < 4) delay(1_000)
            }
            assertTrue("home_foreground_and_focus_required", backgrounded)
            report("background_interval_started", mode, state.getInt("processId"), 0)
            // Match the observed external-app run's background interval before its close call.
            delay(30_000)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.acquireContentProviderClient(URI)?.use { provider ->
                    if (provider.call("finish", null, null) == null) "NULL_RESULT" else "RECEIPT"
                } ?: "PROVIDER_UNAVAILABLE"
            } catch (_: DeadObjectException) { "DEAD_OBJECT" }
            catch (_: TransactionTooLargeException) { "TRANSACTION_TOO_LARGE" }
            catch (_: RemoteException) { "REMOTE_EXCEPTION" }
            catch (_: SecurityException) { "PERMISSION_DENIED" }
            catch (_: IllegalStateException) { "PROVIDER_STATE" }
            catch (_: Exception) { "TRANSPORT_FAILURE" }
        }
        report(outcome, mode!!, state.getInt("processId"), SystemClock.elapsedRealtime() - startedAt)
        // A missing receipt remains a failure; inspect/close the known fixture externally, never replay.
        assertTrue("fixture_close_$outcome", outcome == "RECEIPT")
    }

    private fun report(stage: String, mode: String, processId: Int, elapsedMs: Long) =
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("fixtureCloseStage", stage)
            putString("fixtureCloseMode", mode)
            putInt("fixtureProcessId", processId)
            putLong("fixtureCloseElapsedMs", elapsedMs)
            putInt("modelRequests", 0)
            putInt("textInputs", 0)
        })

    private companion object {
        val URI: Uri = Uri.parse("content://com.example.rootpilot.fixture.state")
    }
}
