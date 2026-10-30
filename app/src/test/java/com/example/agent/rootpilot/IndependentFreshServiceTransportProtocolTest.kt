package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Focus
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Phase
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Sample
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Shade
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class IndependentFreshServiceTransportProtocolTest {
    private val session = "afe2c3df-0088-41d4-b49f-3b7629721100"
    private val nonce = "bfe2c3df-0088-41d4-b49f-3b7629721100"
    private val identity = FreshInputReadinessProtocol.Identity(session, "cfe2c3df-0088-41d4-b49f-3b7629721100", 200, 3, 0, 45L)
    private val args get() = mapOf(IndependentFreshServiceTransportProtocol.MODE to "true", "freshSessionId" to session)
    private fun observation(shade: Shade = Shade.ABSENT): IndependentFreshServiceTransportProtocol.Observation {
        val evidence = NotificationServiceTransportEvidence.Session(nonce, 100, 101)
        val owner = Any()
        assertTrue(evidence.created(owner, 100, 101, true))
        assertTrue(evidence.received(owner, nonce, true, 100, 101, true))
        assertTrue(evidence.destroyed(owner, 100, 101, true))
        Phase.entries.forEach { phase ->
            assertTrue(evidence.record(Sample(phase, true, true, true, true, true, true,
                if (phase == Phase.SHADE_OPENED || phase == Phase.BEFORE_ACTION) Focus.SYSTEM_UI else Focus.FIXTURE,
                true, true, if (phase == Phase.SHADE_OPENED || phase == Phase.BEFORE_ACTION) Shade.PRESENT else shade)))
        }
        evidence.close()
        return IndependentFreshServiceTransportProtocol.Observation(session, nonce, 100,
            FreshInputReadinessProtocol.Creation("READY", "NONE", identity, false), identity, true, true,
            true, "NONE", emptyList(), IndependentFreshServiceTransportProtocol.cleanupKeys.associateWith { true },
            0, 0, true, 1, 0, true, true, true, evidence)
    }
    @Test fun explicitIndependentAdmissionRejectsEveryOldReceiptModeAndDiagnosticFlag() {
        assertEquals(session, IndependentFreshServiceTransportProtocol.admit(args + ("class" to "test_runner_class")))
        listOf("notificationServiceTransportDiagnostic", "notificationInputReadinessDiagnostic", "freshNotificationInputReadinessDiagnostic",
            "recoveryReceiptId", "retainedFailureReceiptId", "freshFixtureArrivalDiagnostic", "freshFixtureLifecycleDiagnostic",
            "freshHandoffStageDiagnostic").forEach { key ->
            listOf("true", "false", session).forEach { value ->
                assertThrows(Exception::class.java) { IndependentFreshServiceTransportProtocol.admit(args + (key to value)) }
            }
        }
        listOf(null, "", session.uppercase(), "not-a-session", true, 1).forEach { value ->
            assertThrows(Exception::class.java) { IndependentFreshServiceTransportProtocol.admit(args + ("freshSessionId" to value)) }
        }
        assertThrows(Exception::class.java) { IndependentFreshServiceTransportProtocol.admit(args - IndependentFreshServiceTransportProtocol.MODE) }
    }
    @Test fun closedIndependentSchemaHasNoLegacyReceiptOrStageFields() {
        val report = observation().report()
        assertEquals(setOf("schemaVersion", "diagnosticKind", "stage", "diagnosticPassed", "transportCandidate", "freshSessionId", "transportNonce",
            "mainPid", "creationOutcome", "creationReason", "rejectedAdmissionSelfFinishAttempted", "freshFixtureIdentity", "prelaunchFixtureEmpty",
            "baselineAccepted", "reasonCode", "cleanupReasonCodes", "cleanup", "ownedResourcesRestored", "fixtureDisposition", "fixtureClosureAttempted",
            "retainedReceiptProtection", "approvalAcceptance", "businessApprovalConsumed", "inputAttempted", "modelInvoked", "activityCreated",
            "activityResumed", "serviceCreated", "serviceDispatches", "serviceDestroyed", "actionClicks", "expandClicks", "sourceTokenless",
            "servicePendingIntent", "measurementFrozenBeforeCleanup", "automaticShadeClosed", "allOriginalEditor", "allOriginalBoundConnection",
            "allCurrentConnectionConsistent", "allFixtureEditorValid", "transportSamples"), report.keys)
        assertEquals("INDEPENDENT_FRESH_SERVICE_TRANSPORT", report.getValue("diagnosticKind").jsonPrimitive.content)
        assertEquals("NOT_APPLICABLE", report.getValue("retainedReceiptProtection").jsonPrimitive.content)
        assertEquals(IndependentFreshServiceTransportProtocol.cleanupKeys, report.getValue("cleanup").jsonObject.keys)
        assertEquals("true", report.getValue("transportCandidate").jsonPrimitive.content)
        listOf("recoveryReceiptId", "retainedFailureProtection", "receiptUnchanged", "notificationServiceTransportStage").forEach { assertFalse(it in report) }
        listOf("modelInvoked", "inputAttempted", "approvalAcceptance", "businessApprovalConsumed", "fixtureClosureAttempted").forEach {
            assertEquals("false", report.getValue(it).jsonPrimitive.content)
        }
    }
    @Test fun everyCleanupFalseOrUnknownBlocksPassWithoutInventingReceiptSuccess() {
        val good = observation()
        IndependentFreshServiceTransportProtocol.cleanupKeys.forEach { key ->
            listOf(false, null).forEach { value ->
                val bad = good.copy(cleanup = good.cleanup + (key to value))
                assertFalse(bad.passed()); assertEquals("false", bad.report().getValue("transportCandidate").jsonPrimitive.content)
                assertEquals("UNCONFIRMED", bad.report().getValue("fixtureDisposition").jsonPrimitive.content)
                if (value == null) assertEquals(JsonNull, bad.report().getValue("cleanup").jsonObject.getValue(key))
            }
        }
        assertThrows(Exception::class.java) { good.copy(cleanup = good.cleanup - "imeRestored").passed() }
        assertThrows(Exception::class.java) { good.copy(cleanup = good.cleanup + ("receiptUnchanged" to true)).passed() }
    }
    @Test fun failedCreationCannotBeRepairedByDispositionIdentityOrSuccessfulCleanup() {
        val good = observation()
        listOf("NOT_STARTED", "TIMED_OUT", "ERROR", "REJECTED").forEach { outcome ->
            val bad = good.copy(creation = FreshInputReadinessProtocol.Creation(outcome, outcome, null, null))
            assertFalse(bad.passed())
            assertEquals(outcome, bad.report().getValue("creationOutcome").jsonPrimitive.content)
            assertEquals("false", bad.report().getValue("transportCandidate").jsonPrimitive.content)
        }
        val absent = good.copy(identity = null, activityCreated = null, activityResumed = null, serviceObserved = false,
            cleanup = good.cleanup.mapValues { null }, prelaunchEmpty = null, baselineAccepted = null,
            creation = FreshInputReadinessProtocol.Creation("NOT_STARTED", "NONE", null, null))
        val report = absent.report()
        listOf("freshFixtureIdentity", "activityCreated", "activityResumed", "serviceCreated", "serviceDispatches", "serviceDestroyed",
            "prelaunchFixtureEmpty", "baselineAccepted", "rejectedAdmissionSelfFinishAttempted").forEach { assertEquals(JsonNull, report.getValue(it)) }
    }
    @Test fun baselineBindingLifecycleAndCleanupFailuresAllBlockPass() {
        val good = observation()
        listOf(good.copy(baselineAccepted = false), good.copy(baselineAccepted = null), good.copy(prelaunchEmpty = false),
            good.copy(identity = identity.copy(taskId = 4)), good.copy(completed = false), good.copy(reason = "EDITOR_READY"),
            good.copy(cleanupReasons = listOf("CLEANUP_INCOMPLETE")), good.copy(activityCreated = 1), good.copy(activityResumed = null),
            good.copy(sourceTokenless = false), good.copy(servicePendingIntent = false), good.copy(measuredBeforeCleanup = false),
            good.copy(actionClicks = 2), good.copy(serviceObserved = false)).forEach {
            assertFalse(it.passed()); assertEquals("false", it.report().getValue("transportCandidate").jsonPrimitive.content)
        }
    }
    @Test fun completeDiagnosticDoesNotPromoteUnknownOrStillOpenShadeToTransportCandidate() {
        listOf(Shade.UNKNOWN, Shade.PRESENT).forEach { shade ->
            val current = observation(shade)
            assertTrue(current.passed())
            assertEquals("false", current.report().getValue("transportCandidate").jsonPrimitive.content)
        }
    }
}
