package com.example.agent.rootpilot

import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.agent.BuildConfig
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only. The host must first force-stop this package without clearing its data,
 * verify no package activities/services remain, then start a fresh, isolated instrumentation run.
 * This quiescent precondition is required because the production store has no empty-store CAS.
 */
@RunWith(AndroidJUnit4::class)
class RootPilotApiConfigProvisionInstrumentedTest {
    @Test
    fun provisionEmptyStoreFromAuthorizedAdbPeer() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_provision_opt_in_required", args.getString("rootPilotProvisionApi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var server: LocalServerSocket? = null
        var peer: LocalSocket? = null
        var keyBytes: ByteArray? = null
        var challenge: ByteArray? = null
        var reason: Reason? = null
        var phase = Reason.ADMISSION
        var saveAttempted = false
        var saved = false
        var configVerified = false
        var cleanupSucceeded = true
        var peerUid = -1
        var callbackRegistered = false
        val activityChanged = AtomicBoolean(false)
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, _ ->
            if (activity.packageName == PACKAGE) activityChanged.set(true)
        }
        fun onMain(block: () -> Unit) {
            var failed: Reason? = null
            instrumentation.runOnMainSync {
                try { block() }
                catch (error: Throwable) { failed = (error as? Failure)?.code ?: Reason.MAIN_THREAD }
            }
            failed?.let { throw Failure(it) }
        }
        try {
            requireFixed(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                Reason.DEBUG_ONLY)
            requireFixed(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE &&
                context.applicationInfo.uid == Process.myUid(), Reason.IDENTITY)
            requireFixed(args.getString("rootPilotProvisionBaseline") == "fresh_process_empty_store" &&
                args.getString("rootPilotProvisionHostQuiescent") == "verified_no_activity_or_service", Reason.ADMISSION)
            lifecycle.addLifecycleCallback(callback)
            callbackRegistered = true
            val store = RootPilotApiConfigStore.create(context)

            fun mainQuiescent() {
                requireFixed(!activityChanged.get() && Stage.values().filter { it != Stage.DESTROYED }.all {
                    lifecycle.getActivitiesInStage(it).none { activity -> activity.packageName == PACKAGE }
                }, Reason.ACTIVITY_PRESENT)
                // A prior RootPilotService instance, even if destroyed, violates the fresh-process premise.
                requireFixed(ServiceDestroyObservation.current == null, Reason.SERVICE_PRESENT)
                @Suppress("DEPRECATION")
                val services = context.getSystemService(ActivityManager::class.java)
                    ?.getRunningServices(Int.MAX_VALUE) ?: throw Failure(Reason.SERVICE_CHECK)
                requireFixed(services.none { it.service.packageName == PACKAGE }, Reason.SERVICE_PRESENT)
                val state = RootPilotService.uiState.value
                requireFixed(!state.running && !state.apiConfigured && state.config == RootPilotConfig() &&
                    state.status == RootPilotStatus.IDLE && state.pendingAction == null &&
                    state.lastAction == null && state.frame == null && state.step == 0 && state.errorMessage == null,
                    Reason.STATE_NOT_EMPTY)
            }

            fun storageQuiescent() {
                requireFixed(!File(context.filesDir, RootPilotRunStore.FILE_NAME).exists() &&
                    listOf("rootpilot_original_ime", "rootpilot_original_ime.bak", "rootpilot_original_ime.new")
                        .none { File(context.noBackupFilesDir, it).exists() }, Reason.RECOVERY_PRESENT)
                // A corrupt/unreadable existing store also fails closed; it is never cleared or replaced.
                requireFixed(store.read() == null, Reason.STORE_NOT_EMPTY)
            }

            onMain { mainQuiescent() }
            storageQuiescent()
            val random = SecureRandom()
            val socketName = "rootpilot-api-v1-" + ByteArray(32).also(random::nextBytes).hex()
            val nonce = ByteArray(RootPilotApiProvisionProtocol.CHALLENGE_BYTES).also(random::nextBytes)
            challenge = nonce
            phase = Reason.SOCKET_BIND
            val listener = LocalServerSocket(socketName)
            server = listener
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            // Nonblocking accept also closes the poll/accept disconnect race without any retry.
            Os.fcntlInt(listener.fileDescriptor, OsConstants.F_SETFL,
                Os.fcntlInt(listener.fileDescriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            fun remaining(): Int {
                val value = deadline - SystemClock.elapsedRealtime()
                requireFixed(value > 0, Reason.TIMEOUT)
                return value.coerceAtMost(TIMEOUT_MS).toInt()
            }
            phase = Reason.READY_REPORT
            instrumentation.sendStatus(0, Bundle().apply {
                putString("provisionStage", "ready")
                putString("provisionSocket", socketName)
                putString("provisionChallenge", nonce.hex())
            })
            phase = Reason.SOCKET_ACCEPT
            // poll bounds accept without a background worker, sleep, or reconnect retry.
            val poll = StructPollfd().apply {
                fd = listener.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            requireFixed(Os.poll(arrayOf(poll), remaining()) == 1 &&
                poll.revents.toInt() and OsConstants.POLLIN != 0, Reason.TIMEOUT)
            val connection = listener.accept()
            peer = connection
            listener.close()
            server = null // One accepted connection consumes the endpoint, including an invalid peer.
            phase = Reason.PEER_CREDENTIALS
            peerUid = connection.peerCredentials.uid
            requireFixed(RootPilotApiProvisionProtocol.trustedPeer(peerUid), Reason.PEER_REJECTED)
            connection.soTimeout = remaining()
            phase = Reason.CHALLENGE_WRITE
            connection.outputStream.write(RootPilotApiProvisionProtocol.hello(nonce))
            connection.outputStream.flush()
            phase = Reason.PAYLOAD
            val received = RootPilotApiProvisionProtocol.readKey(connection.inputStream) {
                connection.soTimeout = remaining()
            }
            keyBytes = received
            phase = Reason.PRE_SAVE
            onMain {
                // One main-loop turn prevents Activity/Service starts between admission and save.
                // The fresh-process premise still excludes existing workers; this is not a store CAS.
                mainQuiescent()
                storageQuiescent()
                remaining()
                // This immutable String is required by the existing production store and retained state.
                // Mutable transport buffers are wiped; neither config nor failures are ever rendered.
                val config = RootPilotApiConfig(apiKey = received.toString(Charsets.US_ASCII),
                    baseUrl = "https://api.deepseek.com", model = "deepseek-flash")
                received.fill(0)
                keyBytes = null
                phase = Reason.STORE_SAVE
                saveAttempted = true
                store.save(config)
                saved = true
                phase = Reason.STORE_READBACK
                requireFixed(store.read() == config, Reason.STORE_READBACK)
                phase = Reason.MEMORY_UPDATE
                RootPilotService.updateApiConfig(config)
                val state = RootPilotService.uiState.value
                requireFixed(state.apiConfigured && state.config == config.applyTo(RootPilotConfig()) &&
                    !state.running && state.pendingAction == null && state.status == RootPilotStatus.IDLE &&
                    ServiceDestroyObservation.current == null, Reason.MEMORY_READBACK)
            }
            configVerified = true
            connection.soTimeout = remaining()
            phase = Reason.ACK_WRITE
            connection.outputStream.write("RPAP1 OK\n".toByteArray(Charsets.US_ASCII))
            connection.outputStream.flush()
        } catch (error: Throwable) {
            reason = when (error) {
                is Failure -> error.code
                is RootPilotApiProvisionProtocol.Failure -> when (error.code) {
                    RootPilotApiProvisionProtocol.Code.LENGTH -> Reason.PAYLOAD_LENGTH
                    RootPilotApiProvisionProtocol.Code.TRUNCATED -> Reason.PAYLOAD_TRUNCATED
                    RootPilotApiProvisionProtocol.Code.TRAILING -> Reason.PAYLOAD_TRAILING
                    RootPilotApiProvisionProtocol.Code.KEY_FORMAT -> Reason.PAYLOAD_FORMAT
                    else -> Reason.PAYLOAD_IO
                }
                else -> phase
            }
            // Do not echo untrusted input or exception messages. A timeout/closed peer may prevent ACK.
            try {
                peer?.let {
                    it.soTimeout = 1_000
                    it.outputStream.write("RPAP1 FAIL\n".toByteArray(Charsets.US_ASCII))
                    it.outputStream.flush()
                }
            } catch (_: Throwable) { /* The fixed final instrumentation result remains authoritative. */ }
        } finally {
            keyBytes?.fill(0)
            challenge?.fill(0)
            try { peer?.close() } catch (_: Throwable) { cleanupSucceeded = false }
            try { server?.close() } catch (_: Throwable) { cleanupSucceeded = false }
            if (callbackRegistered) {
                try { lifecycle.removeLifecycleCallback(callback) }
                catch (_: Throwable) { cleanupSucceeded = false }
            }
        }
        if (reason == null && activityChanged.get()) reason = Reason.ACTIVITY_PRESENT
        val passed = configVerified && reason == null && cleanupSucceeded
        val failure = if (saveAttempted && !passed) Reason.SAVED_NOT_VERIFIED else reason
            ?: if (!cleanupSucceeded) Reason.CLEANUP else null
        // No deletion/rollback after a save attempt: a failed save/readback may already have persisted.
        try {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("provisionStage", if (passed) "verified" else "failed")
                putString("reasonCode", failure?.name ?: "NONE")
                putString("failureReasonCode", reason?.name ?: if (!cleanupSucceeded) "CLEANUP" else "NONE")
                putBoolean("passed", passed)
                putBoolean("saveAttempted", saveAttempted)
                putBoolean("saved", saved)
                putBoolean("cleanupSucceeded", cleanupSucceeded)
                putInt("peerUid", peerUid)
            })
        } catch (_: Throwable) { throw AssertionError("rootpilot_api_provision_failed:REPORT") }
        if (!passed) throw AssertionError("rootpilot_api_provision_failed:${failure?.name ?: "UNKNOWN"}")
    }

    @Test
    fun inspectSavedStoreReadOnly() {
        assumeTrue("explicit_inspect_opt_in_required",
            InstrumentationRegistry.getArguments().getString("rootPilotInspectApi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var present = false
        var keyNonempty = false
        var officialBase = false
        var expectedModel = false
        var readable = false
        var recoveryClear = false
        var completed = false
        try {
            requireFixed(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                Reason.DEBUG_ONLY)
            requireFixed(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE &&
                context.applicationInfo.uid == Process.myUid(), Reason.IDENTITY)
            present = File(context.noBackupFilesDir, RootPilotApiConfigStore.FILE_NAME).exists()
            // Only inspect recovery metadata; never load a task body or alter recovery state.
            recoveryClear = !File(context.filesDir, RootPilotRunStore.FILE_NAME).exists() &&
                listOf("rootpilot_original_ime", "rootpilot_original_ime.bak", "rootpilot_original_ime.new")
                    .none { File(context.noBackupFilesDir, it).exists() }
            // read() decrypts with getKey(), not getOrCreateKey(): no Keystore key is created.
            val config = RootPilotApiConfigStore.create(context).read()
            readable = config != null
            if (config != null) {
                keyNonempty = config.apiKey.isNotEmpty()
                officialBase = config.baseUrl == "https://api.deepseek.com"
                expectedModel = config.model == "deepseek-flash"
            }
            completed = true
        } catch (_: Throwable) { /* Preserve unknown state; never render a config or exception. */ }
        val passed = completed && present && readable && keyNonempty && officialBase && expectedModel && recoveryClear
        try {
            instrumentation.sendStatus(0, Bundle().apply {
                putBoolean("present", present)
                putBoolean("keyNonempty", keyNonempty)
                putBoolean("officialBase", officialBase)
                putBoolean("expectedModel", expectedModel)
                putBoolean("readable", readable)
                putBoolean("passed", passed)
            })
        } catch (_: Throwable) { throw AssertionError("rootpilot_api_inspect_failed:REPORT") }
        if (!passed) throw AssertionError("rootpilot_api_inspect_failed")
    }

    private fun requireFixed(value: Boolean, code: Reason) {
        if (!value) throw Failure(code)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Failure(val code: Reason) : RuntimeException(code.name)
    private enum class Reason {
        DEBUG_ONLY, IDENTITY, ADMISSION, MAIN_THREAD, ACTIVITY_PRESENT, SERVICE_PRESENT, SERVICE_CHECK, STATE_NOT_EMPTY,
        RECOVERY_PRESENT, STORE_NOT_EMPTY, SOCKET_BIND, READY_REPORT, SOCKET_ACCEPT, TIMEOUT,
        PEER_CREDENTIALS, PEER_REJECTED, CHALLENGE_WRITE, PAYLOAD, PAYLOAD_LENGTH, PAYLOAD_TRUNCATED,
        PAYLOAD_TRAILING, PAYLOAD_FORMAT, PAYLOAD_IO, PRE_SAVE, STORE_SAVE, STORE_READBACK, MEMORY_UPDATE,
        MEMORY_READBACK, ACK_WRITE, SAVED_NOT_VERIFIED, CLEANUP,
    }

    private companion object {
        const val PACKAGE = "com.example.agent"
        const val TIMEOUT_MS = 30_000L
    }
}
