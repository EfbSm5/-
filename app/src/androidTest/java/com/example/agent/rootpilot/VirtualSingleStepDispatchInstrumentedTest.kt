package com.example.agent.rootpilot

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public ViewModel entry with an intercepted service dispatch, never a device run. */
@RunWith(AndroidJUnit4::class)
class VirtualSingleStepDispatchInstrumentedTest {
    @Test fun dispatchesVirtualSingleStepButKeepsCaptureAndApiEditingBlocked() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualSingleStepDispatchAcceptance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val original = RootPilotService.uiState.value
        check(!original.running && original.pendingAction == null && original.status in setOf(
            RootPilotStatus.IDLE, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED)) { "service_busy" }
        check(!target.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists()) { "recovery_pending" }
        RootPilotService.historyState(target)
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "virtual-dispatch-").toFile()
        val dispatches = mutableListOf<Intent>()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = directory
            override fun startForegroundService(service: Intent): ComponentName {
                dispatches += Intent(service)
                return ComponentName(packageName, RootPilotService::class.java.name)
            }
        }
        val synthetic = RootPilotApiConfig("fixture", "http://127.0.0.1", "fixture")
        val apiFile = directory.resolve("synthetic-api.enc").apply { writeBytes(byteArrayOf(1)) }
        val cipher = object : ApiConfigCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("synthetic_store_write_forbidden")
            override fun decrypt(ciphertext: ByteArray): ByteArray {
                check(ciphertext.contentEquals(byteArrayOf(1))) { "synthetic_store_invalid" }
                return Json.encodeToString(synthetic).encodeToByteArray()
            }
        }
        var owner: ViewModelStore? = null
        var model: RootPilotViewModel? = null
        var stage = "create"
        var failure: String? = null
        val evidence = Bundle()
        fun onMain(block: () -> Unit) {
            var thrown: Throwable? = null
            instrumentation.runOnMainSync {
                try { block() } catch (error: Throwable) { thrown = error }
            }
            thrown?.let { throw it }
        }
        try {
            onMain {
                model = RootPilotViewModel(context, RootPilotApiConfigStore(apiFile, cipher),
                    ioDispatcher = Dispatchers.Main.immediate,
                    appCatalog = AppCatalog { emptyList() },
                    appLaunchStore = AppLaunchAllowlistStore(directory.resolve("apps.json")))
                owner = ViewModelStore().also { it.put("model", model!!) }
            }
            val viewModel = model ?: error("model_missing")
            withTimeout(5_000) {
                while (viewModel.apiState.value.busy || viewModel.appLaunchState.value.busy ||
                    viewModel.fileWorkspaceBusy.value) delay(20)
            }
            check(viewModel.apiState.value.configured && !viewModel.apiState.value.editing && dispatches.isEmpty()) { "synthetic_setup_failed" }
            val config = synthetic.applyTo(RootPilotConfig(task = "fixed dispatch", allowScreenUpload = true,
                executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = "com.example.rootpilot.fixture"))
            stage = "dispatch"
            onMain {
                RootPilotService.updateConfig(config)
                viewModel.singleStep()
                check(dispatches.size == 1 && dispatches.single().action == RootPilotService.ACTION_SINGLE_STEP &&
                    dispatches.single().component?.className == RootPilotService::class.java.name &&
                    dispatches.single().getStringExtra(RootPilotService.EXTRA_EXECUTION_DISPLAY) == ExecutionDisplay.VIRTUAL.name &&
                    dispatches.single().getBooleanExtra(RootPilotService.EXTRA_ALLOW_SCREEN_UPLOAD, false)) { "single_step_not_dispatched" }
                viewModel.captureScreen()
                check(dispatches.size == 1) { "virtual_capture_dispatched" }
                viewModel.editApiConfig()
                check(viewModel.apiState.value.editing) { "editor_not_open" }
                viewModel.singleStep()
                check(dispatches.size == 1) { "editing_dispatched" }
            }
            evidence.putBoolean("singleStepDispatched", true)
            evidence.putBoolean("virtualCaptureRejected", true)
            evidence.putBoolean("apiEditingRejected", true)
            stage = "complete"
        } catch (_: Throwable) {
            failure = "dispatch_regression_failed_$stage"
        } finally {
            try {
                onMain {
                    owner?.clear()
                    if (!RootPilotService.uiState.value.running &&
                        RootPilotService.uiState.value.config.apiKey == synthetic.apiKey) {
                        RootPilotService.updateApiConfig(if (original.apiConfigured) RootPilotApiConfig(
                            original.config.apiKey, original.config.baseUrl, original.config.model) else null)
                        RootPilotService.updateConfig(original.config)
                    }
                    evidence.putBoolean("configRestored", RootPilotService.uiState.value.config == original.config &&
                        RootPilotService.uiState.value.apiConfigured == original.apiConfigured)
                }
            } catch (_: Throwable) {
                if (failure == null) failure = "dispatch_regression_cleanup_unconfirmed"
            }
            evidence.putBoolean("temporaryFilesRemoved", directory.deleteRecursively())
            evidence.putBoolean("networkUsed", false)
            evidence.putBoolean("deviceServiceStarted", false)
            evidence.putString("stage", stage)
            if (!evidence.getBoolean("configRestored") || !evidence.getBoolean("temporaryFilesRemoved")) {
                if (failure == null) failure = "dispatch_regression_cleanup_unconfirmed"
            }
            evidence.putString("failure", failure ?: "none")
            instrumentation.sendStatus(0, evidence)
        }
        check(failure == null && stage == "complete") { failure ?: "dispatch_regression_unknown" }
    }
}
