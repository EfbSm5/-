package com.example.agent.rootpilot

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.BuildConfig
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads one surviving failed-run fixture; diagnostic success never changes the original verdict. */
@RunWith(AndroidJUnit4::class)
class NotificationTypeCommitDiagnosticInstrumentedTest {
    @Test
    fun inspectFailedNotificationFixtureState() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_diagnostic_opt_in_required", args.getString("notificationTypeCommitDiagnostic") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var phase = Reason.ADMISSION
        var failure: Reason? = null
        var verified = false
        var ready = false
        var empty = false
        var matches = false
        var commitCalls = -1
        try {
            requireFixed(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                Reason.DEBUG_ONLY)
            requireFixed(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE &&
                context.applicationInfo.uid == Process.myUid(), Reason.IDENTITY)
            val receiptId = args.getString("recoveryReceiptId").orEmpty()
            requireFixed(validId(receiptId), Reason.RECEIPT_ID)
            val root = context.noBackupFilesDir.resolve("rootpilot-type-process-recovery-test")
            val directory = root.resolve(receiptId)
            fun checkDirectories() {
                listOf(context.noBackupFilesDir, root, directory).forEach {
                    requireFixed(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(it.toPath()), Reason.RECEIPT_PATH)
                }
            }
            phase = Reason.RECEIPT_READ
            checkDirectories()
            val baselineFile = directory.resolve("baseline.json")
            val invalidatedFile = directory.resolve("invalidated")
            val baselineBytes = readBounded(baselineFile, 64 * 1024)
            val invalidatedBytes = readBounded(invalidatedFile, 64)
            requireFixed(invalidatedBytes.contentEquals("NOTIFICATION_EXECUTION_FAILED".encodeToByteArray()),
                Reason.INVALIDATION_REASON)
            phase = Reason.BASELINE_SCHEMA
            val baseline = Json.parseToJsonElement(baselineBytes.decodeToString()) as? JsonObject
                ?: throw Failure(Reason.BASELINE_SCHEMA)
            requireFixed(baseline.keys == BASELINE_KEYS, Reason.BASELINE_SCHEMA)
            fun string(key: String): String {
                val value = baseline[key] as? JsonPrimitive ?: throw Failure(Reason.BASELINE_SCHEMA)
                requireFixed(value.isString, Reason.BASELINE_SCHEMA)
                return value.content
            }
            fun integer(key: String): Int {
                val value = baseline[key] as? JsonPrimitive ?: throw Failure(Reason.BASELINE_SCHEMA)
                requireFixed(!value.isString, Reason.BASELINE_SCHEMA)
                return value.intOrNull ?: throw Failure(Reason.BASELINE_SCHEMA)
            }
            val fixtureId = string("fixtureInstanceId")
            val fixturePid = integer("fixturePid")
            val mainPid = integer("mainPid")
            requireFixed(string("id") == receiptId && integer("appUid") == Process.myUid() && mainPid > 0 &&
                validId(fixtureId) && fixturePid > 0 && fixturePid != mainPid && fixturePid != Process.myPid(),
                Reason.BASELINE_BINDING)
            val fixture = FixtureClient(context)
            phase = Reason.FIXTURE_IDENTITY
            fixture.verifyIdentity()
            phase = Reason.FIXTURE_STATE
            // Exactly one state call. Do not launch, wait for readiness, capture, finish, or retry.
            val state = fixture.call("state")
            @Suppress("DEPRECATION")
            requireFixed(state.get("instanceId") is String && state.get("processId") is Int &&
                state.get("fieldId") is Int && state.get("commitCalls") is Int &&
                state.get("ready") is Boolean && state.get("empty") is Boolean && state.get("matches") is Boolean,
                Reason.FIXTURE_STATE)
            requireFixed(state.getString("instanceId") == fixtureId && state.getInt("processId") == fixturePid &&
                state.getInt("fieldId") == android.R.id.edit, Reason.FIXTURE_BINDING)
            commitCalls = state.getInt("commitCalls")
            requireFixed(commitCalls >= 0, Reason.FIXTURE_STATE)
            ready = state.getBoolean("ready")
            empty = state.getBoolean("empty")
            matches = state.getBoolean("matches")
            phase = Reason.RECEIPT_RECHECK
            checkDirectories()
            requireFixed(readBounded(baselineFile, 64 * 1024).contentEquals(baselineBytes) &&
                readBounded(invalidatedFile, 64).contentEquals(invalidatedBytes), Reason.RECEIPT_CHANGED)
            checkDirectories()
            verified = true
        } catch (error: Throwable) {
            failure = (error as? Failure)?.code ?: phase
        }
        // No cleanup/finally can manufacture successful restoration or mutate the failed receipt.
        try {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("fixtureCommitDiagnosticStage", if (verified) "verified" else "failed")
                putString("reasonCode", failure?.name ?: "NONE")
                putBoolean("diagnosticPassed", verified)
                putBoolean("readOnly", verified)
                putBoolean("receiptUnchanged", verified)
                putBoolean("sameFixtureInstance", verified)
                putBoolean("fixtureReady", verified && ready)
                putBoolean("fixtureEmpty", verified && empty)
                putBoolean("fixtureTextMatches", verified && matches)
                putInt("commitCalls", if (verified) commitCalls else -1)
            })
        } catch (_: Throwable) {
            throw AssertionError(Reason.REPORT_FAILED.name)
        }
        failure?.let { throw AssertionError(it.name) }
    }

    private fun readBounded(file: File, limit: Int): ByteArray {
        requireFixed(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath()), Reason.RECEIPT_PATH)
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Channels.newInputStream(Files.newByteChannel(file.toPath(), options)).use {
            it.readNBytes(limit + 1)
        }.also { requireFixed(it.isNotEmpty() && it.size <= limit, Reason.RECEIPT_SIZE) }
    }

    private fun validId(value: String): Boolean = value.length == 36 &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun requireFixed(value: Boolean, code: Reason) {
        if (!value) throw Failure(code)
    }

    private class Failure(val code: Reason) : AssertionError(code.name)
    private enum class Reason {
        ADMISSION, DEBUG_ONLY, IDENTITY, RECEIPT_ID, RECEIPT_PATH, RECEIPT_READ, RECEIPT_SIZE,
        INVALIDATION_REASON, BASELINE_SCHEMA, BASELINE_BINDING, FIXTURE_IDENTITY, FIXTURE_STATE,
        FIXTURE_BINDING, RECEIPT_RECHECK, RECEIPT_CHANGED, REPORT_FAILED,
    }
    private companion object {
        const val PACKAGE = "com.example.agent"
        val BASELINE_KEYS = setOf("id", "mainPid", "appUid", "bootCount", "apiConfigured", "manualConfirmation",
            "allowScreenUpload", "originalIme", "apiDigest", "allowlistDigest", "fixtureInstanceId", "fixturePid")
    }
}
