package com.example.agent.rootpilot.information

import android.app.KeyguardManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotRunStore
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.DisplayRoutingRootExecutor
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.sameTarget
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import com.example.agent.rootpilot.withRootPilotAcceptanceScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in offline production-router check; calculator reads only, no screenshot, model or input. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayUiTreeInstrumentedTest {
    @Test
    fun restoresFlagsOnFailureCancellationAndBeforeMainReads() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualUiTreeScopeAcceptance") == "true")
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val state = RootPilotService.uiState.value
            check(!state.running && state.pendingAction == null &&
                !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "scope_busy_or_recovery" }
            val service = RootPilotAccessibilityService.connectedService ?: error("scope_accessibility_unavailable")
            val original = service.serviceInfo?.flags ?: error("scope_service_info_unavailable")
            val layout = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            check(original and layout == 0) { "scope_layout_already_enabled" }
            fun restored() = RootPilotAccessibilityService.connectedService === service &&
                service.serviceInfo?.flags == original && RootPilotService.uiState.value === state
            var complete = false
            try {
                withTimeout(10_000) {
                    var failureObserved = false
                    try {
                        service.queryTree(true) {
                            check(service.serviceInfo?.flags == (original or layout)) { "scope_layout_not_applied" }
                            throw ScopeCollectionFailure()
                        }
                    } catch (_: ScopeCollectionFailure) { failureObserved = true }
                    check(failureObserved && restored()) { "scope_failure_not_restored" }
                    val entered = CompletableDeferred<Unit>()
                    val cancelled = launch {
                        service.queryTree(true) { entered.complete(Unit); awaitCancellation() }
                    }
                    entered.await()
                    cancelled.cancelAndJoin()
                    check(restored()) { "scope_cancel_not_restored" }
                    val virtualEntered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val virtual = launch {
                        service.queryTree(true) { virtualEntered.complete(Unit); release.await() }
                    }
                    virtualEntered.await()
                    var mainEntered = false
                    val main = async(start = CoroutineStart.UNDISPATCHED) {
                        service.queryTree(false) { mainEntered = true; check(restored()) { "scope_main_flags_changed" } }
                    }
                    check(!mainEntered) { "scope_main_not_serialized" }
                    release.complete(Unit)
                    virtual.join()
                    main.await()
                    check(mainEntered && restored()) { "scope_main_or_restore_failed" }
                    complete = true
                }
            } finally {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putBoolean("scopeFailureCancellationAndMainPassed", complete && restored())
                    putBoolean("scopeFlagsRestored", restored())
                })
            }
        }
    }

    private class ScopeCollectionFailure : Exception()

    @Test
    fun readsOwnedTreeAndRejectsForeignOrReleasedSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualUiTreeAcceptance") == "true")
        runBlocking { withRootPilotAcceptanceScreen { accept() } }
    }

    private suspend fun accept() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun requireState(value: Boolean, code: String) { if (!value) throw AssertionError(code) }
        val originalState = RootPilotService.uiState.value
        val originalIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val manager = context.getSystemService(DisplayManager::class.java)
            ?: throw AssertionError("display_manager_unavailable")
        fun noPrivateDisplay() = manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }
        requireState(context.packageName == "com.example.agent", "target_package")
        requireState(!originalState.running && originalState.pendingAction == null &&
            !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), "busy_or_recovery")
        requireState(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false,
            "device_locked")
        requireState(RootPilotAccessibilityService.connectedService != null, "accessibility_unavailable")
        val originalService = RootPilotAccessibilityService.connectedService
            ?: throw AssertionError("accessibility_unavailable")
        val originalFlags = originalService.serviceInfo?.flags
            ?: throw AssertionError("service_info_unavailable")
        requireState(!originalIme.isNullOrBlank() && noPrivateDisplay(), "environment_not_ready")
        fun calculator(): RootPilotApp {
            val component = context.packageManager.getLaunchIntentForPackage(CALCULATOR)?.component
                ?: throw AssertionError("calculator_unavailable")
            requireState(component.packageName == CALCULATOR &&
                component.className == "com.miui.calculator.cal.CalculatorActivity", "calculator_identity")
            return RootPilotApp(CALCULATOR, "系统计算器", component.className)
        }
        val main = RejectMain()
        val executor = DisplayRoutingRootExecutor(context, main, AppCatalog { listOf(calculator()) })
        var complete = false
        var cleaned = false
        var failure: Throwable? = null
        var nodes = 0
        var truncated = false
        try {
            withTimeout(60_000) {
                requireState(executor.beginRun(RootPilotConfig(executionDisplay = ExecutionDisplay.VIRTUAL,
                    virtualDisplayStartPackage = CALCULATOR)) is RootExecutionResult.Success, "begin_failed")
                requireState(executor.execute(ExecutableRootAction.OpenApp(calculator())) is RootExecutionResult.Success,
                    "open_failed")
                delay(500) // Match the production loop's post-action settle interval.
                var expected = executor.observeScreen()
                repeat(5) { attempt ->
                    if (expected.foregroundPackage != CALCULATOR || expected.focusedPackage != CALCULATOR) {
                        if (attempt > 0) delay(1_000)
                        expected = executor.observeScreen()
                    }
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putBoolean("observationDisplayPositive", expected.displayId > 0)
                    putBoolean("observationSessionMatches", expected.sessionId == executor.sessionIdentity)
                    putBoolean("observationForegroundCalculator", expected.foregroundPackage == CALCULATOR)
                    putBoolean("observationFocusedCalculator", expected.focusedPackage == CALCULATOR)
                    putBoolean("observationWindowBound", expected.focusedWindowId != null)
                    putBoolean("observationKeyboardKnown", expected.keyboardVisible != null)
                })
                requireState(expected.displayId > 0 && expected.sessionId == executor.sessionIdentity &&
                    expected.foregroundPackage == CALCULATOR && expected.focusedPackage == CALCULATOR &&
                    expected.focusedWindowId != null, "calculator_not_focused")
                fun rejected(result: DeviceInfoResult) {
                    requireState(result.unavailable == DeviceInfoUnavailable.TARGET_NOT_READY && result.data == null,
                        "foreign_or_released_tree_returned")
                }
                rejected(executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected.copy(displayId = 0, sessionId = null)))
                rejected(executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected.copy(sessionId = "foreign-session")))
                rejected(AndroidUiTreeProvider(context.packageName).query(expected))
                rejected(AndroidUiTreeProvider(context.packageName, DisplaySession(expected.displayId, expected.sessionId!!))
                    .query(expected.copy(displayId = 0, sessionId = null)))
                val result = executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected)
                requireState(RootPilotAccessibilityService.connectedService === originalService &&
                    originalService.serviceInfo?.flags == originalFlags, "tree_flags_not_restored")
                requireState(result.unavailable == null && result.source == DeviceInfoSource.UI_SEMANTICS,
                    "owned_tree_unavailable")
                val data = result.data ?: throw AssertionError("tree_data_missing")
                requireState(data["display_id"]?.jsonPrimitive?.intOrNull == expected.displayId &&
                    data["package_name"]?.jsonPrimitive?.contentOrNull == CALCULATOR &&
                    data["bounds_unit"]?.jsonPrimitive?.contentOrNull == "physical_screen_pixels", "tree_metadata")
                val tree = data["nodes"]?.jsonArray ?: throw AssertionError("tree_nodes_missing")
                nodes = tree.size
                truncated = result.truncated
                requireState(nodes in 1..UiTreeSnapshot.MAX_NODES, "tree_limit")
                if (nodes == 1 && truncated &&
                    InstrumentationRegistry.getArguments().getString("diagnoseVirtualTreeCache") == "true") {
                    val service = RootPilotAccessibilityService.connectedService
                        ?: throw AssertionError("accessibility_unavailable")
                    val window = service.windowsOnAllDisplays[expected.displayId]?.singleOrNull {
                        it.displayId == expected.displayId && it.isFocused &&
                            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                    } ?: throw AssertionError("diagnostic_window_unavailable")
                    val root = window.root ?: throw AssertionError("diagnostic_root_unavailable")
                    requireState(root.windowId == window.id && root.packageName?.toString() == CALCULATOR,
                        "diagnostic_root_identity")
                    var missing = 0
                    var hidden = 0
                    var becameVisible = 0
                    var refreshFailed = 0
                    var foreign = 0
                    val comparePrefetch = InstrumentationRegistry.getArguments()
                        .getString("diagnoseVirtualTreePrefetch") == "true"
                    var noPrefetchMissing = 0
                    var noPrefetchForeign = 0
                    var noPrefetchVisible = 0
                    var noPrefetchHidden = 0
                    val childrenBefore = root.childCount
                    val rootRefreshed = root.refresh()
                    requireState(root.windowId == window.id && root.packageName?.toString() == CALCULATOR,
                        "diagnostic_root_identity")
                    val childrenAfter = root.childCount
                    requireState(childrenAfter <= 32, "diagnostic_child_limit")
                    repeat(childrenAfter) { index ->
                        val child = root.getChild(index)
                        if (comparePrefetch) {
                            val direct = root.getChild(index, 0)
                            when {
                                direct == null -> noPrefetchMissing++
                                direct.windowId != window.id || direct.packageName?.toString() != CALCULATOR ->
                                    noPrefetchForeign++
                                direct.isVisibleToUser -> noPrefetchVisible++
                                else -> noPrefetchHidden++
                            }
                        }
                        if (child == null) missing++ else {
                            if (child.windowId != window.id || child.packageName?.toString() != CALCULATOR) {
                                foreign++
                                return@repeat
                            }
                            val visible = child.isVisibleToUser
                            if (!visible) hidden++
                            if (!child.refresh()) refreshFailed++
                            else if (!visible && child.isVisibleToUser) becameVisible++
                        }
                    }
                    val linkCounts = if (InstrumentationRegistry.getArguments()
                            .getString("diagnoseVirtualTreeLinks") == "true") {
                        inspectCalculatorLinks(root, window.id)
                    } else null
                    val layoutCounts = if (InstrumentationRegistry.getArguments()
                            .getString("diagnoseVirtualTreeLayoutNodes") == "true") {
                        inspectLayoutFlag(service, executor, expected)
                    } else null
                    requireState(expected.sameTarget(executor.observeScreen()), "diagnostic_window_changed")
                    val refreshed = executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putBoolean("diagnosticRootRefreshSucceeded", rootRefreshed)
                        putInt("diagnosticChildrenBefore", childrenBefore); putInt("diagnosticChildrenAfter", childrenAfter)
                        putInt("diagnosticMissingChildren", missing); putInt("diagnosticHiddenChildren", hidden)
                        putInt("diagnosticChildrenBecameVisible", becameVisible); putInt("diagnosticRefreshFailed", refreshFailed)
                        putInt("diagnosticForeignChildren", foreign)
                        if (comparePrefetch) {
                            putInt("diagnosticNoPrefetchMissing", noPrefetchMissing)
                            putInt("diagnosticNoPrefetchForeign", noPrefetchForeign)
                            putInt("diagnosticNoPrefetchVisible", noPrefetchVisible)
                            putInt("diagnosticNoPrefetchHidden", noPrefetchHidden)
                        }
                        linkCounts?.let(::putAll)
                        layoutCounts?.let(::putAll)
                        putBoolean("diagnosticRefreshedTreeAvailable", refreshed.unavailable == null)
                        putInt("diagnosticRefreshedNodeCount", refreshed.data?.get("nodes")?.jsonArray?.size ?: 0)
                    })
                    // Keep the original result as the acceptance assertion; the probe cannot turn it into PASS.
                }
                val rootBounds = tree.first().jsonObject["bounds"]?.jsonObject
                    ?: throw AssertionError("root_bounds_missing")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    for (edge in listOf("left", "top", "right", "bottom")) {
                        rootBounds[edge]?.jsonPrimitive?.intOrNull?.let { putInt("rootBounds_$edge", it) }
                    }
                })
                requireState(rootBounds["left"]?.jsonPrimitive?.intOrNull == 0 &&
                    rootBounds["top"]?.jsonPrimitive?.intOrNull == 0 &&
                    rootBounds["right"]?.jsonPrimitive?.intOrNull == VirtualDisplayProtocol.WIDTH &&
                    rootBounds["bottom"]?.jsonPrimitive?.intOrNull == VirtualDisplayProtocol.HEIGHT, "virtual_pixel_bounds")
                val labels = tree.flatMap { node -> listOf("text", "content_description").mapNotNull {
                    node.jsonObject[it]?.jsonPrimitive?.contentOrNull
                } }.map { label -> label.filterNot { it.isWhitespace() || it == ',' } }
                requireState(labels.any { it.contains("123×45") } && labels.any { it.contains("5535") },
                    "known_product_not_present")
                requireState(expected.sameTarget(executor.observeScreen()), "window_changed")
                requireState(executor.endRun() is RootExecutionResult.Success, "close_failed")
                rejected(executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected))
                requireState(main.calls == 0, "main_fallback")
                complete = true
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(10_000) {
                        cleaned = executor.endRun() is RootExecutionResult.Success && !executor.validateSession() &&
                            executor.sessionIdentity == null && noPrivateDisplay()
                        requireState(cleaned && main.calls == 0, "cleanup_or_fallback")
                        requireState(RootPilotService.uiState.value === originalState &&
                            RootPilotAccessibilityService.connectedService === originalService &&
                            originalService.serviceInfo?.flags == originalFlags &&
                            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == originalIme &&
                            !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
                            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), "environment_changed")
                    }
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                    if (!cleaned) executor.cancel()
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putBoolean("virtualTreeReadPassed", complete && cleaned && failure == null)
                    putInt("nodeCount", nodes); putBoolean("truncated", truncated)
                    putInt("mainDisplayCalls", main.calls); putBoolean("cleanupConfirmed", cleaned)
                    putInt("modelRequests", 0); putInt("inputActions", 0); putInt("screenshotReads", 0)
                })
            }
        }
        failure?.let { throw it }
        requireState(complete && cleaned, "acceptance_incomplete")
    }

    private suspend fun inspectLayoutFlag(
        service: RootPilotAccessibilityService,
        executor: DisplayRoutingRootExecutor,
        expected: ScreenObservation,
    ): Bundle {
        val info = service.serviceInfo ?: throw AssertionError("diagnostic_service_info_unavailable")
        val originalFlags = info.flags
        val include = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        try {
            check(RootPilotAccessibilityService.connectedService === service &&
                expected.sameTarget(executor.observeScreen())) { "diagnostic_layout_target_changed" }
            val comparison = Bundle().apply {
                putBoolean("diagnosticCacheClearSucceeded", service.clearCache())
                putAll(summarizeTree("diagnosticCacheClear", executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected)))
            }
            service.serviceInfo = info
            check(service.serviceInfo?.flags == originalFlags) { "diagnostic_same_flags_changed" }
            comparison.putAll(summarizeTree("diagnosticSameFlags", executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected)))
            info.flags = originalFlags or include
            service.serviceInfo = info
            check(service.serviceInfo?.flags == info.flags) { "diagnostic_layout_flag_not_applied" }
            val result = executor.queryDeviceInfo(DeviceInfoTool.UI_TREE, expected)
            comparison.putAll(summarizeTree("diagnosticLayout", result))
            return comparison.apply {
                putBoolean("diagnosticLayoutOriginallyEnabled", originalFlags and include != 0)
            }
        } finally {
            withContext(NonCancellable) {
                val restored = runCatching {
                    withTimeout(5_000) {
                        if (RootPilotAccessibilityService.connectedService !== service) return@withTimeout false
                        info.flags = originalFlags
                        service.serviceInfo = info
                        val restoredFlags = service.serviceInfo?.flags
                        RootPilotAccessibilityService.connectedService === service && restoredFlags == originalFlags
                    }
                }.getOrDefault(false)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putBoolean("diagnosticLayoutFlagsRestored", restored)
                })
                check(restored) { "diagnostic_layout_flag_restore_failed" }
            }
        }
    }

    private fun summarizeTree(prefix: String, result: DeviceInfoResult): Bundle {
        val nodes = result.data?.get("nodes")?.jsonArray
        val labels = nodes.orEmpty().flatMap { node -> listOf("text", "content_description").mapNotNull {
            node.jsonObject[it]?.jsonPrimitive?.contentOrNull
        } }.map { label -> label.filterNot { it.isWhitespace() || it == ',' } }
        return Bundle().apply {
            putBoolean("${prefix}TreeAvailable", result.unavailable == null)
            putInt("${prefix}NodeCount", nodes?.size ?: 0)
            putBoolean("${prefix}Truncated", result.truncated)
            putBoolean("${prefix}KnownExpression", labels.any { it.contains("123×45") })
            putBoolean("${prefix}KnownProduct", labels.any { it.contains("5535") })
        }
    }

    private fun inspectCalculatorLinks(root: android.view.accessibility.AccessibilityNodeInfo, windowId: Int): Bundle {
        val matches = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/expression")
        require(matches.size <= 8) { "diagnostic_id_limit" }
        var owned = 0
        var visible = 0
        var sensitive = 0
        var foreign = 0
        var parent: android.view.accessibility.AccessibilityNodeInfo? = null
        for (node in matches) {
            if (node.windowId != windowId || node.packageName?.toString() != CALCULATOR) {
                foreign++
                continue
            }
            owned++
            if (node.isVisibleToUser) visible++
            val protected = node.isPassword || node.isAccessibilityDataSensitive
            if (protected) sensitive++
            if (parent == null && node.isVisibleToUser && !protected) {
                val candidate = node.getParent(0)
                if (candidate != null && candidate.windowId == windowId &&
                    candidate.packageName?.toString() == CALCULATOR) parent = candidate
            }
        }
        val selected = parent
        var ancestor = selected
        var ancestorDepth = 0
        var ancestorEnded = false
        var ancestorForeign = false
        var ancestorProtected = false
        var ancestorHidden = false
        while (ancestor != null && ancestorDepth < 16 && ancestor != root) {
            if (ancestor.windowId != windowId || ancestor.packageName?.toString() != CALCULATOR) {
                ancestorForeign = true
                break
            }
            if (ancestor.isPassword || ancestor.isAccessibilityDataSensitive) {
                ancestorProtected = true
                break
            }
            if (!ancestor.isVisibleToUser) {
                ancestorHidden = true
                break
            }
            val next = ancestor.getParent(0)
            ancestorDepth++
            if (next == null) {
                ancestorEnded = true
                break
            }
            ancestor = next
        }
        val topReadable = ancestor != null && !ancestorForeign && !ancestorProtected && !ancestorHidden &&
            ancestor.windowId == windowId && ancestor.packageName?.toString() == CALCULATOR &&
            ancestor.isVisibleToUser && !ancestor.isPassword && !ancestor.isAccessibilityDataSensitive
        val readableParent = selected != null && selected.isVisibleToUser &&
            !selected.isPassword && !selected.isAccessibilityDataSensitive
        var childCount = 0
        var missingChildren = 0
        var ownedChildren = 0
        var foreignChildren = 0
        if (readableParent) {
            childCount = selected!!.childCount
            require(childCount in 0..32) { "diagnostic_parent_child_limit" }
            repeat(childCount) { index ->
                val child = selected.getChild(index, 0)
                when {
                    child == null -> missingChildren++
                    child.windowId != windowId || child.packageName?.toString() != CALCULATOR -> foreignChildren++
                    else -> ownedChildren++
                }
            }
        }
        return Bundle().apply {
            putInt("diagnosticIdOwned", owned); putInt("diagnosticIdVisible", visible)
            putInt("diagnosticIdSensitive", sensitive); putInt("diagnosticIdForeign", foreign)
            putBoolean("diagnosticIdParentReadable", readableParent)
            putBoolean("diagnosticIdParentIsRoot", selected == root)
            putInt("diagnosticIdParentChildCount", childCount)
            putInt("diagnosticIdParentMissingChildren", missingChildren)
            putInt("diagnosticIdParentOwnedChildren", ownedChildren)
            putInt("diagnosticIdParentForeignChildren", foreignChildren)
            putInt("diagnosticAncestorDepth", ancestorDepth)
            putBoolean("diagnosticAncestorEnded", ancestorEnded)
            putBoolean("diagnosticAncestorForeign", ancestorForeign)
            putBoolean("diagnosticAncestorProtected", ancestorProtected)
            putBoolean("diagnosticAncestorHidden", ancestorHidden)
            putBoolean("diagnosticAncestorReachedRoot", ancestor == root)
            putBoolean("diagnosticAncestorReadable", topReadable)
            if (topReadable) {
                putInt("diagnosticAncestorChildCount", ancestor!!.childCount)
                putBoolean("diagnosticAncestorClassMatchesRoot", ancestor.className == root.className)
            }
        }
    }

    private class RejectMain : RootExecutor {
        var calls = 0
        private fun reject(): Nothing { calls++; throw AssertionError("main_fallback") }
        override suspend fun checkRoot(): RootExecutionResult = reject()
        override suspend fun observeScreen(): ScreenObservation = reject()
        override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult = reject()
        override suspend fun captureScreen(): RootScreenshotResult = reject()
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = reject()
        override fun cancel() = Unit
    }

    private companion object { const val CALCULATOR = "com.miui.calculator" }
}
