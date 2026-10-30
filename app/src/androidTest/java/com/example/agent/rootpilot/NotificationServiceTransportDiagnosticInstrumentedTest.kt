package com.example.agent.rootpilot

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ResultReceiver
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.agent.BuildConfig
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Focus
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Phase
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Sample
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Shade
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.ImeTextInput
import com.example.agent.rootpilot.input.InputConnectionBridge
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.root.RootExecutionResult
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A synthetic notification-to-debug-Service transport experiment, never an approval/input acceptance. */
@RunWith(AndroidJUnit4::class)
class NotificationServiceTransportDiagnosticInstrumentedTest {
    @Test
    fun inspectNotificationServiceTransport() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_diagnostic_opt_in_required", args.getString("notificationServiceTransportDiagnostic") == "true")
        exclusiveMode(args, "notificationServiceTransportDiagnostic")
        val probe = Probe(args.getString("recoveryReceiptId").orEmpty())
        try { probe.run() } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) { probe.cleanup() } }
        probe.report()
    }

    @Test
    fun inspectNotificationInputReadiness() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_readiness_opt_in_required", args.getString("notificationInputReadinessDiagnostic") == "true")
        exclusiveMode(args, "notificationInputReadinessDiagnostic")
        val probe = Probe(args.getString("recoveryReceiptId").orEmpty(), readinessOnly = true)
        try { probe.run() } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) { probe.cleanupReadiness() } }
        probe.reportReadiness()
    }

    @Test
    fun inspectFreshNotificationInputReadiness() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_fresh_readiness_opt_in_required", args.getString("freshNotificationInputReadinessDiagnostic") == "true")
        exclusiveMode(args, "freshNotificationInputReadinessDiagnostic")
        ensure(!args.containsKey("recoveryReceiptId"), Reason.FRESH_ADMISSION)
        val lifecycleDiagnostic = args.getString("freshFixtureLifecycleDiagnostic") == "true"
        ensure(!lifecycleDiagnostic || args.getString("freshHandoffStageDiagnostic") == "true", Reason.FRESH_ADMISSION)
        val arrivalDiagnostic = args.getString("freshFixtureArrivalDiagnostic") == "true"
        ensure(!args.containsKey("freshFixtureArrivalDiagnostic") || arrivalDiagnostic && lifecycleDiagnostic &&
            args.getString("freshHandoffStageDiagnostic") == "true", Reason.FRESH_ADMISSION)
        val probe = Probe(args.getString("retainedFailureReceiptId").orEmpty(), readinessOnly = true,
            freshSessionId = args.getString("freshSessionId").orEmpty(),
            handoffStageDiagnostic = args.getString("freshHandoffStageDiagnostic") == "true",
            lifecycleDiagnostic = lifecycleDiagnostic, arrivalDiagnostic = arrivalDiagnostic)
        try { probe.run() } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) { probe.cleanupReadiness() } }
        probe.reportFreshArrival("UNKNOWN")
        if (args.getString("freshHandoffStageDiagnostic") == "true") probe.reportFreshHandoffStage()
        probe.reportReadiness()
    }

    @Test
    fun inspectIndependentFreshNotificationServiceTransport() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_independent_transport_opt_in_required",
            args.getString(IndependentFreshServiceTransportProtocol.MODE) == "true")
        val probe = Probe(freshSessionId = args.getString("freshSessionId").orEmpty(),
            source = IndependentFreshServiceTransportProtocol.Source.INDEPENDENT_FRESH)
        try {
            IndependentFreshServiceTransportProtocol.admit(bundleArgumentValues(args))
            probe.run()
        } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) {
            try { probe.cleanup() } catch (_: Throwable) { probe.independentCleanupFailed() }
        } }
        probe.reportIndependent()
    }

    @Test
    fun inspectExistingFixtureRootCollapseServiceTransport() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_existing_root_collapse_opt_in_required", args.getString(ExistingFixtureRootCollapseProtocol.MODE) == "true")
        val expected = runCatching { ExistingFixtureRootCollapseProtocol.admit(bundleArgumentValues(args), Process.myPid()) }.getOrNull()
        val probe = Probe(freshSessionId = expected?.sessionId, existingRootMode = true, expectedExisting = expected)
        try { probe.run() } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) {
            try { probe.cleanup() } catch (_: Throwable) { probe.independentCleanupFailed() }
        } }
        probe.reportExistingRootCollapse()
    }

    @Test
    fun frameworkResultReceiversKeepCallbacksAfterParcelRoundTrip() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frameworkLoader = ResultReceiver::class.java.classLoader
        val senderThread = Thread.currentThread()
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        val lifecycleCount = java.util.concurrent.atomic.AtomicInteger()
        val ready = CompletableDeferred<Pair<Int, String?>>()
        val lifecycle = CompletableDeferred<Pair<Int, String?>>()
        val readyOnMain = java.util.concurrent.atomic.AtomicBoolean()
        val lifecycleThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val readyReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                readyCount.incrementAndGet()
                readyOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                ready.complete(resultCode to resultData?.getString("marker"))
            }
        }
        val lifecycleReceiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                lifecycleCount.incrementAndGet()
                lifecycleThread.set(Thread.currentThread())
                lifecycle.complete(resultCode to resultData?.getString("marker"))
            }
        }
        for (receiver in listOf(readyReceiver, lifecycleReceiver)) {
            check(runCatching { Class.forName(receiver.javaClass.name, false, frameworkLoader) }.isFailure)
        }
        val parcel = Parcel.obtain()
        val decoded = try {
            parcel.writeBundle(Bundle().apply {
                putParcelable("freshReadinessReceiver", frameworkResultReceiver(readyReceiver))
                putParcelable("freshLifecycleReceiver", frameworkResultReceiver(lifecycleReceiver))
            })
            parcel.setDataPosition(0)
            val extras = checkNotNull(parcel.readBundle(frameworkLoader))
            check(extras.keySet() == setOf("freshReadinessReceiver", "freshLifecycleReceiver"))
            listOf(checkNotNull(extras.getParcelable("freshReadinessReceiver", ResultReceiver::class.java)),
                checkNotNull(extras.getParcelable("freshLifecycleReceiver", ResultReceiver::class.java)))
        } finally { parcel.recycle() }
        check(decoded.all { it.javaClass == ResultReceiver::class.java })
        decoded[0].send(1, Bundle().apply { putString("marker", "ready") })
        decoded[1].send(2, Bundle().apply { putString("marker", "lifecycle") })
        withTimeout(2_000) {
            check(ready.await() == (1 to "ready"))
            check(lifecycle.await() == (2 to "lifecycle"))
        }
        instrumentation.waitForIdleSync()
        check(readyCount.get() == 1 && lifecycleCount.get() == 1)
        check(readyOnMain.get() && lifecycleThread.get() === senderThread)
    }

    @Suppress("DEPRECATION")
    private fun bundleArgumentValues(args: Bundle): Map<String, Any?> = args.keySet().associateWith { args.get(it) }

    private class Probe(
        private val receiptId: String? = null, private val readinessOnly: Boolean = false, private val freshSessionId: String? = null,
        private val source: IndependentFreshServiceTransportProtocol.Source = IndependentFreshServiceTransportProtocol.Source.RETAINED_FAILURE,
        private val handoffStageDiagnostic: Boolean = false,
        private val lifecycleDiagnostic: Boolean = false,
        private val arrivalDiagnostic: Boolean = false,
        private val existingRootMode: Boolean = false,
        private val expectedExisting: FreshInputReadinessProtocol.Identity? = null,
    ) {
        private val freshMode = freshSessionId != null
        private val independent = source == IndependentFreshServiceTransportProtocol.Source.INDEPENDENT_FRESH
        private val isolated = independent || existingRootMode
        private val collapse = FixedStatusBarCollapse()
        private val receivedSampled = CompletableDeferred<Unit>()
        private var rootWithinDispatchBudget = false
        private var independentPrelaunchEmpty: Boolean? = null
        private var independentBaselineAccepted: Boolean? = null
        private var serviceObserverWasRegistered = false
        private var activityGuardWasRegistered = false
        private var rootServiceSnapshotTaken = false
        private val independentCleanup = IndependentFreshServiceTransportProtocol.cleanupKeys.associateWith { null as Boolean? }.toMutableMap()
        private val freshHandoffStage = java.util.concurrent.atomic.AtomicInteger(-1)
        private val freshHandoffProgressOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        private var freshHandoffAtTerminal = -1
        private var oldFixtureId = ""
        private var freshIdentity: FreshInputReadinessProtocol.Identity? = null
        private var creation = FreshInputReadinessProtocol.Creation("NOT_STARTED", "NONE", null, null)
        private var freshLaunchAttempted = false
        private var freshDispositionReadAttempted = false
        private val readiness = if (readinessOnly) NotificationInputReadinessEvidence() else null
        private var freshArrivalEpoch: String? = null
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val arrivalReporter = FreshFixtureArrivalProtocol.Once { value ->
            instrumentation.sendStatus(0, Bundle().apply { putString("freshFixtureOnCreateArrival", value) })
        }
        private val context = instrumentation.targetContext
        private val app = context.applicationContext as Application
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val ime = AndroidImeEnvironment(context)
        private val store = FileImeRestoreStore(context)
        private val nonce = UUID.randomUUID().toString()
        private val action = "$PACKAGE.SERVICE_TRANSPORT_DIAGNOSTIC.$nonce"
        private val data = Uri.parse("rootpilot-service-transport://probe/$nonce")
        private val title = "Service传输诊断 $nonce"
        private val channel = "rootpilot-service-transport-$nonce"
        private val evidence = NotificationServiceTransportEvidence.Session(nonce, Process.myPid(), Process.myUid())
        private val nextSampled = CompletableDeferred<Unit>()
        private val destroyedSampled = CompletableDeferred<Unit>()
        private var phase = Reason.ADMISSION
        private var reason: Reason? = null
        private val cleanupReasons = mutableListOf<Reason>()
        private var fixtureId = ""
        private var fixturePid = -1
        private lateinit var receipt: File
        private var receiptBefore: Map<String, ByteArray>? = null
        private var rootServiceBefore: ServiceDestroyObservation.Instance? = null
        private var ready: InputConnectionBridge.Editor? = null
        private var baselineWindow: Int? = null
        private var shadeWindow: Int? = null
        private var shadeCalibrated = false
        private var automation: UiAutomation? = null
        private var ui: UiDevice? = null
        private var originalFlags: Int? = null
        private var originalAccessibility: Pair<String?, String?>? = null
        private var originalIme: String? = null
        private var lockHeld = false
        private var imeSaveAttempted = false
        private var pending: PendingIntent? = null
        private var notificationBefore: Set<Pair<String?, Int>>? = null
        private var channelCreated = false
        private var notificationPublished = false
        private var activityCreated = 0
        private var activityResumed = 0
        private var activityGuardRegistered = false
        private var observerRegistered = false
        private var ownedService: NotificationServiceTransportProbeService? = null
        private var serviceDestroyed = false
        private var closing = false
        private var completed = false
        private var actionClicks = 0
        private var expandClicks = 0
        private var sourceTokenless = false
        private var servicePendingIntent = false
        private var measuredBeforeCleanup = false
        private var receiptUnchanged = false
        private var fixtureUnchanged = false
        private var rootServiceUnchanged = false
        private var serviceStopped = false
        private var observerUnregistered = false
        private var activityGuardUnregistered = false
        private var pendingCancelled = false
        private var notificationsRestored = false
        private var imeRestored = false
        private var automationReleased = false
        private var configuratorRestored = false
        private var accessibilityRestored = false

        suspend fun run() {
            ensure(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Build.VERSION.SDK_INT >= 29,
                Reason.DEBUG_API)
            ensure(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE && context.applicationInfo.uid == Process.myUid(), Reason.IDENTITY)
            if (existingRootMode) ensure(expectedExisting != null && receiptId == null && !readinessOnly &&
                !handoffStageDiagnostic && !lifecycleDiagnostic && !arrivalDiagnostic, Reason.EXISTING_ADMISSION)
            else if (independent) ensure(receiptId == null && !readinessOnly && validId(freshSessionId.orEmpty()) &&
                !handoffStageDiagnostic && !lifecycleDiagnostic && !arrivalDiagnostic, Reason.FRESH_ADMISSION)
            else {
                ensure(validId(receiptId.orEmpty()), Reason.RECEIPT_ID)
                if (freshMode) ensure(receiptId == RETAINED_FAILURE_RECEIPT && validId(freshSessionId.orEmpty()) &&
                    freshSessionId != receiptId, Reason.FRESH_ADMISSION)
            }
            rootServiceBefore = ServiceDestroyObservation.current
            rootServiceSnapshotTaken = true
            ensure(rootServiceBefore?.destroyed != false && !RootPilotService.uiState.value.running &&
                RootPilotService.uiState.value.pendingAction == null && NotificationServiceTransportProbe.observer == null, Reason.NOT_IDLE)
            if (existingRootMode) {
                phase = Reason.FIXTURE_BINDING
                val client = FixtureClient(context); client.verifyIdentity()
                rememberFreshIdentity(ExistingFixtureRootCollapseProtocol.state(bundleValues(client.call("state")), expectedExisting!!, Process.myPid()))
            } else if (independent) {
                phase = Reason.FRESH_CREATION
                createFreshFixture()
            } else {
                phase = Reason.RECEIPT
                receipt = context.noBackupFilesDir.resolve("rootpilot-type-process-recovery-test").resolve(receiptId!!)
                receiptBefore = receiptBytes()
                val bytes = receiptBefore!!
                ensure(bytes["invalidated"]?.contentEquals("NOTIFICATION_EXECUTION_FAILED".encodeToByteArray()) == true, Reason.INVALIDATION)
                val baseline = Json.parseToJsonElement(bytes.getValue("baseline.json").decodeToString()) as? JsonObject ?: throw Failure(Reason.BASELINE)
                ensure(baseline.keys == BASELINE_KEYS, Reason.BASELINE)
                fun string(key: String): String = (baseline[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Failure(Reason.BASELINE)
                fun number(key: String): Int = (baseline[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw Failure(Reason.BASELINE)
                if (freshMode) {
                    val retainedFixtureId = string("fixtureInstanceId")
                    val retainedFixturePid = number("fixturePid")
                    ensure(string("id") == receiptId && number("appUid") == Process.myUid() && validId(retainedFixtureId) &&
                        retainedFixturePid > 0 && retainedFixturePid != Process.myPid(), Reason.FIXTURE_BINDING)
                    oldFixtureId = retainedFixtureId
                    ensure(freshSessionId != oldFixtureId, Reason.FRESH_ADMISSION)
                    phase = Reason.FRESH_CREATION
                    createFreshFixture()
                } else {
                    fixtureId = string("fixtureInstanceId"); fixturePid = number("fixturePid")
                    ensure(string("id") == receiptId && number("appUid") == Process.myUid() && validId(fixtureId) && fixturePid > 0 && fixturePid != Process.myPid(), Reason.FIXTURE_BINDING)
                    checkFixture()
                }
            }
            ensure(manager.areNotificationsEnabled() && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                Reason.NOTIFICATION_PERMISSION)
            notificationBefore = manager.activeNotifications.map { it.tag to it.id }.toSet()
            phase = Reason.IME_BASELINE
            ensure(AndroidImeEnvironment.sessionMutex.tryLock(), Reason.IME_BUSY); lockHeld = true
            ensure(store.read() == null && imeRestoreFilesAbsent(), Reason.IME_BASELINE)
            originalIme = ime.currentId()
            ensure(!originalIme.isNullOrBlank() && originalIme != ime.ownId && ime.isEnabled(originalIme!!) && ime.isEnabled(ime.ownId), Reason.IME_BASELINE)
            imeSaveAttempted = true
            ensure(store.save(originalIme!!) && store.read() == originalIme, Reason.IME_SAVE)
            phase = Reason.IME_SELECT
            ensure(ime.select(ime.ownId) is RootExecutionResult.Success && ime.currentId() == ime.ownId, Reason.IME_SELECT)
            phase = Reason.EDITOR_READY
            if (readinessOnly) waitForReadiness() else {
                ready = withTimeout(5_000) { InputConnectionBridge.editor.filterNotNull().first {
                    withContext(Dispatchers.Main.immediate) { validTarget(it) && it.owner.currentInputConnection === it.connection }
                } }
            }
            phase = Reason.UI_INITIALIZE
            originalAccessibility = accessibility()
            val config = Configurator.getInstance()
            originalFlags = config.uiAutomationFlags
            config.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            ensure(config.uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, Reason.UI_INITIALIZE)
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) ?: throw Failure(Reason.UI_INITIALIZE)
            ui = UiDevice.getInstance(instrumentation)
            withContext(Dispatchers.Main.immediate) {
                ensure(NotificationServiceTransportProbe.register(serviceObserver), Reason.OBSERVER); observerRegistered = true
                serviceObserverWasRegistered = true
                readiness?.serviceObserverWasRegistered = true
                app.registerActivityLifecycleCallbacks(activityGuard); activityGuardRegistered = true
                activityGuardWasRegistered = true
                readiness?.activityGuardWasRegistered = true
                sampleMain(Phase.BASELINE)
            }
            val first = evidence.samples.single()
            val accepted = first.sameEditor && first.sameBoundConnection && first.currentConnectionConsistent && first.fixtureEditorValid &&
                first.focus == Focus.FIXTURE && first.fixtureEditorVisible && first.sameFixtureWindow
            if (isolated) independentBaselineAccepted = accepted
            ensure(accepted, Reason.EDITOR_READY)
            if (readinessOnly) { completed = true; return }
            phase = Reason.PUBLISH
            ensure(manager.getNotificationChannel(channel) == null, Reason.PUBLISH)
            channelCreated = true
            manager.createNotificationChannel(NotificationChannel(channel, "Service传输诊断", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            })
            val source = Intent(context, NotificationServiceTransportProbeService::class.java).setAction(action).setData(data).putExtra(MARKER, nonce)
            ensure(source.extras?.keySet() == setOf(MARKER) && !source.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN), Reason.TOKEN_ISOLATION)
            pending = PendingIntent.getService(context, 0, source, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            servicePendingIntent = pending!!.isService && !pending!!.isForegroundService && !pending!!.isActivity
            ensure(servicePendingIntent, Reason.PUBLISH)
            ensure(source.extras?.keySet() == setOf(MARKER) && !source.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN), Reason.TOKEN_ISOLATION)
            sourceTokenless = true
            notificationPublished = true
            manager.notify(nonce, NOTIFICATION_ID, Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText("仅诊断Service传输，不批准或输入")
                .addAction(Notification.Action.Builder(null, BUTTON, pending).build()).setAutoCancel(false).setOnlyAlertOnce(true).build())
            phase = Reason.SHADE
            ensure(ui!!.openNotification(), Reason.SHADE)
            ensure(ui!!.wait(Until.hasObject(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(title)), 5_000), Reason.TITLE)
            withContext(Dispatchers.Main.immediate) { sampleMain(Phase.SHADE_OPENED) }
            ensure(shadeCalibrated, Reason.SHADE_EVIDENCE)
            var row = findRow()
            var buttons = row.findObjects(By.pkg(SYSTEM_UI).text(BUTTON))
            if (buttons.isEmpty()) {
                val expand = row.findObjects(By.res(NotificationIdentityExpand.resourcePattern)).singleOrNull() ?: throw Failure(Reason.EXPAND)
                ensure(expand.applicationPackage == SYSTEM_UI && expand.isEnabled && expand.isClickable, Reason.EXPAND)
                expandClicks++; expand.click()
                ensure(ui!!.wait(object : Condition<UiDevice, Boolean> {
                    override fun apply(container: UiDevice): Boolean = findRow().findObjects(By.pkg(SYSTEM_UI).text(BUTTON)).isNotEmpty()
                }, 5_000), Reason.EXPAND_WAIT)
                row = findRow(); buttons = row.findObjects(By.pkg(SYSTEM_UI).text(BUTTON))
            }
            val button = buttons.singleOrNull() ?: throw Failure(Reason.ACTION)
            ensure(button.isEnabled && button.isClickable && evidence.createdCount == 0 && manager.activeNotifications.singleOrNull {
                it.tag == nonce && it.id == NOTIFICATION_ID
            }?.notification?.actions?.singleOrNull()?.actionIntent == pending, Reason.ACTION)
            withContext(Dispatchers.Main.immediate) { sampleMain(Phase.BEFORE_ACTION) }
            phase = Reason.DISPATCH
            actionClicks++; button.click()
            withTimeout(5_000) {
                if (existingRootMode) {
                    receivedSampled.await()
                    ensure(reason == null && evidence.failure == null, Reason.SERVICE_LIFECYCLE)
                    collapse.run()
                    ensure(collapse.result.outcome == "SUCCESS" && collapse.result.exitCode == 0 && collapse.result.exitConfirmed == true, Reason.ROOT_COLLAPSE)
                }
                nextSampled.await(); destroyedSampled.await()
            }
            if (existingRootMode) rootWithinDispatchBudget = true
            withContext(Dispatchers.Main.immediate) { sampleMain(Phase.FINAL_MEASURED); evidence.freeze(); measuredBeforeCleanup = true }
            ensure(evidence.complete && activityCreated == 0 && activityResumed == 0, Reason.SERVICE_LIFECYCLE)
            completed = true
        }

        private val serviceObserver = object : NotificationServiceTransportProbe.Observer {
            override fun created(service: NotificationServiceTransportProbeService) {
                ownedService = service
                evidence.created(service, Process.myPid(), Process.myUid(), Looper.myLooper() == Looper.getMainLooper())
            }
            override fun received(service: NotificationServiceTransportProbeService, intent: Intent?, startId: Int) {
                val validSource = intent?.component?.className == NotificationServiceTransportProbeService::class.java.name &&
                    intent.component?.packageName == PACKAGE && intent.action == action && intent.data == data &&
                    intent.extras?.keySet() == setOf(MARKER) && !intent.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) && startId > 0
                if (!evidence.received(service, intent?.getStringExtra(MARKER), validSource, Process.myPid(), Process.myUid(),
                        Looper.myLooper() == Looper.getMainLooper()) || closing) return
                captureService { sampleMain(Phase.SERVICE_RECEIVED) }
                if (existingRootMode) receivedSampled.complete(Unit)
                Handler(Looper.getMainLooper()).post {
                    if (!closing && !evidence.frozen) captureService { sampleMain(Phase.NEXT_MAIN_TURN) }
                    nextSampled.complete(Unit)
                }
            }
            override fun destroyed(service: NotificationServiceTransportProbeService) {
                val accepted = evidence.destroyed(service, Process.myPid(), Process.myUid(), Looper.myLooper() == Looper.getMainLooper())
                if (service === ownedService) serviceDestroyed = true
                if (accepted && !closing && !evidence.frozen) captureService { sampleMain(Phase.SERVICE_DESTROYED) }
                destroyedSampled.complete(Unit)
            }
        }
        private fun captureService(block: () -> Unit) { try { block() } catch (error: Throwable) { if (reason == null) reason = (error as? Failure)?.code ?: Reason.SAMPLE } }
        private val activityGuard = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activityCreated++ }
            override fun onActivityResumed(activity: Activity) { activityResumed++ }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }

        private fun validTarget(editor: InputConnectionBridge.Editor): Boolean = editor.info.packageName == FIXTURE && editor.info.fieldId == android.R.id.edit &&
            editor.info.inputType != InputType.TYPE_NULL && !InputConnectionBridge.isPassword(editor.info.inputType)
        private suspend fun waitForReadiness() {
            val state = readiness!!
            try {
                ready = withTimeout(5_000) { InputConnectionBridge.editor.filterNotNull().first {
                    withContext(Dispatchers.Main.immediate) { validTarget(it) && it.owner.currentInputConnection === it.connection }
                } }
                state.wait = NotificationInputReadinessEvidence.Wait.SATISFIED
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                state.wait = NotificationInputReadinessEvidence.Wait.TIMED_OUT
                throw error
            } catch (error: Throwable) {
                state.wait = NotificationInputReadinessEvidence.Wait.ERROR
                throw error
            }
        }

        private fun sampleReadinessBaselineMain() {
            val state = readiness!!
            val detail = state.details
            state.sampleStarted = true
            ensure(Looper.myLooper() == Looper.getMainLooper(), Reason.SAMPLE)
            ensure(Configurator.getInstance().uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, Reason.UI_INITIALIZE)
            val initial = ready ?: throw Failure(Reason.EDITOR_READY)
            val editor = InputConnectionBridge.editor.value
            val windows = automation?.windows ?: throw Failure(Reason.WINDOW_SAMPLE)
            detail.windowsPresent = windows.isNotEmpty()
            ensure(windows.isNotEmpty(), Reason.WINDOW_SAMPLE)
            var focused = Focus.UNKNOWN
            var fixtureVisible = false
            var fixtureWindow: Int? = null
            var visibleShade: Int? = null
            var rootsComplete = true
            val focusedWindows = windows.filter { it.isFocused }
            detail.uniqueFocusedWindow = focusedWindows.size == 1
            windows.forEach { window ->
                val root = window.root
                if (root == null) { rootsComplete = false; detail.rootsComplete = false; return@forEach }
                val pkg = root.packageName?.toString()
                if (window.isFocused && focusedWindows.size == 1) {
                    detail.focusedWindowFixture = pkg == FIXTURE
                    focused = when (pkg) {
                        FIXTURE -> if ((window.type == AccessibilityWindowInfo.TYPE_APPLICATION).also {
                            detail.focusedWindowApplication = it
                        }) Focus.FIXTURE else Focus.OTHER
                        SYSTEM_UI -> Focus.SYSTEM_UI
                        null -> Focus.UNKNOWN
                        else -> Focus.OTHER
                    }
                }
                if (pkg == FIXTURE && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused && focusedWindows.size == 1 &&
                    root.findAccessibilityNodeInfosByViewId("android:id/edit").let { nodes ->
                        detail.nodesReturned(nodes.isEmpty())
                        nodes.any { node ->
                            node.isVisibleToUser.also(detail::visible) && node.isEditable.also(detail::editable) && node.isFocused.also(detail::focused)
                        }.also { detail.nodesCompleted() }
                    }) {
                    fixtureVisible = true; fixtureWindow = window.id
                }
                if (pkg == SYSTEM_UI && root.findAccessibilityNodeInfosByViewId("$SYSTEM_UI:id/notification_stack_scroller").any { it.isVisibleToUser }) {
                    ensure(visibleShade == null, Reason.SHADE_EVIDENCE); visibleShade = window.id
                }
            }
            detail.rootsComplete = rootsComplete
            if (detail.fixtureNodesQueried == null) detail.fixtureNodesQueried = false
            if (focused == Focus.FIXTURE && fixtureVisible) baselineWindow = fixtureWindow
            detail.baselineWindowEstablished = baselineWindow != null
            val shade = when {
                visibleShade != null -> Shade.PRESENT
                shadeCalibrated && rootsComplete && windows.none { it.id == shadeWindow } -> Shade.ABSENT
                else -> Shade.UNKNOWN
            }
            val sample = Sample(Phase.BASELINE, true, editor != null, editor === initial, initial.owner.currentInputConnection === initial.connection,
                editor != null && editor.owner.currentInputConnection === editor.connection, editor != null && validTarget(editor),
                focused, fixtureVisible, baselineWindow != null && fixtureWindow == baselineWindow, shade)
            state.sample = sample
            ensure(evidence.record(sample), Reason.PHASE_ORDER)
        }

        private fun sampleMain(at: Phase) {
            if (readinessOnly && at == Phase.BASELINE) { sampleReadinessBaselineMain(); return }
            ensure(Looper.myLooper() == Looper.getMainLooper(), Reason.SAMPLE)
            ensure(Configurator.getInstance().uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, Reason.UI_INITIALIZE)
            val initial = ready ?: throw Failure(Reason.EDITOR_READY)
            val editor = InputConnectionBridge.editor.value
            val windows = automation?.windows ?: throw Failure(Reason.WINDOW_SAMPLE)
            ensure(windows.isNotEmpty(), Reason.WINDOW_SAMPLE)
            var focused = Focus.UNKNOWN
            var fixtureVisible = false
            var fixtureWindow: Int? = null
            var visibleShade: Int? = null
            var rootsComplete = true
            val focusedWindows = windows.filter { it.isFocused }
            windows.forEach { window ->
                val root = window.root
                if (root == null) { rootsComplete = false; return@forEach }
                val pkg = root.packageName?.toString()
                if (window.isFocused && focusedWindows.size == 1) focused = when (pkg) {
                    FIXTURE -> if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) Focus.FIXTURE else Focus.OTHER
                    SYSTEM_UI -> Focus.SYSTEM_UI
                    null -> Focus.UNKNOWN
                    else -> Focus.OTHER
                }
                if (pkg == FIXTURE && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused && focusedWindows.size == 1 &&
                    root.findAccessibilityNodeInfosByViewId("android:id/edit")
                        .any { it.isVisibleToUser && it.isEditable && it.isFocused }) {
                    fixtureVisible = true; fixtureWindow = window.id
                }
                if (pkg == SYSTEM_UI && root.findAccessibilityNodeInfosByViewId("$SYSTEM_UI:id/notification_stack_scroller").any { it.isVisibleToUser }) {
                    ensure(visibleShade == null, Reason.SHADE_EVIDENCE); visibleShade = window.id
                }
            }
            if (at == Phase.BASELINE && focused == Focus.FIXTURE && fixtureVisible) baselineWindow = fixtureWindow
            if (at == Phase.SHADE_OPENED && visibleShade != null) { shadeWindow = visibleShade; shadeCalibrated = true }
            val shade = when {
                visibleShade != null -> Shade.PRESENT
                shadeCalibrated && rootsComplete && windows.none { it.id == shadeWindow } -> Shade.ABSENT
                else -> Shade.UNKNOWN
            }
            ensure(evidence.record(Sample(at, true, editor != null, editor === initial, initial.owner.currentInputConnection === initial.connection,
                editor != null && editor.owner.currentInputConnection === editor.connection, editor != null && validTarget(editor),
                focused, fixtureVisible, baselineWindow != null && fixtureWindow == baselineWindow, shade)), Reason.PHASE_ORDER)
        }

        private fun findRow(): UiObject2 {
            val titleNode = ui!!.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(title)).singleOrNull()
                ?: throw Failure(Reason.TITLE)
            var current: UiObject2? = titleNode; var child: UiObject2? = null
            repeat(12) {
                val node = current ?: throw Failure(Reason.ROW)
                val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = node.resourceName))
                val parent = if (named) null else node.parent
                if (named || NotificationRowStructure.isRow(NotificationRowStructure.Node(node.resourceName, node.applicationPackage, node.className,
                        node.isEnabled, node.isClickable, node.isScrollable), child?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage) },
                        parent?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage, it.className) })) {
                    ensure(node.applicationPackage == SYSTEM_UI && node.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern)
                        .text(title)).singleOrNull() == titleNode, Reason.ROW)
                    return node
                }
                child = node; current = parent
            }
            throw Failure(Reason.ROW)
        }
        @Suppress("DEPRECATION")
        private fun bundleValues(bundle: Bundle): Map<String, Any?> = bundle.keySet().associateWith { bundle.get(it) }

        private fun rememberFreshIdentity(identity: FreshInputReadinessProtocol.Identity) {
            freshIdentity = identity
            fixtureId = identity.instanceId
            fixturePid = identity.pid
        }

        private suspend fun createFreshFixture() {
            val handoff = FreshInputReadinessProtocol.Handoff()
            val result = CompletableDeferred<FreshInputReadinessProtocol.Creation>()
            val client = FixtureClient(context)
            try {
                client.verifyIdentity()
                val component = ComponentName(FIXTURE, "$FIXTURE.ExecutionFixtureActivity")
                @Suppress("DEPRECATION")
                val activity = context.packageManager.getActivityInfo(component, 0)
                ensure(activity.packageName == FIXTURE && activity.name == component.className && activity.exported &&
                    activity.permission == "$FIXTURE.ACCESS", Reason.FRESH_ADMISSION)
                val prelaunchState = if (arrivalDiagnostic) {
                    val envelope = client.freshState(freshSessionId!!)
                    freshArrivalEpoch = FreshFixtureArrivalProtocol.baseline { freshArrivalValues(envelope) }
                    freshOriginalState(envelope)
                } else client.call("state")
                val prelaunchEmpty = prelaunchState.isEmpty && client.call("virtual_state").isEmpty
                if (independent) independentPrelaunchEmpty = prelaunchEmpty
                ensure(prelaunchEmpty, Reason.FRESH_ADMISSION)
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        recordFreshHandoffProgress(3, "CALLBACK_RECEIVED")
                        val observed = handoff.offer {
                            val values = resultData?.let { bundleValues(it) } ?: emptyMap()
                            if (independent) FreshInputReadinessProtocol.independentCallback(resultCode, values, freshSessionId!!, Process.myPid())
                            else FreshInputReadinessProtocol.callback(resultCode, values, freshSessionId!!, oldFixtureId, Process.myPid())
                        }
                        if (observed != null) result.complete(observed)
                    }
                }
                // The diagnostic receiver is independent of the one-shot readiness Handoff.
                // A null Handler delivers on a Binder thread even if the target main thread stalls.
                val lifecycleReceiver = if (lifecycleDiagnostic) object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (!freshHandoffProgressOpen.get()) return
                        try {
                            val values = resultData?.let { bundleValues(it) } ?: return
                            val event = FreshInputReadinessProtocol.lifecycle(resultCode, values, freshSessionId!!)
                            if (!freshHandoffProgressOpen.get()) return
                            instrumentation.sendStatus(0, Bundle().apply {
                                putString("freshFixtureLifecycle", event)
                            })
                        } catch (_: Exception) {
                            // Evidence is observational and cannot decide readiness.
                        }
                    }
                } else null
                recordFreshHandoffProgress(0, "MAIN_NOT_ENTERED")
                creation = withTimeout(2_000) {
                    withContext(Dispatchers.Main.immediate) {
                        recordFreshHandoffProgress(1, "MAIN_ENTERED")
                        freshLaunchAttempted = true
                        val intent = Intent().setComponent(component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            .putExtra("freshSessionId", freshSessionId).putExtra("freshReadinessReceiver", frameworkResultReceiver(receiver))
                        if (lifecycleReceiver != null) {
                            intent.putExtra("freshLifecycleDiagnostic", true)
                            intent.putExtra("freshLifecycleReceiver", frameworkResultReceiver(lifecycleReceiver))
                        }
                        if (arrivalDiagnostic) intent.putExtra("freshArrivalDiagnostic", true)
                        context.startActivity(intent)
                        recordFreshHandoffProgress(2, "START_RETURNED")
                    }
                    result.await()
                }
                ensure(creation.outcome == "READY", Reason.FRESH_CREATION)
                rememberFreshIdentity(creation.identity ?: throw Failure(Reason.FRESH_CREATION))
                // A lifecycle signal alone never admits the IME/UI stages.
                checkFixture()
            } catch (_: TimeoutCancellationException) {
                creation = FreshInputReadinessProtocol.Creation("TIMED_OUT", "TIMED_OUT", null, null)
                throw Failure(Reason.FRESH_CREATION)
            } catch (error: Throwable) {
                if (creation.outcome == "NOT_STARTED") creation = FreshInputReadinessProtocol.Creation("ERROR",
                    if (freshLaunchAttempted) "START_ERROR" else "PROTOCOL", null, null)
                throw error
            } finally {
                freshHandoffProgressOpen.set(false)
                if (handoffStageDiagnostic) freshHandoffAtTerminal = freshHandoffStage.get()
                handoff.close()
            }
        }

        private fun recordFreshHandoffProgress(stage: Int, label: String) {
            if (!handoffStageDiagnostic || !freshHandoffProgressOpen.get()) return
            val previous = freshHandoffStage.getAndUpdate { current -> maxOf(current, stage) }
            if (stage <= previous || !freshHandoffProgressOpen.get()) return
            try {
                instrumentation.sendStatus(0, Bundle().apply { putString("freshHandoffProgress", label) })
            } catch (_: Exception) {
                // Progress is observational; a reporting failure must not change the handoff result.
            }
        }

        fun reportFreshHandoffStage() {
            val stage = when (freshHandoffAtTerminal) {
                -1 -> "NOT_STARTED"
                0 -> "MAIN_NOT_ENTERED"
                1 -> "MAIN_ENTERED"
                2 -> "START_RETURNED"
                3 -> "CALLBACK_RECEIVED"
                else -> throw AssertionError(Reason.REPORT_FAILED.name)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("freshHandoffStage", stage) })
        }

        fun reportFreshArrival(value: String) {
            if (arrivalDiagnostic) arrivalReporter.report(value)
        }

        @Suppress("DEPRECATION")
        private fun freshArrivalValues(envelope: Bundle): Map<String, Any?> {
            require(envelope.keySet() == setOf("state", "processEpoch", "arrival"))
            val arrival = envelope.get("arrival") as? Bundle ?: error("arrival_shape")
            return mapOf("processEpoch" to envelope.get("processEpoch"), "arrival" to bundleValues(arrival))
        }

        @Suppress("DEPRECATION")
        private fun freshOriginalState(envelope: Bundle): Bundle =
            envelope.get("state") as? Bundle ?: throw Failure(Reason.FIXTURE_STATE)

        private suspend fun verifyFreshDisposition(): Boolean {
            // After a failed handoff this single read classifies retention only; it cannot resume run().
            try {
                if (freshIdentity == null) {
                    if (!freshLaunchAttempted || freshDispositionReadAttempted) return false
                    freshDispositionReadAttempted = true
                    val client = FixtureClient(context); client.verifyIdentity()
                    val state = if (arrivalDiagnostic) {
                        val envelope = client.freshState(freshSessionId!!)
                        FreshFixtureArrivalProtocol.observeThenRead(
                            { FreshFixtureArrivalProtocol.classify(freshArrivalEpoch, freshArrivalValues(envelope),
                                freshSessionId, oldFixtureId) },
                            ::reportFreshArrival, { freshOriginalState(envelope) })
                    } else client.call("state")
                    rememberFreshIdentity(freshStateIdentity(state))
                } else checkFixture()
                return true
            } finally {
                reportFreshArrival("UNKNOWN")
            }
        }

        private fun freshStateIdentity(state: Bundle): FreshInputReadinessProtocol.Identity =
            if (existingRootMode) ExistingFixtureRootCollapseProtocol.state(bundleValues(state), expectedExisting!!, Process.myPid())
            else if (independent) FreshInputReadinessProtocol.independentState(bundleValues(state), freshSessionId!!, Process.myPid())
            else FreshInputReadinessProtocol.state(bundleValues(state), freshSessionId!!, oldFixtureId, Process.myPid())

        private suspend fun checkFixture() {
            val client = FixtureClient(context); client.verifyIdentity(); val state = client.call("state")
            if (freshMode) {
                ensure(freshIdentity != null && freshStateIdentity(state) == freshIdentity, Reason.FIXTURE_STATE)
                return
            }
            @Suppress("DEPRECATION")
            ensure(state.get("instanceId") is String && state.get("processId") is Int && state.get("fieldId") is Int && state.get("ready") is Boolean &&
                state.get("empty") is Boolean && state.get("matches") is Boolean && state.get("commitCalls") is Int, Reason.FIXTURE_STATE)
            ensure(state.getString("instanceId") == fixtureId && state.getInt("processId") == fixturePid && state.getInt("fieldId") == android.R.id.edit &&
                state.getBoolean("ready") && state.getBoolean("empty") && !state.getBoolean("matches") && state.getInt("commitCalls") == 0, Reason.FIXTURE_STATE)
        }
        private fun receiptBytes(): Map<String, ByteArray> {
            listOf(context.noBackupFilesDir, receipt.parentFile!!, receipt).forEach {
                ensure(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it.toPath()), Reason.RECEIPT)
            }
            val files = receipt.listFiles() ?: throw Failure(Reason.RECEIPT)
            ensure(files.isNotEmpty() && files.map { it.name }.toSet().all { it in RECEIPT_FILES }, Reason.RECEIPT)
            return files.associate { file ->
                ensure(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT)
                val bytes = Channels.newInputStream(Files.newByteChannel(file.toPath(), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
                    .use { it.readNBytes(64 * 1024 + 1) }
                ensure(bytes.size <= 64 * 1024, Reason.RECEIPT); file.name to bytes
            }
        }
        private fun accessibility() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) to
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        private fun imeRestoreFilesAbsent() = listOf("rootpilot_original_ime", "rootpilot_original_ime.bak", "rootpilot_original_ime.new")
            .none { Files.exists(context.noBackupFilesDir.resolve(it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        fun fail(error: Throwable) { if (reason == null) reason = (error as? Failure)?.code ?: phase }
        private suspend fun verify(code: Reason, block: suspend () -> Boolean): Boolean {
            val field = if (isolated) when (code) {
                Reason.FIXTURE_CHANGED -> "fixtureBindingConfirmed"
                Reason.ROOT_SERVICE_CHANGED -> "rootServiceUnchanged"
                Reason.SERVICE_STOP -> "probeServiceStopped"
                Reason.OBSERVER_CLEANUP -> "serviceObserverUnregistered"
                Reason.ACTIVITY_GUARD_CLEANUP -> "activityGuardUnregistered"
                Reason.PENDING_INTENT_CANCEL -> "pendingIntentCancelled"
                Reason.NOTIFICATION_CLEANUP -> "notificationsRestored"
                Reason.IME_RESTORE -> "imeRestored"
                Reason.UI_RELEASE -> "automationReleased"
                Reason.FLAGS_RESTORE -> "configuratorRestored"
                Reason.ACCESSIBILITY_RESTORE -> "accessibilityRestored"
                else -> null
            } else null
            return try {
                block().also { value ->
                    if (field != null) independentCleanup[field] = value
                    if (!value) cleanupReasons += code
                }
            } catch (_: Throwable) {
                if (field != null) independentCleanup[field] = null
                cleanupReasons += code
                false
            }
        }

        fun independentCleanupFailed() { cleanupReasons += Reason.CLEANUP_INCOMPLETE }

        suspend fun cleanup() {
            // Freeze all decision evidence before cancelling PI, stopping Service, or touching UI.
            withContext(Dispatchers.Main.immediate) { closing = true; evidence.close() }
            pendingCancelled = verify(Reason.PENDING_INTENT_CANCEL) { pending?.cancel(); true }
            verify(Reason.NOTIFICATION_CANCEL) { if (notificationPublished) manager.cancel(nonce, NOTIFICATION_ID); true }
            serviceStopped = verify(Reason.SERVICE_STOP) {
                val awaitDestruction = withContext(Dispatchers.Main.immediate) {
                    when (NotificationServiceTransportEvidence.serviceCleanup(
                        observerRegistered, NotificationServiceTransportProbe.observer === serviceObserver,
                        ownedService != null, evidence.dispatchCount > 0, actionClicks > 0, serviceDestroyed,
                    )) {
                        NotificationServiceTransportEvidence.ServiceCleanup.UNOWNED -> throw Failure(Reason.SERVICE_STOP)
                        NotificationServiceTransportEvidence.ServiceCleanup.NOT_STARTED,
                        NotificationServiceTransportEvidence.ServiceCleanup.DESTROYED -> false
                        NotificationServiceTransportEvidence.ServiceCleanup.STOP_AND_AWAIT -> {
                            // A click can already be in flight before onCreate supplies the owned instance.
                            context.stopService(Intent(context, NotificationServiceTransportProbeService::class.java))
                            true
                        }
                    }
                }
                if (awaitDestruction) {
                    withTimeout(5_000) { destroyedSampled.await() }
                    withContext(Dispatchers.Main.immediate) {
                        observerRegistered && NotificationServiceTransportProbe.observer === serviceObserver &&
                            ownedService != null && serviceDestroyed
                    }
                } else true
            }
            observerUnregistered = verify(Reason.OBSERVER_CLEANUP) { withContext(Dispatchers.Main.immediate) {
                if (observerRegistered) { ensure(NotificationServiceTransportProbe.unregister(serviceObserver), Reason.OBSERVER_CLEANUP); observerRegistered = false }
                NotificationServiceTransportProbe.observer == null
            } }
            activityGuardUnregistered = verify(Reason.ACTIVITY_GUARD_CLEANUP) { withContext(Dispatchers.Main.immediate) {
                if (activityGuardRegistered) { app.unregisterActivityLifecycleCallbacks(activityGuard); activityGuardRegistered = false }; true
            } }
            if (!existingRootMode) verify(Reason.SHADE_CLEANUP) { ui?.let { if (it.currentPackageName == SYSTEM_UI) it.pressBack() else true } ?: true }
            verify(Reason.CHANNEL_CLEANUP) { if (channelCreated) manager.deleteNotificationChannel(channel); true }
            notificationsRestored = verify(Reason.NOTIFICATION_CLEANUP) {
                (notificationBefore?.let { manager.activeNotifications.map { it.tag to it.id }.toSet() == it } ?: true) &&
                    (!channelCreated || manager.getNotificationChannel(channel) == null)
            }
            automationReleased = verify(Reason.UI_RELEASE) { automation == null || instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) != null }
            configuratorRestored = verify(Reason.FLAGS_RESTORE) { originalFlags?.let { flags -> Configurator.getInstance().let {
                it.uiAutomationFlags = flags; it.uiAutomationFlags == flags
            } } ?: true }
            accessibilityRestored = verify(Reason.ACCESSIBILITY_RESTORE) { originalAccessibility?.let { accessibility() == it } ?: true }
            imeRestored = verify(Reason.IME_RESTORE) {
                if (imeSaveAttempted) ensure(ImeTextInput(ime, store).recover() is RootExecutionResult.Success, Reason.IME_RESTORE)
                originalIme?.let { ime.currentId() == it && store.read() == null && imeRestoreFilesAbsent() } ?: true
            }
            if (lockHeld) { AndroidImeEnvironment.sessionMutex.unlock(); lockHeld = false }
            fixtureUnchanged = verify(Reason.FIXTURE_CHANGED) {
                if (existingRootMode) { ensure(freshIdentity != null, Reason.FIXTURE_CHANGED); checkFixture(); true }
                else if (independent) verifyFreshDisposition()
                else { if (fixtureId.isNotEmpty()) checkFixture(); fixtureId.isNotEmpty() }
            }
            if (!isolated) receiptUnchanged = verify(Reason.RECEIPT_CHANGED) { receiptBefore?.let { before -> val after = receiptBytes()
                before.keys == after.keys && before.all { (name, bytes) -> bytes.contentEquals(after.getValue(name)) }
            } ?: false }
            rootServiceUnchanged = verify(Reason.ROOT_SERVICE_CHANGED) {
                if (isolated) ensure(rootServiceSnapshotTaken, Reason.ROOT_SERVICE_CHANGED)
                ServiceDestroyObservation.current === rootServiceBefore &&
                    !RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null
            }
        }

        suspend fun cleanupReadiness() {
            val state = readiness!!
            suspend fun closeOwnedObservers() {
                closing = true; evidence.close()
                serviceStopped = verify(Reason.SERVICE_STOP) {
                    state.serviceCleanup = NotificationInputReadinessEvidence.serviceCleanup(
                        observerRegistered, observerRegistered && NotificationServiceTransportProbe.observer === serviceObserver,
                        ownedService != null || evidence.createdCount > 0 || evidence.destroyedCount > 0,
                        evidence.dispatchCount > 0, actionClicks > 0 || pending != null,
                    )
                    state.serviceCleanup == NotificationInputReadinessEvidence.ServiceCleanup.NOT_STARTED
                }
                var serviceUnregisteredOwned = false
                observerUnregistered = verify(Reason.OBSERVER_CLEANUP) {
                    if (observerRegistered) {
                        ensure(NotificationServiceTransportProbe.observer === serviceObserver &&
                            NotificationServiceTransportProbe.unregister(serviceObserver), Reason.OBSERVER_CLEANUP)
                        observerRegistered = false; serviceUnregisteredOwned = true
                    }
                    true
                }
                state.serviceObserverCleanup = NotificationInputReadinessEvidence.observerCleanup(
                    state.serviceObserverWasRegistered, serviceUnregisteredOwned,
                )
                var guardUnregisteredOwned = false
                activityGuardUnregistered = verify(Reason.ACTIVITY_GUARD_CLEANUP) {
                    if (activityGuardRegistered) {
                        app.unregisterActivityLifecycleCallbacks(activityGuard)
                        activityGuardRegistered = false; guardUnregisteredOwned = true
                    }
                    true
                }
                state.activityGuardCleanup = NotificationInputReadinessEvidence.observerCleanup(
                    state.activityGuardWasRegistered, guardUnregisteredOwned,
                )
            }
            // Before registration this session was never shared with Main or a Service.
            // Do not wait for Main, stop a component, or inspect/unregister another run's observer.
            if (observerRegistered || activityGuardRegistered) withContext(Dispatchers.Main.immediate) { closeOwnedObservers() }
            else closeOwnedObservers()
            pendingCancelled = verify(Reason.PENDING_INTENT_CANCEL) { pending?.cancel(); true }
            verify(Reason.NOTIFICATION_CANCEL) { if (notificationPublished) manager.cancel(nonce, NOTIFICATION_ID); true }
            verify(Reason.CHANNEL_CLEANUP) { if (channelCreated) manager.deleteNotificationChannel(channel); true }
            notificationsRestored = verify(Reason.NOTIFICATION_CLEANUP) {
                (notificationBefore?.let { manager.activeNotifications.map { it.tag to it.id }.toSet() == it } ?: true) &&
                    (!channelCreated || manager.getNotificationChannel(channel) == null)
            }
            automationReleased = verify(Reason.UI_RELEASE) { automation == null || instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) != null }
            configuratorRestored = verify(Reason.FLAGS_RESTORE) { originalFlags?.let { flags -> Configurator.getInstance().let {
                it.uiAutomationFlags = flags; it.uiAutomationFlags == flags
            } } ?: true }
            accessibilityRestored = verify(Reason.ACCESSIBILITY_RESTORE) { originalAccessibility?.let { accessibility() == it } ?: true }
            imeRestored = verify(Reason.IME_RESTORE) {
                if (imeSaveAttempted) ensure(ImeTextInput(ime, store).recover() is RootExecutionResult.Success, Reason.IME_RESTORE)
                originalIme?.let { ime.currentId() == it && store.read() == null && imeRestoreFilesAbsent() } ?: true
            }
            if (lockHeld) { AndroidImeEnvironment.sessionMutex.unlock(); lockHeld = false }
            fixtureUnchanged = verify(Reason.FIXTURE_CHANGED) {
                if (freshMode) verifyFreshDisposition()
                else { if (fixtureId.isNotEmpty()) checkFixture(); fixtureId.isNotEmpty() }
            }
            receiptUnchanged = verify(Reason.RECEIPT_CHANGED) { receiptBefore?.let { before -> val after = receiptBytes()
                before.keys == after.keys && before.all { (name, bytes) -> bytes.contentEquals(after.getValue(name)) }
            } ?: false }
            rootServiceUnchanged = verify(Reason.ROOT_SERVICE_CHANGED) { ServiceDestroyObservation.current === rootServiceBefore &&
                !RootPilotService.uiState.value.running && RootPilotService.uiState.value.pendingAction == null }
        }

        fun reportReadiness() {
            val state = readiness!!
            val serviceEvents = evidence.createdCount + evidence.dispatchCount + evidence.destroyedCount
            val activityEvents = activityCreated + activityResumed
            val forbiddenEffect = channelCreated || pending != null || notificationPublished || actionClicks != 0 || expandClicks != 0
            val sourceReason = reason?.name ?: if (serviceEvents != 0 || activityEvents != 0 || evidence.failure != null || forbiddenEffect)
                Reason.SERVICE_LIFECYCLE.name else "NONE"
            // Endpoint ownership covers only resources this probe acquired, never runner exit.
            val ownedResourcesRestored = imeRestored && automationReleased && configuratorRestored && accessibilityRestored &&
                state.serviceObserverCleanup != NotificationInputReadinessEvidence.ObserverCleanup.UNSAFE &&
                state.activityGuardCleanup != NotificationInputReadinessEvidence.ObserverCleanup.UNSAFE
            val passed = state.passed(completed, sourceReason, cleanupReasons.isNotEmpty(), evidence.closed,
                forbiddenEffect, serviceEvents, activityEvents) && (!freshMode || (creation.outcome == "READY" &&
                freshIdentity != null && fixtureUnchanged && receiptUnchanged && rootServiceUnchanged && ownedResourcesRestored))
            val readinessReason = state.reason(sourceReason, phase == Reason.UI_INITIALIZE, cleanupReasons.isNotEmpty()).let {
                if (!passed && it == NotificationInputReadinessEvidence.Reason.NONE) NotificationInputReadinessEvidence.Reason.BASELINE_SAMPLE_ERROR else it
            }
            val sample = state.sample
            fun boolean(value: Boolean?) = value?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
            fun count(registered: Boolean, safe: Boolean, value: Int) =
                NotificationInputReadinessEvidence.observedCount(registered, safe, value)?.toString() ?: "null"
            val serviceCountsSafe = state.serviceObserverCleanup == NotificationInputReadinessEvidence.ObserverCleanup.UNREGISTERED_OWNED &&
                state.serviceCleanup == NotificationInputReadinessEvidence.ServiceCleanup.NOT_STARTED
            val activityCountsSafe = state.activityGuardCleanup == NotificationInputReadinessEvidence.ObserverCleanup.UNREGISTERED_OWNED
            try { instrumentation.sendStatus(0, Bundle().apply {
                if (freshMode) {
                    putInt("schemaVersion", 3); putString("diagnosticKind", "FRESH_INPUT_READINESS_BASELINE")
                    putString("freshInputReadinessStage", if (passed) "observed" else "failed")
                    putBoolean("endpointReadinessPassed", passed)
                    putString("freshSessionId", freshSessionId)
                    putString("retainedFailureProtection", buildJsonObject {
                        put("receiptId", receiptId); put("unchanged", receiptUnchanged)
                    }.toString())
                    putString("freshFixtureIdentity", freshIdentity?.let { identity -> buildJsonObject {
                        put("freshSessionId", identity.sessionId); put("fixtureInstanceId", identity.instanceId)
                        put("fixturePid", identity.pid); put("fixtureTaskId", identity.taskId)
                        put("fixtureDisplayId", identity.displayId); put("fixtureProcessStartTicks", identity.processStartTicks)
                    } }?.toString() ?: "null")
                    putString("creationOutcome", creation.outcome); putString("creationReason", creation.reason)
                    putString("rejectedAdmissionSelfFinishAttempted", boolean(creation.selfFinish).toString())
                    putBoolean("finalFixtureBindingConfirmed", fixtureUnchanged)
                    putString("fixtureLeftOpen", if (fixtureUnchanged) "true" else "null")
                    putBoolean("fixtureClosureAttempted", false)
                    putBoolean("ownedResourcesRestored", ownedResourcesRestored)
                    putBoolean("businessApprovalConsumed", false)
                } else {
                    putInt("schemaVersion", 2); putString("diagnosticKind", "INPUT_READINESS_BASELINE")
                    putString("inputReadinessStage", if (passed) "verified" else "failed")
                    putBoolean("diagnosticPassed", passed); putString("recoveryReceiptId", if (validId(receiptId.orEmpty())) receiptId else "")
                    putInt("fixturePid", fixturePid)
                }
                putString("readinessReasonCode", readinessReason.name); putString("sourceReasonCode", sourceReason)
                putString("cleanupReasonCodes", buildJsonArray { cleanupReasons.forEach { add(JsonPrimitive(it.name)) } }.toString())
                putInt("mainPid", Process.myPid())
                putString("waitOutcome", state.wait.name); putString("baselineOutcome", state.baseline(sourceReason).name)
                putBoolean("baselineSampleObserved", sample != null)
                putString("originalSample", buildJsonObject {
                    put("mainThread", boolean(sample?.mainThread)); put("editorPresent", boolean(sample?.editorPresent))
                    put("sameEditor", boolean(sample?.sameEditor)); put("sameBoundConnection", boolean(sample?.sameBoundConnection))
                    put("currentConnectionConsistent", boolean(sample?.currentConnectionConsistent)); put("fixtureEditorValid", boolean(sample?.fixtureEditorValid))
                    put("fixtureEditorVisible", boolean(sample?.fixtureEditorVisible)); put("sameFixtureWindow", boolean(sample?.sameFixtureWindow))
                    put("focus", sample?.focus?.name?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("shade", sample?.shade?.name?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                }.toString())
                putString("baselineDetails", buildJsonObject { state.details.values().forEach { (key, value) -> put(key, boolean(value)) } }.toString())
                putString("baselineFailureFields", buildJsonArray { state.failureFields.forEach { add(JsonPrimitive(it)) } }.toString())
                putBoolean("observationMayAffectTiming", true)
                putBoolean("serviceObserverWasRegistered", state.serviceObserverWasRegistered)
                putBoolean("activityGuardWasRegistered", state.activityGuardWasRegistered)
                putString("probeServiceCleanup", state.serviceCleanup.name)
                putString("serviceObserverCleanup", state.serviceObserverCleanup.name)
                putString("activityGuardCleanup", state.activityGuardCleanup.name)
                putBoolean("notificationChannelCreated", channelCreated); putBoolean("pendingIntentCreated", pending != null)
                putBoolean("notificationPublished", notificationPublished); putBoolean("approvalAcceptance", false); putBoolean("inputAttempted", false)
                putInt("actionClicks", actionClicks); putInt("expandClicks", expandClicks)
                // String JSON scalars distinguish an unobserved lifecycle (null) from a measured zero.
                putString("serviceCreated", count(state.serviceObserverWasRegistered, serviceCountsSafe, evidence.createdCount))
                putString("serviceDispatches", count(state.serviceObserverWasRegistered, serviceCountsSafe, evidence.dispatchCount))
                putString("serviceDestroyed", count(state.serviceObserverWasRegistered, serviceCountsSafe, evidence.destroyedCount))
                putString("activityCreated", count(state.activityGuardWasRegistered, activityCountsSafe, activityCreated))
                putString("activityResumed", count(state.activityGuardWasRegistered, activityCountsSafe, activityResumed))
                putBoolean("fixtureUnchanged", fixtureUnchanged); putBoolean("receiptUnchanged", receiptUnchanged)
                putBoolean("rootServiceUnchanged", rootServiceUnchanged); putBoolean("pendingIntentCancelled", pendingCancelled)
                putBoolean("notificationsRestored", notificationsRestored); putBoolean("imeRestored", imeRestored)
                putBoolean("automationReleased", automationReleased); putBoolean("configuratorRestored", configuratorRestored)
                putBoolean("accessibilityRestored", accessibilityRestored)
            }) } catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
            if (!passed) throw AssertionError(readinessReason.name)
        }

        fun reportExistingRootCollapse() {
            val observation = ExistingFixtureRootCollapseProtocol.Observation(
                expectedExisting, freshIdentity, nonce, Process.myPid(), independentBaselineAccepted, completed,
                reason?.name ?: if (evidence.failure != null) Reason.SERVICE_LIFECYCLE.name else "NONE",
                cleanupReasons.map { it.name }, independentCleanup.toMap(),
                NotificationInputReadinessEvidence.observedCount(activityGuardWasRegistered, activityGuardUnregistered, activityCreated),
                NotificationInputReadinessEvidence.observedCount(activityGuardWasRegistered, activityGuardUnregistered, activityResumed),
                serviceObserverWasRegistered, actionClicks, expandClicks, sourceTokenless, servicePendingIntent, measuredBeforeCleanup,
                collapse.result, rootWithinDispatchBudget, evidence,
            )
            try { instrumentation.sendStatus(0, Bundle().apply {
                putString(ExistingFixtureRootCollapseProtocol.REPORT, observation.report().toString())
            }) } catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
            if (!observation.passed()) throw AssertionError((reason ?: cleanupReasons.firstOrNull() ?: Reason.ROOT_COLLAPSE).name)
        }

        fun reportIndependent() {
            val observation = IndependentFreshServiceTransportProtocol.Observation(
                freshSessionId, nonce, Process.myPid(), creation, freshIdentity, independentPrelaunchEmpty, independentBaselineAccepted,
                completed, reason?.name ?: if (evidence.failure != null) Reason.SERVICE_LIFECYCLE.name else "NONE",
                cleanupReasons.map { it.name }, independentCleanup.toMap(),
                NotificationInputReadinessEvidence.observedCount(activityGuardWasRegistered, activityGuardUnregistered, activityCreated),
                NotificationInputReadinessEvidence.observedCount(activityGuardWasRegistered, activityGuardUnregistered, activityResumed),
                serviceObserverWasRegistered, actionClicks, expandClicks, sourceTokenless, servicePendingIntent, measuredBeforeCleanup, evidence,
            )
            try { instrumentation.sendStatus(0, Bundle().apply {
                putString(IndependentFreshServiceTransportProtocol.REPORT, observation.report().toString())
            }) } catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
            if (!observation.passed()) throw AssertionError((reason ?: cleanupReasons.firstOrNull() ?: Reason.SERVICE_LIFECYCLE).name)
        }

        fun report() {
            val passed = completed && reason == null && cleanupReasons.isEmpty() && evidence.complete && evidence.closed &&
                activityCreated == 0 && activityResumed == 0 && sourceTokenless && servicePendingIntent && measuredBeforeCleanup && actionClicks == 1
            val samples = evidence.samples
            try { instrumentation.sendStatus(0, Bundle().apply {
                putString("notificationServiceTransportStage", if (passed) "verified" else "failed")
                putString("reasonCode", reason?.name ?: if (evidence.failure != null) "SERVICE_LIFECYCLE" else "NONE")
                putString("cleanupReasonCodes", cleanupReasons.joinToString(",") { it.name })
                putBoolean("diagnosticPassed", passed); putBoolean("transportCandidate", evidence.candidate(passed))
                if (validId(receiptId.orEmpty())) putString("recoveryReceiptId", receiptId)
                putInt("mainPid", Process.myPid()); putInt("fixturePid", fixturePid)
                putBoolean("approvalAcceptance", false); putBoolean("inputAttempted", false)
                putInt("actionClicks", actionClicks); putInt("expandClicks", expandClicks)
                putInt("activityCreated", activityCreated); putInt("activityResumed", activityResumed)
                putInt("serviceCreated", evidence.createdCount); putInt("serviceDispatches", evidence.dispatchCount); putInt("serviceDestroyed", evidence.destroyedCount)
                putBoolean("sourceTokenless", sourceTokenless); putBoolean("servicePendingIntent", servicePendingIntent)
                putBoolean("serviceSameProcess", evidence.failure == null && evidence.dispatchCount == 1)
                putBoolean("measurementFrozenBeforeCleanup", measuredBeforeCleanup)
                putString("automaticShadeClosed", evidence.automaticShadeClosed)
                putBoolean("allOriginalEditor", evidence.allOriginalEditor); putBoolean("allOriginalBoundConnection", evidence.allOriginalConnection)
                putBoolean("allCurrentConnectionConsistent", evidence.allConsistent); putBoolean("allFixtureEditorValid", evidence.allValid)
                putBoolean("fixtureUnchanged", fixtureUnchanged); putBoolean("receiptUnchanged", receiptUnchanged)
                putBoolean("rootServiceUnchanged", rootServiceUnchanged); putBoolean("probeServiceStopped", serviceStopped)
                putBoolean("serviceObserverUnregistered", observerUnregistered); putBoolean("activityGuardUnregistered", activityGuardUnregistered)
                putBoolean("pendingIntentCancelled", pendingCancelled); putBoolean("notificationsRestored", notificationsRestored)
                putBoolean("imeRestored", imeRestored); putBoolean("automationReleased", automationReleased)
                putBoolean("configuratorRestored", configuratorRestored); putBoolean("accessibilityRestored", accessibilityRestored)
                putString("transportSamples", buildJsonArray { samples.forEach { s -> add(buildJsonObject {
                    put("phase", s.phase.name); put("mainThread", s.mainThread); put("editorPresent", s.editorPresent)
                    put("sameEditor", s.sameEditor); put("sameBoundConnection", s.sameBoundConnection)
                    put("currentConnectionConsistent", s.currentConnectionConsistent); put("fixtureEditorValid", s.fixtureEditorValid)
                    put("focus", s.focus.name); put("fixtureEditorVisible", s.fixtureEditorVisible)
                    put("sameFixtureWindow", s.sameFixtureWindow); put("shade", s.shade.name)
                }) } }.toString())
            }) } catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
            if (!passed) throw AssertionError((reason ?: cleanupReasons.firstOrNull() ?: Reason.SERVICE_LIFECYCLE).name)
        }
    }
    private class Failure(val code: Reason) : AssertionError(code.name)
    private enum class Reason {
        ADMISSION, DEBUG_API, IDENTITY, RECEIPT_ID, NOT_IDLE, RECEIPT, INVALIDATION, BASELINE, FIXTURE_BINDING, FIXTURE_STATE,
        FRESH_ADMISSION, FRESH_CREATION, CLEANUP_INCOMPLETE, EXISTING_ADMISSION, ROOT_COLLAPSE,
        NOTIFICATION_PERMISSION, IME_BUSY, IME_BASELINE, IME_SAVE, IME_SELECT, EDITOR_READY, UI_INITIALIZE, OBSERVER, PUBLISH,
        TOKEN_ISOLATION, SHADE, SHADE_EVIDENCE, TITLE, ROW, EXPAND, EXPAND_WAIT, ACTION, DISPATCH, SERVICE_LIFECYCLE,
        SAMPLE, WINDOW_SAMPLE, PHASE_ORDER, PENDING_INTENT_CANCEL, NOTIFICATION_CANCEL, SERVICE_STOP, OBSERVER_CLEANUP,
        ACTIVITY_GUARD_CLEANUP, SHADE_CLEANUP, CHANNEL_CLEANUP, NOTIFICATION_CLEANUP, UI_RELEASE, FLAGS_RESTORE,
        ACCESSIBILITY_RESTORE, IME_RESTORE, FIXTURE_CHANGED, RECEIPT_CHANGED, ROOT_SERVICE_CHANGED, REPORT_FAILED,
    }
    private companion object {
        const val PACKAGE = "com.example.agent"
        const val FIXTURE = "com.example.rootpilot.fixture"
        const val SYSTEM_UI = "com.android.systemui"
        const val MARKER = "notificationServiceTransportNonce"
        const val BUTTON = "诊断传输（不输入）"
        const val NOTIFICATION_ID = 92732
        const val RETAINED_FAILURE_RECEIPT = "457c1072-0074-4a20-997d-64d19e89613b"
        // Keep the original Binder/Handler callback, but put only the framework class on the wire.
        fun frameworkResultReceiver(receiver: ResultReceiver): ResultReceiver {
            val parcel = Parcel.obtain()
            return try {
                receiver.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                ResultReceiver.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        fun exclusiveMode(args: Bundle, expected: String) {
            val modes = listOf("notificationServiceTransportDiagnostic", "notificationInputReadinessDiagnostic", "freshNotificationInputReadinessDiagnostic",
                IndependentFreshServiceTransportProtocol.MODE, ExistingFixtureRootCollapseProtocol.MODE)
            ensure(modes.count { args.getString(it) == "true" } == 1 && args.getString(expected) == "true", Reason.FRESH_ADMISSION)
        }
        val BASELINE_KEYS = setOf("id", "mainPid", "appUid", "bootCount", "apiConfigured", "manualConfirmation", "allowScreenUpload",
            "originalIme", "apiDigest", "allowlistDigest", "fixtureInstanceId", "fixturePid")
        val RECEIPT_FILES = setOf("baseline.json", "invalidated", "invalidated-ready.json", "claimed")
        fun ensure(value: Boolean, code: Reason) { if (!value) throw Failure(code) }
        fun validId(value: String): Boolean = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
