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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.agent.BuildConfig
import com.example.agent.rootpilot.NotificationImeIdentityEvidence.Phase
import com.example.agent.rootpilot.NotificationImeIdentityEvidence.Sample
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

/** Synthetic window/IME diagnostic only: no model, approval, input, or original receipt mutation. */
@RunWith(AndroidJUnit4::class)
class NotificationImeWindowDiagnosticInstrumentedTest {
    @Test
    fun inspectSurvivingFixtureAcrossNotificationWindow() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_diagnostic_opt_in_required", args.getString("notificationImeWindowDiagnostic") == "true")
        val probe = Probe(args.getString("recoveryReceiptId").orEmpty())
        try { probe.run() } catch (error: Throwable) { probe.fail(error) }
        finally { withContext(NonCancellable) { probe.cleanup() } }
        probe.report()
    }

    private class Probe(private val receiptId: String) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val app = context.applicationContext as Application
        private val ime = AndroidImeEnvironment(context)
        private val store = FileImeRestoreStore(context)
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val nonce = UUID.randomUUID().toString()
        private val data = Uri.parse("rootpilot-ime-diagnostic://window/$nonce")
        private val channel = "rootpilot-ime-diagnostic-$nonce"
        private val action = "$PACKAGE.IME_WINDOW_DIAGNOSTIC.$nonce"
        private val title = "IME窗口诊断 $nonce"
        private val dummy = "diagnostic-$nonce"
        private val samples = mutableListOf<Sample>()
        private var phase = Reason.ADMISSION
        private var reason: Reason? = null
        private val cleanupReasons = mutableListOf<Reason>()
        private var fixtureId = ""
        private var fixturePid = -1
        private lateinit var receipt: File
        private var receiptBefore: Map<String, ByteArray>? = null
        private var serviceBefore: ServiceDestroyObservation.Instance? = null
        private var originalIme: String? = null
        private var lockHeld = false
        private var imeSaveAttempted = false
        private var originalAccessibility: Pair<String?, String?>? = null
        private var originalFlags: Int? = null
        private var automation: UiAutomation? = null
        private var ui: UiDevice? = null
        private var pending: PendingIntent? = null
        private var channelCreated = false
        private var notificationPublished = false
        private var notificationBefore: Set<Pair<String?, Int>>? = null
        private var ready: InputConnectionBridge.Editor? = null
        private var observer: Observer? = null
        private var clicked = false
        private var sourceTokenlessBefore = false
        private var sourceTokenlessAfter = false
        private var completed = false
        private var receiptUnchanged = false
        private var fixtureUnchanged = false
        private var imeRestored = false
        private var automationReleased = false
        private var flagsRestored = false
        private var accessibilityRestored = false
        private var notificationsRestored = false
        private var serviceObservationUnchanged = false

        suspend fun run() {
            ensure(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                Build.VERSION.SDK_INT >= 29, Reason.DEBUG_API)
            ensure(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE &&
                context.applicationInfo.uid == Process.myUid(), Reason.IDENTITY)
            ensure(validId(receiptId), Reason.RECEIPT_ID)
            serviceBefore = ServiceDestroyObservation.current
            ensure(serviceBefore?.destroyed != false && !RootPilotService.uiState.value.running &&
                RootPilotService.uiState.value.pendingAction == null, Reason.NOT_IDLE)
            phase = Reason.RECEIPT
            receipt = context.noBackupFilesDir.resolve("rootpilot-type-process-recovery-test").resolve(receiptId)
            receiptBefore = receiptBytes()
            val bytes = receiptBefore!!
            ensure(bytes["invalidated"]?.contentEquals("NOTIFICATION_EXECUTION_FAILED".encodeToByteArray()) == true,
                Reason.INVALIDATION)
            val baseline = Json.parseToJsonElement(bytes.getValue("baseline.json").decodeToString()) as? JsonObject
                ?: throw Failure(Reason.BASELINE)
            ensure(baseline.keys == BASELINE_KEYS, Reason.BASELINE)
            fun string(key: String): String = (baseline[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw Failure(Reason.BASELINE)
            fun number(key: String): Int = (baseline[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw Failure(Reason.BASELINE)
            fixtureId = string("fixtureInstanceId")
            fixturePid = number("fixturePid")
            ensure(string("id") == receiptId && number("appUid") == Process.myUid() && validId(fixtureId) &&
                fixturePid > 0 && fixturePid != Process.myPid(), Reason.FIXTURE_BINDING)
            checkFixture()
            ensure(manager.areNotificationsEnabled() && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED, Reason.NOTIFICATION_PERMISSION)
            notificationBefore = manager.activeNotifications.map { it.tag to it.id }.toSet()
            phase = Reason.IME_BASELINE
            ensure(AndroidImeEnvironment.sessionMutex.tryLock(), Reason.IME_BUSY)
            lockHeld = true
            ensure(store.read() == null && imeRestoreFilesAbsent(), Reason.IME_BASELINE)
            originalIme = ime.currentId()
            ensure(!originalIme.isNullOrBlank() && originalIme != ime.ownId && ime.isEnabled(originalIme!!) &&
                ime.isEnabled(ime.ownId), Reason.IME_BASELINE)
            // Persist the restoration target before the first IME change; no production input call.
            imeSaveAttempted = true
            ensure(store.save(originalIme!!) && store.read() == originalIme, Reason.IME_SAVE)
            phase = Reason.IME_SELECT
            ensure(ime.select(ime.ownId) is RootExecutionResult.Success && ime.currentId() == ime.ownId, Reason.IME_SELECT)
            phase = Reason.EDITOR_READY
            ready = withTimeout(5_000) {
                InputConnectionBridge.editor.filterNotNull().first {
                    withContext(Dispatchers.Main.immediate) { validTarget(it) && it.owner.currentInputConnection === it.connection }
                }
            }
            sample(Phase.BASELINE)
            ensure(!samples.single().boundGuardRejects && samples.single().currentConnectionValid && samples.single().targetAllowed,
                Reason.EDITOR_READY)
            phase = Reason.UI_INITIALIZE
            originalAccessibility = accessibility()
            val config = Configurator.getInstance()
            originalFlags = config.uiAutomationFlags
            config.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            ensure(config.uiAutomationFlags == UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, Reason.UI_INITIALIZE)
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                ?: throw Failure(Reason.UI_INITIALIZE)
            ui = UiDevice.getInstance(instrumentation)
            sample(Phase.UI_READY)
            val watched = Observer().also { observer = it }
            withContext(Dispatchers.Main.immediate) {
                app.registerActivityLifecycleCallbacks(watched)
                watched.registered = true
            }
            phase = Reason.PUBLISH
            ensure(manager.getNotificationChannel(channel) == null, Reason.PUBLISH)
            channelCreated = true
            manager.createNotificationChannel(NotificationChannel(channel, "IME窗口诊断", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            })
            // No approval token is serialized in the queued PI. Only the live pre/post-create
            // guard temporarily supplies one to preserve onCreate without any Service dispatch.
            val source = Intent(context, RootPilotNotificationActivity::class.java)
                .setAction(action).setData(data).putExtra(MARKER, nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ensure(!source.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) &&
                source.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) == null, Reason.TOKEN_ISOLATION)
            sourceTokenlessBefore = true
            pending = PendingIntent.getActivity(context, 0, source,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            ensure(!source.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) &&
                source.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) == null, Reason.TOKEN_ISOLATION)
            sourceTokenlessAfter = true
            notificationPublished = true
            manager.notify(nonce, NOTIFICATION_ID, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
                .setContentText("仅诊断窗口与输入连接，不执行输入")
                .addAction(Notification.Action.Builder(null, BUTTON, pending).build())
                .setAutoCancel(false).setOnlyAlertOnce(true).build())
            phase = Reason.SHADE
            sample(Phase.BEFORE_SHADE)
            ensure(ui!!.openNotification(), Reason.SHADE)
            sample(Phase.SHADE)
            ensure(ui!!.wait(Until.hasObject(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern)
                .text(title)), 5_000), Reason.TITLE)
            var row = findRow()
            var buttons = row.findObjects(By.pkg(SYSTEM_UI).text(BUTTON))
            if (buttons.isEmpty()) {
                val candidate = row.findObjects(By.res(NotificationIdentityExpand.resourcePattern)).singleOrNull()
                    ?: throw Failure(Reason.EXPAND)
                ensure(candidate.applicationPackage == SYSTEM_UI && candidate.isEnabled && candidate.isClickable, Reason.EXPAND)
                candidate.click()
                ensure(ui!!.wait(object : Condition<UiDevice, Boolean> {
                    override fun apply(container: UiDevice): Boolean {
                        val titles = container.findObjects(By.pkg(SYSTEM_UI)
                            .res(NotificationIdentityEvidence.titleResourcePattern).text(title))
                        if (titles.isEmpty()) return false
                        ensure(titles.size == 1, Reason.TITLE)
                        val expanded = try { findRow() } catch (error: Failure) {
                            if (error.code == Reason.ROW) return false else throw error
                        }
                        return expanded.findObjects(By.pkg(SYSTEM_UI).text(BUTTON)).isNotEmpty()
                    }
                }, 5_000), Reason.EXPAND_WAIT)
                row = findRow()
                buttons = row.findObjects(By.pkg(SYSTEM_UI).text(BUTTON))
            }
            val button = buttons.singleOrNull() ?: throw Failure(Reason.ACTION)
            ensure(button.isEnabled && button.isClickable && watched.registered && watched.created == 0, Reason.ACTION)
            ensure(manager.activeNotifications.singleOrNull { it.tag == nonce && it.id == NOTIFICATION_ID }
                ?.notification?.actions?.singleOrNull()?.actionIntent == pending, Reason.ACTION)
            sample(Phase.BEFORE_CLICK)
            phase = Reason.ACTIVITY
            withContext(Dispatchers.Main.immediate) {
                ensure(watched.registered && watched.created == 0, Reason.ACTION)
                watched.clickAttempted = true
            }
            button.click()
            clicked = true
            withTimeout(10_000) { watched.finished.await() }
            ensure(ui!!.wait(Until.hasObject(By.pkg(FIXTURE).res("android:id/edit")), 5_000), Reason.FIXTURE_RETURN)
            sample(Phase.FIXTURE_RETURNED)
            ensure(watched.failure == null && watched.injected && watched.stripped && watched.focusObserved &&
                watched.created == 1 && watched.resumed == 1 && watched.destroyed == 1, Reason.ACTIVITY)
            completed = true
        }

        private fun validTarget(editor: InputConnectionBridge.Editor): Boolean =
            editor.info.packageName == FIXTURE && editor.info.fieldId == android.R.id.edit &&
                editor.info.inputType != InputType.TYPE_NULL && !InputConnectionBridge.isPassword(editor.info.inputType)

        private suspend fun sample(stage: Phase) = withContext(Dispatchers.Main.immediate) { sampleMain(stage) }
        private fun sampleMain(stage: Phase) {
            val bound = ready ?: throw Failure(Reason.EDITOR_READY)
            val current = InputConnectionBridge.editor.value
            samples += Sample(stage, current != null, current === bound,
                bound.owner.currentInputConnection === bound.connection,
                current != null && current.owner.currentInputConnection === current.connection,
                current != null && validTarget(current))
        }

        private inner class Observer : Application.ActivityLifecycleCallbacks {
            var registered = false
            var clickAttempted = false
            var closing = false
            var failure: Reason? = null
            var owned: Activity? = null
            var created = 0
            var resumed = 0
            var destroyed = 0
            var injected = false
            var stripped = false
            var focusObserved = false
            val finished = CompletableDeferred<Unit>()
            private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
            private fun matches(activity: Activity): Boolean = activity is RootPilotNotificationActivity && activity.intent.data == data
            private fun strip(activity: Activity): Boolean = try {
                activity.intent.removeExtra(RootPilotService.EXTRA_APPROVAL_TOKEN)
                !activity.intent.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) &&
                    activity.intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) == null
            } catch (_: Throwable) { false }
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                if (!matches(activity)) return
                owned = activity
                try {
                    ensure(Looper.myLooper() === Looper.getMainLooper() && registered && clickAttempted &&
                        !closing && !injected && activity.intent.action == action &&
                        activity.intent.getStringExtra(MARKER) == nonce && !activity.intent.hasExtra(RootPilotService.EXTRA_APPROVAL_TOKEN),
                        Reason.ACTIVITY_BINDING)
                    activity.intent.putExtra(RootPilotService.EXTRA_APPROVAL_TOKEN, dummy)
                    injected = true
                } catch (_: Throwable) {
                    failure = Reason.ACTIVITY_BINDING
                    strip(activity)
                    activity.finish()
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity === owned) created++ }
            override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
                if (activity !== owned && !matches(activity)) return
                var accepted = false
                try {
                    accepted = !closing && injected && created == 1 && !activity.isFinishing && activity.intent.action == action &&
                        activity.intent.getStringExtra(MARKER) == nonce &&
                        activity.intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN) == dummy
                } catch (_: Throwable) { failure = Reason.ACTIVITY_BINDING }
                finally {
                    // Mutate the actual Intent, not its copied extras Bundle, before any focus.
                    stripped = strip(activity)
                    if (!accepted || !stripped) { failure = Reason.TOKEN_ISOLATION; activity.finish() }
                }
                try {
                    sampleMain(Phase.ACTIVITY_POST_CREATED)
                    if (accepted && stripped) {
                        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                            if (hasFocus && !focusObserved) {
                                focusObserved = true
                                try { sampleMain(Phase.ACTIVITY_FOCUS) } catch (_: Throwable) { failure = Reason.ACTIVITY }
                            }
                        }
                        focusListener = listener
                        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                    }
                } catch (_: Throwable) { failure = Reason.ACTIVITY; activity.finish() }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity === owned) {
                    resumed++
                    try { sampleMain(Phase.ACTIVITY_RESUMED) } catch (_: Throwable) { failure = Reason.ACTIVITY; activity.finish() }
                }
            }
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivityPostDestroyed(activity: Activity) {
                if (activity !== owned) return
                destroyed++
                try {
                    focusListener?.let { activity.window.decorView.viewTreeObserver.takeIf { it.isAlive }
                        ?.removeOnWindowFocusChangeListener(it) }
                    sampleMain(Phase.ACTIVITY_DESTROYED)
                } catch (_: Throwable) { failure = Reason.ACTIVITY }
                Handler(Looper.getMainLooper()).post {
                    try { sampleMain(Phase.NEXT_MAIN_TURN) } catch (_: Throwable) { failure = Reason.ACTIVITY }
                    if (closing) unregister()
                    finished.complete(Unit)
                }
            }
            fun close() {
                closing = true
                try {
                    owned?.takeUnless { it.isDestroyed }?.let { activity ->
                        try {
                            ensure(strip(activity), Reason.TOKEN_ISOLATION)
                        } finally { activity.finish() }
                    }
                } finally { unregister() }
                // Normal completion reaches postDestroyed before closing. Failure cleanup is
                // finite: queued/restarted launches have the original tokenless Intent and
                // finish in onCreate even after Instrumentation.finish kills this process.
            }
            private fun unregister() { if (registered) { app.unregisterActivityLifecycleCallbacks(this); registered = false } }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }

        private fun findRow(): UiObject2 {
            val titleNode = ui!!.findObjects(By.pkg(SYSTEM_UI).res(NotificationIdentityEvidence.titleResourcePattern).text(title))
                .singleOrNull() ?: throw Failure(Reason.TITLE)
            var current: UiObject2? = titleNode
            var child: UiObject2? = null
            repeat(12) {
                val node = current ?: throw Failure(Reason.ROW)
                val named = NotificationRowStructure.isRow(NotificationRowStructure.Node(resourceName = node.resourceName))
                val parent = if (named) null else node.parent
                if (named || NotificationRowStructure.isRow(NotificationRowStructure.Node(node.resourceName,
                        node.applicationPackage, node.className, node.isEnabled, node.isClickable, node.isScrollable),
                        child?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage) },
                        parent?.let { NotificationRowStructure.Node(it.resourceName, it.applicationPackage, it.className) })) {
                    ensure(node.applicationPackage == SYSTEM_UI && node.findObjects(By.pkg(SYSTEM_UI)
                        .res(NotificationIdentityEvidence.titleResourcePattern).text(title)).singleOrNull() == titleNode, Reason.ROW)
                    return node
                }
                child = node; current = parent
            }
            throw Failure(Reason.ROW)
        }

        private suspend fun checkFixture() {
            val client = FixtureClient(context)
            client.verifyIdentity()
            val state = client.call("state")
            @Suppress("DEPRECATION")
            ensure(state.get("instanceId") is String && state.get("processId") is Int && state.get("fieldId") is Int &&
                state.get("ready") is Boolean && state.get("empty") is Boolean && state.get("matches") is Boolean &&
                state.get("commitCalls") is Int, Reason.FIXTURE_STATE)
            ensure(state.getString("instanceId") == fixtureId && state.getInt("processId") == fixturePid &&
                state.getInt("fieldId") == android.R.id.edit && state.getBoolean("ready") && state.getBoolean("empty") &&
                !state.getBoolean("matches") && state.getInt("commitCalls") == 0, Reason.FIXTURE_STATE)
        }

        private fun receiptBytes(): Map<String, ByteArray> {
            listOf(context.noBackupFilesDir, receipt.parentFile!!, receipt).forEach {
                ensure(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it.toPath()), Reason.RECEIPT)
            }
            val files = receipt.listFiles() ?: throw Failure(Reason.RECEIPT)
            ensure(files.isNotEmpty() && files.map { it.name }.toSet().all { it in RECEIPT_FILES }, Reason.RECEIPT)
            return files.associate { file ->
                ensure(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT)
                val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                val bytes = Channels.newInputStream(Files.newByteChannel(file.toPath(), options)).use { it.readNBytes(64 * 1024 + 1) }
                ensure(bytes.size <= 64 * 1024, Reason.RECEIPT)
                file.name to bytes
            }
        }
        private fun accessibility() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) to
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        private fun imeRestoreFilesAbsent(): Boolean = listOf("rootpilot_original_ime", "rootpilot_original_ime.bak", "rootpilot_original_ime.new")
            .none { Files.exists(context.noBackupFilesDir.resolve(it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        fun fail(error: Throwable) { reason = (error as? Failure)?.code ?: phase }
        private suspend fun verify(code: Reason, block: suspend () -> Boolean): Boolean = try {
            block().also { if (!it) cleanupReasons += code }
        } catch (_: Throwable) { cleanupReasons += code; false }

        suspend fun cleanup() {
            verify(Reason.GUARD_CLEANUP) { withContext(Dispatchers.Main.immediate) { observer?.close() }; true }
            // UI cleanup must finish before releasing accessibility and restoring Configurator.
            verify(Reason.SHADE_CLEANUP) { ui?.let { if (it.currentPackageName == SYSTEM_UI) it.pressBack() else true } ?: true }
            verify(Reason.NOTIFICATION_CANCEL) { if (notificationPublished) manager.cancel(nonce, NOTIFICATION_ID); true }
            verify(Reason.PENDING_INTENT_CANCEL) { pending?.cancel(); true }
            verify(Reason.CHANNEL_CLEANUP) { if (channelCreated) manager.deleteNotificationChannel(channel); true }
            notificationsRestored = verify(Reason.NOTIFICATION_CLEANUP) {
                (notificationBefore?.let { manager.activeNotifications.map { it.tag to it.id }.toSet() == it } ?: true) &&
                    (!channelCreated || manager.getNotificationChannel(channel) == null)
            }
            automationReleased = verify(Reason.UI_RELEASE) {
                automation == null || instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or
                    UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) != null
            }
            flagsRestored = verify(Reason.FLAGS_RESTORE) {
                originalFlags?.let { flags -> Configurator.getInstance().let { it.uiAutomationFlags = flags; it.uiAutomationFlags == flags } } ?: true
            }
            accessibilityRestored = verify(Reason.ACCESSIBILITY_RESTORE) { originalAccessibility?.let { accessibility() == it } ?: true }
            imeRestored = verify(Reason.IME_RESTORE) {
                if (imeSaveAttempted) ensure(ImeTextInput(ime, store).recover() is RootExecutionResult.Success, Reason.IME_RESTORE)
                originalIme?.let { ime.currentId() == it && store.read() == null && imeRestoreFilesAbsent() } ?: true
            }
            if (lockHeld) { AndroidImeEnvironment.sessionMutex.unlock(); lockHeld = false }
            fixtureUnchanged = verify(Reason.FIXTURE_CHANGED) { if (fixtureId.isNotEmpty()) checkFixture(); fixtureId.isNotEmpty() }
            receiptUnchanged = verify(Reason.RECEIPT_CHANGED) {
                receiptBefore?.let { before -> val after = receiptBytes(); before.keys == after.keys &&
                    before.all { (name, bytes) -> bytes.contentEquals(after.getValue(name)) } } ?: false
            }
            serviceObservationUnchanged = verify(Reason.SERVICE_CHANGED) { ServiceDestroyObservation.current === serviceBefore }
        }

        suspend fun report() {
            val values = withContext(Dispatchers.Main.immediate) { samples.toList() }
            val passed = completed && reason == null && cleanupReasons.isEmpty()
            try {
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("notificationImeWindowStage", if (passed) "verified" else "failed")
                    putString("reasonCode", reason?.name ?: "NONE")
                    putString("cleanupReasonCodes", cleanupReasons.joinToString(",") { it.name })
                    putBoolean("diagnosticPassed", passed)
                    if (validId(receiptId)) putString("recoveryReceiptId", receiptId)
                    putInt("mainPid", Process.myPid())
                    putInt("fixturePid", fixturePid)
                    putBoolean("approvalAcceptance", false)
                    putBoolean("inputAttempted", false)
                    putBoolean("clicked", clicked)
                    putBoolean("sourceTokenlessBefore", sourceTokenlessBefore)
                    putBoolean("sourceTokenlessAfter", sourceTokenlessAfter)
                    putBoolean("tokenInjected", observer?.injected == true)
                    putBoolean("tokenStripped", observer?.stripped == true)
                    putBoolean("activityFocusObserved", observer?.focusObserved == true)
                    putInt("activityCreated", observer?.created ?: 0)
                    putInt("activityResumed", observer?.resumed ?: 0)
                    putInt("activityDestroyed", observer?.destroyed ?: 0)
                    putBoolean("lateLaunchGuardRetained", observer?.registered == true)
                    putBoolean("receiptUnchanged", receiptUnchanged)
                    putBoolean("fixtureUnchanged", fixtureUnchanged)
                    putBoolean("imeRestored", imeRestored)
                    putBoolean("automationReleased", automationReleased)
                    putBoolean("configuratorRestored", flagsRestored)
                    putBoolean("accessibilityRestored", accessibilityRestored)
                    putBoolean("notificationsRestored", notificationsRestored)
                    putBoolean("serviceObservationUnchanged", serviceObservationUnchanged)
                    putString("firstBoundDriftPhase", if (values.firstOrNull()?.let { !it.boundGuardRejects && it.currentConnectionValid && it.targetAllowed } == true)
                        NotificationImeIdentityEvidence.firstBoundDrift(values)?.name ?: "NONE" else "UNAVAILABLE")
                    putString("imeIdentitySamples", buildJsonArray { values.forEach { s -> add(buildJsonObject {
                        put("phase", s.phase.name); put("editorPresent", s.editorPresent); put("sameEditor", s.sameEditor)
                        put("sameBoundConnection", s.sameBoundConnection); put("currentConnectionValid", s.currentConnectionValid)
                        put("targetAllowed", s.targetAllowed); put("boundGuardRejects", s.boundGuardRejects)
                    }) } }.toString())
                })
            } catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
            if (!passed) throw AssertionError((reason ?: cleanupReasons.firstOrNull() ?: Reason.ACTIVITY).name)
        }
    }

    private class Failure(val code: Reason) : AssertionError(code.name)
    private enum class Reason {
        ADMISSION, DEBUG_API, IDENTITY, RECEIPT_ID, NOT_IDLE, RECEIPT, INVALIDATION, BASELINE, FIXTURE_BINDING,
        FIXTURE_STATE, FIXTURE_RETURN, NOTIFICATION_PERMISSION, IME_BUSY, IME_BASELINE, IME_SAVE, IME_SELECT, EDITOR_READY,
        UI_INITIALIZE, PUBLISH, SHADE, TITLE, ROW, EXPAND, EXPAND_WAIT, ACTION, ACTIVITY, ACTIVITY_BINDING, TOKEN_ISOLATION,
        GUARD_CLEANUP, SHADE_CLEANUP, NOTIFICATION_CANCEL, PENDING_INTENT_CANCEL, CHANNEL_CLEANUP,
        NOTIFICATION_CLEANUP, UI_RELEASE, FLAGS_RESTORE, ACCESSIBILITY_RESTORE,
        IME_RESTORE, FIXTURE_CHANGED, RECEIPT_CHANGED, SERVICE_CHANGED, REPORT_FAILED,
    }
    private companion object {
        const val PACKAGE = "com.example.agent"
        const val FIXTURE = "com.example.rootpilot.fixture"
        const val SYSTEM_UI = "com.android.systemui"
        const val MARKER = "notificationImeDiagnosticNonce"
        const val BUTTON = "诊断窗口（不输入）"
        const val NOTIFICATION_ID = 92731
        val BASELINE_KEYS = setOf("id", "mainPid", "appUid", "bootCount", "apiConfigured", "manualConfirmation",
            "allowScreenUpload", "originalIme", "apiDigest", "allowlistDigest", "fixtureInstanceId", "fixturePid")
        val RECEIPT_FILES = setOf("baseline.json", "invalidated", "invalidated-ready.json", "claimed")
        fun ensure(value: Boolean, code: Reason) { if (!value) throw Failure(code) }
        fun validId(value: String): Boolean = value.length == 36 &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
