package com.example.agent.rootpilot

import android.app.Notification
import android.app.Activity
import android.os.SystemClock
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Condition
import java.util.concurrent.atomic.AtomicBoolean
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * diagnoseInitialization is opt-in initialization only: notificationInitialization=true, --no-restart,
 * expectedRootPilotPid=<existing main PID>, recoveryReceiptId=<canonical UUID>, and
 * recoveryBaseline=empty_task_saved_api_or_defaults. No Activity, notification, tree,
 * screenshot, input, model, global action, or task control is invoked.
 *
 * DONT_SUPPRESS_ACCESSIBILITY_SERVICES deliberately differs from the old notification
 * test's default flags: this diagnoses safe initialization, not an exact replay of that path.
 * Configurator changes are library-local and restored; no Secure setting is written.
 * Initialization explicitly acquires UiAutomation and UiDevice once. Cleanup switches an acquired
 * UiAutomation once to DONT_USE_ACCESSIBILITY before strictly verifying the original settings.
 * UiDevice construction reaches UiDevice.Api24Impl.getUiAutomationWithRetry via QueryController: the
 * existing library may obtain the handle up to three times, 500 ms apart when null.
 * This test adds no retry; one explicit call is not one total handle acquisition.
 * The instrumentation owns its UiAutomation connection; this test does not destroy it.
 *
 * runId is the receipt UUID solely for host correlation, NOT a business run. Initialization
 * creates no evidence directory; neither method reads or changes recovery receipts/invalidation markers.
 * Unchanged state/log/history digests are local evidence, not network interception.
 */
@RunWith(AndroidJUnit4::class)
class NotificationInitializationInstrumentedTest {
    @Test fun diagnoseInitialization() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationInitialization") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnostic = Diagnostic(instrumentation, args)
        try {
            diagnostic.initialize()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            // Each guard catches its own errors. A failed connection never skips verification.
            try {
                diagnostic.restoreConfigurator()
            } finally {
                try {
                    diagnostic.verifyOriginalState()
                } finally {
                    diagnostic.report()
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    /**
     * Opt-in structure comparison only, NOT real Service/notification approval acceptance.
     * All three PendingIntents only open the harmless app home and are never sent or clicked.
     * Only the unique fixed SystemUI title and its <=12 ancestor metadata nodes are queried;
     * no text/description getter, sibling tree, screenshot, action click, or model is used.
     */
    @Test fun inspectOwnNotificationStructure() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationStructure") == "true")
        val diagnostic = Diagnostic(InstrumentationRegistry.getInstrumentation(), args, structureMode = true)
        try {
            diagnostic.initialize()
            diagnostic.inspectStructure()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            try {
                diagnostic.cleanStructure()
            } finally {
                try {
                    diagnostic.restoreConfigurator()
                } finally {
                    try {
                        diagnostic.verifyOriginalState()
                    } finally {
                        diagnostic.report()
                    }
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    /** Row-only metadata/equality diagnostic, never approval acceptance; disabled by default. */
    @Test fun inspectOwnNotificationIdentity() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationIdentity") == "true")
        val diagnostic = Diagnostic(InstrumentationRegistry.getInstrumentation(), args, identityMode = true)
        try {
            diagnostic.initialize()
            diagnostic.inspectIdentity()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            try { diagnostic.cleanStructure() } finally {
                try { diagnostic.restoreConfigurator() } finally {
                    try { diagnostic.verifyOriginalState() } finally { diagnostic.report() }
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    /** One row-local gesture experiment, never a guarantee of OEM expansion or approval. */
    @Test fun inspectOwnNotificationExpansion() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationExpansion") == "true")
        val diagnostic = Diagnostic(InstrumentationRegistry.getInstrumentation(), args,
            identityMode = true, expansionMode = true)
        try {
            diagnostic.initialize()
            diagnostic.inspectExpansion()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            try { diagnostic.cleanStructure() } finally {
                try { diagnostic.restoreConfigurator() } finally {
                    try { diagnostic.verifyOriginalState() } finally { diagnostic.report() }
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    /** One guarded public longClick experiment, independently disabled by default. */
    @Test fun inspectOwnNotificationLongPressExpansion() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationLongPressExpansion") == "true")
        val diagnostic = Diagnostic(InstrumentationRegistry.getInstrumentation(), args,
            identityMode = true, expansionMode = true, longPressMode = true)
        try {
            diagnostic.initialize()
            diagnostic.inspectExpansion()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            try { diagnostic.cleanStructure() } finally {
                try { diagnostic.restoreConfigurator() } finally {
                    try { diagnostic.verifyOriginalState() } finally { diagnostic.report() }
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    /** One memory-only full-screen acquisition; only the verified independent own-row crop is encoded. */
    @Test fun inspectOwnNotificationRoi() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("notificationRoiCapture") == "true")
        val diagnostic = Diagnostic(InstrumentationRegistry.getInstrumentation(), args, identityMode = true, roiMode = true)
        try {
            diagnostic.initialize()
            diagnostic.inspectRoi()
        } catch (error: Throwable) {
            diagnostic.recordFailure(error)
        } finally {
            try { diagnostic.cleanStructure() } finally {
                try { diagnostic.restoreConfigurator() } finally {
                    try { diagnostic.verifyOriginalState() } finally { diagnostic.report() }
                }
            }
        }
        if (!diagnostic.passed) fail(diagnostic.failureCode())
    }

    private class Diagnostic(
        private val instrumentation: Instrumentation,
        private val args: Bundle,
        private val structureMode: Boolean = false,
        private val identityMode: Boolean = false,
        private val expansionMode: Boolean = false,
        private val longPressMode: Boolean = false,
        private val roiMode: Boolean = false,
    ) {
        private var context: Context? = null
        private var stage = Stage.BASELINE
        private var reason = Reason.NONE
        private var cleanupReason = Reason.NONE
        private val requestedReceiptId = args.getString("recoveryReceiptId").orEmpty()
        private val receiptId = requestedReceiptId.takeIf(::validId).orEmpty()
        private var beforeIdentity: Identity? = null
        private var beforeIme: String? = null
        private var imeCaptured = false
        private var beforeAccessibility: Accessibility? = null
        private var beforeState: RootPilotUiState? = null
        private var beforeLogs: String? = null
        private var beforeHistory: Map<String, String>? = null
        private var beforeRecovery: Map<String, String>? = null
        private var beforeConfigFiles: Map<String, String>? = null
        private var configurator: Configurator? = null
        private var originalFlags: Int? = null
        private var initializationSucceeded = false
        private var configuratorRestored = false
        private var automationAccessibilityReleased = false
        private var uiAutomationAvailable = false
        private var uiDeviceAvailable = false
        private var mainProcessUnchanged = false
        private var imeUnchanged = false
        private var accessibilityUnchanged = false
        private var accessibilityEnabledWhileConnected = AccessibilityEnabledDiagnostic.UNKNOWN
        private var enabledServicesUnchanged = false
        private var stateUnchanged = false
        private var logsUnchanged = false
        private var historyUnchanged = false
        private var recoveryUnchanged = false
        private var configFilesUnchanged = false
        private var finalIdle = false
        private var finalRecoveryAbsent = false
        private var baselineRestored = false
        private var noModelRequests = false
        private var noScreenshots = false
        private var noExecutions = false
        private var automation: UiAutomation? = null
        private val notificationMode get() = structureMode || identityMode
        private var device: UiDevice? = null
        private var notificationManager: NotificationManager? = null
        private var beforeNotifications: Set<Pair<String?, Int>>? = null
        private val ownTag = "rootpilot-structure-$receiptId"
        private val controlTitle get() = if (identityMode) NotificationIdentityEvidence.title(receiptId) else STRUCTURE_TITLE
        private val ownIntents = mutableListOf<PendingIntent>()
        private var notificationAttempted = false
        private var shadeOpened = false
        private var structureCaptured = false
        private var notificationRemoved = false
        private var ownRowExpanded = false
        private var noOwnActivityLaunch = false
        private var expansionBytes: ByteArray? = null
        private var expansionFailureBytes: ByteArray? = null
        private var timeoutFailureBytes: ByteArray? = null
        private var timeoutOwnershipVerified = false
        private var timeoutObserverVerified = false
        private var failureObserverVerified = false
        private var failureStructureCaptured = false
        private var captureBudget = NotificationCaptureBudget.NONE
        private var screenshotCalls = 0
        private var roiPrepared = false
        private var roiCaptured = false
        private var roiWidth = 0
        private var roiHeight = 0
        private var roiBytes: ByteArray? = null
        private var noProductScreenshots = false
        private var roiObserver: OwnActivityObservation? = null
        private var roiObserverRegistrationAttempted = false

        val passed: Boolean get() = initializationSucceeded && baselineRestored &&
            noModelRequests && (if (roiMode) noProductScreenshots else noScreenshots) && noExecutions &&
            (!notificationMode || (structureCaptured && notificationRemoved)) &&
            (!expansionMode || (ownRowExpanded && noOwnActivityLaunch)) &&
            (!roiMode || (roiCaptured && noOwnActivityLaunch && screenshotCalls == 1)) &&
            reason == Reason.NONE && cleanupReason == Reason.NONE

        fun initialize() {
            context = instrumentation.targetContext
            // Capture independently before checking admission. Failed capture prevents all UI APIs.
            capture { beforeIdentity = identity() }
            capture { beforeIme = secure(Settings.Secure.DEFAULT_INPUT_METHOD); imeCaptured = true }
            capture { beforeAccessibility = accessibility() }
            capture { beforeState = state() }
            capture { beforeLogs = logDigest(state()) }
            capture { beforeHistory = historyFiles() }
            capture { beforeRecovery = recoveryFiles() }
            capture { beforeConfigFiles = configFiles() }
            if (reason != Reason.NONE) return

            requireFixed(beforeIdentity?.processName == TARGET && context().packageName == TARGET,
                Reason.TARGET_PROCESS)
            requireFixed(args.getString("expectedRootPilotPid")?.toIntOrNull() == Process.myPid(),
                Reason.NO_RESTART_REQUIRED)
            requireFixed(validId(receiptId), Reason.RECEIPT_ID)
            requireFixed(args.getString("recoveryBaseline") == "empty_task_saved_api_or_defaults",
                Reason.BASELINE_ACK_REQUIRED)
            val original = beforeState ?: throw DiagnosticFailure(Reason.BASELINE_OTHER)
            requireFixed(idle(original), Reason.SERVICE_BUSY)
            requireFixed(beforeRecovery!!.isEmpty(), Reason.EXISTING_RECOVERY)
            requireFixed(original.config.task.isEmpty() && original.config.executionDisplay == ExecutionDisplay.MAIN &&
                original.config.virtualDisplayStartPackage.isEmpty(), Reason.BASELINE_UNRESTORABLE)
            requireFixed(!beforeIme.isNullOrBlank(), Reason.IME_UNAVAILABLE)
            val saved = if (original.apiConfigured) {
                RootPilotApiConfigStore.create(context()).read()
                    ?: throw DiagnosticFailure(Reason.BASELINE_UNRESTORABLE)
            } else RootPilotApiConfig()
            requireFixed(saved.applyTo(original.config) == original.config, Reason.BASELINE_UNRESTORABLE)
            // Recheck admission after reads, without waiting for or clearing another task.
            requireFixed(state() == original && recoveryFiles() == beforeRecovery, Reason.BASELINE_CHANGED)

            stage = Stage.UI_AUTOMATION
            val automation: UiAutomation? = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
            this.automation = automation
            uiAutomationAvailable = automation != null
            requireFixed(uiAutomationAvailable, Reason.UI_AUTOMATION_NULL)

            stage = Stage.CONFIGURATOR
            val config = Configurator.getInstance()
            configurator = config
            originalFlags = config.uiAutomationFlags
            config.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            requireFixed(config.uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
                Reason.CONFIGURATOR_FLAGS)

            stage = Stage.UI_DEVICE
            val device: UiDevice? = UiDevice.getInstance(instrumentation)
            uiDeviceAvailable = device != null
            requireFixed(uiDeviceAvailable, Reason.UI_DEVICE_NULL)
            this.device = device
            if (!notificationMode) {
                val connected = accessibility()
                accessibilityEnabledWhileConnected = AccessibilityEnabledDiagnostic.classify(connected.enabled)
                enabledServicesUnchanged = beforeAccessibility != null &&
                    connected.enabledServices == beforeAccessibility?.enabledServices
            }
            initializationSucceeded = true
        }

        private fun postControlNotification(): UiDevice {
            stage = if (identityMode) Stage.IDENTITY else Stage.STRUCTURE
            val ui = device ?: throw DiagnosticFailure(Reason.UI_DEVICE_NULL)
            val keyguard = context().getSystemService(KeyguardManager::class.java)
            requireFixed(keyguard != null && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked,
                Reason.STRUCTURE_LOCKED)
            val manager = context().getSystemService(NotificationManager::class.java)
                ?: throw DiagnosticFailure(Reason.STRUCTURE_NOTIFICATIONS_UNAVAILABLE)
            notificationManager = manager
            beforeNotifications = manager.activeNotifications.map { it.tag to it.id }.toSet()
            requireFixed(beforeNotifications!!.isEmpty(), Reason.STRUCTURE_EXISTING_NOTIFICATION)
            requireFixed(manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(STRUCTURE_CHANNEL)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true,
                Reason.STRUCTURE_CHANNEL_UNAVAILABLE)

            // These are isolated activity PIs, not production approval/stop capabilities.
            fun harmlessIntent(slot: Int): PendingIntent = PendingIntent.getActivity(
                context(), STRUCTURE_REQUEST_BASE + slot,
                Intent(context(), RootPilotActivity::class.java)
                    .setData(Uri.parse("rootpilot-structure://home/$receiptId/$slot")),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).also { ownIntents += it }
            val open = harmlessIntent(0)
            val confirm = harmlessIntent(1)
            val stop = harmlessIntent(2)
            val text = "仅通知结构对照，不执行任何动作"
            val notification = NotificationCompat.Builder(context(), STRUCTURE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(controlTitle)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send,
                    "确认当前动作", confirm).setAuthenticationRequired(true).build())
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
                .build()
            // A failed Binder response may still have posted it; cleanup is admitted before notify.
            notificationAttempted = true
            manager.notify(ownTag, STRUCTURE_NOTIFICATION_ID, notification)
            requireFixed(manager.activeNotifications.singleOrNull()?.let {
                it.tag == ownTag && it.id == STRUCTURE_NOTIFICATION_ID
            } == true, Reason.STRUCTURE_NOTIFICATION_NOT_OWNED)
            // Exactly one open request; only a confirmed opening admits the single Back cleanup.
            shadeOpened = ui.openNotification()
            requireFixed(shadeOpened, Reason.STRUCTURE_SHADE_OPEN_FAILED)
            return ui
        }

        fun inspectStructure() {
            if (!initializationSucceeded) return
            val ui = postControlNotification()
            // This probe discovers the title resource ID, so bind only the unique known own title.
            val selector = By.pkg(SYSTEM_UI).text(STRUCTURE_TITLE)
            requireFixed(ui.wait(Until.hasObject(selector), 5_000), Reason.STRUCTURE_TITLE_TIMEOUT)
            val titles = ui.findObjects(selector)
            requireFixed(titles.isNotEmpty(), Reason.STRUCTURE_TITLE_MISSING)
            requireFixed(titles.size == 1, Reason.STRUCTURE_TITLE_AMBIGUOUS)
            val snapshot = NotificationStructureEvidence.capture(titles.single(), { it.parent }) {
                NotificationStructureEvidence.Node(it.resourceName, it.className, it.applicationPackage,
                    it.isEnabled, it.isClickable, it.isScrollable)
            }
            stage = Stage.STRUCTURE_EVIDENCE
            NotificationStructureEvidenceFile.write(context().noBackupFilesDir.toPath(), receiptId,
                snapshot.json(receiptId, Process.myPid()))
            // Exclusive writing is not atomic publication. Only a fully written, forced, closed
            // file plus a successful final diagnostic receipt can be accepted by the host.
            structureCaptured = true
        }

        fun inspectIdentity() {
            if (!initializationSucceeded) return
            val ui = postControlNotification()
            requireFixed(ui.wait(Until.hasObject(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(controlTitle)), 5_000),
                Reason.IDENTITY_INITIAL_TITLE_WAIT_TIMEOUT)
            val handles = NotificationIdentityEvidence.Handles<UiObject2> { it.recycle() }
            fun retain(node: UiObject2?): UiObject2? = node?.let(handles::retain)
            try {
                val appLabel = context().applicationInfo.loadLabel(context().packageManager).toString()
                requireFixed(appLabel.isNotBlank(), Reason.IDENTITY_APP_LABEL_EMPTY)
                fun ownNotification() {
                    val active = notificationManager?.activeNotifications ?: throw DiagnosticFailure(Reason.IDENTITY_NOT_OWNED)
                    // Reject foreign/extra entries before reading any notification's title or capabilities.
                    requireFixed(active.size == 1 && active.single().let {
                        it.packageName == TARGET && it.tag == ownTag && it.id == STRUCTURE_NOTIFICATION_ID &&
                            it.notification.channelId == STRUCTURE_CHANNEL
                    }, Reason.IDENTITY_NOT_OWNED)
                    requireFixed(NotificationIdentityEvidence.owns(active.map { entry ->
                        val value = entry.notification
                        val actions = value.actions.orEmpty()
                        val intents = listOfNotNull(value.contentIntent) + actions.mapNotNull { it.actionIntent }
                        NotificationIdentityEvidence.OwnIdentity(entry.packageName, entry.tag, entry.id, value.channelId,
                            value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == controlTitle,
                            actions.size == 2 && intents.size == 3 && intents == ownIntents &&
                                intents.distinct().size == 3 && intents.all {
                                    it.creatorPackage == TARGET && it.creatorUid == Process.myUid() && it.isActivity && it.isImmutable
                                })
                    }, ownTag, STRUCTURE_NOTIFICATION_ID, STRUCTURE_CHANNEL), Reason.IDENTITY_NOT_OWNED)
                }
                fun metadata(n: UiObject2) = NotificationRowStructure.Node(n.resourceName,
                    n.applicationPackage, n.className, n.isEnabled, n.isClickable, n.isScrollable)
                fun bindRow(bindingPhase: NotificationIdentityEvidence.BindingPhase): UiObject2 {
                    ownNotification()
                    val title = try {
                        // Same public selector/query chain as business acceptance; only the known nonce is queried globally.
                        NotificationIdentityEvidence.publicTitle(
                            ui.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(controlTitle)),
                            { handles.retain(it) })
                    } catch (error: NotificationIdentityEvidence.BindingFailure) {
                        throw DiagnosticFailure(Reason.valueOf(NotificationIdentityEvidence.bindingReason(bindingPhase, error.code)))
                    }
                    var current = title
                    var child: UiObject2? = null
                    repeat(12) {
                        val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = current.resourceName))
                        val parent = if (named) null else retain(current.parent)
                        if (named || NotificationRowStructure.isRow(metadata(current), child?.let(::metadata), parent?.let(::metadata))) {
                            requireFixed(current.applicationPackage == SYSTEM_UI, Reason.IDENTITY_ROW_UNRELIABLE)
                            return current
                        }
                        child = current
                        current = parent ?: throw DiagnosticFailure(Reason.IDENTITY_ROW_UNRELIABLE)
                    }
                    throw DiagnosticFailure(Reason.IDENTITY_ROW_UNRELIABLE)
                }
                fun capture(row: UiObject2, phase: NotificationIdentityEvidence.Phase) =
                    NotificationIdentityEvidence.capturePublic(row, phase, { it.childCount }, { it.children },
                        { handles.retain(it) }) { n ->
                        val text = n.text
                        val description = n.contentDescription
                        NotificationIdentityEvidence.Node(n.resourceName, n.className, n.applicationPackage,
                            n.isEnabled, n.isClickable, n.isScrollable, n.isCheckable, n.isChecked, n.isFocusable, n.isFocused,
                            n.isSelected, n.isLongClickable, text == appLabel, description == appLabel,
                            !text.isNullOrEmpty(), !description.isNullOrEmpty(),
                            NotificationIdentityEvidence.matchesTitle(n.applicationPackage, n.resourceName, text, controlTitle))
                    }.also { requireFixed(it.titleCount == 1, Reason.IDENTITY_ROW_TITLE_NOT_UNIQUE) }
                fun expandCandidates(row: UiObject2): List<NotificationIdentityExpand.Candidate<UiObject2>> {
                    // One row-scoped query combines both exact IDs; retain all wrappers before any validation.
                    val found = row.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityExpand.resourcePattern))
                    found.forEach { handles.retain(it) }
                    return found.map { NotificationIdentityExpand.Candidate(it, it.applicationPackage,
                        it.resourceName, it.isEnabled, it.isClickable) }
                }
                val currentRow = bindRow(NotificationIdentityEvidence.BindingPhase.INITIAL)
                val current = capture(currentRow, NotificationIdentityEvidence.Phase.CURRENT)
                val phases = mutableListOf(current)
                // Incomplete captures cannot authorize an expansion, including an otherwise unique button.
                val expand = if (current.truncated) null else NotificationIdentityExpand.initial(expandCandidates(currentRow))
                val status = when {
                    current.truncated -> NotificationIdentityEvidence.ExpandStatus.BOUNDED_INCOMPLETE
                    expand == null -> NotificationIdentityEvidence.ExpandStatus.MISSING
                    !expand.available -> NotificationIdentityEvidence.ExpandStatus.UNAVAILABLE
                    else -> {
                        val rebound = bindRow(NotificationIdentityEvidence.BindingPhase.REBIND)
                        // Re-query the freshly bound row; never click the cached initial wrapper.
                        val fresh = NotificationIdentityExpand.fresh(expand, expandCandidates(rebound))
                        var ancestor: UiObject2? = fresh.node
                        var inside = false
                        repeat(NotificationIdentityEvidence.MAX_LEVELS) {
                            if (ancestor == rebound) inside = true
                            if (!inside) ancestor = retain(ancestor?.parent)
                        }
                        requireFixed(inside, Reason.IDENTITY_EXPAND_CHANGED)
                        ownNotification()
                        fresh.node.click() // One expand only, then the original wait and strict rebind.
                        requireFixed(ui.wait(Until.hasObject(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(controlTitle)), 5_000),
                            Reason.IDENTITY_AFTER_EXPAND_TITLE_WAIT_TIMEOUT)
                        phases += capture(bindRow(NotificationIdentityEvidence.BindingPhase.AFTER_EXPAND), NotificationIdentityEvidence.Phase.AFTER_EXPAND)
                        NotificationIdentityEvidence.ExpandStatus.CLICKED
                    }
                }
                ownNotification()
                stage = Stage.IDENTITY_EVIDENCE
                val bytes = NotificationIdentityEvidence.json(receiptId, Process.myPid(), status, phases)
                NotificationIdentityEvidenceFile.write(context().noBackupFilesDir.toPath(), receiptId, bytes)
                structureCaptured = true
            } catch (error: NotificationIdentityExpand.Failure) {
                throw DiagnosticFailure(when (error.code) {
                    NotificationIdentityExpand.Code.AMBIGUOUS -> Reason.IDENTITY_EXPAND_AMBIGUOUS
                    NotificationIdentityExpand.Code.CHANGED -> Reason.IDENTITY_EXPAND_CHANGED
                })
            } finally {
                // Retain all returned wrappers before admission checks; equal nodes may be distinct objects.
                handles.close()
            }
        }

        fun inspectRoi() {
            if (!initializationSucceeded) return
            val observer = OwnActivityObservation().also { roiObserver = it }
            val application = context().applicationContext as Application
            roiObserverRegistrationAttempted = true
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(observer) }
            val ui = postControlNotification()
            stage = Stage.ROI
            val bridge = automation ?: throw DiagnosticFailure(Reason.ROI_CAPTURE)
            requireFixed(android.os.Build.VERSION.SDK_INT >= 30, Reason.ROI_PRIVACY_API)
            val selector = By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(controlTitle)
            requireFixed(ui.wait(Until.hasObject(selector), 5_000), Reason.ROI_IDENTITY)
            val handles = NotificationIdentityEvidence.Handles<UiObject2> { it.recycle() }
            var cropped: Bitmap? = null
            try {
                val label = context().applicationInfo.loadLabel(context().packageManager).toString()
                requireFixed(label.isNotBlank(), Reason.ROI_IDENTITY)
                fun retain(node: UiObject2?): UiObject2? = node?.let(handles::retain)
                fun metadata(node: UiObject2) = NotificationRowStructure.Node(node.resourceName,
                    node.applicationPackage, node.className, node.isEnabled, node.isClickable, node.isScrollable)
                fun ownNotification() {
                    val active = notificationManager?.activeNotifications ?: throw DiagnosticFailure(Reason.ROI_IDENTITY)
                    requireFixed(active.size == 1 && active.single().let {
                        it.packageName == TARGET && it.tag == ownTag && it.id == STRUCTURE_NOTIFICATION_ID &&
                            it.notification.channelId == STRUCTURE_CHANNEL
                    }, Reason.ROI_IDENTITY)
                    requireFixed(NotificationIdentityEvidence.owns(active.map { entry ->
                        val value = entry.notification
                        val actions = value.actions.orEmpty()
                        val intents = listOfNotNull(value.contentIntent) + actions.mapNotNull { it.actionIntent }
                        NotificationIdentityEvidence.OwnIdentity(entry.packageName, entry.tag, entry.id, value.channelId,
                            value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == controlTitle,
                            actions.size == 2 && intents.size == 3 && intents == ownIntents && intents.distinct().size == 3 &&
                                intents.all { it.creatorPackage == TARGET && it.creatorUid == Process.myUid() && it.isActivity && it.isImmutable })
                    }, ownTag, STRUCTURE_NOTIFICATION_ID, STRUCTURE_CHANNEL), Reason.ROI_IDENTITY)
                }
                fun bounds(rect: Rect) = NotificationRoiEvidence.Bounds(rect.left, rect.top, rect.right, rect.bottom)
                fun frame(): NotificationRoiEvidence.Frame {
                    ownNotification()
                    requireFixed(!observer.launched.get() && ui.currentPackageName == SYSTEM_UI, Reason.ROI_IDENTITY)
                    val keyguard = context().getSystemService(KeyguardManager::class.java)
                    requireFixed(keyguard != null && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked, Reason.ROI_PRIVACY_LOCK)
                    val titles = ui.findObjects(selector).onEach { handles.retain(it) }
                    requireFixed(titles.size == 1, Reason.ROI_IDENTITY)
                    val title = titles.single()
                    var current = title
                    var child: UiObject2? = null
                    var row: UiObject2? = null
                    for (level in 0 until 12) {
                        val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = current.resourceName))
                        val parent = if (named) null else retain(current.parent)
                        if (named || NotificationRowStructure.isRow(metadata(current), child?.let(::metadata), parent?.let(::metadata))) {
                            row = current
                            break
                        }
                        child = current
                        current = parent ?: throw DiagnosticFailure(Reason.ROI_IDENTITY)
                    }
                    val bound = row ?: throw DiagnosticFailure(Reason.ROI_IDENTITY)
                    requireFixed(bound.applicationPackage == SYSTEM_UI && bound.displayId == 0, Reason.ROI_IDENTITY)
                    val snapshot = NotificationIdentityEvidence.capturePublic(bound, NotificationIdentityEvidence.Phase.CURRENT,
                        { it.childCount }, { it.children }, { handles.retain(it) }) { node ->
                        val text = node.text
                        val description = node.contentDescription
                        NotificationIdentityEvidence.Node(node.resourceName, node.className, node.applicationPackage,
                            node.isEnabled, node.isClickable, node.isScrollable, node.isCheckable, node.isChecked,
                            node.isFocusable, node.isFocused, node.isSelected, node.isLongClickable,
                            text == label, description == label, !text.isNullOrEmpty(), !description.isNullOrEmpty(),
                            NotificationIdentityEvidence.matchesTitle(node.applicationPackage, node.resourceName, text, controlTitle),
                            text == "确认当前动作", text == "停止")
                    }
                    requireFixed(NotificationExpansionEvidence.before(snapshot) == NotificationExpansionEvidence.Code.NONE, Reason.ROI_IDENTITY)
                    val roi = bounds(bound.visibleBounds)
                    val nodes = NotificationIdentityEvidence.Handles<AccessibilityNodeInfo> { it.recycle() }
                    val windowHandles = NotificationIdentityEvidence.Handles<AccessibilityWindowInfo> { it.recycle() }
                    try {
                        val root = bridge.rootInActiveWindow?.let(nodes::retain) ?: throw DiagnosticFailure(Reason.ROI_PRIVACY_ROOT)
                        requireFixed(root.packageName?.toString() == SYSTEM_UI && root.windowId >= 0, Reason.ROI_PRIVACY_ROOT)
                        // Copy the already unique title; its cached node remains owned by UiObject2.
                        val activeTitle = nodes.retain(AccessibilityNodeInfo(title.accessibilityNodeInfo))
                        val activeBounds = Rect().also(activeTitle::getBoundsInScreen)
                        requireFixed(activeTitle.packageName?.toString() == SYSTEM_UI, Reason.ROI_PRIVACY_MATCH_PKG)
                        requireFixed(NotificationIdentityEvidence.titleResourcePattern.matcher(activeTitle.viewIdResourceName.orEmpty()).matches(),
                            Reason.ROI_PRIVACY_MATCH_ID)
                        requireFixed(activeTitle.text?.toString() == controlTitle, Reason.ROI_PRIVACY_MATCH_TEXT)
                        requireFixed(activeTitle.windowId == root.windowId, Reason.ROI_PRIVACY_MATCH_WINDOW)
                        requireFixed(activeBounds == title.visibleBounds, Reason.ROI_PRIVACY_MATCH_BOUNDS)
                        val windows = bridge.windows.onEach { windowHandles.retain(it) }
                        requireFixed(windows.size in 1..8, Reason.ROI_PRIVACY_WINDOWS)
                        val publicWindows = windows.map { window ->
                            val rect = Rect().also(window::getBoundsInScreen)
                            NotificationRoiEvidence.Window(window.id, window.displayId, window.layer, bounds(rect), window.isActive)
                        }
                        return NotificationRoiEvidence.Frame(ui.displayWidth, ui.displayHeight, ui.displayRotation, bound.displayId,
                            roi, root.windowId, publicWindows, owned = true, fullIdentity = true, systemUi = true)
                    } finally {
                        try { windowHandles.close() } finally { nodes.close() }
                    }
                }
                fun admit(code: NotificationRoiEvidence.Code) {
                    if (code != NotificationRoiEvidence.Code.NONE) throw DiagnosticFailure(Reason.valueOf(code.name))
                }
                val before = frame()
                admit(NotificationRoiEvidence.admit(before))
                instrumentation.runOnMainSync { requireFixed(!observer.launched.get(), Reason.ROI_POST) }
                val roi = before.roi ?: throw DiagnosticFailure(Reason.ROI_BOUNDS)
                // Source is never passed to compression, file IO, evidence, history, or screenshot managers.
                screenshotCalls += 1
                val full = bridge.takeScreenshot() ?: throw DiagnosticFailure(Reason.ROI_CAPTURE)
                try {
                    admit(NotificationRoiEvidence.bitmap(before, full.width, full.height, full.config == Bitmap.Config.ARGB_8888,
                        full.allocationByteCount))
                    roiWidth = roi.width.toInt()
                    roiHeight = roi.height.toInt()
                    val independent = Bitmap.createBitmap(roiWidth, roiHeight, Bitmap.Config.ARGB_8888)
                    cropped = independent
                    requireFixed(independent !== full && independent.width == roiWidth && independent.height == roiHeight,
                        Reason.ROI_CAPTURE)
                    val canvas = Canvas(independent)
                    try {
                        canvas.drawBitmap(full, Rect(roi.left, roi.top, roi.right, roi.bottom), Rect(0, 0, roiWidth, roiHeight), null)
                    } finally { canvas.setBitmap(null) }
                } finally { full.recycle() }
                admit(NotificationRoiEvidence.after(before, frame()))
                instrumentation.runOnMainSync { requireFixed(!observer.launched.get(), Reason.ROI_POST) }
                ownNotification()
                val output = NotificationRoiEvidence.PngOutput()
                requireFixed(cropped?.compress(Bitmap.CompressFormat.PNG, 100, output) == true, Reason.ROI_CAPTURE)
                roiBytes = output.bytes()
                requireFixed(NotificationRoiEvidence.pngValid(roiBytes!!, roiWidth, roiHeight), Reason.ROI_CAPTURE)
                roiPrepared = true
                structureCaptured = true
            } finally {
                cropped?.recycle()
                handles.close()
            }
        }

        private fun finishRoiObservation() {
            if (!roiObserverRegistrationAttempted) return
            val observation = roiObserver
            var absent = false
            try {
                instrumentation.runOnMainSync {
                    absent = observation != null && !observation.launched.get()
                    (context().applicationContext as Application).unregisterActivityLifecycleCallbacks(observation!!)
                }
                noOwnActivityLaunch = absent
                requireFixed(absent, Reason.ROI_POST)
            } finally { roiObserverRegistrationAttempted = false }
        }

        fun inspectExpansion() {
            if (!initializationSucceeded) return
            val ui = postControlNotification()
            stage = Stage.EXPANSION
            val selector = By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(controlTitle)
            requireFixed(ui.wait(Until.hasObject(selector), 5_000), Reason.IDENTITY_INITIAL_TITLE_WAIT_TIMEOUT)
            val handles = NotificationIdentityEvidence.Handles<UiObject2> { it.recycle() }
            val observer = OwnActivityObservation()
            val application = context().applicationContext as Application
            var registrationAttempted = false
            val timeoutCandidate = if (longPressMode) NotificationExpansionTimeoutEvidence.Candidate<UiObject2>() else null
            var postTimeout = false
            var recheckTimeoutOwnership: (() -> Unit)? = null
            try {
                val label = context().applicationInfo.loadLabel(context().packageManager).toString()
                requireFixed(label.isNotBlank(), Reason.IDENTITY_APP_LABEL_EMPTY)
                fun ownNotification() {
                    val active = notificationManager?.activeNotifications ?: throw DiagnosticFailure(Reason.IDENTITY_NOT_OWNED)
                    requireFixed(active.size == 1 && active.single().let {
                        it.packageName == TARGET && it.tag == ownTag && it.id == STRUCTURE_NOTIFICATION_ID &&
                            it.notification.channelId == STRUCTURE_CHANNEL
                    }, Reason.IDENTITY_NOT_OWNED)
                    requireFixed(NotificationIdentityEvidence.owns(active.map { entry ->
                        val value = entry.notification
                        val actions = value.actions.orEmpty()
                        val intents = listOfNotNull(value.contentIntent) + actions.mapNotNull { it.actionIntent }
                        NotificationIdentityEvidence.OwnIdentity(entry.packageName, entry.tag, entry.id, value.channelId,
                            value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == controlTitle,
                            actions.size == 2 && intents.size == 3 && intents == ownIntents && intents.distinct().size == 3 &&
                                intents.all { it.creatorPackage == TARGET && it.creatorUid == Process.myUid() && it.isActivity && it.isImmutable })
                    }, ownTag, STRUCTURE_NOTIFICATION_ID, STRUCTURE_CHANNEL), Reason.IDENTITY_NOT_OWNED)
                }
                recheckTimeoutOwnership = {
                    ownNotification()
                    requireFixed(ui.currentPackageName == SYSTEM_UI, Reason.EXPANSION_LEFT_SYSTEM_UI)
                }
                fun metadata(n: UiObject2) = NotificationRowStructure.Node(n.resourceName,
                    n.applicationPackage, n.className, n.isEnabled, n.isClickable, n.isScrollable)
                data class BoundRow(val node: UiObject2, val modal: Boolean = false)
                fun bind(phase: NotificationIdentityEvidence.BindingPhase): BoundRow {
                    requireFixed(!observer.launched.get(), Reason.EXPANSION_OWN_ACTIVITY_LAUNCHED)
                    val activePackage = ui.currentPackageName
                    val site = when (phase) {
                        NotificationIdentityEvidence.BindingPhase.INITIAL -> NotificationExpansionEvidence.GuardSite.INITIAL_BIND
                        NotificationIdentityEvidence.BindingPhase.REBIND -> NotificationExpansionEvidence.GuardSite.REBIND
                        NotificationIdentityEvidence.BindingPhase.AFTER_EXPAND -> NotificationExpansionEvidence.GuardSite.AFTER_BIND
                    }
                    requireFixed(activePackage == SYSTEM_UI,
                        Reason.valueOf(NotificationExpansionEvidence.activeRootReason(site, activePackage).name))
                    ownNotification()
                    var current = try {
                        NotificationIdentityEvidence.publicTitle(ui.findObjects(selector), { handles.retain(it) })
                    } catch (error: NotificationIdentityEvidence.BindingFailure) {
                        throw DiagnosticFailure(Reason.valueOf(NotificationIdentityEvidence.bindingReason(phase, error.code)))
                    }
                    fun rowFailure(): Nothing {
                        if (longPressMode && phase == NotificationIdentityEvidence.BindingPhase.AFTER_EXPAND) {
                            try {
                                requireFixed(!observer.launched.get(), Reason.EXPANSION_OWN_ACTIVITY_LAUNCHED)
                                val failurePackage = ui.currentPackageName
                                requireFixed(failurePackage == SYSTEM_UI, Reason.EXPANSION_LEFT_SYSTEM_UI)
                                ownNotification()
                                val title = NotificationIdentityEvidence.publicTitle(ui.findObjects(selector), { handles.retain(it) })
                                val snapshot = NotificationStructureEvidence.capture(title, { node -> node.parent?.let(handles::retain) }) { node ->
                                    NotificationStructureEvidence.Node(node.resourceName, node.className, node.applicationPackage,
                                        node.isEnabled, node.isClickable, node.isScrollable)
                                }
                                requireFixed(!observer.launched.get(), Reason.EXPANSION_OWN_ACTIVITY_LAUNCHED)
                                requireFixed(ui.currentPackageName == SYSTEM_UI, Reason.EXPANSION_LEFT_SYSTEM_UI)
                                ownNotification()
                                expansionFailureBytes = snapshot.expansionFailureJson(receiptId, Process.myPid())
                            } catch (_: Throwable) { expansionFailureBytes = null }
                        }
                        throw DiagnosticFailure(Reason.IDENTITY_ROW_UNRELIABLE)
                    }
                    var child: UiObject2? = null
                    repeat(12) {
                        val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = current.resourceName))
                        val parent = if (named) null else current.parent?.let(handles::retain)
                        if (named || NotificationRowStructure.isRow(metadata(current), child?.let(::metadata), parent?.let(::metadata))) {
                            if (current.applicationPackage != SYSTEM_UI) rowFailure()
                            requireFixed(!current.visibleBounds.isEmpty, Reason.EXPANSION_BOUNDS_EMPTY)
                            return BoundRow(current)
                        }
                        if (longPressMode && phase == NotificationIdentityEvidence.BindingPhase.AFTER_EXPAND &&
                            NotificationRowStructure.isModalRow(metadata(current), child?.let(::metadata), parent?.let(::metadata))) {
                            requireFixed(!current.visibleBounds.isEmpty, Reason.EXPANSION_BOUNDS_EMPTY)
                            return BoundRow(current, modal = true)
                        }
                        child = current
                        current = parent ?: rowFailure()
                    }
                    rowFailure()
                }
                fun capture(row: BoundRow, phase: NotificationIdentityEvidence.Phase): NotificationIdentityEvidence.Snapshot<UiObject2> {
                    val describeNode: (UiObject2) -> NotificationIdentityEvidence.Node = { n ->
                        val text = n.text
                        val description = n.contentDescription
                        NotificationIdentityEvidence.Node(n.resourceName, n.className, n.applicationPackage,
                            n.isEnabled, n.isClickable, n.isScrollable, n.isCheckable, n.isChecked, n.isFocusable, n.isFocused,
                            n.isSelected, n.isLongClickable, text == label, description == label,
                            !text.isNullOrEmpty(), !description.isNullOrEmpty(),
                            NotificationIdentityEvidence.matchesTitle(n.applicationPackage, n.resourceName, text, controlTitle),
                            confirmActionMatches = text == "确认当前动作", stopActionMatches = text == "停止")
                    }
                    val snapshot = if (longPressMode && row.modal && phase == NotificationIdentityEvidence.Phase.AFTER_EXPAND) {
                        try {
                            NotificationIdentityEvidence.captureModalContentPublic(row.node, phase, { it.childCount }, { it.children },
                                { handles.retain(it) }, describeNode)
                        } catch (error: NotificationIdentityEvidence.ModalContentFailure) {
                            throw DiagnosticFailure(Reason.valueOf(error.code.name))
                        }
                    } else NotificationIdentityEvidence.capturePublic(row.node, phase, { it.childCount }, { it.children },
                        { handles.retain(it) }, describeNode)
                    return if (row.modal) snapshot.withModalBinding() else snapshot
                }
                fun admit(code: NotificationExpansionEvidence.Code, site: NotificationCaptureBudget.Site,
                    snapshot: NotificationIdentityEvidence.Snapshot<UiObject2>) {
                    if (longPressMode && code == NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED) {
                        captureBudget = NotificationCaptureBudget.report(site, code, snapshot.captureBudget)
                    }
                    val identitySite = when (site) {
                        NotificationCaptureBudget.Site.INITIAL_CURRENT -> NotificationExpansionEvidence.IdentitySite.INITIAL_CURRENT
                        NotificationCaptureBudget.Site.REBIND_CURRENT -> NotificationExpansionEvidence.IdentitySite.REBIND_CURRENT
                        NotificationCaptureBudget.Site.AFTER_EXPAND -> NotificationExpansionEvidence.IdentitySite.AFTER_EXPAND
                        NotificationCaptureBudget.Site.NONE -> null
                    }
                    val reasonCode = NotificationExpansionEvidence.identityReason(longPressMode, identitySite, code)
                    requireFixed(code == NotificationExpansionEvidence.Code.NONE, Reason.valueOf(reasonCode.name))
                }
                // Register on main before the fresh pre-gesture binding; this observes, not prevents, mislaunches.
                registrationAttempted = true
                instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(observer) }
                val row = bind(NotificationIdentityEvidence.BindingPhase.INITIAL)
                val current = capture(row, NotificationIdentityEvidence.Phase.CURRENT)
                admit(NotificationExpansionEvidence.before(current), NotificationCaptureBudget.Site.INITIAL_CURRENT, current)
                // Rebind once immediately before the only gesture and revalidate the complete visible phase.
                val freshBound = bind(NotificationIdentityEvidence.BindingPhase.REBIND)
                val fresh = freshBound.node
                val freshCurrent = capture(freshBound, NotificationIdentityEvidence.Phase.CURRENT)
                admit(NotificationExpansionEvidence.before(freshCurrent), NotificationCaptureBudget.Site.REBIND_CURRENT, freshCurrent)
                var preGesturePackageReason = Reason.NONE
                requireFixed(!observer.launched.get() && run {
                    val activePackage = ui.currentPackageName
                    preGesturePackageReason = Reason.valueOf(NotificationExpansionEvidence.activeRootReason(
                        NotificationExpansionEvidence.GuardSite.PRE_GESTURE, activePackage).name)
                    activePackage == SYSTEM_UI
                }, if (observer.launched.get()) Reason.EXPANSION_OWN_ACTIVITY_LAUNCHED else preGesturePackageReason)
                requireFixed(!fresh.visibleBounds.isEmpty, Reason.EXPANSION_BOUNDS_EMPTY)
                if (longPressMode) {
                    requireFixed(NotificationExpansionEvidence.longPressAvailable(fresh.isEnabled, fresh.isLongClickable,
                        !fresh.visibleBounds.isEmpty), Reason.EXPANSION_LONG_PRESS_UNAVAILABLE)
                    fresh.longClick() // Public center-touch void gesture, not semantic expand or an OEM guarantee.
                } else {
                    fresh.setGestureMarginPercentage(0.25f)
                    fresh.swipe(Direction.DOWN, 0.5f) // Public void gesture, not semantic expand or an OEM guarantee.
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var after: NotificationIdentityEvidence.Snapshot<UiObject2>? = null
                val expanded = ui.wait(object : Condition<UiDevice, Boolean> {
                    override fun apply(device: UiDevice): Boolean {
                        requireFixed(SystemClock.uptimeMillis() <= deadline, Reason.EXPANSION_POST_TIMEOUT)
                        timeoutCandidate?.beginQuery()
                        val snapshot = capture(bind(NotificationIdentityEvidence.BindingPhase.AFTER_EXPAND),
                            NotificationIdentityEvidence.Phase.AFTER_EXPAND)
                        val decision = NotificationExpansionEvidence.after(snapshot)
                        timeoutCandidate?.observe(snapshot, decision)
                        if (decision == NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING) return false
                        admit(decision, NotificationCaptureBudget.Site.AFTER_EXPAND, snapshot)
                        requireFixed(SystemClock.uptimeMillis() <= deadline, Reason.EXPANSION_POST_TIMEOUT)
                        ownNotification()
                        val activePackage = ui.currentPackageName
                        requireFixed(activePackage == SYSTEM_UI, Reason.valueOf(NotificationExpansionEvidence.activeRootReason(
                            NotificationExpansionEvidence.GuardSite.AFTER_VALIDATE, activePackage).name))
                        after = snapshot
                        return true
                    }
                }, 5_000)
                requireFixed(expanded == true && after != null, Reason.EXPANSION_POST_TIMEOUT)
                ownNotification()
                // Main-thread unregister is also a barrier for lifecycle callbacks queued during the gesture/checks.
                instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(observer) }
                registrationAttempted = false
                requireFixed(!observer.launched.get(), Reason.EXPANSION_OWN_ACTIVITY_LAUNCHED)
                requireFixed(SystemClock.uptimeMillis() <= deadline, Reason.EXPANSION_POST_TIMEOUT)
                noOwnActivityLaunch = true
                ownRowExpanded = true
                expansionBytes = NotificationIdentityEvidence.expansionJson(receiptId, Process.myPid(),
                    listOf(freshCurrent, after!!), noOwnActivityLaunch, longPress = longPressMode)
                structureCaptured = true
            } catch (error: Throwable) {
                postTimeout = longPressMode && error is DiagnosticFailure && error.reason == Reason.EXPANSION_POST_TIMEOUT
                throw error
            } finally {
                try {
                    if (postTimeout && timeoutCandidate?.available == true && !observer.launched.get()) {
                        try {
                            recheckTimeoutOwnership?.invoke() ?: throw DiagnosticFailure(Reason.IDENTITY_NOT_OWNED)
                            timeoutOwnershipVerified = true
                        } catch (_: Throwable) { timeoutCandidate.beginQuery() }
                    }
                    if (registrationAttempted) instrumentation.runOnMainSync {
                        application.unregisterActivityLifecycleCallbacks(observer)
                    }
                    if (postTimeout && timeoutOwnershipVerified) {
                        timeoutObserverVerified = !observer.launched.get()
                        timeoutFailureBytes = try {
                            timeoutCandidate?.bytes(receiptId, Process.myPid(), timeoutOwnershipVerified, timeoutObserverVerified)
                        } catch (_: Throwable) { null }
                    }
                    if (longPressMode && expansionFailureBytes != null) {
                        failureObserverVerified = !observer.launched.get()
                        if (!failureObserverVerified) expansionFailureBytes = null
                    }
                } finally { handles.close() }
            }
        }

        fun cleanStructure() {
            if (!notificationMode) return
            if (notificationAttempted) verify(Reason.STRUCTURE_NOTIFICATION_REMOVE_FAILED) {
                notificationManager?.cancel(ownTag, STRUCTURE_NOTIFICATION_ID)
                notificationManager != null
            }
            // Independently cancel every capability even when another cleanup operation fails.
            ownIntents.forEach { pending -> verify(Reason.STRUCTURE_PI_CANCEL_FAILED) { pending.cancel(); true } }
            if (shadeOpened) verify(Reason.STRUCTURE_SHADE_CLOSE_FAILED) {
                val ui = device ?: return@verify false
                if (ui.currentPackageName == SYSTEM_UI) ui.pressBack() else true
            }
            notificationRemoved = verify(Reason.STRUCTURE_NOTIFICATIONS_CHANGED) {
                val manager = notificationManager ?: context().getSystemService(NotificationManager::class.java)
                    ?: return@verify false
                val after = manager.activeNotifications.map { it.tag to it.id }.toSet()
                // No publication before baseline capture is also a valid no-side-effect cleanup.
                if (beforeNotifications == null) after.none { it.first == ownTag && it.second == STRUCTURE_NOTIFICATION_ID }
                else after == beforeNotifications
            }
        }

        fun recordFailure(error: Throwable) {
            if (reason == Reason.NONE) reason = classify(stage, error)
        }

        private fun capture(block: () -> Unit) {
            try { block() } catch (error: Throwable) { recordFailure(error) }
        }

        fun restoreConfigurator() {
            // All UI cleanup precedes this boundary. Never acquire UiDevice afterwards: doing so
            // would reconnect accessibility with its configured flags and invalidate restoration.
            automationAccessibilityReleased = if (automation != null) {
                verify(Reason.UI_AUTOMATION_RELEASE_FAILED) {
                    instrumentation.getUiAutomation(
                        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or
                            UiAutomation.FLAG_DONT_USE_ACCESSIBILITY,
                    ) != null
                }
            } else true
            val flags = originalFlags
            if (flags == null) {
                // No setter was reached, so there is no library configuration to restore.
                configuratorRestored = configurator == null
                return
            }
            configuratorRestored = verify(Reason.CONFIGURATOR_NOT_RESTORED, Stage.CONFIGURATOR_RESTORE) {
                val config = configurator ?: return@verify false
                config.uiAutomationFlags = flags
                config.uiAutomationFlags == flags
            }
        }

        fun verifyOriginalState() {
            // Do not short-circuit: every actual read runs even when an earlier guard failed.
            mainProcessUnchanged = verify(Reason.MAIN_PROCESS_CHANGED) {
                val current = identity()
                beforeIdentity != null && current == beforeIdentity
            }
            imeUnchanged = verify(Reason.IME_CHANGED) {
                val current = secure(Settings.Secure.DEFAULT_INPUT_METHOD)
                imeCaptured && current == beforeIme
            }
            accessibilityUnchanged = verify(Reason.ACCESSIBILITY_CHANGED) {
                val current = accessibility()
                beforeAccessibility != null && current == beforeAccessibility
            }
            stateUnchanged = verify(Reason.TASK_STATE_CHANGED) {
                val current = state()
                finalIdle = idle(current)
                beforeState != null && current == beforeState
            }
            logsUnchanged = verify(Reason.LOGS_CHANGED) {
                val current = logDigest(state())
                beforeLogs != null && current == beforeLogs
            }
            historyUnchanged = verify(Reason.HISTORY_CHANGED) {
                val current = historyFiles()
                beforeHistory != null && current == beforeHistory
            }
            recoveryUnchanged = verify(Reason.RECOVERY_CHANGED) {
                val current = recoveryFiles()
                finalRecoveryAbsent = current.isEmpty()
                beforeRecovery != null && current == beforeRecovery
            }
            configFilesUnchanged = verify(Reason.CONFIG_FILES_CHANGED) {
                val current = configFiles()
                beforeConfigFiles != null && current == beforeConfigFiles
            }
            // All evidence must be available. Failure does not manufacture successful guards.
            val noActivity = mainProcessUnchanged && stateUnchanged && logsUnchanged && historyUnchanged &&
                recoveryUnchanged && finalIdle && finalRecoveryAbsent
            noModelRequests = noActivity
            noScreenshots = noActivity && (!roiMode || screenshotCalls == 0)
            if (roiMode) noProductScreenshots = noActivity
            noExecutions = noActivity
            baselineRestored = mainProcessUnchanged && imeUnchanged && accessibilityUnchanged && stateUnchanged &&
                logsUnchanged && historyUnchanged && recoveryUnchanged && configFilesUnchanged &&
                finalIdle && finalRecoveryAbsent && configuratorRestored && automationAccessibilityReleased &&
                (!notificationMode || notificationRemoved)
        }

        private fun verify(
            mismatch: Reason,
            failureStage: Stage = Stage.VERIFY,
            block: () -> Boolean,
        ): Boolean = try {
            block().also { if (!it && cleanupReason == Reason.NONE) cleanupReason = mismatch }
        } catch (error: Throwable) {
            if (cleanupReason == Reason.NONE) cleanupReason = classify(failureStage, error)
            false
        }

        fun report() {
            if (roiMode) {
                stage = Stage.ROI
                try { finishRoiObservation() } catch (error: Throwable) { recordFailure(error) }
                if (roiPrepared && baselineRestored && noOwnActivityLaunch && noProductScreenshots && noModelRequests && noExecutions &&
                    reason == Reason.NONE && cleanupReason == Reason.NONE) {
                    try {
                        NotificationRoiEvidence.write(context().noBackupFilesDir.toPath(), receiptId, Process.myPid(), roiWidth, roiHeight,
                            roiBytes ?: throw DiagnosticFailure(Reason.ROI_CAPTURE), screenshotCalls,
                            NotificationRoiEvidence.Proofs(freshOwnRow = true, fullIdentity = true, boundsStable = true,
                                windowChecksPassed = true, ownNotificationUnchanged = true, noOwnActivityLaunch = noOwnActivityLaunch,
                                cleanupVerified = baselineRestored, fullScreenMemoryOnly = true, independentRoiBitmap = true,
                                noModelRequests = noModelRequests, noExecutions = noExecutions))
                        roiCaptured = true
                    } catch (error: Throwable) { recordFailure(error) }
                }
                roiBytes = null
            }
            // Expansion publishes no acceptable artifact until observation and every original cleanup guard pass.
            if (expansionMode && passed) {
                try {
                    stage = Stage.IDENTITY_EVIDENCE
                    NotificationIdentityEvidenceFile.write(context().noBackupFilesDir.toPath(), receiptId,
                        expansionBytes ?: throw DiagnosticFailure(Reason.EXPANSION_PHASE_INVALID))
                } catch (error: Throwable) { recordFailure(error) }
            }
            if (longPressMode && NotificationExpansionEvidence.failurePublishable(reason.name, cleanupReason.name,
                baselineRestored, failureObserverVerified, expansionFailureBytes != null)) {
                try {
                    NotificationStructureEvidenceFile.writeExpansionFailure(context().noBackupFilesDir.toPath(), receiptId,
                        expansionFailureBytes!!)
                    failureStructureCaptured = true
                } catch (_: Throwable) { /* Preserve the original failure; an incomplete file is never exportable. */ }
            }
            if (NotificationExpansionTimeoutEvidence.publishable(longPressMode, reason.name, cleanupReason.name,
                baselineRestored, timeoutOwnershipVerified, timeoutObserverVerified, timeoutFailureBytes != null)) {
                try {
                    NotificationIdentityEvidenceFile.writeExpansionTimeout(context().noBackupFilesDir.toPath(), receiptId,
                        timeoutFailureBytes!!)
                    failureStructureCaptured = true
                } catch (_: Throwable) { /* Historical evidence never changes the original timeout or becomes acceptance. */ }
            }
            val bundle = Bundle().apply {
                putString(if (roiMode) "notificationRoiCaptureStage" else if (longPressMode) "notificationLongPressExpansionStage" else if (expansionMode) "notificationExpansionStage" else if (identityMode) "notificationIdentityStage" else if (structureMode) "notificationStructureStage" else "initDiagnosticStage",
                    if (passed) "verified" else "failed")
                if (notificationMode) {
                    putBoolean("structureCaptured", structureCaptured)
                    putBoolean("notificationRemoved", notificationRemoved)
                }
                if (expansionMode) {
                    putBoolean("ownRowExpanded", ownRowExpanded)
                    putBoolean("noOwnActivityLaunch", noOwnActivityLaunch)
                }
                if (roiMode) {
                    putBoolean("roiCaptured", roiCaptured)
                    putBoolean("noOwnActivityLaunch", noOwnActivityLaunch)
                    putBoolean("noProductScreenshots", noProductScreenshots)
                    putInt("screenshotCalls", screenshotCalls)
                }
                if (longPressMode) {
                    putBoolean("failureStructureCaptured", failureStructureCaptured)
                    putString("captureSite", captureBudget.captureSite.name)
                    putString("budgetCause", captureBudget.summary.budgetCause.name)
                    putInt("visitedNodes", captureBudget.summary.visitedNodes)
                    putInt("maxVisitedDepth", captureBudget.summary.maxVisitedDepth)
                    putInt("pendingAtFirstCut", captureBudget.summary.pendingAtFirstCut)
                    putInt("firstCutVisitedNodes", captureBudget.summary.firstCutVisitedNodes)
                    putInt("firstCutDepth", captureBudget.summary.firstCutDepth)
                }
                putString("reasonCode", reason.name)
                putString("cleanupReasonCode", cleanupReason.name)
                putString("recoveryReceiptId", receiptId)
                // Correlation ID only; no model task or business run was started.
                putString("runId", receiptId)
                putInt("mainPid", Process.myPid())
                putBoolean("passed", passed)
                putBoolean("baselineRestored", baselineRestored)
                putBoolean("uiAutomationAvailable", uiAutomationAvailable)
                putBoolean("uiDeviceAvailable", uiDeviceAvailable)
                putBoolean("mainProcessUnchanged", mainProcessUnchanged)
                putBoolean("imeUnchanged", imeUnchanged)
                putBoolean("accessibilityUnchanged", accessibilityUnchanged)
                if (!notificationMode) {
                    putString("accessibilityEnabledBefore", beforeAccessibility?.let {
                        AccessibilityEnabledDiagnostic.classify(it.enabled)
                    }?.name ?: AccessibilityEnabledDiagnostic.UNKNOWN.name)
                    putString("accessibilityEnabledWhileConnected", accessibilityEnabledWhileConnected.name)
                    putBoolean("enabledServicesUnchanged", enabledServicesUnchanged)
                }
                putBoolean("noModelRequests", noModelRequests)
                putBoolean("noScreenshots", noScreenshots)
                putBoolean("noExecutions", noExecutions)
            }
            try { instrumentation.sendStatus(0, bundle) }
            catch (_: Throwable) { if (reason == Reason.NONE) reason = Reason.REPORT_FAILED }
        }

        fun failureCode(): String = if (reason != Reason.NONE) reason.name else cleanupReason.name

        private fun context(): Context = context ?: throw DiagnosticFailure(Reason.BASELINE_OTHER)
        private fun state(): RootPilotUiState = RootPilotService.uiState.value
        private fun identity() = Identity(Process.myPid(), Process.myUid(), Application.getProcessName())
        private fun secure(key: String): String? = Settings.Secure.getString(context().contentResolver, key)
        private fun accessibility() = Accessibility(
            secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            secure(Settings.Secure.ACCESSIBILITY_ENABLED),
        )
        private fun logDigest(value: RootPilotUiState): String {
            val hash = MessageDigest.getInstance("SHA-256")
            value.logs.forEach { line ->
                val bytes = line.toByteArray(Charsets.UTF_8)
                hash.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                hash.update(0.toByte())
                hash.update(bytes)
            }
            return hex(hash.digest())
        }

        private fun historyFiles(): Map<String, String> = directoryFiles(
            context().noBackupFilesDir.resolve("rootpilot_history"),
        )

        private fun recoveryFiles(): Map<String, String> {
            val snapshots = directoryFiles(context().filesDir) { it.startsWith(RootPilotRunStore.FILE_NAME) }
            val ime = directoryFiles(context().noBackupFilesDir) { it.startsWith("rootpilot_original_ime") }
            return snapshots.mapKeys { "snapshot/${it.key}" } + ime.mapKeys { "ime/${it.key}" }
        }

        private fun configFiles(): Map<String, String> = directoryFiles(context().noBackupFilesDir) {
            it == RootPilotApiConfigStore.FILE_NAME || it == "rootpilot_app_launch_allowlist.json"
        }
    }

    private class OwnActivityObservation : Application.ActivityLifecycleCallbacks {
        val launched = AtomicBoolean(false)
        override fun onActivityCreated(activity: Activity, state: Bundle?) { launched.set(true) }
        override fun onActivityResumed(activity: Activity) { launched.set(true) }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private data class Identity(val pid: Int, val uid: Int, val processName: String)
    private data class Accessibility(val enabledServices: String?, val enabled: String?)
    private class DiagnosticFailure(val reason: Reason) : RuntimeException()
    private enum class Stage { BASELINE, UI_AUTOMATION, CONFIGURATOR, UI_DEVICE, STRUCTURE, STRUCTURE_EVIDENCE, IDENTITY, IDENTITY_EVIDENCE, EXPANSION, ROI, CONFIGURATOR_RESTORE, VERIFY }
    private enum class Category { SECURITY, ILLEGAL_STATE, NULL_POINTER, TIMEOUT, OTHER }
    private enum class Reason {
        NONE, ROI_IDENTITY, ROI_BOUNDS, ROI_PRIVACY, ROI_LIMIT, ROI_CAPTURE, ROI_POST, ROI_WRITE,
        ROI_PRIVACY_API, ROI_PRIVACY_LOCK, ROI_PRIVACY_ROOT, ROI_PRIVACY_MATCH,
        ROI_PRIVACY_WINDOWS, ROI_PRIVACY_META, ROI_PRIVACY_OWN, ROI_PRIVACY_OVERLAP,
        ROI_PRIVACY_MATCH_COUNT, ROI_PRIVACY_MATCH_PKG, ROI_PRIVACY_MATCH_ID,
        ROI_PRIVACY_MATCH_TEXT, ROI_PRIVACY_MATCH_WINDOW, ROI_PRIVACY_MATCH_BOUNDS,
        TARGET_PROCESS, NO_RESTART_REQUIRED, RECEIPT_ID, BASELINE_ACK_REQUIRED,
        SERVICE_BUSY, EXISTING_RECOVERY, BASELINE_UNRESTORABLE, IME_UNAVAILABLE, BASELINE_CHANGED,
        UI_AUTOMATION_NULL, UI_AUTOMATION_RELEASE_FAILED, UI_DEVICE_NULL, CONFIGURATOR_FLAGS, CONFIGURATOR_NOT_RESTORED,
        MAIN_PROCESS_CHANGED, IME_CHANGED, ACCESSIBILITY_CHANGED, TASK_STATE_CHANGED,
        LOGS_CHANGED, HISTORY_CHANGED, RECOVERY_CHANGED, CONFIG_FILES_CHANGED, REPORT_FAILED,
        BASELINE_SECURITY, BASELINE_ILLEGAL_STATE, BASELINE_NULL_POINTER, BASELINE_TIMEOUT, BASELINE_OTHER,
        UI_AUTOMATION_SECURITY, UI_AUTOMATION_ILLEGAL_STATE, UI_AUTOMATION_NULL_POINTER, UI_AUTOMATION_TIMEOUT, UI_AUTOMATION_OTHER,
        UI_AUTOMATION_CONNECTION_SHUTDOWN, UI_AUTOMATION_ALREADY_CONNECTED,
        UI_AUTOMATION_ALREADY_REGISTERED, UI_AUTOMATION_REGISTRATION_FAILED, UI_AUTOMATION_DISCONNECT_CONNECTING,
        CONFIGURATOR_SECURITY, CONFIGURATOR_ILLEGAL_STATE, CONFIGURATOR_NULL_POINTER, CONFIGURATOR_TIMEOUT, CONFIGURATOR_OTHER,
        UI_DEVICE_SECURITY, UI_DEVICE_ILLEGAL_STATE, UI_DEVICE_NULL_POINTER, UI_DEVICE_TIMEOUT, UI_DEVICE_OTHER,
        CONFIGURATOR_RESTORE_SECURITY, CONFIGURATOR_RESTORE_ILLEGAL_STATE, CONFIGURATOR_RESTORE_NULL_POINTER,
        CONFIGURATOR_RESTORE_TIMEOUT, CONFIGURATOR_RESTORE_OTHER,
        VERIFY_SECURITY, VERIFY_ILLEGAL_STATE, VERIFY_NULL_POINTER, VERIFY_TIMEOUT, VERIFY_OTHER,
        STRUCTURE_SECURITY, STRUCTURE_ILLEGAL_STATE, STRUCTURE_NULL_POINTER, STRUCTURE_TIMEOUT, STRUCTURE_OTHER,
        STRUCTURE_LOCKED, STRUCTURE_NOTIFICATIONS_UNAVAILABLE, STRUCTURE_EXISTING_NOTIFICATION,
        STRUCTURE_CHANNEL_UNAVAILABLE, STRUCTURE_NOTIFICATION_NOT_OWNED, STRUCTURE_SHADE_OPEN_FAILED,
        STRUCTURE_TITLE_TIMEOUT, STRUCTURE_TITLE_MISSING, STRUCTURE_TITLE_AMBIGUOUS,
        STRUCTURE_NOTIFICATION_REMOVE_FAILED, STRUCTURE_PI_CANCEL_FAILED, STRUCTURE_SHADE_CLOSE_FAILED,
        STRUCTURE_NOTIFICATIONS_CHANGED, STRUCTURE_EVIDENCE_INVALID, STRUCTURE_EVIDENCE_EXISTS,
        STRUCTURE_EVIDENCE_IO, STRUCTURE_EVIDENCE_UNSUPPORTED, STRUCTURE_EVIDENCE_SECURITY,
        STRUCTURE_EVIDENCE_ILLEGAL_STATE, STRUCTURE_EVIDENCE_NULL_POINTER, STRUCTURE_EVIDENCE_TIMEOUT,
        STRUCTURE_EVIDENCE_OTHER,
        IDENTITY_SECURITY, IDENTITY_ILLEGAL_STATE, IDENTITY_NULL_POINTER, IDENTITY_TIMEOUT, IDENTITY_OTHER,
        IDENTITY_TITLE_TIMEOUT, IDENTITY_APP_LABEL_EMPTY, IDENTITY_NOT_OWNED, IDENTITY_ROOT_MISSING,
        IDENTITY_TITLE_NOT_UNIQUE, IDENTITY_ROW_UNRELIABLE, IDENTITY_ROW_TITLE_NOT_UNIQUE,
        IDENTITY_INITIAL_TITLE_WAIT_TIMEOUT, IDENTITY_AFTER_EXPAND_TITLE_WAIT_TIMEOUT,
        IDENTITY_INITIAL_TITLE_MISSING, IDENTITY_INITIAL_TITLE_AMBIGUOUS,
        IDENTITY_REBIND_TITLE_MISSING, IDENTITY_REBIND_TITLE_AMBIGUOUS,
        IDENTITY_AFTER_EXPAND_TITLE_MISSING, IDENTITY_AFTER_EXPAND_TITLE_AMBIGUOUS,
        IDENTITY_WINDOW_LIMIT, IDENTITY_ROOT_LIMIT, IDENTITY_MATCH_LIMIT,
        IDENTITY_EXPAND_AMBIGUOUS, IDENTITY_EXPAND_CHANGED, IDENTITY_EXPAND_REJECTED,
        IDENTITY_EVIDENCE_INVALID, IDENTITY_EVIDENCE_EXISTS, IDENTITY_EVIDENCE_IO,
        IDENTITY_EVIDENCE_UNSUPPORTED, IDENTITY_EVIDENCE_SECURITY, IDENTITY_EVIDENCE_ILLEGAL_STATE,
        IDENTITY_EVIDENCE_NULL_POINTER, IDENTITY_EVIDENCE_TIMEOUT, IDENTITY_EVIDENCE_OTHER,
        EXPANSION_SECURITY, EXPANSION_ILLEGAL_STATE, EXPANSION_NULL_POINTER, EXPANSION_TIMEOUT, EXPANSION_OTHER,
        EXPANSION_OWN_ACTIVITY_LAUNCHED, EXPANSION_LEFT_SYSTEM_UI, EXPANSION_BOUNDS_EMPTY, EXPANSION_POST_TIMEOUT,
        EXPANSION_PHASE_INVALID, EXPANSION_TRUNCATED, EXPANSION_IDENTITY_INVALID,
        EXPANSION_INITIAL_CURRENT_IDENTITY_INVALID, EXPANSION_REBIND_CURRENT_IDENTITY_INVALID,
        EXPANSION_AFTER_EXPAND_IDENTITY_INVALID, EXPANSION_MODAL_PREFIX_IDENTITY_INVALID,
        EXPANSION_MODAL_PREFIX_PHASE_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CHILDREN_EMPTY, EXPANSION_MODAL_PREFIX_ROW_CHILDREN_MULTIPLE,
        EXPANSION_MODAL_PREFIX_EXPANDED_CHILDREN_EMPTY, EXPANSION_MODAL_PREFIX_EXPANDED_CHILDREN_MULTIPLE,
        EXPANSION_MODAL_PREFIX_ROW_EXPANDED_MISSING, EXPANSION_MODAL_PREFIX_ROW_EXPANDED_AMBIGUOUS,
        EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_MISSING, EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_AMBIGUOUS,
        EXPANSION_MODAL_PREFIX_ROW_PACKAGE_INVALID, EXPANSION_MODAL_PREFIX_ROW_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_ROW_SCROLLABLE_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CLASS_INVALID, EXPANSION_MODAL_PREFIX_ROW_CANDIDATE_PRESENT,
        EXPANSION_MODAL_PREFIX_EXPANDED_RESOURCE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_PACKAGE_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_CLASS_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_LONG_CLICKABLE_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_SCROLLABLE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_CANDIDATE_PRESENT,
        EXPANSION_MODAL_PREFIX_CONTENT_RESOURCE_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_PACKAGE_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_CLASS_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_LONG_CLICKABLE_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_SCROLLABLE_INVALID,
        EXPANSION_ACTION_ALREADY_PRESENT, EXPANSION_ACTION_MISSING,
        EXPANSION_ACTION_AMBIGUOUS, EXPANSION_ACTION_UNAVAILABLE, EXPANSION_LONG_PRESS_UNAVAILABLE,
        EXPANSION_INITIAL_BIND_ACTIVE_OWN, EXPANSION_INITIAL_BIND_ACTIVE_OTHER, EXPANSION_INITIAL_BIND_PACKAGE_UNAVAILABLE,
        EXPANSION_REBIND_ACTIVE_OWN, EXPANSION_REBIND_ACTIVE_OTHER, EXPANSION_REBIND_PACKAGE_UNAVAILABLE,
        EXPANSION_PRE_GESTURE_ACTIVE_OWN, EXPANSION_PRE_GESTURE_ACTIVE_OTHER, EXPANSION_PRE_GESTURE_PACKAGE_UNAVAILABLE,
        EXPANSION_AFTER_BIND_ACTIVE_OWN, EXPANSION_AFTER_BIND_ACTIVE_OTHER, EXPANSION_AFTER_BIND_PACKAGE_UNAVAILABLE,
        EXPANSION_AFTER_VALIDATE_ACTIVE_OWN, EXPANSION_AFTER_VALIDATE_ACTIVE_OTHER, EXPANSION_AFTER_VALIDATE_PACKAGE_UNAVAILABLE,
    }

    private companion object {
        const val TARGET = "com.example.agent"
        const val SYSTEM_UI = "com.android.systemui"
        const val STRUCTURE_CHANNEL = "rootpilot_agent"
        const val STRUCTURE_TITLE = "RootPilot · 第 1 步"
        const val STRUCTURE_NOTIFICATION_ID = 0x525053
        const val STRUCTURE_REQUEST_BASE = 0x525100
        val IDLE_STATES = setOf(RootPilotStatus.IDLE, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)
        fun idle(value: RootPilotUiState) = !value.running && value.pendingAction == null && value.status in IDLE_STATES
        fun validId(value: String) = value.length == 36 &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun requireFixed(value: Boolean, reason: Reason) { if (!value) throw DiagnosticFailure(reason) }
        fun classify(stage: Stage, error: Throwable): Reason {
            if (error is DiagnosticFailure) return error.reason
            if (stage == Stage.ROI) return if (error is NotificationRoiEvidence.Failure) Reason.valueOf(error.code.name) else Reason.ROI_CAPTURE
            if (stage == Stage.UI_AUTOMATION) {
                UiAutomationFailure.classify(error)?.let { return Reason.valueOf(it.name) }
            }
            if (stage == Stage.IDENTITY_EVIDENCE) {
                if (error is TimeoutCancellationException) return Reason.IDENTITY_EVIDENCE_TIMEOUT
                return Reason.valueOf(NotificationIdentityEvidenceFile.classify(error).name)
            }
            if (stage == Stage.STRUCTURE_EVIDENCE) {
                if (error is TimeoutCancellationException) return Reason.STRUCTURE_EVIDENCE_TIMEOUT
                return Reason.valueOf(NotificationStructureEvidenceFile.classify(error).name)
            }
            val category = when (error) {
                is SecurityException -> Category.SECURITY
                is TimeoutException, is TimeoutCancellationException -> Category.TIMEOUT
                is IllegalStateException -> Category.ILLEGAL_STATE
                is NullPointerException -> Category.NULL_POINTER
                else -> Category.OTHER
            }
            return Reason.valueOf("${stage.name}_${category.name}")
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** Read raw bytes only: RunHistoryStore.read() would delete existing temporary files. */
        fun directoryFiles(directory: File, include: (String) -> Boolean = { true }): Map<String, String> {
            val path = directory.toPath()
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) { return emptyMap() }
            requireFixed(attributes.isDirectory && !attributes.isSymbolicLink, Reason.BASELINE_UNRESTORABLE)
            val result = sortedMapOf<String, String>()
            Files.newDirectoryStream(path).use { entries ->
                var count = 0
                for (entry in entries) {
                    requireFixed(++count <= 1_024, Reason.BASELINE_UNRESTORABLE)
                    val name = entry.fileName.toString()
                    if (!include(name)) continue
                    val file = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    requireFixed(file.isRegularFile && !file.isSymbolicLink, Reason.BASELINE_UNRESTORABLE)
                    val hash = MessageDigest.getInstance("SHA-256")
                    Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { input ->
                        val buffer = ByteArray(8_192)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            requireFixed(total <= 8L * 1024 * 1024, Reason.BASELINE_UNRESTORABLE)
                            hash.update(buffer, 0, size)
                        }
                    }
                    result[name] = hex(hash.digest())
                }
            }
            return result
        }
    }
}
