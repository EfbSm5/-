package com.example.agent.rootpilot

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test-only independent admission and closed report; never a retained-failure repair. */
internal object IndependentFreshServiceTransportProtocol {
    enum class Source { RETAINED_FAILURE, INDEPENDENT_FRESH }
    const val MODE = "independentFreshNotificationServiceTransportDiagnostic"
    const val REPORT = "independentFreshServiceTransportReport"
    val cleanupKeys = setOf("fixtureBindingConfirmed", "rootServiceUnchanged", "probeServiceStopped",
        "serviceObserverUnregistered", "activityGuardUnregistered", "pendingIntentCancelled", "notificationsRestored",
        "imeRestored", "automationReleased", "configuratorRestored", "accessibilityRestored")

    fun admit(args: Map<String, Any?>): String {
        require(args[MODE] == "true")
        require(setOf("notificationServiceTransportDiagnostic", "notificationInputReadinessDiagnostic",
            "freshNotificationInputReadinessDiagnostic", "recoveryReceiptId", "retainedFailureReceiptId",
            "freshFixtureArrivalDiagnostic", "freshFixtureLifecycleDiagnostic", "freshHandoffStageDiagnostic")
            .none { it in args })
        val session = args["freshSessionId"] as? String ?: error("independent_session")
        require(FreshInputReadinessProtocol.canonicalId(session))
        return session
    }

    data class Observation(
        val session: String?, val nonce: String, val mainPid: Int,
        val creation: FreshInputReadinessProtocol.Creation, val identity: FreshInputReadinessProtocol.Identity?,
        val prelaunchEmpty: Boolean?, val baselineAccepted: Boolean?,
        val completed: Boolean, val reason: String, val cleanupReasons: List<String>,
        val cleanup: Map<String, Boolean?>,
        val activityCreated: Int?, val activityResumed: Int?, val serviceObserved: Boolean,
        val actionClicks: Int, val expandClicks: Int,
        val sourceTokenless: Boolean, val servicePendingIntent: Boolean, val measuredBeforeCleanup: Boolean,
        val evidence: NotificationServiceTransportEvidence.Session,
    ) {
        fun passed(): Boolean {
            require(cleanup.keys == cleanupKeys)
            return session != null && FreshInputReadinessProtocol.canonicalId(session) &&
                creation.outcome == "READY" && creation.reason == "NONE" && creation.selfFinish == false &&
                identity != null && identity == creation.identity && identity.sessionId == session &&
                prelaunchEmpty == true && baselineAccepted == true && completed && reason == "NONE" &&
                cleanupReasons.isEmpty() && cleanup.values.all { it == true } &&
                evidence.complete && evidence.closed && serviceObserved && activityCreated == 0 && activityResumed == 0 &&
                sourceTokenless && servicePendingIntent && measuredBeforeCleanup && actionClicks == 1
        }

        fun report() = buildJsonObject {
            val passed = passed()
            fun bool(value: Boolean?) = value?.let(::JsonPrimitive) ?: JsonNull
            fun count(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull
            put("schemaVersion", 1)
            put("diagnosticKind", "INDEPENDENT_FRESH_SERVICE_TRANSPORT")
            put("stage", if (passed) "observed" else "failed")
            put("diagnosticPassed", passed)
            put("transportCandidate", evidence.candidate(passed))
            put("freshSessionId", session?.takeIf(FreshInputReadinessProtocol::canonicalId)?.let(::JsonPrimitive) ?: JsonNull)
            put("transportNonce", nonce)
            put("mainPid", mainPid)
            put("creationOutcome", creation.outcome)
            put("creationReason", creation.reason)
            put("rejectedAdmissionSelfFinishAttempted", bool(creation.selfFinish))
            put("freshFixtureIdentity", identity?.let { id -> buildJsonObject {
                put("freshSessionId", id.sessionId); put("fixtureInstanceId", id.instanceId); put("fixturePid", id.pid)
                put("fixtureTaskId", id.taskId); put("fixtureDisplayId", id.displayId); put("fixtureProcessStartTicks", id.processStartTicks)
            } } ?: JsonNull)
            put("prelaunchFixtureEmpty", bool(prelaunchEmpty))
            put("baselineAccepted", bool(baselineAccepted))
            put("reasonCode", reason)
            put("cleanupReasonCodes", buildJsonArray { cleanupReasons.forEach { add(JsonPrimitive(it)) } })
            put("cleanup", buildJsonObject { cleanupKeys.forEach { put(it, bool(cleanup[it])) } })
            val restored = cleanup.values.all { it == true } && cleanupReasons.isEmpty()
            put("ownedResourcesRestored", restored)
            put("fixtureDisposition", if (restored && cleanup["fixtureBindingConfirmed"] == true && identity != null)
                "LEFT_OPEN_OWNED" else "UNCONFIRMED")
            put("fixtureClosureAttempted", false)
            put("retainedReceiptProtection", "NOT_APPLICABLE")
            put("approvalAcceptance", false); put("businessApprovalConsumed", false)
            put("inputAttempted", false); put("modelInvoked", false)
            put("activityCreated", count(activityCreated)); put("activityResumed", count(activityResumed))
            put("serviceCreated", count(evidence.createdCount.takeIf { serviceObserved }))
            put("serviceDispatches", count(evidence.dispatchCount.takeIf { serviceObserved }))
            put("serviceDestroyed", count(evidence.destroyedCount.takeIf { serviceObserved }))
            put("actionClicks", actionClicks); put("expandClicks", expandClicks)
            put("sourceTokenless", sourceTokenless); put("servicePendingIntent", servicePendingIntent)
            put("measurementFrozenBeforeCleanup", measuredBeforeCleanup)
            put("automaticShadeClosed", evidence.automaticShadeClosed)
            put("allOriginalEditor", evidence.allOriginalEditor); put("allOriginalBoundConnection", evidence.allOriginalConnection)
            put("allCurrentConnectionConsistent", evidence.allConsistent); put("allFixtureEditorValid", evidence.allValid)
            put("transportSamples", buildJsonArray { evidence.samples.forEach { s -> add(buildJsonObject {
                put("phase", s.phase.name); put("mainThread", s.mainThread); put("editorPresent", s.editorPresent)
                put("sameEditor", s.sameEditor); put("sameBoundConnection", s.sameBoundConnection)
                put("currentConnectionConsistent", s.currentConnectionConsistent); put("fixtureEditorValid", s.fixtureEditorValid)
                put("focus", s.focus.name); put("fixtureEditorVisible", s.fixtureEditorVisible)
                put("sameFixtureWindow", s.sameFixtureWindow); put("shade", s.shade.name)
            }) } })
        }
    }
}
