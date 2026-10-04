package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.information.DeviceInfoResult
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotKey
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.DisplayRoutingRootExecutor
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.sameTarget
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline, direct-backend evidence only; no Service/model/production confirmation is exercised.
 * Select one method with its own opt-in argument. Arithmetic requires the already connected
 * RootPilot accessibility service and instrumentation --no-restart; no UiAutomation is acquired.
 * Only the virtual calculator's public nodes and images are read. Artifacts stay in a unique cache
 * directory. The fixed sequence may append a calculation; it never clears calculator history.
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayAcceptanceInstrumentedTest {
    @Test
    fun createsAndClosesEmptyVirtualDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualDisplayTransportAcceptance") == "true")
        runBlocking { accept(arithmetic = false) }
    }

    @Test
    fun calculatesKnownProductOnVirtualDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualDisplayAcceptance") == "true")
        runBlocking { accept(arithmetic = true) }
    }

    @Test
    fun cancelsOwnedVirtualDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualDisplayCancellationAcceptance") == "true")
        runBlocking { accept(arithmetic = false, cancel = true) }
    }

    @Test
    fun inspectsVirtualCalculatorWithoutInput() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualDisplayInspection") == "true")
        runBlocking { accept(arithmetic = true, inspect = true) }
    }

    @Test
    fun continuesVerifiedSingleDigitWithoutReplayingIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualDisplayContinueVerifiedOne") == "true")
        val directory = InstrumentationRegistry.getArguments().getString("priorVirtualAcceptanceDirectory").orEmpty()
        check(Regex("virtual-display-acceptance-[0-9a-f-]{36}").matches(directory), Reason.PRIOR_RECEIPT_REQUIRED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receipt = Json.parseToJsonElement(context.cacheDir.resolve(directory).resolve("metadata.json").readText()).jsonObject
        check(receipt["executedTaps"]?.jsonPrimitive?.intOrNull == 1 &&
            receipt["cleanupConfirmed"]?.jsonPrimitive?.booleanOrNull == true &&
            receipt["networkUsed"]?.jsonPrimitive?.booleanOrNull == false &&
            receipt["keySamples"]?.jsonArray?.singleOrNull()?.jsonObject?.get("index")?.jsonPrimitive?.intOrNull == 0,
            Reason.PRIOR_RECEIPT_REQUIRED)
        runBlocking { accept(arithmetic = true, startIndex = 1) }
    }

    private suspend fun accept(arithmetic: Boolean, cancel: Boolean = false, inspect: Boolean = false, startIndex: Int = 0) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = Evidence(if (inspect) "inspection" else if (arithmetic) "arithmetic" else if (cancel) "cancellation" else "transport")
        evidence.previouslyVerifiedKeys = startIndex
        val main = RejectMainDisplay()
        var backend: DisplayRoutingRootExecutor? = null
        var originalIme: String? = null
        var failure: Reason? = null
        try {
            withTimeout(RUN_TIMEOUT_MS) {
                check(context.packageName == TARGET_APP, Reason.TARGET_PACKAGE)
                evidence.directory = File(context.cacheDir, "virtual-display-acceptance-${UUID.randomUUID()}")
                    .also { check(it.mkdir(), Reason.ARTIFACT_WRITE) }
                val state = RootPilotService.uiState.value
                check(!state.running && state.pendingAction == null && state.status in IDLE_STATES, Reason.SERVICE_BUSY)
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists(), Reason.RECOVERY_PENDING)
                check(!context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), Reason.IME_RECOVERY_PENDING)
                originalIme = currentIme(context)
                check(!originalIme.isNullOrBlank(), Reason.IME_UNAVAILABLE)
                if (arithmetic) {
                    check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, Reason.DEVICE_LOCKED)
                    check(RootPilotAccessibilityService.connectedService != null, Reason.ACCESSIBILITY_UNAVAILABLE)
                }
                val resolvedApp = calculator(context)
                // Re-resolve the sole allowed component on every backend allowlist check; no store writes.
                val catalog = AppCatalog { listOf(calculator(context)) }
                val executor = DisplayRoutingRootExecutor(context, main, catalog).also { backend = it }
                evidence.stage = "begin"
                val beginResult = executor.beginRun(RootPilotConfig(
                    executionDisplay = ExecutionDisplay.VIRTUAL,
                    virtualDisplayStartPackage = resolvedApp.packageName,
                ))
                if (beginResult is RootExecutionResult.Failure) {
                    evidence.beginFailureCode = beginResult.message.takeIf { it in BACKEND_REASON_CODES }
                        ?: "unrecognized_backend_reason"
                }
                check(beginResult is RootExecutionResult.Success, Reason.BEGIN_FAILED)
                check(executor.initialApp == resolvedApp, Reason.INITIAL_APP)
                check(!executor.sessionIdentity.isNullOrBlank(), Reason.SESSION_IDENTITY)
                evidence.sessionValid = executor.validateSession()
                check(evidence.sessionValid == true, Reason.SESSION_INVALID)
                var lastTap: ExecutableRootAction.Tap? = null
                if (arithmetic) {
                    evidence.stage = "open_calculator"
                    check(executor.execute(ExecutableRootAction.OpenApp(resolvedApp)) is RootExecutionResult.Success, Reason.OPEN_FAILED)
                    delay(500) // Match AgentLoop's post-action settle interval.
                    if (!inspect) {
                        var ready = false
                        repeat(5) { attempt ->
                            if (!ready) {
                                check(executor.validateSession(), Reason.SESSION_INVALID)
                                val observed = executor.observeScreen()
                                ready = observed.displayId > 0 && observed.sessionId == executor.sessionIdentity &&
                                    observed.foregroundPackage == CALCULATOR && observed.focusedPackage == CALCULATOR
                                if (!ready && attempt < 4) delay(1_000)
                            }
                        }
                        check(ready, Reason.CALCULATOR_NOT_FOCUSED)
                    }
                    if (inspect) {
                        val pendingScreen = executor.observeScreen()
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                            putInt("inspectionDisplayId", pendingScreen.displayId)
                            putBoolean("inspectionReady", pendingScreen.displayId > 0)
                        })
                        delay(10_000) // Bounded read-only window for independent display diagnostics.
                        val screen = observe(executor, evidence)
                        saveScreenshot(executor, screen, evidence, "inspection.png")
                        val service = RootPilotAccessibilityService.connectedService ?: fail(Reason.ACCESSIBILITY_UNAVAILABLE)
                        val root = service.windowsOnAllDisplays[screen.displayId]?.singleOrNull {
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
                        }?.root ?: fail(Reason.ROOT_UNAVAILABLE)
                        check(root.packageName?.toString() == CALCULATOR, Reason.ROOT_IDENTITY)
                        val nodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/expression")
                        check(nodes.size <= 32, Reason.TREE_LIMIT)
                        evidence.expressionSamples = nodes.map { node ->
                            check(node.packageName?.toString() == CALCULATOR && !node.isPassword && !node.isAccessibilityDataSensitive,
                                Reason.SENSITIVE_NODE)
                            val bounds = Rect().also(node::getBoundsInScreen)
                            buildJsonObject {
                                put("visible", node.isVisibleToUser)
                                put("knownExpression", boundedLabels(node).map(::expression).filter { it != Expression.OTHER }
                                    .distinct().singleOrNull()?.name ?: "OTHER")
                                put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
                            }
                        }
                        check(screen.sameTarget(observe(executor)), Reason.WINDOW_CHANGED)
                    } else {
                    val initial = sample(executor, evidence, KEYS[startIndex])
                    check(if (startIndex == 0) initial.isKnownInitial() else initial.expression == Expression.ONE,
                        Reason.INITIAL_EXPRESSION)
                    evidence.initialExpressionKnown = true
                    saveScreenshot(executor, initial.observation, evidence, "initial.png")
                    verifyUnsupportedActions(context, originalIme, executor, initial, evidence)

                    for (index in startIndex until KEYS.size) {
                        val key = KEYS[index]
                        evidence.stage = "key_$index"
                        evidence.nodeFound = false
                        evidence.expressionMatched = false
                        val current = sample(executor, evidence, key)
                        evidence.expressionMatched = if (index == 0) current.isKnownInitial()
                            else current.expression == key.before
                        check(evidence.expressionMatched, Reason.EXPRESSION_MISMATCH)
                        val tap = current.tap ?: fail(Reason.KEY_UNAVAILABLE)
                        val keyEvidence = KeyEvidence(index, current.expression, tap.x, tap.y)
                        evidence.keys += keyEvidence
                        evidence.approvedKeys++
                        if (key.digit) evidence.approvedDigits++
                        val receipt = executor.execute(tap)
                        keyEvidence.receipt = if (receipt is RootExecutionResult.Success) "success" else "failure"
                        check(receipt is RootExecutionResult.Success, Reason.TAP_FAILED)
                        evidence.executedTaps++
                        lastTap = tap
                        delay(500)
                    }
                    evidence.stage = "verify_product"
                    val result = sample(executor, evidence, null)
                    evidence.result5535 = result.productInCurrentRow &&
                        result.expression in setOf(Expression.PRODUCT, Expression.COMPLETE)
                    check(evidence.result5535, Reason.RESULT_NOT_OBSERVED)
                    check(evidence.executedTaps == 7 - startIndex && evidence.approvedDigits == 5 - startIndex, Reason.EXECUTION_COUNT)
                    saveScreenshot(executor, result.observation, evidence, "result.png")
                    }
                }
                evidence.stage = "close"
                if (cancel) executor.cancel()
                check(executor.endRun() is RootExecutionResult.Success, Reason.END_UNCONFIRMED)
                evidence.endAcknowledged = true
                evidence.sessionValid = executor.validateSession()
                check(evidence.sessionValid == false && executor.sessionIdentity == null, Reason.SESSION_STILL_VALID)
                if (arithmetic && !inspect) {
                    evidence.postCloseCaptureRejected = executor.captureScreen() is RootScreenshotResult.Failure
                    check(evidence.postCloseCaptureRejected, Reason.POST_CLOSE_CAPTURE)
                    // This is the last verified key's center, used only after a confirmed session close.
                    evidence.postCloseTapRejected = executor.execute(lastTap ?: fail(Reason.KEY_UNAVAILABLE)) is RootExecutionResult.Failure
                    check(evidence.postCloseTapRejected, Reason.POST_CLOSE_TAP)
                }
                check(main.calls == 0, Reason.MAIN_DISPLAY_ROUTING)
                evidence.bodyComplete = true
            }
        } catch (error: Throwable) {
            failure = reason(error)
        } finally {
            // A second close request is unconditional, including begin/assertion/timeout failures.
            // Only acknowledgement plus invalidation confirms cleanup; cancellation alone does not.
            withContext(NonCancellable) {
                backend?.let { executor ->
                    evidence.cleanupRequested = true
                    try {
                        withTimeout(CLEANUP_TIMEOUT_MS) {
                            val closed = executor.endRun() is RootExecutionResult.Success
                            evidence.sessionValid = executor.validateSession()
                            evidence.cleanupConfirmed = closed && evidence.sessionValid == false && executor.sessionIdentity == null
                        }
                    } catch (_: Throwable) {
                        evidence.sessionValid = null
                    }
                    if (!evidence.cleanupConfirmed) {
                        evidence.cleanupReason = "cleanup_unconfirmed"
                        if (failure == null) failure = Reason.CLEANUP_UNCONFIRMED
                        try { executor.cancel() } catch (_: Throwable) { /* No release acknowledgement. */ }
                    }
                }
                try {
                    evidence.imeUnchanged = originalIme != null && currentIme(context) == originalIme &&
                        !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()
                    if (originalIme != null && !evidence.imeUnchanged && failure == null) failure = Reason.IME_CHANGED
                } catch (_: Throwable) {
                    if (failure == null) failure = Reason.IME_UNAVAILABLE
                }
                evidence.mainDisplayCalls = main.calls
                if (main.calls != 0 && failure == null) failure = Reason.MAIN_DISPLAY_ROUTING
                evidence.failure = failure?.name ?: "none"
                evidence.passed = failure == null && evidence.bodyComplete && evidence.cleanupConfirmed
                try { evidence.publish() } catch (_: Throwable) {
                    evidence.passed = false
                    if (failure == null) failure = Reason.ARTIFACT_WRITE
                }
            }
        }
        failure?.let { throw AssertionError(it.name) }
        check(evidence.passed, Reason.ACCEPTANCE_INCOMPLETE)
    }

    private fun calculator(context: Context): RootPilotApp {
        val pm = context.packageManager
        val component = pm.getLaunchIntentForPackage(CALCULATOR)?.component ?: fail(Reason.CALCULATOR_UNAVAILABLE)
        check(component.packageName == CALCULATOR && component.className == CALCULATOR_ACTIVITY, Reason.CALCULATOR_IDENTITY)
        val info = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        check(info.packageName == CALCULATOR && info.exported && info.enabled && info.applicationInfo.enabled,
            Reason.CALCULATOR_IDENTITY)
        return RootPilotApp(CALCULATOR, "系统计算器", info.name)
    }

    private suspend fun verifyUnsupportedActions(
        context: Context,
        originalIme: String?,
        backend: DisplayRoutingRootExecutor,
        initial: Sample,
        evidence: Evidence,
    ) {
        val tap = initial.tap ?: fail(Reason.KEY_UNAVAILABLE)
        val unsupported = listOf(
            ExecutableRootAction.Type("virtual-display-test"),
            ExecutableRootAction.Swipe(tap.x, tap.y, tap.x, tap.y, 300),
            ExecutableRootAction.Key(RootPilotKey.BACK),
        )
        for (action in unsupported) {
            check(!backend.supports(action), Reason.UNSUPPORTED_ACTION_ACCEPTED)
            check(backend.execute(action) is RootExecutionResult.Failure, Reason.UNSUPPORTED_ACTION_ACCEPTED)
            check(currentIme(context) == originalIme, Reason.IME_CHANGED)
            evidence.unsupportedRejected++
        }
        val uiTree = backend.queryDeviceInfo(DeviceInfoTool.UI_TREE, initial.observation)
        evidence.uiTreeNotSupported = uiTree.unavailable == DeviceInfoUnavailable.NOT_SUPPORTED && uiTree.data == null
        check(evidence.uiTreeNotSupported, Reason.UI_TREE_NOT_REJECTED)
    }

    private suspend fun observe(backend: DisplayRoutingRootExecutor, evidence: Evidence? = null): ScreenObservation {
        val observation = backend.observeScreen()
        evidence?.apply {
            displayId = observation.displayId
            calculatorForeground = observation.foregroundPackage == CALCULATOR
            calculatorFocused = observation.focusedPackage == CALCULATOR
        }
        check(observation.displayId > 0 && observation.sessionId != null && observation.sessionId == backend.sessionIdentity,
            Reason.DISPLAY_IDENTITY)
        check(observation.foregroundPackage == CALCULATOR && observation.focusedPackage == CALCULATOR &&
            observation.foregroundActivity != null && observation.focusedWindowId != null, Reason.CALCULATOR_NOT_FOCUSED)
        return observation
    }

    private suspend fun sample(backend: DisplayRoutingRootExecutor, evidence: Evidence, key: KeySpec?): Sample {
        val before = observe(backend, evidence)
        evidence.displayId = before.displayId
        val collected = withContext(Dispatchers.IO) { collectCalculator(before.displayId, key) }
        check(before.sameTarget(observe(backend)), Reason.WINDOW_CHANGED)
        check(backend.validateSession(), Reason.SESSION_INVALID)
        evidence.sessionValid = true
        if (key != null) evidence.nodeFound = collected.tap != null
        return Sample(before, collected.expression, collected.productInCurrentRow, collected.tap)
    }

    private suspend fun collectCalculator(displayId: Int, key: KeySpec?): Collected {
        val started = SystemClock.elapsedRealtime()
        val service = RootPilotAccessibilityService.connectedService ?: fail(Reason.ACCESSIBILITY_UNAVAILABLE)
        fun selectedWindow(): AccessibilityWindowInfo {
            val windows = service.windowsOnAllDisplays[displayId] ?: fail(Reason.WINDOW_UNAVAILABLE)
            check(windows.size <= MAX_WINDOWS, Reason.WINDOW_LIMIT)
            return windows.filter { it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                .singleOrNull() ?: fail(Reason.WINDOW_UNAVAILABLE)
        }
        val window = selectedWindow()
        val root = window.root ?: fail(Reason.ROOT_UNAVAILABLE)
        check(root.packageName?.toString() == CALCULATOR && root.windowId == window.id && root.isVisibleToUser,
            Reason.ROOT_IDENTITY)
        fun safe(node: AccessibilityNodeInfo) {
            check(node.windowId == window.id && node.packageName?.toString() == CALCULATOR, Reason.TREE_PACKAGE)
            check(!node.isPassword && !node.isAccessibilityDataSensitive, Reason.SENSITIVE_NODE)
        }
        fun unique(id: String): AccessibilityNodeInfo {
            val nodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/$id")
            check(nodes.size <= MAX_NODES, Reason.TREE_LIMIT)
            val visible = nodes.filter { safe(it); it.isVisibleToUser }
            check(visible.size == 1, Reason.TREE_INCOMPLETE)
            return visible.single().also {
                check(it.refresh() && it.isVisibleToUser, Reason.TREE_INCOMPLETE)
                safe(it)
            }
        }
        val expressionNodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/expression")
        check(expressionNodes.size <= MAX_NODES, Reason.TREE_LIMIT)
        val visibleExpressions = expressionNodes.filter { node ->
            safe(node)
            node.isVisibleToUser && insideDisplay(Rect().also(node::getBoundsInScreen))
        }.map { node ->
            check(node.refresh() && node.isVisibleToUser, Reason.TREE_INCOMPLETE)
            safe(node)
            node to Rect().also(node::getBoundsInScreen)
        }
        val bottom = visibleExpressions.maxOfOrNull { it.second.bottom } ?: fail(Reason.EXPRESSION_UNAVAILABLE)
        val current = visibleExpressions.filter { it.second.bottom == bottom }.singleOrNull()
            ?: fail(Reason.EXPRESSION_UNAVAILABLE)
        // This fixed calculator renders history above the current input row. Require
        // a unique, non-overlapping lowest expression, never a matching historical value.
        check(visibleExpressions.all { it === current || it.second.bottom <= current.second.top }, Reason.EXPRESSION_UNAVAILABLE)
        val expressionNode = current.first
        val labels = boundedLabels(expressionNode).map(::expression).filter { it != Expression.OTHER }.distinct()
        check(labels.size == 1, Reason.EXPRESSION_UNAVAILABLE)
        var ancestor = expressionNode.parent
        var row: AccessibilityNodeInfo? = null
        var displayBounds: Rect? = null
        repeat(MAX_DEPTH) {
            val node = ancestor ?: return@repeat
            safe(node)
            if (node.viewIdResourceName == "$CALCULATOR:id/history_item" && row == null) row = node
            if (node.viewIdResourceName == "$CALCULATOR:id/listView") {
                displayBounds = Rect().also(node::getBoundsInScreen)
                ancestor = null
            } else ancestor = node.parent
        }
        val expressionBounds = Rect().also(expressionNode::getBoundsInScreen)
        check(row != null && insideDisplay(expressionBounds) && displayBounds?.contains(expressionBounds) == true,
            Reason.EXPRESSION_UNAVAILABLE)
        // Inspect only the current row. Unavailable unrelated page children cannot
        // authorize a key returned by the exact resource-ID query below.
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(requireNotNull(row) to 0)
        var product = false
        var visited = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() - started < TREE_TIMEOUT_MS, Reason.TREE_TIMEOUT)
            check(visited < MAX_NODES, Reason.TREE_LIMIT)
            val (node, depth) = pending.removeLast()
            visited++
            safe(node)
            if (!node.isVisibleToUser) continue
            val bounds = Rect().also(node::getBoundsInScreen)
            if (insideDisplay(bounds) && displayBounds?.contains(bounds) == true &&
                boundedLabels(node).any { expression(it) == Expression.PRODUCT }) product = true
            val children = node.childCount
            check(depth < MAX_DEPTH || children == 0, Reason.TREE_LIMIT)
            check(children <= MAX_NODES - visited - pending.size, Reason.TREE_LIMIT)
            for (childIndex in children - 1 downTo 0) {
                val child = node.getChild(childIndex) ?: fail(Reason.TREE_INCOMPLETE)
                pending.add(child to depth + 1)
            }
        }
        val tap = key?.let {
            val node = unique(it.resourceId)
            check(node.isEnabled && node.isClickable, Reason.KEY_UNAVAILABLE)
            check(boundedLabels(node).any { label -> label in it.labels }, Reason.KEY_LABEL)
            val bounds = Rect().also(node::getBoundsInScreen)
            check(insideDisplay(bounds), Reason.KEY_BOUNDS)
            ExecutableRootAction.Tap(bounds.centerX(), bounds.centerY())
        }
        check(SystemClock.elapsedRealtime() - started < TREE_TIMEOUT_MS, Reason.TREE_TIMEOUT)
        check(RootPilotAccessibilityService.connectedService === service && selectedWindow().id == window.id, Reason.WINDOW_CHANGED)
        val currentRoot = selectedWindow().root ?: fail(Reason.ROOT_UNAVAILABLE)
        check(currentRoot.windowId == window.id && currentRoot.packageName?.toString() == CALCULATOR, Reason.WINDOW_CHANGED)
        return Collected(labels.single(), product, tap)
    }

    private fun boundedLabels(node: AccessibilityNodeInfo): List<String> = listOfNotNull(node.text, node.contentDescription)
        .map { check(it.length <= MAX_LABEL_LENGTH, Reason.LABEL_LIMIT); it.toString().trim() }

    private fun insideDisplay(bounds: Rect): Boolean = !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 &&
        bounds.right <= WIDTH && bounds.bottom <= HEIGHT

    private fun expression(value: String): Expression = when (value.filterNot { it.isWhitespace() || it == ',' }.removePrefix("=")) {
        "0" -> Expression.ZERO
        "1" -> Expression.ONE
        "12" -> Expression.TWELVE
        "123" -> Expression.HUNDRED_TWENTY_THREE
        "123×" -> Expression.MULTIPLY
        "123×4" -> Expression.MULTIPLY_FOUR
        "123×45" -> Expression.COMPLETE
        "5535" -> Expression.PRODUCT
        else -> Expression.OTHER
    }

    private suspend fun saveScreenshot(
        backend: DisplayRoutingRootExecutor,
        expected: ScreenObservation,
        evidence: Evidence,
        filename: String,
    ) {
        check(expected.sameTarget(observe(backend)), Reason.WINDOW_CHANGED)
        val capture = backend.captureScreen() as? RootScreenshotResult.Success ?: fail(Reason.CAPTURE_FAILED)
        check(expected.sameTarget(observe(backend)), Reason.WINDOW_CHANGED)
        check(capture.pngBytes.size in 8..MAX_PNG_BYTES, Reason.PNG_INVALID)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(capture.pngBytes, 0, capture.pngBytes.size, options)
        evidence.width = options.outWidth
        evidence.height = options.outHeight
        check(options.outMimeType == "image/png" && options.outWidth == WIDTH && options.outHeight == HEIGHT, Reason.PNG_INVALID)
        val bitmap = BitmapFactory.decodeByteArray(capture.pngBytes, 0, capture.pngBytes.size) ?: fail(Reason.PNG_INVALID)
        try { check(bitmap.width == WIDTH && bitmap.height == HEIGHT, Reason.PNG_INVALID) }
        finally { bitmap.recycle() }
        (evidence.directory ?: fail(Reason.ARTIFACT_WRITE)).resolve(filename).writeBytes(capture.pngBytes)
        evidence.screenshots++
    }

    private fun currentIme(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private data class KeySpec(val resourceId: String, val labels: Set<String>, val before: Expression, val digit: Boolean = true)
    private data class KeyEvidence(val index: Int, val expression: Expression, val x: Int, val y: Int, var receipt: String = "unknown")
    private enum class Expression { ZERO, ONE, TWELVE, HUNDRED_TWENTY_THREE, MULTIPLY, MULTIPLY_FOUR, COMPLETE, PRODUCT, OTHER }
    private data class Collected(val expression: Expression, val productInCurrentRow: Boolean, val tap: ExecutableRootAction.Tap?)
    private data class Sample(val observation: ScreenObservation, val expression: Expression, val productInCurrentRow: Boolean,
        val tap: ExecutableRootAction.Tap?) {
        fun isKnownInitial(): Boolean = expression == Expression.ZERO ||
            (productInCurrentRow && expression in setOf(Expression.PRODUCT, Expression.COMPLETE))
    }

    private class RejectMainDisplay : RootExecutor {
        var calls = 0
            private set
        private fun reject(): Nothing { calls++; fail(Reason.MAIN_DISPLAY_ROUTING) }
        override suspend fun beginRun(config: RootPilotConfig): RootExecutionResult = reject()
        override suspend fun endRun(): RootExecutionResult = reject()
        override suspend fun validateSession(): Boolean = reject()
        override suspend fun checkRoot(): RootExecutionResult = reject()
        override suspend fun observeScreen(): ScreenObservation = reject()
        override suspend fun captureScreen(): RootScreenshotResult = reject()
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = reject()
        override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult = reject()
        override fun cancel() = Unit
    }

    private class Evidence(val scenario: String) {
        var directory: File? = null
        var stage = "preflight"
        var failure = "none"
        var beginFailureCode = "none"
        var cleanupReason = "none"
        var passed = false
        var bodyComplete = false
        var sessionValid: Boolean? = null
        var displayId = -1
        var calculatorForeground = false
        var calculatorFocused = false
        var width = 0
        var height = 0
        var screenshots = 0
        var nodeFound = false
        var expressionMatched = false
        var initialExpressionKnown = false
        var result5535 = false
        var approvedKeys = 0
        var previouslyVerifiedKeys = 0
        var approvedDigits = 0
        var executedTaps = 0
        var unsupportedRejected = 0
        var uiTreeNotSupported = false
        var postCloseCaptureRejected = false
        var postCloseTapRejected = false
        var endAcknowledged = false
        var cleanupRequested = false
        var cleanupConfirmed = false
        var imeUnchanged = false
        var mainDisplayCalls = 0
        val keys = mutableListOf<KeyEvidence>()
        var expressionSamples = emptyList<kotlinx.serialization.json.JsonObject>()

        fun publish() {
            val metadata = buildJsonObject {
                put("scenario", scenario); put("stage", stage); put("failure", failure)
                put("beginFailureCode", beginFailureCode)
                put("cleanupReason", cleanupReason); put("passed", passed); put("bodyComplete", bodyComplete)
                put("executionSource", "direct_backend_fixed_sequence"); put("productionConfirmation", false)
                put("networkUsed", false); put("sessionStateKnown", sessionValid != null)
                put("sessionValid", sessionValid == true); put("displayId", displayId)
                put("calculatorForeground", calculatorForeground); put("calculatorFocused", calculatorFocused)
                put("pngWidth", width); put("pngHeight", height); put("screenshotCount", screenshots)
                put("nodeFound", nodeFound); put("expressionMatched", expressionMatched)
                put("initialExpressionKnown", initialExpressionKnown); put("result5535Observed", result5535)
                put("expressionSamples", kotlinx.serialization.json.JsonArray(expressionSamples))
                put("approvedArithmeticKeys", approvedKeys); put("approvedDigits", approvedDigits); put("executedTaps", executedTaps)
                put("previouslyVerifiedKeysNotReplayed", previouslyVerifiedKeys)
                putJsonArray("keySamples") {
                    keys.forEach { key ->
                        add(buildJsonObject {
                            put("index", key.index); put("expression", key.expression.name)
                            put("centerX", key.x); put("centerY", key.y); put("receipt", key.receipt)
                        })
                    }
                }
                put("unsupportedRejected", unsupportedRejected); put("uiTreeNotSupported", uiTreeNotSupported)
                put("postCloseCaptureRejected", postCloseCaptureRejected); put("postCloseTapRejected", postCloseTapRejected)
                put("endAcknowledged", endAcknowledged); put("cleanupRequested", cleanupRequested)
                put("cleanupConfirmed", cleanupConfirmed); put("imeUnchanged", imeUnchanged); put("mainDisplayCalls", mainDisplayCalls)
            }.toString()
            directory?.resolve("metadata.json")?.writeText(metadata)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("virtualDisplayAcceptance", metadata)
                putString("artifactDirectoryName", directory?.name.orEmpty())
            })
        }
    }

    private class AcceptanceFailure(val reason: Reason) : RuntimeException(reason.name)
    private enum class Reason {
        TARGET_PACKAGE, ARTIFACT_WRITE, SERVICE_BUSY, RECOVERY_PENDING, IME_RECOVERY_PENDING, IME_UNAVAILABLE, DEVICE_LOCKED,
        ACCESSIBILITY_UNAVAILABLE, CALCULATOR_UNAVAILABLE, CALCULATOR_IDENTITY,
        BEGIN_FAILED, INITIAL_APP, SESSION_IDENTITY, SESSION_INVALID, OPEN_FAILED, INITIAL_EXPRESSION,
        KEY_UNAVAILABLE, KEY_LABEL, KEY_BOUNDS, EXPRESSION_UNAVAILABLE, EXPRESSION_MISMATCH, TAP_FAILED,
        RESULT_NOT_OBSERVED, EXECUTION_COUNT, END_UNCONFIRMED, SESSION_STILL_VALID, POST_CLOSE_CAPTURE,
        POST_CLOSE_TAP, MAIN_DISPLAY_ROUTING, CLEANUP_UNCONFIRMED, IME_CHANGED, ACCEPTANCE_INCOMPLETE,
        UNSUPPORTED_ACTION_ACCEPTED, UI_TREE_NOT_REJECTED, DISPLAY_IDENTITY, CALCULATOR_NOT_FOCUSED,
        WINDOW_UNAVAILABLE, WINDOW_LIMIT, ROOT_UNAVAILABLE, ROOT_IDENTITY, WINDOW_CHANGED, TREE_TIMEOUT,
        TREE_LIMIT, TREE_PACKAGE, SENSITIVE_NODE, TREE_INCOMPLETE, LABEL_LIMIT, CAPTURE_FAILED, PNG_INVALID,
        TIMEOUT, CANCELLED, UNEXPECTED, PRIOR_RECEIPT_REQUIRED,
    }

    private companion object {
        const val TARGET_APP = "com.example.agent"
        const val CALCULATOR = "com.miui.calculator"
        const val CALCULATOR_ACTIVITY = "com.miui.calculator.cal.CalculatorActivity"
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val MAX_WINDOWS = 16
        const val MAX_NODES = 512
        const val MAX_DEPTH = 32
        const val MAX_LABEL_LENGTH = 128
        const val MAX_PNG_BYTES = 16 * 1024 * 1024
        const val TREE_TIMEOUT_MS = 3_000L
        const val RUN_TIMEOUT_MS = 180_000L
        const val CLEANUP_TIMEOUT_MS = 10_000L
        val BACKEND_REASON_CODES = VirtualDisplayProtocol.Reason.entries.map { it.code }.toSet()
        val IDLE_STATES = setOf(RootPilotStatus.IDLE, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)
        val KEYS = listOf(
            KeySpec("digit_1", setOf("1"), Expression.ZERO),
            KeySpec("digit_2", setOf("2"), Expression.ONE),
            KeySpec("digit_3", setOf("3"), Expression.TWELVE),
            KeySpec("op_mul", setOf("×", "乘", "乘号"), Expression.HUNDRED_TWENTY_THREE, digit = false),
            KeySpec("digit_4", setOf("4"), Expression.MULTIPLY),
            KeySpec("digit_5", setOf("5"), Expression.MULTIPLY_FOUR),
            KeySpec("btn_equal_s", setOf("=", "等于"), Expression.COMPLETE, digit = false),
        )
        fun check(value: Boolean, reason: Reason) { if (!value) fail(reason) }
        fun fail(reason: Reason): Nothing = throw AcceptanceFailure(reason)
        fun reason(error: Throwable): Reason = when (error) {
            is AcceptanceFailure -> error.reason
            is TimeoutCancellationException -> Reason.TIMEOUT
            is CancellationException -> Reason.CANCELLED
            else -> Reason.UNEXPECTED
        }
    }
}
