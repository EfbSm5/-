package com.example.agent.rootpilot

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Existing-bound test source only: no invented FRESH creation/empty-prelaunch evidence. */
internal object ExistingFixtureRootCollapseProtocol {
    const val MODE = "existingFixtureRootCollapseDiagnostic"
    const val REPORT = "existingFixtureRootCollapseReport"
    private val keys = setOf(MODE, "expectedFreshSessionId", "expectedFixtureInstanceId", "expectedFixturePid",
        "expectedFixtureTaskId", "expectedFixtureDisplayId", "expectedFixtureProcessStartTicks")
    fun admit(args: Map<String, Any?>, mainPid: Int): FreshInputReadinessProtocol.Identity {
        require(args.keys.all { it in keys || it == "class" } && args.keys.containsAll(keys) && args[MODE] == "true")
        fun id(key: String) = (args[key] as? String)?.also { require(FreshInputReadinessProtocol.canonicalId(it)) } ?: error("argument")
        fun number(key: String): String = (args[key] as? String)?.also { require(it.matches(Regex("0|[1-9][0-9]*"))) } ?: error("argument")
        val result = FreshInputReadinessProtocol.Identity(id("expectedFreshSessionId"), id("expectedFixtureInstanceId"),
            number("expectedFixturePid").toInt(), number("expectedFixtureTaskId").toInt(),
            number("expectedFixtureDisplayId").toInt(), number("expectedFixtureProcessStartTicks").toLong())
        require(result.pid > 0 && result.pid != mainPid && result.taskId > 0 && result.displayId == 0 && result.processStartTicks > 0)
        return result
    }
    fun state(values: Map<String, Any?>, expected: FreshInputReadinessProtocol.Identity, mainPid: Int): FreshInputReadinessProtocol.Identity =
        FreshInputReadinessProtocol.independentState(values, expected.sessionId, mainPid).also { require(it == expected) }

    data class Observation(
        val expected: FreshInputReadinessProtocol.Identity?, val identity: FreshInputReadinessProtocol.Identity?, val nonce: String,
        val mainPid: Int, val baselineAccepted: Boolean?, val completed: Boolean, val reason: String, val cleanupReasons: List<String>,
        val cleanup: Map<String, Boolean?>, val activityCreated: Int?, val activityResumed: Int?, val serviceObserved: Boolean,
        val actionClicks: Int, val expandClicks: Int, val sourceTokenless: Boolean, val servicePendingIntent: Boolean,
        val measuredBeforeCleanup: Boolean, val root: FixedStatusBarCollapse.Result, val rootWithinDispatchBudget: Boolean,
        val evidence: NotificationServiceTransportEvidence.Session,
    ) {
        fun restored(): Boolean {
            require(cleanup.keys == IndependentFreshServiceTransportProtocol.cleanupKeys)
            return cleanupReasons.isEmpty() && cleanup.values.all { it == true }
        }
        fun passed(): Boolean = expected != null && identity == expected && baselineAccepted == true && completed &&
            reason == "NONE" && restored() && activityCreated == 0 && activityResumed == 0 && serviceObserved &&
            actionClicks == 1 && sourceTokenless && servicePendingIntent && measuredBeforeCleanup && evidence.complete && evidence.closed &&
            root.attempts == 1 && root.outcome == "SUCCESS" && root.exitCode == 0 && root.exitConfirmed == true && rootWithinDispatchBudget &&
            evidence.allOriginalEditor && evidence.allOriginalConnection && evidence.allConsistent && evidence.allValid &&
            evidence.automaticShadeClosed == "true"
        fun candidate(): Boolean = evidence.candidate(passed())
        fun report() = buildJsonObject {
            fun tuple(value: FreshInputReadinessProtocol.Identity?) = value?.let { buildJsonObject {
                put("freshSessionId", it.sessionId); put("fixtureInstanceId", it.instanceId); put("fixturePid", it.pid)
                put("fixtureTaskId", it.taskId); put("fixtureDisplayId", it.displayId); put("fixtureProcessStartTicks", it.processStartTicks)
            } } ?: JsonNull
            fun bool(value: Boolean?) = value?.let(::JsonPrimitive) ?: JsonNull
            fun count(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull
            put("schemaVersion", 1); put("diagnosticKind", "EXISTING_BOUND_ROOT_COLLAPSE_SERVICE_TRANSPORT")
            put("source", "EXISTING_BOUND"); put("stage", if (passed()) "observed" else "failed")
            put("diagnosticPassed", passed()); put("rootAssistedCandidate", candidate())
            put("transportNonce", nonce); put("mainPid", mainPid)
            put("expectedFixtureIdentity", tuple(expected)); put("observedFixtureIdentity", tuple(identity))
            put("baselineAccepted", bool(baselineAccepted)); put("reasonCode", reason)
            put("cleanupReasonCodes", buildJsonArray { cleanupReasons.forEach { add(JsonPrimitive(it)) } })
            put("cleanup", buildJsonObject { cleanup.forEach { (key, value) -> put(key, bool(value)) } })
            put("ownedResourcesRestored", restored()); put("fixtureDisposition", if (identity == expected && expected != null && restored()) "LEFT_OPEN" else "UNCONFIRMED")
            put("fixtureClosureAttempted", false); put("fixtureLaunchAttempted", false); put("retainedReceiptProtection", "NOT_APPLICABLE")
            put("approvalAcceptance", false); put("businessApprovalConsumed", false); put("inputAttempted", false); put("modelInvoked", false)
            put("activityCreated", count(activityCreated)); put("activityResumed", count(activityResumed))
            put("serviceCreated", count(if (serviceObserved) evidence.createdCount else null))
            put("serviceDispatches", count(if (serviceObserved) evidence.dispatchCount else null))
            put("serviceDestroyed", count(if (serviceObserved) evidence.destroyedCount else null))
            put("actionClicks", actionClicks); put("expandClicks", expandClicks); put("sourceTokenless", sourceTokenless)
            put("servicePendingIntent", servicePendingIntent); put("measurementFrozenBeforeCleanup", measuredBeforeCleanup)
            put("rootCommand", "cmd statusbar collapse"); put("rootAttempts", root.attempts); put("rootOutcome", root.outcome)
            put("rootExitCode", count(root.exitCode)); put("rootOutputBytes", root.outputBytes); put("rootWithinOriginalDispatchBudget", rootWithinDispatchBudget)
            put("rootTerminationRequested", root.terminationRequested); put("rootExitConfirmed", bool(root.exitConfirmed))
            put("rootAssistedShadeClosed", evidence.automaticShadeClosed)
            put("allOriginalEditor", evidence.allOriginalEditor); put("allOriginalBoundConnection", evidence.allOriginalConnection)
            put("allCurrentConnectionConsistent", evidence.allConsistent); put("allFixtureEditorValid", evidence.allValid)
            put("transportSamples", buildJsonArray { evidence.samples.forEach { sample -> add(buildJsonObject {
                put("phase", sample.phase.name); put("mainThread", sample.mainThread); put("editorPresent", sample.editorPresent)
                put("sameEditor", sample.sameEditor); put("sameBoundConnection", sample.sameBoundConnection)
                put("currentConnectionConsistent", sample.currentConnectionConsistent); put("fixtureEditorValid", sample.fixtureEditorValid)
                put("focus", sample.focus.name); put("fixtureEditorVisible", sample.fixtureEditorVisible)
                put("sameFixtureWindow", sample.sameFixtureWindow); put("shade", sample.shade.name)
            }) } })
        }
    }
}
