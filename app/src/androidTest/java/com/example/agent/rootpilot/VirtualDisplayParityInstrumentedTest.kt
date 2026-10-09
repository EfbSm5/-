package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.input.InputConnectionBridge
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.parity.OwnedVirtualDisplayProbe
import com.example.agent.rootpilot.parity.ImeWindowMetadata
import com.example.agent.rootpilot.parity.OwnedImeProbeObservation
import com.example.agent.rootpilot.parity.OwnedImeProbeVisibility
import com.example.agent.rootpilot.parity.ProbeFailure
import com.example.agent.rootpilot.parity.ProbeCommand
import com.example.agent.rootpilot.parity.ProbeDisplayIdentity
import com.example.agent.rootpilot.parity.ProbeProfile
import com.example.agent.rootpilot.parity.VirtualTextEditPlan
import com.example.agent.rootpilot.root.RootScreenObserver
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.sameTarget
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated offline probes. An observed platform result does not enable a product action. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayParityInstrumentedTest {
    @Test fun homeWithSystemDecorationsTargetsOnlyOwnedDisplay() = runBlocking {
        optIn("virtualParityHomeProbe")
        probe(ProbeProfile.DECORATED) { guard ->
            guard.openFixture()
            guard.stage = "home_delivery"
            guard.owned()
            guard.request(ProbeCommand.HOME)
            guard.stage = "home_observation"
            val home = await {
                guard.environment()
                val observation = guard.observer.observe()
                val section = displaySection(command("exec dumpsys activity activities", 512 * 1024), guard.identity.displayId)
                observation.foregroundPackage != null && observation.foregroundPackage != PACKAGE &&
                    observation.displayId == guard.identity.displayId && observation.sessionId == guard.probe.sessionId &&
                    observation.focusedPackage == observation.foregroundPackage && observation.keyboardVisible == false &&
                    section.any { Regex("\\btype=home\\b").containsMatchIn(it) }
            }
            guard.evidence.putBoolean("ownedHomeObserved", home)
            check(home) { "owned_home_not_observed" }
        }
    }

    @Test fun localKeyboardAppearsWithoutChangingDefaultIme() = runBlocking {
        optIn("virtualParityImeProbe")
        val timing = InstrumentationRegistry.getArguments().getString("virtualParityImeTiming")
        check(timing == null || timing == "before_open") { "ime_timing_forbidden" }
        probe(requireSemantics = true) { guard ->
            guard.evidence.putBoolean("localImeBeforeLaunch", timing == "before_open")
            if (timing == "before_open") {
                guard.stage = "local_ime_policy"
                check(guard.request(ProbeCommand.IME_LOCAL).imePolicy == 0) { "local_ime_policy_unconfirmed" }
                guard.stage = "fixture_open"
                guard.request(ProbeCommand.OPEN_FIXTURE)
                guard.bindFixture()
                // LOCAL may show the IME during launch; bind through the metadata-only observer.
                guard.keyboardObservation()
            } else {
                guard.openFixture()
                guard.owned()
                guard.stage = "local_ime_policy"
                check(guard.request(ProbeCommand.IME_LOCAL).imePolicy == 0) { "local_ime_policy_unconfirmed" }
            }
            guard.stage = "keyboard_request"
            val request = guard.fixtureCommand("virtual_show_keyboard")
            guard.evidence.putBoolean("keyboardRequestAccepted", request.getBoolean("requestAccepted"))
            guard.stage = "keyboard_observation"
            val visible = await {
                guard.keyboardObservation() == OwnedImeProbeVisibility.VISIBLE
            }
            guard.evidence.putBoolean("ownedKeyboardObserved", visible)
            check(visible) { "owned_keyboard_not_observed" }
            // Never sample the full virtual surface while a personal IME is visible.
            guard.stage = "keyboard_hide"
            guard.evidence.putBoolean("keyboardHideRequestAccepted",
                guard.fixtureCommand("virtual_hide_keyboard").getBoolean("requestAccepted"))
            check(guard.request(ProbeCommand.IME_HIDE).imePolicy == 2) { "hidden_ime_policy_unconfirmed" }
            check(await { guard.keyboardObservation() == OwnedImeProbeVisibility.HIDDEN }) { "keyboard_hide_unconfirmed" }
            guard.evidence.putBoolean("keyboardHiddenAgain", true)
        }
    }

    @Test fun displayResizesAndRendersInBothGeometries() = runBlocking {
        optIn("virtualParityResizeProbe")
        probe { guard ->
            guard.openFixture()
            for ((mode, landscape) in listOf(ProbeCommand.LANDSCAPE to true, ProbeCommand.PORTRAIT to false)) {
                guard.stage = "resize_${mode.name.lowercase()}"
                guard.owned()
                val info = guard.request(mode)
                check((info.width > info.height) == landscape) { "resize_geometry_unconfirmed" }
                guard.bindFixture(allowReplacement = true)
                guard.owned()
                guard.captureMetadata()
                guard.evidence.putBoolean(if (landscape) "landscapeRendered" else "portraitRendered", true)
            }
        }
    }

    @Test fun displayRotatesWithoutChangingMainRotation() = runBlocking {
        optIn("virtualParityRotationProbe")
        probe { guard ->
            guard.openFixture()
            for ((mode, rotation) in listOf(ProbeCommand.ROTATE_90 to 1, ProbeCommand.ROTATE_0 to 0)) {
                guard.stage = "rotation_$rotation"
                guard.owned()
                check(guard.request(mode).rotation == rotation) { "rotation_unconfirmed" }
                guard.bindFixture(allowReplacement = true)
                guard.owned()
                val state = guard.fixtureState()
                check(state.getInt("rotation", -1) == rotation) { "fixture_rotation_unconfirmed" }
                guard.captureMetadata()
                guard.evidence.putBoolean("rotation${rotation}Rendered", true)
            }
        }
    }

    @Test fun repeatedCapturesKeepTheSameOwnedPageAndSession() = runBlocking {
        optIn("virtualParityRetainedCaptureProbe")
        probe { guard ->
            guard.openFixture()
            repeat(3) {
                guard.stage = "retained_capture_$it"
                guard.owned()
                guard.fixtureCommand("virtual_redraw")
                check(guard.fixtureState().getInt("probeFrameTick", -1) == it + 1) { "fixture_redraw_unconfirmed" }
                guard.captureMetadata()
                check(guard.fixtureState().getBoolean("empty")) { "unexpected_edit" }
            }
            guard.evidence.putInt("sameSessionCaptures", 3)
        }
    }

    @Test fun insertsAtKnownNonemptyCaretAndReadsBackContentAndSelection() = runBlocking {
        optIn("virtualParityCursorProbe")
        probe(requireSemantics = true) { guard -> edit(guard, selection = false) }
    }

    @Test fun replacesOnlyKnownSelectionAndReadsBackContentAndSelection() = runBlocking {
        optIn("virtualParitySelectionProbe")
        probe(requireSemantics = true) { guard -> edit(guard, selection = true) }
    }

    @Test fun cancellationBeforeNonemptySubmitRestoresFlagsAndLeavesSeedUntouched() = runBlocking {
        optIn("virtualParityCancelledInputProbe")
        probe(requireSemantics = true) { guard -> edit(guard, selection = false, cancelBeforeSubmit = true) }
    }

    private suspend fun edit(guard: Guard, selection: Boolean, cancelBeforeSubmit: Boolean = false) {
        guard.openFixture()
        guard.stage = "seed_editor"
        guard.owned()
        guard.fixtureCommand(if (selection) "virtual_seed_selection" else "virtual_seed_cursor")
        val seed = if (selection) "甲乙丙" else "甲乙"
        val input = if (selection) "中文🙂\n第二行" else "🙂"
        val start = 1
        val end = if (selection) 2 else 1
        val service = guard.service ?: error("semantics_disabled")
        val connection = service.connectionIdentity ?: error("semantics_connection_missing")
        var submissions = 0
        var selectionCalls = 0
        guard.stage = "nonempty_edit"
        val cancelled = try {
            withContext(Dispatchers.IO) {
                service.queryTree(virtual = true) {
                    guard.owned(withinSemanticsQuery = true)
                    val before = guard.observer.observe()
                    val (root, editor) = editor(service, connection, guard)
                    check(editor.text?.toString() == seed && editor.textSelectionStart == start && editor.textSelectionEnd == end) {
                        "fixed_seed_unconfirmed"
                    }
                    val plan = VirtualTextEditPlan.create(editor.text, start, end, input) ?: error("edit_plan_rejected")
                    check(!cancelBeforeSubmit || submissions == 0) { "early_submission" }
                    if (cancelBeforeSubmit) currentCoroutineContext().cancel()
                    currentCoroutineContext().ensureActive()
                    val (freshRoot, freshEditor) = editor(service, connection, guard)
                    check(freshRoot == root && freshEditor == editor && before.sameTarget(guard.observer.observe()) &&
                        plan.matchesSource(freshEditor.text, freshEditor.textSelectionStart, freshEditor.textSelectionEnd)) {
                        "edit_source_changed"
                    }
                    guard.owned(withinSemanticsQuery = true)
                    val (submitRoot, submitEditor) = editor(service, connection, guard)
                    check(submitRoot == freshRoot && submitEditor == freshEditor &&
                        plan.matchesSource(submitEditor.text, submitEditor.textSelectionStart, submitEditor.textSelectionEnd)) {
                        "edit_source_changed_before_submit"
                    }
                    currentCoroutineContext().ensureActive()
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.replacement)
                    }
                    submissions++
                    check(freshEditor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "set_text_not_accepted" }
                    check(await {
                        guard.owned(withinSemanticsQuery = true)
                        val (_, current) = editor(service, connection, guard)
                        current == freshEditor && current.text?.toString() == plan.replacement
                    }) { "edit_content_unconfirmed" }
                    val (_, result) = editor(service, connection, guard)
                    check(result == freshEditor && result.text?.toString() == plan.replacement) { "edit_target_changed" }
                    guard.owned(withinSemanticsQuery = true)
                    val (_, selectionTarget) = editor(service, connection, guard)
                    check(selectionTarget == result && selectionTarget.text?.toString() == plan.replacement) { "edit_target_changed_before_selection" }
                    currentCoroutineContext().ensureActive()
                    val caret = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.caret)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.caret)
                    }
                    selectionCalls++
                    check(result.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)) { "set_selection_not_accepted" }
                    check(await {
                        guard.owned(withinSemanticsQuery = true)
                        val (_, current) = editor(service, connection, guard)
                        current == result && plan.matchesResult(current.text, current.textSelectionStart, current.textSelectionEnd)
                    }) { "edit_result_unconfirmed" }
                }
            }
            false
        } catch (_: CancellationException) {
            true
        }
        guard.evidence.putInt("setTextCalls", submissions)
        guard.evidence.putInt("setSelectionCalls", selectionCalls)
        check(cancelled == cancelBeforeSubmit) { "unexpected_cancellation" }
        guard.owned()
        val state = guard.fixtureState()
        if (cancelBeforeSubmit) {
            check(submissions == 0 && selectionCalls == 0 && state.getBoolean("cursorSeedMatches") &&
                state.getInt("selectionStart", -1) == 1 && state.getInt("selectionEnd", -1) == 1) { "cancelled_edit_changed" }
            guard.evidence.putBoolean("cancelledBeforeSubmit", true)
        } else {
            check(submissions == 1 && selectionCalls == 1 && state.getBoolean(if (selection) "selectionSampleMatches" else "cursorSampleMatches") &&
                state.getInt("selectionStart", -1) == start + input.length &&
                state.getInt("selectionEnd", -1) == start + input.length) { "fixture_edit_result_unconfirmed" }
            guard.evidence.putBoolean("fixedEditAndCaretObserved", true)
        }
    }

    private suspend fun editor(service: RootPilotAccessibilityService, connection: Any, guard: Guard): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        check(RootPilotAccessibilityService.connectedService === service && service.connectionIdentity === connection) { "semantics_changed" }
        val window = service.windowsOnAllDisplays[guard.identity.displayId]?.singleOrNull {
            it.displayId == guard.identity.displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: error("owned_window_unavailable")
        val root = window.root ?: error("owned_root_unavailable")
        check(root.refresh() && root.windowId == window.id && root.packageName?.toString() == PACKAGE && root.isVisibleToUser) {
            "owned_root_changed"
        }
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("editor_unavailable")
        check(node.refresh() && node.packageName?.toString() == PACKAGE && node.windowId == root.windowId &&
            node.viewIdResourceName == "android:id/edit" && node.isVisibleToUser && node.isEnabled && node.isEditable && node.isFocused &&
            node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && !InputConnectionBridge.isPassword(node.inputType) &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) { "editor_ineligible" }
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        var ancestor: AccessibilityNodeInfo? = node
        var reached = false
        repeat(33) {
            if (!reached) {
                currentCoroutineContext().ensureActive()
                val current = ancestor ?: error("incomplete_ancestry")
                check(seen.add(current) && current.refresh() && current.packageName?.toString() == PACKAGE &&
                    current.windowId == root.windowId && !current.isPassword && !current.isAccessibilityDataSensitive) { "unsafe_ancestry" }
                reached = current == root
                if (!reached) ancestor = current.parent
            }
        }
        check(reached && RootPilotAccessibilityService.connectedService === service && service.connectionIdentity === connection) {
            "editor_binding_changed"
        }
        val currentWindow = node.window ?: error("editor_window_unavailable")
        check(currentWindow.id == window.id && currentWindow.displayId == guard.identity.displayId &&
            currentWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION && currentWindow.isFocused) { "editor_window_changed" }
        return root to node
    }

    private suspend fun probe(profile: ProbeProfile = ProbeProfile.PLAIN, requireSemantics: Boolean = false, body: suspend (Guard) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = OwnedVirtualDisplayProbe(context, instrumentation.context.applicationInfo.sourceDir, profile)
        val evidence = Bundle().apply { putBoolean("networkUsed", false); putBoolean("pixelsExported", false) }
        var guard: Guard? = null
        var started = false
        var failure: String? = null
        var stage = "preflight"
        try {
            withTimeout(60_000) {
                stage = "preflight_idle"
                idle(context)
                stage = "preflight_existing_display"
                val manager = context.getSystemService(DisplayManager::class.java)
                check(manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) || it.name.startsWith(PROBE_PREFIX) }) {
                    "existing_owned_display"
                }
                stage = "preflight_fixture_identity"
                check(context.packageManager.resolveContentProvider("$PACKAGE.state", 0)?.packageName == PACKAGE &&
                    context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) { "fixture_identity" }
                stage = "preflight_fixture_closed"
                check(fixtureCall(context, "virtual_state").isEmpty) { "fixture_already_open" }
                stage = "preflight_service"
                val originalService = RootPilotAccessibilityService.connectedService
                if (requireSemantics) check(originalService?.connectionIdentity != null) { "semantics_disabled" }
                stage = "preflight_main_identity"
                val main = mainIdentity()
                stage = "preflight_baseline"
                val baseline = Baseline(main, manager.getDisplay(0)?.rotation ?: error("main_display_unavailable"),
                    setting(context, Settings.Secure.DEFAULT_INPUT_METHOD), enabledImes(context),
                    setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), originalService,
                    originalService?.connectionIdentity, originalService?.serviceInfo?.flags, RootPilotService.uiState.value.config)
                stage = "create"
                started = true
                val identity = probe.start()
                guard = Guard(context, probe, identity, baseline, evidence)
                check(identity.displayId > 0 && identity.imePolicy == 2) { "initial_display_unconfirmed" }
                guard!!.environment()
                body(guard!!)
                stage = "complete"
            }
        } catch (error: Exception) {
            failure = "parity_failed_${guard?.stage ?: stage}"
            (error as? ProbeFailure)?.let { evidence.putString("probeReason", it.reason.name) }
        } finally {
            withContext(NonCancellable) {
                try {
                    val released = probe.close()
                    val gone = context.getSystemService(DisplayManager::class.java).displays.none { it.name == PROBE_PREFIX + probe.sessionId }
                    evidence.putBoolean("releaseConfirmed", released)
                    evidence.putBoolean("ownedDisplayGone", gone)
                    if (started) check(released && gone && fixtureCall(context, "virtual_state").isEmpty) { "display_cleanup_unconfirmed" }
                    if (guard != null) {
                        guard!!.environment()
                        evidence.putBoolean("environmentUnchanged", true)
                    }
                } catch (_: Exception) {
                    failure = "parity_cleanup_unconfirmed"
                }
                evidence.putString("stage", guard?.stage ?: stage)
                evidence.putString("failure", failure)
                evidence.putBoolean("passed", failure == null)
                instrumentation.sendStatus(0, evidence)
            }
        }
        check(failure == null) { failure ?: "parity_unconfirmed" }
    }

    private class Baseline(
        val main: String, val rotation: Int, val ime: String, val imes: List<String>, val services: String,
        val semantics: RootPilotAccessibilityService?, val connection: Any?, val flags: Int?,
        val configIdentity: Any,
    )

    private inner class Guard(val context: Context, val probe: OwnedVirtualDisplayProbe, var identity: ProbeDisplayIdentity,
        val baseline: Baseline, val evidence: Bundle) {
        var stage = "created"
        val service get() = baseline.semantics
        val observer = RootScreenObserver(DisplaySession(identity.displayId, probe.sessionId))
        private val initialDisplay = identity.displayId
        private val uniqueId = identity.uniqueId
        private val helperPid = identity.helperPid
        private var instance: String? = null

        suspend fun environment(checkRestoredFlags: Boolean = true) {
            idle(context)
            check(mainIdentity() == baseline.main && context.getSystemService(DisplayManager::class.java).getDisplay(0)?.rotation == baseline.rotation &&
                setting(context, Settings.Secure.DEFAULT_INPUT_METHOD) == baseline.ime && enabledImes(context) == baseline.imes &&
                setting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == baseline.services &&
                RootPilotAccessibilityService.connectedService === baseline.semantics && baseline.semantics?.connectionIdentity === baseline.connection &&
                (!checkRestoredFlags || baseline.semantics?.serviceInfo?.flags == baseline.flags) &&
                RootPilotService.uiState.value.config === baseline.configIdentity) {
                "main_or_environment_changed"
            }
        }

        suspend fun request(command: ProbeCommand): ProbeDisplayIdentity {
            environment()
            val result = probe.request(command)
            check(result.displayId == initialDisplay && result.uniqueId == uniqueId && result.helperPid == helperPid) { "probe_session_changed" }
            identity = result
            environment()
            return result
        }

        suspend fun openFixture() {
            stage = "fixture_open"
            request(ProbeCommand.OPEN_FIXTURE)
            bindFixture()
            owned()
        }

        suspend fun bindFixture(allowReplacement: Boolean = false) {
            var next: String? = null
            check(await {
                environment()
                val state = fixtureCall(context, "virtual_state")
                check(state.isEmpty || state.getInt("displayId", -1) == initialDisplay) { "fixture_display_changed" }
                if (state.getBoolean("ready") && state.getInt("viewWidth", 0) > 0 && state.getInt("viewHeight", 0) > 0 &&
                    (instance == null || allowReplacement || state.getString("instance") == instance)) {
                    next = state.getString("instance")
                    next != null
                } else false
            }) { "fixture_not_ready" }
            instance = next
        }

        suspend fun fixtureState(): Bundle = fixtureCall(context, "virtual_state").also {
            check(it.getInt("displayId", -1) == initialDisplay) { "fixture_display_changed" }
        }

        suspend fun owned(withinSemanticsQuery: Boolean = false): ScreenObservation {
            environment(checkRestoredFlags = !withinSemanticsQuery)
            val state = fixtureState()
            check(state.getString("instance") == instance && state.getBoolean("ready") && state.getBoolean("editorFocused")) { "fixture_instance_changed" }
            val observation = observer.observe()
            check(observation.displayId == initialDisplay && observation.sessionId == probe.sessionId &&
                observation.foregroundPackage == PACKAGE && observation.foregroundActivity == ACTIVITY &&
                observation.focusedPackage == PACKAGE && observation.focusedWindowId != null &&
                observation.keyboardVisible == false) {
                "owned_target_unavailable"
            }
            return observation
        }

        suspend fun keyboardObservation(): OwnedImeProbeVisibility {
            environment()
            val semantics = service ?: error("semantics_disabled")
            val connection = baseline.connection ?: error("semantics_connection_missing")
            return semantics.queryTree(virtual = false) {
                environment()
                val state = fixtureState()
                check(state.getString("instance") == instance && state.getBoolean("ready") && state.getBoolean("editorFocused")) {
                    "fixture_instance_changed"
                }
                val phase = if (stage == "keyboard_hide") "hide" else "show"
                evidence.putBoolean("${phase}ImeInsetsAvailable", state.getBoolean("imeInsetsAvailable"))
                if (state.containsKey("imeInsetsVisible")) evidence.putBoolean("${phase}ImeInsetsVisible", state.getBoolean("imeInsetsVisible"))
                val before = observer.observe()
                evidence.putBoolean("keyboardOwnedDisplayObserved", before.displayId == initialDisplay && before.sessionId == probe.sessionId)
                evidence.putBoolean("keyboardOwnedForegroundObserved", before.foregroundPackage == PACKAGE && before.foregroundActivity == ACTIVITY)
                evidence.putBoolean("keyboardOwnedFocusObserved", before.focusedPackage == PACKAGE && before.focusedWindowId != null)
                evidence.putBoolean("keyboardHiddenConfirmed", before.keyboardVisible == false)
                check(before.displayId == initialDisplay && before.sessionId == probe.sessionId &&
                    before.foregroundPackage == PACKAGE && before.foregroundActivity == ACTIVITY &&
                    before.focusedPackage == PACKAGE && before.focusedWindowId != null) { "owned_target_unavailable" }
                // No IME text, child nodes or pixels are read, only current window/root metadata.
                val windows = semantics.windowsOnAllDisplays[initialDisplay]?.filter {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }?.map { window ->
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    val root = window.root
                    val refreshed = root?.refresh() == true
                    ImeWindowMetadata(window.displayId, window.id, if (refreshed) root?.windowId else null, refreshed,
                        root?.isVisibleToUser == true, bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
                evidence.putBoolean("keyboardWindowsAvailable", windows != null)
                evidence.putInt("keyboardWindowCount", windows?.size ?: -1)
                evidence.putInt("${phase}KeyboardWindowCount", windows?.size ?: -1)
                evidence.putBoolean("keyboardRootsRefreshed", windows?.isNotEmpty() == true && windows.all { it.rootRefreshed })
                evidence.putBoolean("keyboardRootsVisible", windows?.isNotEmpty() == true && windows.all { it.rootVisible })
                evidence.putBoolean("keyboardBoundsOwned", windows?.isNotEmpty() == true && windows.all {
                    it.displayId == initialDisplay && it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top &&
                        it.right <= identity.width && it.bottom <= identity.height
                })
                val after = observer.observe()
                evidence.putBoolean("keyboardTargetUnchanged", before.sameTarget(after))
                check(before.sameTarget(after) && RootPilotAccessibilityService.connectedService === semantics &&
                    semantics.connectionIdentity === connection) { "keyboard_binding_changed" }
                val finalState = fixtureState()
                check(finalState.getString("instance") == instance && finalState.getBoolean("ready") &&
                    finalState.getBoolean("editorFocused")) { "fixture_instance_changed" }
                fun imeVisible(sample: Bundle): Boolean? = if (sample.getBoolean("imeInsetsAvailable") &&
                    sample.containsKey("imeInsetsVisible")) sample.getBoolean("imeInsetsVisible") else null
                val hiddenByInsets = OwnedImeProbeObservation.hiddenByStableInsets(imeVisible(state), imeVisible(finalState))
                evidence.putBoolean("${phase}ImeInsetsStableHidden", hiddenByInsets)
                environment()
                // A hidden IME can retain its WindowManager object; this probe has two owned Insets samples.
                // The classifier also requires an available, empty owned-display IME window list.
                OwnedImeProbeObservation.classify(initialDisplay, probe.sessionId, after.displayId, after.sessionId,
                    identity.width, identity.height, windows, after.keyboardVisible == false || hiddenByInsets).also {
                    evidence.putString("keyboardVisibility", it.name)
                    evidence.putString("${phase}KeyboardVisibility", it.name)
                }
            }
        }

        suspend fun fixtureCommand(method: String): Bundle {
            environment()
            check(fixtureState().getString("instance") == instance) { "fixture_instance_changed" }
            val binding = Bundle().apply { putString("instance", instance); putInt("displayId", initialDisplay) }
            return fixtureCall(context, method, binding).also {
                check(it.getString("instance") == instance && it.getInt("displayId", -1) == initialDisplay) { "fixture_receipt_changed" }
                environment()
            }
        }

        suspend fun captureMetadata() {
            owned()
            check(identity.imePolicy == 2) { "capture_with_ime_forbidden" }
            check(request(ProbeCommand.CAPTURE_METADATA).frameBytes > 0) { "frame_unavailable" }
            owned()
        }
    }

    private fun optIn(argument: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString(argument) == "true")
    }

    private fun idle(context: Context) {
        val state = RootPilotService.uiState.value
        check(!state.running && state.pendingAction == null && state.status in setOf(RootPilotStatus.IDLE,
            RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED)) { "service_busy" }
        check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists()) { "recovery_pending" }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked && context.getSystemService(PowerManager::class.java).isInteractive) {
            "device_locked_or_off"
        }
    }

    private suspend fun fixtureCall(context: Context, method: String, binding: Bundle? = null): Bundle = withContext(Dispatchers.IO) {
        context.contentResolver.call(Uri.parse("content://$PACKAGE.state"), method, null, binding) ?: error("fixture_unavailable")
    }

    private suspend fun await(predicate: suspend () -> Boolean): Boolean = withTimeout(5_000) {
        while (!predicate()) delay(100)
        true
    }

    private fun setting(context: Context, name: String) = Settings.Secure.getString(context.contentResolver, name).orEmpty()
    private fun enabledImes(context: Context) = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.id }.sorted()

    private suspend fun mainIdentity(): String {
        val section = displaySection(command("exec dumpsys activity activities", 512 * 1024), 0)
        val pattern = Regex("(?:mResumedActivity: |topResumedActivity=|Resumed: )" +
            "(ActivityRecord\\{[0-9a-fA-F]+ u0 com\\.example\\.agent/\\.rootpilot\\.RootPilotActivity t[0-9]+\\})")
        val candidates = section.map(String::trim).filter {
            it.startsWith("mResumedActivity:") || it.startsWith("topResumedActivity=") || it.startsWith("Resumed:")
        }.map { pattern.matchEntire(it)?.groupValues?.get(1) }
        check(candidates.isNotEmpty() && candidates.all { it != null }) { "main_identity_unavailable" }
        return candidates.filterNotNull().distinct().singleOrNull() ?: error("main_identity_ambiguous")
    }

    private fun displaySection(dump: String, displayId: Int): List<String> {
        val lines = dump.lines()
        val header = "Display #$displayId (activities from top to bottom):"
        check(lines.count { it.trim() == header } == 1) { "display_section_unavailable" }
        return lines.dropWhile { it.trim() != header }.drop(1).takeWhile { !it.trimStart().startsWith("Display #") }
    }

    private suspend fun command(value: String, limit: Int): String = withContext(Dispatchers.IO) {
        check(value == "exec dumpsys activity activities") { "probe_command_forbidden" }
        val process = ProcessBuilder("su", "-c", value).redirectError(File("/dev/null")).start()
        try {
            process.outputStream.close()
            val output = ByteArrayOutputStream()
            withTimeout(5_000) {
                while (process.isAlive || process.inputStream.available() > 0) {
                    val count = process.inputStream.available()
                    if (count == 0) { delay(10); continue }
                    val buffer = ByteArray(minOf(count, 4096))
                    val read = process.inputStream.read(buffer)
                    check(read > 0 && output.size() + read <= limit) { "probe_output_limit" }
                    output.write(buffer, 0, read)
                }
            }
            check(process.waitFor(500, TimeUnit.MILLISECONDS) && process.exitValue() == 0) { "probe_command_failed" }
            output.toString("UTF-8")
        } finally {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
        }
    }

    private companion object {
        const val PACKAGE = "com.example.rootpilot.fixture"
        const val ACTIVITY = "$PACKAGE.VirtualCapabilityActivity"
        const val PROBE_PREFIX = "rootpilot-parity-"
    }
}
