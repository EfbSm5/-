package com.example.agent.rootpilot.input

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotApiConfigStore
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.RootPilotRunStore
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.SuRootExecutor
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observes the real service. Safe taps must be approved externally through its actual UI. */
@RunWith(AndroidJUnit4::class)
class ServiceCoordinateInstrumentedTest {
    @Test fun observeProductionFramesAndApprovals() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("serviceCoordinateProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(RootPilotService.uiState.value.status in setOf(RootPilotStatus.IDLE,
            RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)) {
            "Close the existing task before running the probe"
        }
        check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists()) {
            "Resolve the saved task before running the probe"
        }
        val catalog = AndroidAppCatalog(context)
        val fixture = catalog.listApps().single { it.packageName == instrumentation.context.packageName }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "service-coordinate-").toFile()
        val report = directory.resolve("evidence.txt")
        report.writeText("Real Service/AgentLoop; parsed actions, not raw HTTP JSON. No automatic approval.\n")
        val environment = AndroidImeEnvironment(context)
        val originalIme = environment.currentId()
        val saved = requireNotNull(RootPilotApiConfigStore.create(context).read()) { "Save API configuration first" }
        RootPilotService.updateApiConfig(saved)
        assertTrue(SuRootExecutor(appCatalog = catalog).execute(ExecutableRootAction.OpenApp(fixture)) is RootExecutionResult.Success)
        val initialState = RootPilotService.uiState.value
        val accepted = async(start = CoroutineStart.UNDISPATCHED) {
            RootPilotService.uiState.first {
                it !== initialState && it.status in setOf(RootPilotStatus.CAPTURING,
                    RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.WAITING_CONFIRMATION,
                    RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN,
                    RootPilotStatus.FAILED, RootPilotStatus.COMPLETED)
            }
        }
        try {
        RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, RootPilotConfig(
            task = "在当前验收页的第一个输入框输入中文测试，不触碰密码框，不发送任何内容",
            manualConfirmation = true,
            allowScreenUpload = true,
        ))
        instrumentation.sendStatus(0, Bundle().apply { putString("serviceEvidence", "cache/${directory.name}") })
            val finished = withTimeoutOrNull(180_000) {
                accepted.await()
                repeat(3) {
                    val state = RootPilotService.uiState.first {
                        it.status in setOf(RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.FAILED,
                            RootPilotStatus.COMPLETED, RootPilotStatus.STOPPED)
                    }
                    val frame = state.frame
                    frame?.let { directory.resolve("step-${state.step}.jpg").writeBytes(it.bytes) }
                    val action = state.pendingAction
                    val description = when (action) {
                        is RootPilotAction.Tap -> "tap(${action.x},${action.y})"
                        is RootPilotAction.Type -> "type(length=${action.text.length}); not approved"
                        null -> "none"
                        else -> "non-tap; not approved"
                    }
                    report.appendText("uptime=${SystemClock.uptimeMillis()} step=${state.step} status=${state.status} action=$description image=${frame?.width}x${frame?.height} physical=${frame?.physicalWidth}x${frame?.physicalHeight}\n")
                    instrumentation.sendStatus(0, Bundle().apply { putString("serviceProbeStep", "${state.step}:$description") })
                    if (state.status != RootPilotStatus.WAITING_CONFIRMATION || action !is RootPilotAction.Tap) return@withTimeoutOrNull true
                    // The external operator verifies the foreground/target before using the overlay.
                    RootPilotService.uiState.first { it.pendingAction !== action || it.status != RootPilotStatus.WAITING_CONFIRMATION }
                }
                true
            }
            if (finished == null) report.appendText("stopped: 180-second observation budget; test adds no retries; production parsing retry remains\n")
        } finally {
            accepted.cancel()
            withContext(NonCancellable) {
            RootPilotService.send(context, RootPilotService.ACTION_STOP)
            val restored = withTimeoutOrNull(10_000) {
                while (RootPilotService.uiState.value.status != RootPilotStatus.STOPPED ||
                    environment.currentId() != originalIme || FileImeRestoreStore(context).read() != null
                ) delay(100)
                true
            }
            report.appendText("stopped_and_original_ime_restored=${restored == true}\n")
            assertTrue("Stop/IME restoration must be verified", restored == true)
            }
        }
    }
}
