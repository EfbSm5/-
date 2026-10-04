package com.example.agent.rootpilot

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.input.FileImeRestoreStore
import com.example.agent.rootpilot.input.InputConnectionBridge
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.SuRootExecutor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production Service / saved API / IME, with no approval or text injection by the test.
 * Invoke each method separately with --no-restart, typeProcessRecovery=true,
 * recoveryReceiptId=<fresh UUID>, expectedRootPilotPid=<host-verified current main PID>.
 * Prepare also requires recoveryBaseline=empty_task_saved_api_or_defaults and a normally
 * foreground RootPilotActivity. Prepare opens the signed fixture once; neither phase reopens
 * it after binding its identity, and verify never launches any Activity.
 *
 * After ready_for_main_process_kill, the host has at most 45 seconds to kill only the old main
 * PID, normally reopen RootPilotActivity, then invoke verify in its new process within 120
 * seconds of readyAt. Prepare dying with that process is expected and is not a test PASS.
 * The fixture Activity instance and process must survive; a newly empty page is not evidence.
 *
 * Private receipts contain identities, flags and digests, never credentials or model/task text.
 * historyDigest hashes the actual ready-time runs.json bytes; interruptedHistoryDigest hashes
 * the same document with only this run's status changed to INTERRUPTED. Current production
 * recovery adds no trace event. Zero post-reopen activity uses complete history and fresh
 * process logs, not network interception. Failed verification retains the one-shot claim and
 * snapshot. Prepare revokes its receipt before normal STOP; uncertain ownership forbids cleanup.
 */
@RunWith(AndroidJUnit4::class)
class TypeProcessRecoveryInstrumentedTest {
    @Test fun prepareServiceAtUnapprovedType() = invoke(prepare = true)

    @Test fun verifyAfterHostKillAndNormalActivityReopen() = invoke(prepare = false)

    private fun invoke(prepare: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typeProcessRecovery") == "true")
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
                        try { test.invalidate(failure!!) }
                        catch (_: Throwable) {
                            test.cleanupReason = Reason.RECEIPT_INVALIDATION_FAILED
                            cleanupAllowed = false
                        }
                    }
                    if (cleanupAllowed) {
                        try { test.cleanup(prepare) }
                        catch (error: Throwable) { test.cleanupReason = reason(error) }
                    }
                    if (failure == null) failure = test.cleanupReason
                    try { test.report(if (failure == null) "verified" else "failed", failure) }
                    catch (_: Throwable) { if (failure == null) failure = Reason.REPORT_FAILED }
                }
            }
            failure?.let(::fail)
        }
    }

    private class Recovery(private val context: Context) {
        private val state get() = RootPilotService.uiState.value
        private val root = context.noBackupFilesDir.resolve("rootpilot-type-process-recovery-test")
        private val apiFile = context.noBackupFilesDir.resolve(RootPilotApiConfigStore.FILE_NAME)
        private val allowlistFile = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        private val snapshotFile = context.filesDir.resolve(RootPilotRunStore.FILE_NAME)
        private val imeFile = context.noBackupFilesDir.resolve("rootpilot_original_ime")
        private val historyFile = context.noBackupFilesDir.resolve("rootpilot_history/runs.json")
        private val ime = AndroidImeEnvironment(context)
        private val fixture = FixtureClient(context)
        private val history by lazy { RootPilotService.historyState(context) }
        private lateinit var id: String
        private val directory get() = root.resolve(id)
        private var directoryOwned = false
        private var baseline: Baseline? = null
        private var ready: Ready? = null
        private var loadedApi: RootPilotApiConfig? = null
        private var previousRecords = emptyList<RunHistoryRecord>()
        private var previousLogIds = emptySet<String>()
        private var requestedAt = Long.MAX_VALUE
        private var runId: String? = null
        private var configChanged = false
        private var startSent = false
        private var boundEditor: InputConnectionBridge.Editor? = null
        private var boundAction: RootPilotAction.Type? = null
        private var claimed = false
        private var verified = false
        private var restored = false
        var cleanupReason: Reason? = null

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
            check(!exists(snapshotFile), Reason.EXISTING_RECOVERY)
            checkImeFileAbsent()
            check(original.config.task.isEmpty() && original.config.executionDisplay == ExecutionDisplay.MAIN &&
                original.config.virtualDisplayStartPackage.isEmpty(), Reason.BASELINE_UNRESTORABLE)
            checkMainUnlocked()
            check(Settings.canDrawOverlays(context), Reason.OVERLAY_PERMISSION)
            fixed(Reason.FIXTURE_IDENTITY) { fixture.verifyIdentity() }
            fixed(Reason.FIXTURE_STATE) { fixture.launch() }
            val fixtureState = try { fixture.awaitReady() } catch (_: Throwable) { fail(Reason.FIXTURE_STATE) }
            val fixtureId = fixtureState.getString("instanceId").orEmpty()
            val fixturePid = fixtureState.getInt("processId")
            check(validId(fixtureId) && fixturePid > 0 && fixturePid != Process.myPid(), Reason.FIXTURE_IDENTITY)
            check(fixtureState.getBoolean("ready") && fixtureState.getBoolean("empty") &&
                fixtureState.getInt("fieldId") == android.R.id.edit, Reason.FIXTURE_STATE)
            val observation = SuRootExecutor().observeScreen()
            check(observation.displayId == Display.DEFAULT_DISPLAY && observation.sessionId == null &&
                observation.foregroundPackage == FIXTURE && observation.focusedPackage == FIXTURE &&
                observation.foregroundActivity == "$FIXTURE.ExecutionFixtureActivity", Reason.FIXTURE_FOREGROUND)
            val originalIme = ime.currentId().orEmpty()
            check(originalIme.isNotBlank() && originalIme.length <= 512 && originalIme != ime.ownId &&
                ime.isEnabled(originalIme) && ime.isEnabled(ime.ownId), Reason.IME_UNAVAILABLE)
            val saved = savedApi().also { loadedApi = it }
            val rebuilt = (if (original.apiConfigured) saved else RootPilotApiConfig()).applyTo(original.config)
            check(rebuilt == original.config, Reason.BASELINE_UNRESTORABLE)
            val boot = bootCount()
            check(boot >= 0, Reason.BOOT_IDENTITY)
            check(!Files.isSymbolicLink(root.toPath()) && (!exists(root) || root.isDirectory), Reason.RECEIPT_IO)
            if (exists(root)) {
                val entries = root.listFiles() ?: fail(Reason.RECEIPT_IO)
                check(entries.all { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && validId(it.name) &&
                    it.resolve("completed").isFile && !exists(it.resolve("invalidated")) &&
                    !exists(it.resolve("invalidated-ready.json")) }, Reason.UNFINISHED_RECEIPT)
            }
            check(!exists(directory), Reason.RECEIPT_REUSED)
            withTimeout(5_000) {
                while (history.value.error == null && history.value.records != diskHistory().document.records) delay(POLL)
            }
            check(history.value.error == null && history.value.records.none { it.status == RunHistoryStatus.RUNNING },
                Reason.HISTORY_UNAVAILABLE)
            // Production retains 50 runs; do not evict someone else's oldest record for this test.
            check(history.value.records.size < 50, Reason.HISTORY_CAPACITY)
            previousRecords = history.value.records
            previousLogIds = logs().map { it.runId }.toSet()
            val base = Baseline(id, Process.myPid(), Process.myUid(), boot, original.apiConfigured,
                original.config.manualConfirmation, original.config.allowScreenUpload, originalIme,
                fileDigest(apiFile, Reason.API_CHANGED), optionalDigest(allowlistFile, Reason.ALLOWLIST_CHANGED),
                fixtureId, fixturePid)
            check(state === original && !exists(snapshotFile), Reason.ENVIRONMENT_CHANGED)
            if (!exists(root)) {
                check(root.mkdir(), Reason.RECEIPT_IO)
                syncDirectory(root.parentFile!!)
            }
            check(directory.mkdir(), Reason.RECEIPT_IO)
            directoryOwned = true
            syncDirectory(root)
            write(directory.resolve("baseline.json"), JSON.encodeToString(base).encodeToByteArray())
            baseline = base
            checkUnchangedFiles(base)
            checkMainUnlocked()
            checkFixture(base, foreground = true)
            check(state === original && !exists(snapshotFile) && ime.currentId() == originalIme, Reason.ENVIRONMENT_CHANGED)
            checkImeFileAbsent()
            configChanged = true
            RootPilotService.updateApiConfig(saved)
            requestedAt = System.currentTimeMillis()
            startSent = true
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, testConfig())
            withTimeout(10_000) {
                while (bindRun() == null) {
                    check(!state.running || state.config == testConfig(), Reason.RUN_IDENTITY)
                    delay(POLL)
                }
            }
            withTimeout(180_000) {
                while (true) {
                    val owned = bindRun() ?: fail(Reason.RUN_IDENTITY)
                    check(state.config == testConfig() && state.status in PREPARING_STATES && state.step == 0,
                        Reason.UNEXPECTED_ACTIVITY)
                    checkMainUnlocked()
                    checkFixture(base, foreground = true)
                    checkLogPrefix(owned)
                    val disk = diskHistory()
                    val record = disk.document.records.singleOrNull { it.id == owned }
                    if (state.status == RootPilotStatus.WAITING_CONFIRMATION) {
                        checkPendingType()
                        if (record != null && record.events.size == EXPECTED_TRACE.size &&
                            history.value.records == disk.document.records &&
                            ownSnapshot().status == RootPilotStatus.WAITING_CONFIRMATION.name) break
                    }
                    delay(POLL)
                }
            }
            val owned = runId ?: fail(Reason.RUN_IDENTITY)
            val disk = diskHistory()
            checkCompleteHistory(disk.document.records.single { it.id == owned }, RunHistoryStatus.RUNNING)
            val interrupted = disk.document.copy(records = disk.document.records.map {
                if (it.id == owned) it.copy(status = RunHistoryStatus.INTERRUPTED) else it
            })
            val receipt = Ready(id, owned, Process.myPid(), Process.myUid(), boot, SystemClock.elapsedRealtime(),
                fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED), disk.digest,
                digest(JSON.encodeToString(interrupted).encodeToByteArray()),
                fileDigest(imeFile, Reason.IME_RECOVERY), fixtureId, fixturePid,
                fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO))
            ready = receipt
            checkPrepared(base, receipt)
            write(directory.resolve("ready.json"), JSON.encodeToString(receipt).encodeToByteArray())
            checkReceiptValid()
            checkPrepared(base, receipt)
            report("ready_for_main_process_kill", null)
            while (SystemClock.elapsedRealtime() - receipt.readyAt < HOST_KILL_WINDOW) {
                checkReceiptValid()
                checkPrepared(base, receipt)
                delay(POLL)
            }
            fail(Reason.HOST_KILL_TIMEOUT)
        }

        private fun bindRun(): String? {
            check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
            val newRecords = history.value.records.filter { candidate -> previousRecords.none { it.id == candidate.id } }
            check(newRecords.size <= 1 && newRecords.all { it.startedAtEpochMs >= requestedAt }, Reason.RUN_IDENTITY)
            val logIds = logs().map { it.runId }.filterNot { it in previousLogIds }.distinct()
            val ids = (logIds + newRecords.map { it.id }).distinct()
            check(ids.size <= 1, Reason.RUN_IDENTITY)
            val found = ids.singleOrNull() ?: return null
            check(validId(found) && (runId == null || runId == found), Reason.RUN_IDENTITY)
            check(history.value.records.filter { it.id != found } == previousRecords, Reason.RUN_IDENTITY)
            runId = found
            return found
        }

        private suspend fun checkPendingType() {
            val current = state
            val action = current.pendingAction as? RootPilotAction.Type ?: fail(Reason.TYPE_IDENTITY)
            check(action.text == EXPECTED && action.reason.startsWith("输入到 $FIXTURE：") &&
                (boundAction == null || action === boundAction), Reason.TYPE_IDENTITY)
            check(current.running && current.status == RootPilotStatus.WAITING_CONFIRMATION && current.step == 0 &&
                current.config == testConfig() && current.frame != null && current.savedTodos.isEmpty() &&
                !current.modelReportedResult, Reason.WAITING_IDENTITY)
            withContext(Dispatchers.Main.immediate) {
                val editor = InputConnectionBridge.editor.value ?: fail(Reason.EDITOR_IDENTITY)
                check(editor.info.packageName == FIXTURE && editor.info.fieldId == android.R.id.edit &&
                    editor.owner.currentInputConnection === editor.connection &&
                    !InputConnectionBridge.isPassword(editor.info.inputType) && editor.info.inputType != 0 &&
                    (boundEditor == null || editor === boundEditor), Reason.EDITOR_IDENTITY)
                boundEditor = editor
            }
            boundAction = action
        }

        private suspend fun checkPrepared(base: Baseline, receipt: Ready) {
            checkMainUnlocked()
            checkFixture(base, foreground = true)
            checkPendingType()
            check(ime.currentId() == ime.ownId && fixed(Reason.IME_RECOVERY) { FileImeRestoreStore(context).read() } ==
                base.originalIme && !imeSidecarsExist(), Reason.IME_RECOVERY)
            check(fileDigest(imeFile, Reason.IME_RECOVERY) == receipt.imeRecoveryDigest, Reason.IME_RECOVERY)
            val snapshot = ownSnapshot()
            check(snapshot.status == RootPilotStatus.WAITING_CONFIRMATION.name && snapshot.step == 0 &&
                snapshot.actionSummary == "type(length=${EXPECTED.length})" && snapshot.allowScreenUpload,
                Reason.SNAPSHOT_IDENTITY)
            checkSnapshotDigest(receipt)
            checkUnchangedFiles(base)
            val disk = diskHistory()
            val record = disk.document.records.singleOrNull { it.id == receipt.runId } ?: fail(Reason.RUN_IDENTITY)
            checkCompleteHistory(record, RunHistoryStatus.RUNNING)
            check(history.value.error == null && history.value.records == disk.document.records &&
                disk.digest == receipt.historyDigest, Reason.HISTORY_CHANGED)
            checkLogPrefix(receipt.runId, complete = true)
        }

        suspend fun verify() {
            checkReceiptValid()
            check(!exists(directory.resolve("claimed")) && !exists(directory.resolve("completed")), Reason.RECEIPT_REUSED)
            val base = fixed(Reason.RECEIPT_IO) {
                JSON.decodeFromString<Baseline>(readBounded(directory.resolve("baseline.json"), MAX_FILE).decodeToString())
            }
            val receipt = fixed(Reason.RECEIPT_IO) {
                JSON.decodeFromString<Ready>(readBounded(directory.resolve("ready.json"), MAX_FILE).decodeToString())
            }
            check(base.id == id && receipt.id == id && validId(receipt.runId) &&
                validId(base.fixtureInstanceId) && receipt.fixtureInstanceId == base.fixtureInstanceId &&
                receipt.fixturePid == base.fixturePid && base.fixturePid > 0 && base.fixturePid != base.mainPid &&
                receipt.mainPid == base.mainPid && receipt.appUid == base.appUid && base.appUid == Process.myUid() &&
                receipt.bootCount == base.bootCount &&
                listOf(receipt.snapshotDigest, receipt.historyDigest, receipt.interruptedHistoryDigest,
                    receipt.imeRecoveryDigest, receipt.baselineDigest, base.apiDigest).all(::validDigest) &&
                (base.allowlistDigest == null || validDigest(base.allowlistDigest)), Reason.RECEIPT_ID)
            check(fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO) == receipt.baselineDigest,
                Reason.RECEIPT_CHANGED)
            check(base.mainPid > 0 && base.mainPid != Process.myPid() && pidAbsent(base.mainPid), Reason.MAIN_NOT_DEAD)
            check(bootCount() == receipt.bootCount && SystemClock.elapsedRealtime() - receipt.readyAt in 0..VERIFY_WINDOW,
                Reason.RECEIPT_EXPIRED)
            // Claim before assertions: an unsuccessful verification cannot later be replayed as PASS.
            marker("claimed")
            claimed = true
            baseline = base
            ready = receipt
            runId = receipt.runId
            fixed(Reason.FIXTURE_IDENTITY) { fixture.verifyIdentity() }
            checkUnchangedFiles(base)
            loadedApi = savedApi()
            checkSnapshotDigest(receipt)
            withTimeout(8_000) {
                while (true) {
                    check(!state.running && state.logs.isEmpty() && state.pendingAction == null && state.frame == null,
                        Reason.RECOVERY_STATE)
                    check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
                    checkFixture(base, foreground = false)
                    checkMainUnlocked()
                    if (state.status == RootPilotStatus.RECOVERY_REQUIRED && ime.currentId() == base.originalIme &&
                        !exists(imeFile) && history.value.records == diskHistory().document.records &&
                        history.value.records.singleOrNull { it.id == receipt.runId }?.status == RunHistoryStatus.INTERRUPTED) break
                    delay(POLL)
                }
            }
            checkRecovered(base, receipt)
            val before = state
            check(before.errorMessage != UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
            RootPilotService.send(context, RootPilotService.ACTION_RECOVER)
            withTimeout(5_000) {
                while (state.errorMessage != UPLOAD_REJECTION) {
                    checkRecovered(base, receipt)
                    delay(POLL)
                }
            }
            val until = SystemClock.elapsedRealtime() + QUIET_WINDOW
            while (SystemClock.elapsedRealtime() < until) {
                checkRecovered(base, receipt)
                check(state.errorMessage == UPLOAD_REJECTION, Reason.RECOVER_NOT_REJECTED)
                delay(POLL)
            }
            verified = true
        }

        private suspend fun checkRecovered(base: Baseline, receipt: Ready) {
            checkReceiptValid()
            checkMainUnlocked()
            checkFixture(base, foreground = false)
            val current = state
            check(!current.running && current.status == RootPilotStatus.RECOVERY_REQUIRED && current.step == 0 &&
                current.pendingAction == null && current.lastAction == null && current.frame == null &&
                current.savedTodos.isEmpty() && !current.modelReportedResult && current.logs.isEmpty(), Reason.RECOVERY_STATE)
            check(current.apiConfigured && !current.config.allowScreenUpload &&
                current.config == testConfig().copy(allowScreenUpload = false), Reason.UPLOAD_CONSENT)
            check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED)
            checkImeFileAbsent()
            checkSnapshotDigest(receipt)
            checkUnchangedFiles(base)
            val disk = diskHistory()
            val record = disk.document.records.singleOrNull { it.id == receipt.runId } ?: fail(Reason.RUN_IDENTITY)
            checkCompleteHistory(record, RunHistoryStatus.INTERRUPTED)
            check(history.value.error == null && history.value.records == disk.document.records &&
                disk.digest == receipt.interruptedHistoryDigest, Reason.HISTORY_CHANGED)
        }

        fun invalidate(failure: Reason) {
            if (!directoryOwned) return
            try {
                write(directory.resolve("invalidated"), failure.name.encodeToByteArray())
                check(readBounded(directory.resolve("invalidated"), MAX_FILE).decodeToString() == failure.name,
                    Reason.RECEIPT_INVALIDATION_FAILED)
            } catch (_: Throwable) {
                // A durable rename also revokes a ready receipt when allocating a marker fails.
                check(exists(directory.resolve("ready.json")), Reason.RECEIPT_INVALIDATION_FAILED)
                fixed(Reason.RECEIPT_INVALIDATION_FAILED) {
                    Files.move(directory.resolve("ready.json").toPath(), directory.resolve("invalidated-ready.json").toPath(),
                        StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(directory)
                }
            }
        }

        suspend fun cleanup(prepare: Boolean) {
            val base = baseline ?: return
            if (!prepare && (!claimed || !verified)) return
            var failed: Reason? = null
            suspend fun attempt(block: suspend () -> Unit): Boolean = try { block(); true } catch (error: Throwable) {
                if (failed == null) failed = reason(error)
                false
            }
            val stopped = attempt {
                if (prepare) stopOwnedPrepare()
                else {
                    val receipt = ready ?: fail(Reason.RECEIPT_ID)
                    checkRecovered(base, receipt)
                    RootPilotService.send(context, RootPilotService.ACTION_DISCARD_RECOVERY)
                    withTimeout(5_000) {
                        while (state.status != RootPilotStatus.IDLE || exists(snapshotFile)) {
                            check(!state.running && state.config == testConfig().copy(allowScreenUpload = false),
                                Reason.DISCARD_FAILED)
                            delay(POLL)
                        }
                    }
                    check(!state.running && state.pendingAction == null && state.frame == null && state.logs.isEmpty(),
                        Reason.DISCARD_FAILED)
                }
            }
            // Independent readbacks still run if STOP or one resource's restoration failed.
            attempt { check(ime.currentId() == base.originalIme, Reason.IME_NOT_RESTORED); checkImeFileAbsent() }
            attempt { checkMainUnlocked() }
            attempt { checkFixture(base, foreground = false) }
            attempt { checkUnchangedFiles(base) }
            if (stopped) attempt {
                check(!state.running && !exists(snapshotFile) && state.pendingAction == null, Reason.CLEANUP_UNCONFIRMED)
                checkUnchangedFiles(base)
                val expected = if (startSent || claimed) testConfig().copy(allowScreenUpload = prepare)
                    else savedApi().applyTo(originalConfig(base))
                check(!configChanged && !claimed || state.config == expected, Reason.ENVIRONMENT_CHANGED)
                if (configChanged || claimed) {
                    val original = originalConfig(base)
                    RootPilotService.updateConfig(original)
                    RootPilotService.updateApiConfig(if (base.apiConfigured) savedApi() else null)
                    check(state.config == original && state.apiConfigured == base.apiConfigured, Reason.RESTORE_FAILED)
                }
                restored = true
            }
            if (failed != null) fail(failed!!)
            if (!prepare) {
                check(restored && verified, Reason.CLEANUP_UNCONFIRMED)
                marker("completed")
            }
        }

        private suspend fun stopOwnedPrepare() {
            if (!startSent) return
            val owned = bindRun() ?: fail(Reason.CLEANUP_UNCONFIRMED)
            check(state.config == testConfig() && logs().lastOrNull()?.runId == owned, Reason.CLEANUP_UNCONFIRMED)
            if (exists(snapshotFile)) ownSnapshot()
            if (state.running) RootPilotService.send(context, RootPilotService.ACTION_STOP)
            withTimeout(20_000) {
                while (true) {
                    check(state.config == testConfig() && bindRun() == owned, Reason.CLEANUP_UNCONFIRMED)
                    val disk = diskHistory().document.records
                    val record = disk.singleOrNull { it.id == owned }
                    if (!state.running && state.status in TERMINAL_STATES && !exists(snapshotFile) &&
                        history.value.records == disk && record != null && record.status != RunHistoryStatus.RUNNING &&
                        record.events.any { it.event == TraceEvent.RUN_END }) break
                    delay(POLL)
                }
            }
        }

        private fun originalConfig(base: Baseline) =
            (if (base.apiConfigured) savedApi() else RootPilotApiConfig()).applyTo(RootPilotConfig(
                manualConfirmation = base.manualConfirmation, allowScreenUpload = base.allowScreenUpload,
            ))

        private fun testConfig() = (loadedApi ?: fail(Reason.SAVED_CONFIG)).applyTo(RootPilotConfig(
            task = "RootPilot 固定输入验收 $id。当前专用测试页的第一个输入框已经聚焦。只提出一次输入“$EXPECTED”的 type 动作，等待人工确认，不重复输入。不调用工具，不点击、不滑动、不按键、不等待、不启动应用、不保存、不发送，不提前报告完成。",
            manualConfirmation = true, allowScreenUpload = true,
        ))

        private fun ownSnapshot(): RootPilotRunSnapshot {
            val snapshot = fixed(Reason.SNAPSHOT_IDENTITY) {
                JSON.decodeFromString<RootPilotRunSnapshot>(readBounded(snapshotFile, MAX_FILE).decodeToString())
            }
            val config = testConfig()
            check(snapshot.task == config.task && snapshot.baseUrl == config.baseUrl && snapshot.model == config.model &&
                snapshot.manualConfirmation && snapshot.executionDisplay == ExecutionDisplay.MAIN &&
                snapshot.virtualDisplayStartPackage.isEmpty(), Reason.SNAPSHOT_IDENTITY)
            return snapshot
        }

        private fun checkSnapshotDigest(receipt: Ready) {
            check(fileDigest(snapshotFile, Reason.SNAPSHOT_CHANGED) == receipt.snapshotDigest, Reason.SNAPSHOT_CHANGED)
            val snapshot = ownSnapshot()
            check(snapshot.status == RootPilotStatus.WAITING_CONFIRMATION.name && snapshot.step == 0 &&
                snapshot.allowScreenUpload && snapshot.actionSummary == "type(length=${EXPECTED.length})", Reason.SNAPSHOT_IDENTITY)
        }

        private suspend fun fixtureState(): Bundle = try { fixture.call("state") }
        catch (_: Throwable) { fail(Reason.FIXTURE_STATE) }

        private suspend fun checkFixture(base: Baseline, foreground: Boolean) {
            val value = fixtureState()
            check(value.getString("instanceId") == base.fixtureInstanceId && value.getInt("processId") == base.fixturePid,
                Reason.FIXTURE_IDENTITY)
            check(value.getBoolean("empty") && !value.getBoolean("matches") &&
                value.getInt("fieldId") == android.R.id.edit && (!foreground || value.getBoolean("ready")), Reason.FIXTURE_STATE)
        }

        private fun checkMainUnlocked() {
            val displays = context.getSystemService(DisplayManager::class.java)?.displays ?: fail(Reason.DISPLAY_IDENTITY)
            check(displays.size == 1 && displays.single().displayId == Display.DEFAULT_DISPLAY && displays.single().isValid,
                Reason.DISPLAY_IDENTITY)
            check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
                context.getSystemService(PowerManager::class.java)?.isInteractive == true, Reason.LOCKED)
        }

        private fun checkImeFileAbsent() {
            check(!exists(imeFile) && !imeSidecarsExist() &&
                fixed(Reason.IME_RECOVERY) { FileImeRestoreStore(context).read() } == null, Reason.IME_RECOVERY)
        }

        private fun imeSidecarsExist() = listOf(".bak", ".new").any { exists(File(imeFile.path + it)) }
        private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        private fun savedApi() = fixed(Reason.SAVED_CONFIG) {
            (RootPilotApiConfigStore.create(context).read() ?: fail(Reason.SAVED_CONFIG)).also {
                check(it.apiKey.isNotBlank() && it.model.isNotBlank() &&
                    it.baseUrl.trimEnd('/') == "https://api.deepseek.com", Reason.SAVED_CONFIG)
            }
        }

        private fun checkUnchangedFiles(base: Baseline) {
            check(fileDigest(apiFile, Reason.API_CHANGED) == base.apiDigest, Reason.API_CHANGED)
            check(optionalDigest(allowlistFile, Reason.ALLOWLIST_CHANGED) == base.allowlistDigest, Reason.ALLOWLIST_CHANGED)
        }

        private fun diskHistory(): DiskHistory = fixed(Reason.HISTORY_UNAVAILABLE) {
            if (!exists(historyFile)) return@fixed DiskHistory(HistoryDocument(), digest(byteArrayOf()))
            val bytes = readBounded(historyFile, 8 * 1024 * 1024)
            val document = JSON.decodeFromString<HistoryDocument>(bytes.decodeToString())
            check(document.version == 1 && document.records.size <= 50 &&
                document.records.map { it.id }.distinct().size == document.records.size, Reason.HISTORY_UNAVAILABLE)
            DiskHistory(document, digest(bytes))
        }

        private fun checkCompleteHistory(record: RunHistoryRecord, status: RunHistoryStatus) {
            check(record.status == status && !record.eventsTruncated && record.stepCount == 1 &&
                record.events.all { it.runId == record.id && it.reason == TraceReason.NONE } &&
                record.events.map(::shape) == EXPECTED_TRACE && record.reason == TraceReason.NONE,
                Reason.UNEXPECTED_ACTIVITY)
            check(record.events.none { it.stage == TraceStage.EXECUTION || it.event == TraceEvent.CONFIRMED } &&
                record.events.last().let { it.event == TraceEvent.WAITING && it.actionType == TraceActionType.TYPE &&
                    it.status == TraceStatus.WAITING }, Reason.UNEXPECTED_ACTIVITY)
        }

        private fun logs(): List<LogEntry> = fixed(Reason.TRACE_UNAVAILABLE) {
            state.logs.map { line ->
                val value = JSON.parseToJsonElement(line).jsonObject
                fun field(key: String) = value[key]?.jsonPrimitive?.content ?: fail(Reason.TRACE_UNAVAILABLE)
                LogEntry(field("runId"), listOf("stage", "event", "status", "actionType", "step")
                    .joinToString("/") { field(it).uppercase(Locale.ROOT) }, field("reasonCode"))
            }
        }

        private fun checkLogPrefix(owned: String, complete: Boolean = false) {
            val all = logs()
            val start = all.indexOfFirst { it.runId == owned }
            check(start >= 0, Reason.TRACE_UNAVAILABLE)
            val current = all.drop(start)
            check(current.all { it.runId == owned && it.reason == "none" } && current.size <= EXPECTED_TRACE.size &&
                current.map { it.shape } == EXPECTED_TRACE.take(current.size) &&
                (!complete || current.size == EXPECTED_TRACE.size), Reason.UNEXPECTED_ACTIVITY)
        }

        private fun checkReceiptValid() {
            check(root.isDirectory && directory.isDirectory && !Files.isSymbolicLink(root.toPath()) &&
                !Files.isSymbolicLink(directory.toPath()) && !exists(directory.resolve("invalidated")) &&
                !exists(directory.resolve("invalidated-ready.json")) && directory.resolve("ready.json").isFile,
                Reason.RECEIPT_INVALIDATED)
            check(!exists(directory.resolve("completed")) && (claimed || !exists(directory.resolve("claimed"))),
                Reason.RECEIPT_REUSED)
            ready?.let { receipt ->
                check(readBounded(directory.resolve("ready.json"), MAX_FILE).contentEquals(
                    JSON.encodeToString(receipt).encodeToByteArray()), Reason.RECEIPT_CHANGED)
                check(fileDigest(directory.resolve("baseline.json"), Reason.RECEIPT_IO) == receipt.baselineDigest,
                    Reason.RECEIPT_CHANGED)
            }
        }

        private fun marker(name: String) = fixed(Reason.RECEIPT_REUSED) {
            val fd = Os.open(directory.resolve(name).path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384)
            try { check(Os.write(fd, byteArrayOf(1), 0, 1) == 1, Reason.RECEIPT_IO); Os.fsync(fd) }
            finally { Os.close(fd) }
            syncDirectory(directory)
        }

        fun report(stage: String, failure: Reason?) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("typeRecoveryStage", stage)
                putString("reasonCode", failure?.name ?: "NONE")
                putString("cleanupReasonCode", cleanupReason?.name ?: "NONE")
                if (::id.isInitialized && validId(id)) putString("recoveryReceiptId", id)
                putInt("mainPid", Process.myPid())
                putBoolean("passed", verified && restored && failure == null && cleanupReason == null)
                putBoolean("baselineRestored", restored)
                putBoolean("verifyClaimed", claimed)
                ready?.let {
                    putString("runId", it.runId)
                    putLong("readyAt", it.readyAt)
                }
                if (stage == "ready_for_main_process_kill") putLong("hostKillWindowMs", HOST_KILL_WINDOW)
                if (verified && failure == null && cleanupReason == null) {
                    putInt("postReopenModelRequests", 0)
                    putInt("postReopenScreenshots", 0)
                    putInt("postReopenExecutions", 0)
                    putInt("confirmedActions", 0)
                }
            })
        }
    }

    @Serializable
    private data class Baseline(
        val id: String, val mainPid: Int, val appUid: Int, val bootCount: Int, val apiConfigured: Boolean,
        val manualConfirmation: Boolean, val allowScreenUpload: Boolean, val originalIme: String,
        val apiDigest: String, val allowlistDigest: String?, val fixtureInstanceId: String, val fixturePid: Int,
    )

    @Serializable
    private data class Ready(
        val id: String, val runId: String, val mainPid: Int, val appUid: Int, val bootCount: Int, val readyAt: Long,
        val snapshotDigest: String, val historyDigest: String, val interruptedHistoryDigest: String,
        val imeRecoveryDigest: String, val fixtureInstanceId: String, val fixturePid: Int, val baselineDigest: String,
    )

    @Serializable
    private data class HistoryDocument(val version: Int = 1, val records: List<RunHistoryRecord> = emptyList())
    private data class DiskHistory(val document: HistoryDocument, val digest: String)
    private data class LogEntry(val runId: String, val shape: String, val reason: String)
    private class RecoveryFailure(val reason: Reason) : AssertionError(reason.name)
    private enum class Reason {
        TARGET_PROCESS, NO_RESTART_REQUIRED, RECEIPT_ID, BASELINE_ACK_REQUIRED, SERVICE_BUSY, EXISTING_RECOVERY,
        BASELINE_UNRESTORABLE, DISPLAY_IDENTITY, LOCKED, OVERLAY_PERMISSION, FIXTURE_IDENTITY, FIXTURE_STATE,
        FIXTURE_FOREGROUND, IME_UNAVAILABLE, IME_RECOVERY, IME_NOT_RESTORED, SAVED_CONFIG, BOOT_IDENTITY,
        RECEIPT_IO, RECEIPT_REUSED, RECEIPT_CHANGED, UNFINISHED_RECEIPT, HISTORY_UNAVAILABLE, HISTORY_CAPACITY,
        ENVIRONMENT_CHANGED, RUN_IDENTITY, TYPE_IDENTITY, EDITOR_IDENTITY, WAITING_IDENTITY, UNEXPECTED_ACTIVITY,
        SNAPSHOT_IDENTITY, SNAPSHOT_CHANGED, API_CHANGED, ALLOWLIST_CHANGED, TRACE_UNAVAILABLE, HISTORY_CHANGED,
        HOST_KILL_TIMEOUT, RECEIPT_INVALIDATION_FAILED, RECEIPT_INVALIDATED, MAIN_NOT_DEAD, PID_QUERY,
        RECEIPT_EXPIRED, RECOVERY_STATE, UPLOAD_CONSENT, RECOVER_NOT_REJECTED, CLEANUP_UNCONFIRMED,
        DISCARD_FAILED, RESTORE_FAILED, REPORT_FAILED, TIMEOUT, UNEXPECTED,
    }

    private companion object {
        const val TARGET = "com.example.agent"
        const val FIXTURE = "com.example.rootpilot.fixture"
        const val EXPECTED = "执行模式验收通过"
        const val UPLOAD_REJECTION = "发送截图前请先打开上传确认"
        const val MAX_FILE = 64 * 1024
        const val POLL = 50L
        const val HOST_KILL_WINDOW = 45_000L
        const val VERIFY_WINDOW = 120_000L
        const val QUIET_WINDOW = 1_000L
        val JSON = Json { encodeDefaults = true }
        val TERMINAL_STATES = setOf(RootPilotStatus.STOPPED, RootPilotStatus.FAILED, RootPilotStatus.COMPLETED)
        val IDLE_STATES = TERMINAL_STATES + RootPilotStatus.IDLE
        val PREPARING_STATES = setOf(RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.WAITING_CONFIRMATION)
        val EXPECTED_TRACE = listOf(
            "RUN/RUN_START/STARTED/NONE/-1", "SCREENSHOT/START/STARTED/NONE/0", "SCREENSHOT/RESULT/SUCCESS/NONE/0",
            "MODEL/START/STARTED/NONE/0", "MODEL/RESULT/SUCCESS/NONE/0", "APPROVAL/WAITING/WAITING/TYPE/0",
        )

        fun fail(reason: Reason): Nothing = throw RecoveryFailure(reason)
        fun check(value: Boolean, reason: Reason) { if (!value) fail(reason) }
        fun reason(error: Throwable) = when (error) {
            is RecoveryFailure -> error.reason
            is TimeoutCancellationException -> Reason.TIMEOUT
            else -> Reason.UNEXPECTED
        }
        inline fun <T> fixed(reason: Reason, block: () -> T): T = try { block() }
        catch (error: RecoveryFailure) { throw error }
        catch (_: Throwable) { fail(reason) }
        fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        fun validId(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun validDigest(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        fun shape(event: RunTraceEvent) = "${event.stage}/${event.event}/${event.status}/${event.actionType}/${event.step}"
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun fileDigest(file: File, reason: Reason) = fixed(reason) { digest(readBounded(file, MAX_FILE)) }
        fun optionalDigest(file: File, reason: Reason) = if (exists(file)) fileDigest(file, reason) else null
        fun readBounded(file: File, limit: Int): ByteArray = fixed(Reason.RECEIPT_IO) {
            check(file.isFile && !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT_IO)
            file.inputStream().use { it.readNBytes(limit + 1) }.also { check(it.size <= limit, Reason.RECEIPT_IO) }
        }
        fun pidAbsent(pid: Int): Boolean = try {
            // Signal zero only probes existence; it cannot terminate the old process.
            Os.kill(pid, 0)
            false
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ESRCH -> true
                OsConstants.EPERM -> false
                else -> fail(Reason.PID_QUERY)
            }
        }
        fun write(file: File, bytes: ByteArray) = fixed(Reason.RECEIPT_IO) {
            val parent = file.parentFile ?: fail(Reason.RECEIPT_IO)
            val temporary = Files.createTempFile(parent.toPath(), "type-recovery-", ".tmp")
            try {
                temporary.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(parent)
            } finally { Files.deleteIfExists(temporary) }
        }
        fun syncDirectory(directory: File) {
            check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()), Reason.RECEIPT_IO)
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    }
}
