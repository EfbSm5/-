package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/** Test-only foreground window hold; never wakes or unlocks the device or changes system settings. */
internal suspend fun <T> withRootPilotAcceptanceScreen(block: suspend () -> T): T = coroutineScope {
    if (InstrumentationRegistry.getArguments().getString("holdRootPilotScreen") != "true") return@coroutineScope block()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun requireState(value: Boolean, code: String) { if (!value) throw AssertionError(code) }
    requireState(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, "screen_hold_device_locked")
    requireState(!RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null &&
        !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
        !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), "screen_hold_busy_or_recovery")
    var activity: RootPilotActivity? = null
    var originalKeep = false
    var flagOwned = false
    var restored = false
    var launchRequested = false
    val resumed = AtomicReference<RootPilotActivity?>()
    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(current: Activity) {
            if (current is RootPilotActivity) resumed.compareAndSet(null, current)
        }
        override fun onActivityCreated(current: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(current: Activity) = Unit
        override fun onActivityPaused(current: Activity) = Unit
        override fun onActivityStopped(current: Activity) = Unit
        override fun onActivitySaveInstanceState(current: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(current: Activity) = Unit
    }
    val application = context.applicationContext as Application
    var registered = false
    try {
        lateinit var model: RootPilotViewModel
        try {
            withTimeout(5_000) {
                withContext(Dispatchers.Main.immediate) {
                    application.registerActivityLifecycleCallbacks(callbacks)
                    registered = true
                    launchRequested = true
                    instrumentation.sendStatus(0, Bundle().apply { putBoolean("screenHoldReadyForActivity", true) })
                }
                while (activity == null) {
                    withContext(Dispatchers.Main.immediate) {
                        val launched = resumed.get()
                        if (launched != null) {
                            activity = launched
                            requireState(launched.display?.displayId == Display.DEFAULT_DISPLAY, "screen_hold_main_display_required")
                            requireState(WindowInspector.getGlobalWindowViews().any { it === launched.window.decorView },
                                "screen_hold_window_unavailable")
                            originalKeep = launched.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                            flagOwned = true
                            launched.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            model = ViewModelProvider(launched, RootPilotViewModel.Factory(context.applicationContext))
                                .get(RootPilotViewModel::class.java)
                        }
                    }
                    if (activity == null) delay(25)
                }
                while (model.apiState.value.busy || model.appLaunchState.value.busy || model.fileWorkspaceBusy.value) delay(25)
                while (!withContext(Dispatchers.Main.immediate) { activity?.hasWindowFocus() == true }) delay(25)
            }
            requireState(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false,
                "screen_hold_device_locked")
        } catch (_: Exception) { throw AssertionError("screen_hold_setup_failed") }
        val held = activity ?: throw AssertionError("screen_hold_window_unavailable")
        val guard = launch {
            while (isActive) {
                withContext(Dispatchers.Main.immediate) {
                    requireState(!held.isDestroyed && !held.isFinishing &&
                        held.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && held.window.decorView.isShown &&
                        held.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 &&
                        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false,
                        "screen_hold_lost")
                }
                delay(50)
            }
        }
        try { block() } finally { guard.cancelAndJoin() }
    } finally {
        withContext(NonCancellable) {
            withTimeout(5_000) {
                withContext(Dispatchers.Main.immediate) {
                    if (registered) application.unregisterActivityLifecycleCallbacks(callbacks)
                    activity?.let { launched ->
                        try {
                            if (flagOwned) {
                                if (originalKeep) launched.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                else launched.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                restored = (launched.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) == originalKeep
                            }
                        } finally { launched.finish() }
                    }
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putBoolean("screenHoldRequested", true)
                putBoolean("screenHoldAttached", flagOwned)
                putBoolean("screenHoldFlagRestored", restored)
            })
            requireState(!flagOwned || restored, "screen_hold_restore_unconfirmed")
            requireState(!launchRequested || activity != null, "screen_hold_launch_unconfirmed")
        }
    }
}
