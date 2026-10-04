package com.example.agent.rootpilot

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two separate, default-off invocations in the actual com.example.agent process (--no-restart).
 * Both require virtualProcessRecovery=true, recoveryReceiptId=<fresh UUID> and
 * expectedRootPilotPid=<the host-verified current main PID>. Prepare additionally requires
 * recoveryBaseline=empty_task_saved_api_or_defaults. Start RootPilot normally before prepare.
 *
 * prepareOwnedDisplayAtUnapprovedOpenApp emits ready_for_main_process_kill only after durable
 * receipt/snapshot/trace checks. Within 45 seconds the host must kill ONLY the reported main PID
 * (not force-stop the package or kill its root helper), then normally reopen RootPilotActivity.
 * Prepare's instrumentation process necessarily dies: its crash is expected, not a test PASS.
 * Invoke verifyAfterHostKillAndNormalActivityReopen with the SAME receipt ID and the NEW main
 * PID within two minutes. Verify does not launch an Activity or send RESTORE itself.
 *
 * No confirmation, image capture, model call, app launch, input injection or fake controller.
 * Zero operation counts are assertions on complete production trace plus state/snapshot checks,
 * not network interception. A root read-only process listing binds the helper before host kill;
 * signal 0 subsequently proves PID absence. app_process may replace argv with its entry class;
 * in that case binding requires no helper at preflight and one new helper with one owned display.
 * No release-pipe receipt survives the client death.
 * Original allowlist bytes stay exclusively in this app's private noBackup test directory.
 * Credentials are read only through the saved Keystore config; neither token nor ciphertext is
 * copied to receipts. Digests bind unchanged files without exporting their contents.
 *
 * An unclaimed/failed receipt blocks another prepare; a claimed verify can never be replayed.
 * A prepare failure revokes its ready receipt before cleanup; verify rejects either a revocation
 * marker or a moved/missing ready file. Revocation failure blocks cleanup and is never a PASS.
 * Unknown ownership, modified files or unconfirmed teardown retain the snapshot and backup for
 * inspection. Cleanup only uses normal STOP (prepare abort) / DISCARD (verified recovery).
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayProcessRecoveryInstrumentedTest {
    @Test
    fun prepareOwnedDisplayAtUnapprovedOpenApp() = invoke(prepare = true)

    @Test
    fun verifyAfterHostKillAndNormalActivityReopen() = invoke(prepare = false)

    /** Pure text fixtures only; opt in with virtualProcessRecoveryParserFixtures=true. */
    @Test
    fun acceptsOnlyExactHelperProcessShapes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualProcessRecoveryParserFixtures") == "true")
        val session = "11111111-1111-4111-8111-111111111111"
        val otherSession = "22222222-2222-4222-8222-222222222222"
        val entry = VirtualDisplayProtocol.ENTRY
        fun row(vararg arguments: String) = listOf("123") + arguments
        val valid = listOf(
            row("app_process", "/system/bin", entry, session),
            row("/system/bin/app_process", "/system/bin", entry, session),
            row(entry, session),
            row(entry),
        )
        valid.forEach { fields ->
            val helper = bindHelperProcess(listOf(parseHelperProcess(fields)), session)
            check(helper.pid == 123 && (helper.session == session || fields == row(entry)), Reason.PARSER_FIXTURE_FAILED)
        }
        val invalid = listOf(
            row("su", "-c", "app_process", "/system/bin", entry, session),
            row("sh", "-c", "app_process", "/system/bin", entry, session),
            row("app_process", "/data/local/tmp", entry, session),
            row("/other/app_process", "/system/bin", entry, session),
            row("app_process64", "/system/bin", entry, session),
            row("app_process", "/system/bin", "prefix.$entry", session),
            row("app_process", "/system/bin", entry, "invalid-session"),
            row("app_process", "/system/bin", entry, otherSession),
            row("app_process", "/system/bin", entry),
            row("app_process", "/system/bin", entry, session, "extra"),
            row(entry, otherSession),
            listOf("0", "app_process", "/system/bin", entry, session),
        )
        invalid.forEach { fields ->
            val error = runCatching { bindHelperProcess(listOf(parseHelperProcess(fields)), session) }.exceptionOrNull()
            check((error as? RecoveryFailure)?.reason == Reason.HELPER_IDENTITY, Reason.PARSER_FIXTURE_FAILED)
        }
        val helper = parseHelperProcess(valid.first())
        listOf(emptyList(), listOf(helper, helper)).forEach { candidates ->
            val error = runCatching { bindHelperProcess(candidates, session) }.exceptionOrNull()
            check((error as? RecoveryFailure)?.reason == Reason.HELPER_IDENTITY, Reason.PARSER_FIXTURE_FAILED)
        }
    }

    private fun invoke(prepare: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualProcessRecovery") == "true")
        runBlocking {
            val test = Recovery(InstrumentationRegistry.getInstrumentation().targetContext)
            var failure: Reason? = null
            try {
                test.checkInvocation()
                if (prepare) test.prepare() else test.verify()
            } catch (error: Throwable) {
                failure = reason(error)
            } finally {
                withContext(NonCancellable) {
                    var cleanupAllowed = true
                    if (prepare && failure != null) {
                        try { test.invalidatePrepareReceipt(failure!!) }
                        catch (_: Throwable) {
                            failure = Reason.RECEIPT_INVALIDATION_FAILED
                            cleanupAllowed = false
                        }
                    }
                    if (cleanupAllowed) {
                        try { test.cleanup(prepare) }
                        catch (_: Throwable) { if (failure == null) failure = Reason.CLEANUP_UNCONFIRMED }
                    }
                    try { test.report(if (failure == null) "verified" else "failed", failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    private class Recovery(private val context: Context) {
        private val state get() = RootPilotService.uiState.value
        private val root = context.noBackupFilesDir.resolve("rootpilot-process-recovery-test")
        private val selection = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        private val apiFile = context.noBackupFilesDir.resolve(RootPilotApiConfigStore.FILE_NAME)
        private val snapshotFile = context.filesDir.resolve(RootPilotRunStore.FILE_NAME)
        private val imeFile = context.noBackupFilesDir.resolve("rootpilot_original_ime")
        private val historyFile = context.noBackupFilesDir.resolve("rootpilot_history/runs.json")
        private val history by lazy { RootPilotService.historyState(context) }
        private val manager by lazy {
            context.getSystemService(DisplayManager::class.java) ?: fail(Reason.DISPLAY_IDENTITY)
        }
        private lateinit var id: String
        private val directory get() = root.resolve(id)
        private var baseline: Baseline? = null
        private var ready: Ready? = null
        private var selectionChanged = false
        private var configChanged = false
        private var startSent = false
        private var verifyClaimed = false
        private var verificationComplete = false
        private var restored = false
        private var requestedAt = Long.MAX_VALUE
        private var previousIds = emptySet<String>()
        private var loadedApi: RootPilotApiConfig? = null
        private var preparedRunId: String? = null
        private var prepareReceiptInvalidated = false

        fun checkInvocation() {
            val args = InstrumentationRegistry.getArguments()
            check(context.packageName == TARGET && Application.getProcessName() == TARGET, Reason.TARGET_PROCESS)
            check(args.getString("expectedRootPilotPid")?.toIntOrNull() == Process.myPid(), Reason.NO_RESTART_REQUIRED)
            id = args.getString("recoveryReceiptId").orEmpty()
            check(validId(id), Reason.RECEIPT_ID)
        }

        suspend fun prepare() {
            check(InstrumentationRegistry.getArguments().getString("recoveryBaseline") ==
                "empty_task_saved_api_or_defaults", Reason.BASELINE_ACK_REQUIRED)
            val original = state
            check(!original.running && original.pendingAction == null && original.status in IDLE_STATES,
                Reason.SERVICE_BUSY)
            check(!snapshotFile.exists(), Reason.EXISTING_RECOVERY)
            check(!imeFile.exists(), Reason.IME_RECOVERY)
            check(original.config.task.isEmpty() && original.config.executionDisplay == ExecutionDisplay.MAIN &&
                original.config.virtualDisplayStartPackage.isEmpty(), Reason.BASELINE_UNRESTORABLE)
            check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, Reason.LOCKED)
            check(Settings.canDrawOverlays(context), Reason.OVERLAY_PERMISSION)
            check(privateDisplays().isEmpty(), Reason.EXISTING_DISPLAY)
            check(helperProcesses().isEmpty(), Reason.EXISTING_HELPER)
            val launch = context.packageManager.getLaunchIntentForPackage(CALCULATOR)?.component
            check(launch?.packageName == CALCULATOR && launch.className == CALCULATOR_ACTIVITY, Reason.CALCULATOR)
            val saved = savedApi().also { loadedApi = it }
            val rebuilt = (if (original.apiConfigured) saved else RootPilotApiConfig()).applyTo(original.config)
            check(rebuilt == original.config, Reason.BASELINE_UNRESTORABLE)
            val ime = currentIme()
            check(ime.isNotBlank() && ime.length <= 512, Reason.IME_RECOVERY)
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(boot >= 0, Reason.BOOT_IDENTITY)
            check(!Files.isSymbolicLink(root.toPath()) && (!root.exists() || root.isDirectory), Reason.RECEIPT_IO)
            check(root.listFiles()?.all { it.isDirectory && validId(it.name) && it.resolve("completed").isFile } != false,
                Reason.UNFINISHED_RECEIPT)
            check(!directory.exists(), Reason.RECEIPT_REUSED)
            // Wait for the repository's asynchronous initial read; do not invoke its temp-file cleanup.
            withTimeout(5_000) {
                while (history.value.error == null && history.value.records != diskHistory()) delay(POLL)
            }
            check(history.value.error == null && history.value.records.none { it.status == RunHistoryStatus.RUNNING },
                Reason.HISTORY_UNAVAILABLE)
            previousIds = history.value.records.map { it.id }.toSet()
            val selectionBytes = if (selection.exists()) readBounded(selection, MAX_FILE) else null
            val base = Baseline(id, Process.myPid(), boot, original.apiConfigured,
                original.config.manualConfirmation, original.config.allowScreenUpload, ime,
                digest(readBounded(apiFile, MAX_FILE)), selectionBytes != null,
                selectionBytes?.let(::digest))
            check(state === original && !snapshotFile.exists() && privateDisplays().isEmpty(), Reason.ENVIRONMENT_CHANGED)
            check(root.isDirectory || root.mkdir(), Reason.RECEIPT_IO)
            check(directory.mkdir(), Reason.RECEIPT_IO)
            selectionBytes?.let { write(directory.resolve("allowlist.original"), it) }
            write(directory.resolve("baseline.json"), JSON.encodeToString(base).toByteArray())
            baseline = base
            checkSelectionOriginal(base)
            checkApi(base)
            check(state === original && !snapshotFile.exists() && privateDisplays().isEmpty() && currentIme() == ime,
                Reason.ENVIRONMENT_CHANGED)
            // Backup and baseline are durable before the first production configuration mutation.
            selectionChanged = true
            AppLaunchAllowlistStore.create(context).save(setOf(CALCULATOR))
            checkSelectionInstalled()
            configChanged = true
            RootPilotService.updateApiConfig(saved)
            val config = saved.applyTo(testConfig())
            check(!state.running && state.pendingAction == null && state.config == saved.applyTo(original.config) &&
                !snapshotFile.exists(), Reason.ENVIRONMENT_CHANGED)
            requestedAt = System.currentTimeMillis()
            startSent = true
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, config)
            val record = withTimeout(30_000) {
                while (true) {
                    check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
                    val candidates = diskHistory().filter { it.id !in previousIds && it.startedAtEpochMs >= requestedAt }
                    check(candidates.size <= 1, Reason.RUN_IDENTITY)
                    val current = candidates.singleOrNull()
                    if (current != null) preparedRunId = current.id
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION && current?.events?.lastOrNull()?.event == TraceEvent.WAITING &&
                        ownSnapshot().status == RootPilotStatus.WAITING_CONFIRMATION.name) {
                        checkWaiting(current, config)
                        break
                    }
                    check(state.config.task != task() || state.status !in
                        setOf(RootPilotStatus.FAILED, RootPilotStatus.RECOVERY_REQUIRED), Reason.PREPARE_FAILED)
                    delay(POLL)
                }
                diskHistory().single { it.id !in previousIds && it.startedAtEpochMs >= requestedAt }
            }
            val display = bindDisplay()
            val process = bindHelperProcess(helperProcesses(), display.session)
            val helper = process.pid
            check(helper > 0 && helper != Process.myPid() && !pidAbsent(helper), Reason.HELPER_IDENTITY)
            checkWaiting(record, config)
            val snapshot = ownSnapshot()
            check(snapshot.status == RootPilotStatus.WAITING_CONFIRMATION.name && snapshot.allowScreenUpload,
                Reason.SNAPSHOT_IDENTITY)
            val receipt = Ready(id, record.id, display.id, display.session, helper, process.session != null, SystemClock.elapsedRealtime(),
                digest(readBounded(snapshotFile, MAX_FILE)), historyDigest(diskHistory(), record.id))
            ready = receipt
            write(directory.resolve("ready.json"), JSON.encodeToString(receipt).toByteArray())
            checkWaiting(record, config)
            checkPreparedResources(receipt)
            report("ready_for_main_process_kill", null)
            // Keep the real Service pending until the host kills this exact process. If it does not,
            // the deadline leads to normal STOP and restoration, never a successful prepare verdict.
            val until = SystemClock.elapsedRealtime() + HOST_KILL_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkPreparedResources(receipt)
                checkWaiting(record, config)
                checkSnapshotDigest(receipt)
                check(historyDigest(diskHistory(), record.id) == receipt.historyDigest, Reason.UNEXPECTED_ACTIVITY)
                delay(POLL)
            }
            fail(Reason.HOST_KILL_TIMEOUT)
        }

        fun invalidatePrepareReceipt(failure: Reason) {
            if (ready == null) return
            prepareReceiptInvalidated = true
            val invalidated = directory.resolve("invalidated")
            val reasonBytes = failure.name.toByteArray(Charsets.US_ASCII)
            try {
                write(invalidated, reasonBytes)
                check(readBounded(invalidated, MAX_FILE).contentEquals(reasonBytes), Reason.RECEIPT_INVALIDATION_FAILED)
                return
            } catch (_: Throwable) {
                // Revocation must not depend on allocating a new file. Moving the existing receipt
                // also makes verify fail closed, while retaining its metadata and the private backup.
            }
            try {
                val issued = directory.resolve("ready.json")
                val revoked = directory.resolve("invalidated-ready.json")
                Files.move(issued.toPath(), revoked.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(directory)
                check(!issued.exists() && revoked.isFile, Reason.RECEIPT_INVALIDATION_FAILED)
            } catch (_: Throwable) {
                fail(Reason.RECEIPT_INVALIDATION_FAILED)
            }
        }

        private fun checkReceiptValid() {
            check(!directory.resolve("invalidated").exists() && !directory.resolve("invalidated-ready.json").exists() &&
                directory.resolve("ready.json").isFile, Reason.RECEIPT_INVALIDATED)
        }

        private fun checkPreparedResources(receipt: Ready) {
            check(bindDisplay() == OwnedDisplay(receipt.displayId, receipt.session), Reason.DISPLAY_IDENTITY)
            check(!pidAbsent(receipt.helperPid), Reason.HELPER_EXITED_BEFORE_MAIN)
        }

        suspend fun verify() {
            checkReceiptValid()
            check(directory.isDirectory && !directory.resolve("claimed").exists() && !directory.resolve("completed").exists(),
                Reason.RECEIPT_REUSED)
            val base = JSON.decodeFromString<Baseline>(readBounded(directory.resolve("baseline.json"), MAX_FILE).decodeToString())
            val receipt = JSON.decodeFromString<Ready>(readBounded(directory.resolve("ready.json"), MAX_FILE).decodeToString())
            check(base.id == id && receipt.id == id && validId(receipt.runId) && validId(receipt.session) &&
                receipt.displayId > 0 && receipt.helperPid > 0 && receipt.helperPid != base.mainPid,
                Reason.RECEIPT_ID)
            check(base.mainPid != Process.myPid() && pidAbsent(base.mainPid), Reason.MAIN_PROCESS_NOT_DEAD)
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) == base.bootCount &&
                SystemClock.elapsedRealtime() - receipt.readyAt in 0..VERIFY_WINDOW, Reason.RECEIPT_EXPIRED)
            checkApi(base)
            loadedApi = savedApi()
            checkSelectionInstalled()
            checkSelectionBackup(base)
            checkSnapshotDigest(receipt)
            // Reopening the real Activity must already have reloaded credentials and sent RESTORE.
            withTimeout(8_000) {
                while (true) {
                    check(!state.running, Reason.RECOVERY_STATE)
                    check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
                    if (state.status == RootPilotStatus.RECOVERY_REQUIRED &&
                        historySettled(receipt.runId, RunHistoryStatus.INTERRUPTED)) break
                    delay(POLL)
                }
            }
            checkRecovered(receipt, base)
            check(directory.resolve("claimed").createNewFile(), Reason.RECEIPT_REUSED)
            syncDirectory(directory)
            baseline = base
            ready = receipt
            verifyClaimed = true
            withTimeout(10_000) {
                while (privateDisplays().isNotEmpty() || manager.getDisplay(receipt.displayId) != null || !pidAbsent(receipt.helperPid)) {
                    checkRecovered(receipt, base)
                    delay(POLL)
                }
            }
            assertGone(receipt)
            // RECOVER has no config extras. Production checks the still-false upload consent before
            // creating a run/trace/session. The state message transition acknowledges command handling.
            val before = state
            check(!before.config.allowScreenUpload && before.pendingAction == null, Reason.UPLOAD_CONSENT)
            RootPilotService.send(context, RootPilotService.ACTION_RECOVER)
            withTimeout(5_000) {
                while (state.errorMessage == before.errorMessage) {
                    checkRecovered(receipt, base)
                    delay(POLL)
                }
            }
            // Only compare the fixed local rejection text; never serialize UI/error text.
            check(state.errorMessage == "发送截图前请先打开上传确认", Reason.RECOVER_NOT_REJECTED)
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkRecovered(receipt, base)
                assertGone(receipt)
                delay(POLL)
            }
            verificationComplete = true
        }

        private fun checkWaiting(record: RunHistoryRecord, config: RootPilotConfig) {
            val current = state
            check(current.running && current.status == RootPilotStatus.WAITING_CONFIRMATION && current.step == 0 &&
                current.config == config && (current.pendingAction as? RootPilotAction.OpenApp)?.packageName == CALCULATOR,
                Reason.WAITING_IDENTITY)
            check(current.frame == null && current.savedTodos.isEmpty() && !current.modelReportedResult,
                Reason.UNEXPECTED_ACTIVITY)
            checkBootstrap(record, RunHistoryStatus.RUNNING)
            val logs = current.logs.map { JSON.parseToJsonElement(it).jsonObject }
                .filter { it["runId"]?.jsonPrimitive?.contentOrNull == record.id }
            check(logs.map { it["event"]?.jsonPrimitive?.contentOrNull } == listOf("run_start", "waiting"),
                Reason.UNEXPECTED_ACTIVITY)
            ownSnapshot()
        }

        private fun checkBootstrap(record: RunHistoryRecord, status: RunHistoryStatus) {
            check(record.status == status && !record.eventsTruncated &&
                record.events.map { it.event } == listOf(TraceEvent.RUN_START, TraceEvent.WAITING) &&
                record.events.all { it.runId == record.id } && record.events.last().let {
                    it.stage == TraceStage.APPROVAL && it.status == TraceStatus.WAITING &&
                        it.actionType == TraceActionType.OPEN_APP && it.step == 0
                }, Reason.UNEXPECTED_ACTIVITY)
        }

        private fun checkRecovered(receipt: Ready, base: Baseline) {
            checkReceiptValid()
            val current = state
            check(!current.running && current.status == RootPilotStatus.RECOVERY_REQUIRED && current.step == 0 &&
                current.pendingAction == null && current.frame == null && current.savedTodos.isEmpty() &&
                !current.modelReportedResult && current.logs.isEmpty(), Reason.RECOVERY_STATE)
            check(!current.config.allowScreenUpload && current.config ==
                (loadedApi ?: fail(Reason.SAVED_CONFIG)).applyTo(testConfig().copy(allowScreenUpload = false)) &&
                current.apiConfigured, Reason.UPLOAD_CONSENT)
            checkSnapshotDigest(receipt)
            check(currentIme() == base.originalIme && !imeFile.exists(), Reason.IME_CHANGED)
            checkApi(base)
            checkSelectionInstalled()
            check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
            val records = diskHistory()
            val record = records.singleOrNull { it.id == receipt.runId } ?: fail(Reason.RUN_IDENTITY)
            checkBootstrap(record, RunHistoryStatus.INTERRUPTED)
            check(historyDigest(records, receipt.runId) == receipt.historyDigest && history.value.records == records,
                Reason.UNEXPECTED_ACTIVITY)
        }

        suspend fun cleanup(prepare: Boolean) {
            val base = baseline ?: return
            if (prepare) {
                if (startSent) {
                    // No STOP against an unbound user task, including a dispatch that never arrived.
                    ready?.let(::checkSnapshotDigest)
                    if (snapshotFile.exists()) ownSnapshot()
                    val record = history.value.records.filter {
                        it.id !in previousIds && it.startedAtEpochMs >= requestedAt
                    }.singleOrNull() ?: fail(Reason.CLEANUP_UNCONFIRMED)
                    check(state.config.task == task() && (preparedRunId == null || preparedRunId == record.id),
                        Reason.CLEANUP_UNCONFIRMED)
                    preparedRunId = record.id
                    val stopRequested = state.running
                    if (stopRequested) RootPilotService.send(context, RootPilotService.ACTION_STOP)
                    withTimeout(20_000) {
                        while (state.running || state.status !in setOf(RootPilotStatus.STOPPED, RootPilotStatus.FAILED) ||
                            snapshotFile.exists() || privateDisplays().isNotEmpty() ||
                            !historySettled(record.id, if (state.status == RootPilotStatus.STOPPED)
                                RunHistoryStatus.STOPPED else RunHistoryStatus.FAILED, requireRunEnd = true)) {
                            check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
                            delay(POLL)
                        }
                    }
                    check(!stopRequested || state.status == RootPilotStatus.STOPPED, Reason.CLEANUP_UNCONFIRMED)
                    ready?.let(::assertGone)
                }
            } else {
                // A failed verification never discards a snapshot. The one-shot claim and backup
                // remain available for manual diagnosis; a retry cannot turn that failure into PASS.
                if (!verifyClaimed || !verificationComplete) return
                val receipt = ready ?: fail(Reason.RECEIPT_ID)
                checkRecovered(receipt, base)
                assertGone(receipt)
                RootPilotService.send(context, RootPilotService.ACTION_DISCARD_RECOVERY)
                withTimeout(5_000) {
                    while (state.status != RootPilotStatus.IDLE || snapshotFile.exists()) delay(POLL)
                }
                check(!state.running && state.pendingAction == null && !state.config.allowScreenUpload &&
                    state.logs.isEmpty(), Reason.DISCARD_FAILED)
                assertGone(receipt)
            }
            check(!state.running && !snapshotFile.exists() && privateDisplays().isEmpty(), Reason.CLEANUP_UNCONFIRMED)
            check(currentIme() == base.originalIme && !imeFile.exists(), Reason.IME_CHANGED)
            checkApi(base)
            if (configChanged || verifyClaimed) {
                val expected = (loadedApi ?: fail(Reason.SAVED_CONFIG)).applyTo(
                    if (!startSent && prepare) originalConfig(base) else testConfig().copy(allowScreenUpload = prepare),
                )
                check(state.config == expected && state.apiConfigured, Reason.ENVIRONMENT_CHANGED)
            }
            if (selectionChanged || verifyClaimed) {
                checkSelectionInstalled()
                checkSelectionBackup(base)
                if (base.selectionExisted) write(selection, readBounded(directory.resolve("allowlist.original"), MAX_FILE))
                else {
                    check(selection.delete(), Reason.RESTORE_FAILED)
                    syncDirectory(selection.parentFile!!)
                }
            }
            checkSelectionOriginal(base)
            if (configChanged || verifyClaimed) {
                val original = originalConfig(base)
                RootPilotService.updateConfig(original)
                RootPilotService.updateApiConfig(if (base.apiConfigured) savedApi() else null)
                check(state.config == original && state.apiConfigured == base.apiConfigured, Reason.RESTORE_FAILED)
            }
            if (!prepareReceiptInvalidated) {
                write(directory.resolve("completed"), byteArrayOf(1))
                // A failed prepare retains its baseline and private backup even after restoration.
                if (base.selectionExisted) check(directory.resolve("allowlist.original").delete(), Reason.RESTORE_FAILED)
            }
            restored = true
        }

        private fun originalConfig(base: Baseline) =
            (if (base.apiConfigured) savedApi() else RootPilotApiConfig()).applyTo(RootPilotConfig(
                manualConfirmation = base.manualConfirmation, allowScreenUpload = base.allowScreenUpload,
            ))

        private fun testConfig() = RootPilotConfig(task = task(), manualConfirmation = true, allowScreenUpload = true,
            executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = CALCULATOR)

        private fun task() = "RootPilot 副屏进程恢复测试 $id；等待启动确认，不批准任何动作。"

        private fun ownSnapshot(): RootPilotRunSnapshot {
            val snapshot = RootPilotRunStore(snapshotFile).read() ?: fail(Reason.SNAPSHOT_IDENTITY)
            check(snapshot.task == task() && snapshot.manualConfirmation && snapshot.executionDisplay == ExecutionDisplay.VIRTUAL &&
                snapshot.virtualDisplayStartPackage == CALCULATOR && snapshot.step == 0, Reason.SNAPSHOT_IDENTITY)
            return snapshot
        }

        private fun checkSnapshotDigest(receipt: Ready) {
            check(digest(readBounded(snapshotFile, MAX_FILE)) == receipt.snapshotDigest, Reason.SNAPSHOT_CHANGED)
            ownSnapshot()
        }

        private fun privateDisplays() = manager.displays.filter { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }

        private fun bindDisplay(): OwnedDisplay {
            val display = privateDisplays().singleOrNull() ?: fail(Reason.DISPLAY_IDENTITY)
            val session = display.name.removePrefix(VirtualDisplayProtocol.DISPLAY_PREFIX)
            val size = Point().also(display::getRealSize)
            check(validId(session) && display.displayId > 0 && display.isValid && display.rotation == Surface.ROTATION_0 &&
                size.x == VirtualDisplayProtocol.WIDTH && size.y == VirtualDisplayProtocol.HEIGHT, Reason.DISPLAY_IDENTITY)
            return OwnedDisplay(display.displayId, session)
        }

        private fun assertGone(receipt: Ready) {
            check(privateDisplays().isEmpty() && manager.getDisplay(receipt.displayId) == null, Reason.DISPLAY_REMAINING)
            check(pidAbsent(receipt.helperPid), Reason.HELPER_REMAINING)
        }

        private suspend fun helperProcesses(): List<HelperProcess> = withContext(Dispatchers.IO) {
            // Fixed read-only command; no shell interpolation, arbitrary task text or raw output.
            val process = ProcessBuilder("su", "-c", "/system/bin/timeout 3 /system/bin/ps -A -o PID,ARGS")
                .redirectError(File("/dev/null")).start()
            val reader = Executors.newSingleThreadExecutor { Thread(it, "RootPilot-RecoveryPid").apply { isDaemon = true } }
            val output = reader.submit<ByteArray> { process.inputStream.readNBytes(512 * 1024 + 1) }
            try {
                process.outputStream.close()
                check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, Reason.HELPER_QUERY)
                val bytes = output.get(1, TimeUnit.SECONDS)
                check(bytes.size <= 512 * 1024, Reason.HELPER_QUERY)
                val lines = bytes.decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
                check(lines.firstOrNull()?.trim()?.split(Regex("\\s+")) == listOf("PID", "ARGS"), Reason.HELPER_QUERY)
                val matches = lines.drop(1).map { it.trim().split(Regex("\\s+")) }.filter {
                    VirtualDisplayProtocol.ENTRY in it.drop(1)
                }
                matches.map(::parseHelperProcess)
            } finally {
                process.destroyForcibly()
                output.cancel(true)
                reader.shutdownNow()
                process.inputStream.close()
                process.errorStream.close()
            }
        }

        private fun diskHistory(): List<RunHistoryRecord> {
            if (!historyFile.exists()) return emptyList()
            val document = JSON.parseToJsonElement(readBounded(historyFile, 8 * 1024 * 1024).decodeToString()).jsonObject
            return document["records"]?.jsonArray?.map { JSON.decodeFromJsonElement<RunHistoryRecord>(it) }
                ?: fail(Reason.HISTORY_UNAVAILABLE)
        }

        private fun historySettled(runId: String, status: RunHistoryStatus, requireRunEnd: Boolean = false): Boolean {
            val memory = history.value.records.singleOrNull { it.id == runId } ?: return false
            val disk = diskHistory().singleOrNull { it.id == runId } ?: return false
            // record(RUN_END), finish(status) and persistence are separate asynchronous commands.
            return memory == disk && disk.status == status &&
                (!requireRunEnd || disk.events.any { it.event == TraceEvent.RUN_END })
        }

        private fun historyDigest(records: List<RunHistoryRecord>, runId: String): String = digest(
            JSON.encodeToString(records.map { if (it.id == runId) it.copy(status = RunHistoryStatus.RUNNING) else it }).toByteArray(),
        )

        private fun savedApi() = (RootPilotApiConfigStore.create(context).read() ?: fail(Reason.SAVED_CONFIG)).also {
            check(it.apiKey.isNotBlank() && it.model.isNotBlank(), Reason.SAVED_CONFIG)
        }

        private fun checkApi(base: Baseline) =
            check(digest(readBounded(apiFile, MAX_FILE)) == base.apiDigest, Reason.API_CONFIG_CHANGED)

        private fun currentIme() = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()

        private fun checkSelectionInstalled() =
            check(readBounded(selection, MAX_FILE).contentEquals(INSTALLED_SELECTION), Reason.ALLOWLIST_CHANGED)

        private fun checkSelectionBackup(base: Baseline) {
            if (base.selectionExisted) check(digest(readBounded(directory.resolve("allowlist.original"), MAX_FILE)) ==
                base.selectionDigest, Reason.BACKUP_CHANGED)
            else check(!directory.resolve("allowlist.original").exists(), Reason.BACKUP_CHANGED)
        }

        private fun checkSelectionOriginal(base: Baseline) {
            check(selection.exists() == base.selectionExisted, Reason.ALLOWLIST_CHANGED)
            if (base.selectionExisted) check(digest(readBounded(selection, MAX_FILE)) == base.selectionDigest, Reason.ALLOWLIST_CHANGED)
        }

        fun report(stage: String, failure: Reason?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("virtualProcessRecoveryStage", stage)
                putString("reasonCode", failure?.name ?: "NONE")
                if (::id.isInitialized && validId(id)) putString("recoveryReceiptId", id)
                putInt("mainPid", Process.myPid())
                putBoolean("passed", verificationComplete && restored && failure == null)
                putBoolean("baselineRestored", restored)
                putBoolean("verifyClaimed", verifyClaimed)
                ready?.let {
                    putString("runId", it.runId)
                    putInt("ownedDisplayId", it.displayId)
                    putString("sessionId", it.session)
                    putInt("helperPid", it.helperPid)
                    putBoolean("helperSessionArgumentMatched", it.helperSessionArgumentMatched)
                    if (stage == "ready_for_main_process_kill" || verificationComplete) {
                        putInt("executedActions", 0)
                        putInt("screenshots", 0)
                        putInt("modelRequests", 0)
                    }
                }
                if (stage == "ready_for_main_process_kill") putLong("hostKillWindowMs", HOST_KILL_WINDOW)
            })
        }
    }

    @Serializable
    private data class Baseline(
        val id: String, val mainPid: Int, val bootCount: Int, val apiConfigured: Boolean,
        val manualConfirmation: Boolean, val allowScreenUpload: Boolean, val originalIme: String,
        val apiDigest: String, val selectionExisted: Boolean, val selectionDigest: String?,
    )

    @Serializable
    private data class Ready(
        val id: String, val runId: String, val displayId: Int, val session: String, val helperPid: Int,
        val helperSessionArgumentMatched: Boolean,
        val readyAt: Long, val snapshotDigest: String, val historyDigest: String,
    )

    private data class OwnedDisplay(val id: Int, val session: String)
    private data class HelperProcess(val pid: Int, val session: String?)

    private enum class Reason {
        TARGET_PROCESS, NO_RESTART_REQUIRED, RECEIPT_ID, BASELINE_ACK_REQUIRED, SERVICE_BUSY,
        EXISTING_RECOVERY, IME_RECOVERY, BASELINE_UNRESTORABLE, LOCKED, OVERLAY_PERMISSION,
        EXISTING_DISPLAY, EXISTING_HELPER, CALCULATOR, SAVED_CONFIG, BOOT_IDENTITY, RECEIPT_IO, RECEIPT_REUSED,
        UNFINISHED_RECEIPT, HISTORY_UNAVAILABLE, ENVIRONMENT_CHANGED, RUN_IDENTITY, PREPARE_FAILED,
        SNAPSHOT_IDENTITY, WAITING_IDENTITY, UNEXPECTED_ACTIVITY, DISPLAY_IDENTITY, HELPER_IDENTITY,
        HELPER_QUERY, HELPER_EXITED_BEFORE_MAIN, HOST_KILL_TIMEOUT, MAIN_PROCESS_NOT_DEAD, RECEIPT_EXPIRED, RECOVERY_STATE,
        RECEIPT_INVALIDATED, RECEIPT_INVALIDATION_FAILED,
        UPLOAD_CONSENT, RECOVER_NOT_REJECTED, IME_CHANGED, SNAPSHOT_CHANGED, API_CONFIG_CHANGED,
        ALLOWLIST_CHANGED, BACKUP_CHANGED, DISPLAY_REMAINING, HELPER_REMAINING, CLEANUP_UNCONFIRMED,
        DISCARD_FAILED, RESTORE_FAILED, REPORT_FAILED, PARSER_FIXTURE_FAILED, TIMEOUT, UNEXPECTED,
    }

    private class RecoveryFailure(val reason: Reason) : AssertionError(reason.name)

    private companion object {
        const val TARGET = "com.example.agent"
        const val CALCULATOR = "com.miui.calculator"
        const val CALCULATOR_ACTIVITY = "com.miui.calculator.cal.CalculatorActivity"
        const val MAX_FILE = 64 * 1024
        const val POLL = 50L
        const val HOST_KILL_WINDOW = 45_000L
        const val VERIFY_WINDOW = 120_000L
        const val QUIET_WINDOW = 1_000L
        val IDLE_STATES = setOf(RootPilotStatus.IDLE, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)
        val JSON = Json { encodeDefaults = true }
        val INSTALLED_SELECTION = JSON.encodeToString(listOf(CALCULATOR)).toByteArray()

        fun parseHelperProcess(fields: List<String>): HelperProcess {
            val pid = fields.firstOrNull()?.toIntOrNull() ?: fail(Reason.HELPER_IDENTITY)
            check(pid > 0, Reason.HELPER_IDENTITY)
            val arguments = fields.drop(1)
            val session = when {
                arguments.size == 4 && arguments[0] in setOf("app_process", "/system/bin/app_process") &&
                    arguments[1] == "/system/bin" && arguments[2] == VirtualDisplayProtocol.ENTRY &&
                    validId(arguments[3]) -> arguments[3]
                arguments.size in 1..2 && arguments[0] == VirtualDisplayProtocol.ENTRY &&
                    (arguments.size == 1 || validId(arguments[1])) -> arguments.getOrNull(1)
                else -> fail(Reason.HELPER_IDENTITY)
            }
            return HelperProcess(pid, session)
        }

        fun bindHelperProcess(candidates: List<HelperProcess>, session: String): HelperProcess {
            val helper = candidates.singleOrNull() ?: fail(Reason.HELPER_IDENTITY)
            check(helper.session == null || helper.session == session, Reason.HELPER_IDENTITY)
            return helper
        }

        fun fail(reason: Reason): Nothing = throw RecoveryFailure(reason)
        fun check(value: Boolean, reason: Reason) { if (!value) fail(reason) }
        fun reason(error: Throwable) = when (error) {
            is RecoveryFailure -> error.reason
            is TimeoutCancellationException -> Reason.TIMEOUT
            else -> Reason.UNEXPECTED
        }
        fun validId(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun readBounded(file: File, limit: Int): ByteArray {
            check(file.isFile && !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT_IO)
            return file.inputStream().use { it.readNBytes(limit + 1) }.also { check(it.size <= limit, Reason.RECEIPT_IO) }
        }
        fun pidAbsent(pid: Int): Boolean {
            check(pid > 0, Reason.HELPER_IDENTITY)
            return try { Os.kill(pid, 0); false } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.ESRCH -> true
                    OsConstants.EPERM -> false
                    else -> fail(Reason.HELPER_QUERY)
                }
            }
        }
        fun write(file: File, bytes: ByteArray) {
            val parent = file.parentFile ?: fail(Reason.RECEIPT_IO)
            val temporary = Files.createTempFile(parent.toPath(), "recovery-", ".tmp")
            try {
                temporary.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(parent)
            } finally { Files.deleteIfExists(temporary) }
        }
        fun syncDirectory(directory: File) {
            check(directory.isDirectory, Reason.RECEIPT_IO)
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
