package com.example.agent.rootpilot

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.BuildConfig
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** One signed Provider state read; no Activity, UI, IME, receipt, model, or recovery action. */
@RunWith(AndroidJUnit4::class)
class ExistingFreshFixtureStateInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun inspectExistingFreshFixtureState() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("explicit_existing_fixture_read_opt_in_required", args.get(MODE) == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val queryNonce = UUID.randomUUID().toString()
        var expectedSession: String? = null
        var expectedPid: Int? = null
        var attempted = false
        var phase = Reason.BAD_ARGUMENTS
        val result = try {
            check(args.keySet().all { it in ARGUMENT_KEYS })
            expectedSession = (args.get("expectedFreshSessionId") as? String)?.takeIf(FreshInputReadinessProtocol::canonicalId)
            expectedPid = (args.get("expectedFixturePid") as? String)?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
                ?.toIntOrNull()?.takeIf { it > 0 && it != Process.myPid() }
            check(expectedSession != null && expectedPid != null)
            phase = Reason.DEBUG_ONLY
            check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            phase = Reason.APP_IDENTITY
            check(context.packageName == PACKAGE && Application.getProcessName() == PACKAGE && context.applicationInfo.uid == Process.myUid())
            phase = Reason.FIXTURE_IDENTITY
            val client = FixtureClient(context).also { it.verifyIdentity() }
            phase = Reason.QUERY_FAILED
            attempted = true
            val state = client.call("state")
            phase = Reason.INVALID_STATE
            classify(state.keySet().associateWith { state.get(it) }, expectedSession, expectedPid, Process.myPid())
        } catch (_: Throwable) { ReadResult("UNKNOWN", phase) }
        val report = buildJsonObject {
            put("schemaVersion", 1); put("diagnosticKind", "EXISTING_FRESH_FIXTURE_STATE")
            put("queryNonce", queryNonce); put("mainPid", Process.myPid())
            put("expectedFreshSessionId", expectedSession?.let(::JsonPrimitive) ?: JsonNull)
            put("expectedFixturePid", expectedPid?.let(::JsonPrimitive) ?: JsonNull)
            put("queryAttempted", attempted); put("queryCount", if (attempted) 1 else 0)
            put("outcome", result.outcome); put("reasonCode", result.reason.name)
            put("originalFullTupleMatch", JsonNull)
            put("observedTuple", result.observed?.let { observed -> buildJsonObject {
                put("freshSessionId", observed.session); put("fixtureInstanceId", observed.instance)
                put("fixturePid", observed.pid); put("fixtureTaskId", observed.task)
                put("fixtureDisplayId", observed.display); put("fixtureProcessStartTicks", observed.ticks)
            } } ?: JsonNull)
            put("ready", result.observed?.ready?.let(::JsonPrimitive) ?: JsonNull)
            put("empty", result.observed?.empty?.let(::JsonPrimitive) ?: JsonNull)
            put("matches", result.observed?.matches?.let(::JsonPrimitive) ?: JsonNull)
            put("commitCalls", result.observed?.commits?.let(::JsonPrimitive) ?: JsonNull)
            put("readOnly", true); put("priorDiagnosticRepaired", false); put("originalReadyPromoted", false)
        }
        try { instrumentation.sendStatus(0, Bundle().apply { putString(REPORT, report.toString()) }) }
        catch (_: Throwable) { throw AssertionError(Reason.REPORT_FAILED.name) }
        if (result.outcome == "UNKNOWN") throw AssertionError(result.reason.name)
    }

    private data class Observed(
        val session: String, val instance: String, val pid: Int, val task: Int, val display: Int, val ticks: Long,
        val ready: Boolean, val empty: Boolean, val matches: Boolean, val commits: Int,
    )
    private data class ReadResult(val outcome: String, val reason: Reason, val observed: Observed? = null)
    private enum class Reason { NONE, BAD_ARGUMENTS, DEBUG_ONLY, APP_IDENTITY, FIXTURE_IDENTITY, QUERY_FAILED,
        INVALID_STATE, SESSION_MISMATCH, PID_MISMATCH, REPORT_FAILED }

    private companion object {
        const val PACKAGE = "com.example.agent"
        const val MODE = "existingFreshFixtureStateDiagnostic"
        const val REPORT = "existingFreshFixtureStateReport"
        val ARGUMENT_KEYS = setOf("class", MODE, "expectedFreshSessionId", "expectedFixturePid")
        val STATE_KEYS = setOf("freshSessionId", "instanceId", "processId", "fixtureTaskId", "fixtureDisplayId",
            "fixtureProcessStartTicks", "ready", "empty", "matches", "fieldId", "commitCalls")

        /** A current snapshot can bind session/PID, never reconstruct the lost original tuple or READY. */
        fun classify(values: Map<String, Any?>, session: String, expectedPid: Int, mainPid: Int): ReadResult {
            if (values.isEmpty()) return ReadResult("EMPTY", Reason.NONE)
            if (values.keys != STATE_KEYS) return ReadResult("UNKNOWN", Reason.INVALID_STATE)
            val actualSession = values["freshSessionId"] as? String
            val instance = values["instanceId"] as? String
            val pid = values["processId"] as? Int
            val task = values["fixtureTaskId"] as? Int
            val display = values["fixtureDisplayId"] as? Int
            val ticks = values["fixtureProcessStartTicks"] as? Long
            val ready = values["ready"] as? Boolean
            val empty = values["empty"] as? Boolean
            val matches = values["matches"] as? Boolean
            val field = values["fieldId"] as? Int
            val commits = values["commitCalls"] as? Int
            if (actualSession == null || !FreshInputReadinessProtocol.canonicalId(actualSession) ||
                instance == null || !FreshInputReadinessProtocol.canonicalId(instance) || pid == null || pid <= 0 || pid == mainPid ||
                task == null || task <= 0 || display != 0 || ticks == null || ticks <= 0 ||
                ready == null || empty == null || matches == null || field != 16908291 || commits == null || commits < 0)
                return ReadResult("UNKNOWN", Reason.INVALID_STATE)
            if (actualSession != session) return ReadResult("UNKNOWN", Reason.SESSION_MISMATCH)
            if (pid != expectedPid) return ReadResult("UNKNOWN", Reason.PID_MISMATCH)
            return ReadResult("BOUND_OBSERVED", Reason.NONE,
                Observed(actualSession, instance, pid, task, display, ticks, ready, empty, matches, commits))
        }
    }
}
