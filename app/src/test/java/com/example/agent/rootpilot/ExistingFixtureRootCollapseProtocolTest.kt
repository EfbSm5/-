package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Focus
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Phase
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Sample
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Shade
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ExistingFixtureRootCollapseProtocolTest {
    private val identity = FreshInputReadinessProtocol.Identity("afe2c3df-0088-41d4-b49f-3b7629721100",
        "bfe2c3df-0088-41d4-b49f-3b7629721100", 200, 3, 0, 45L)
    private val nonce = "cfe2c3df-0088-41d4-b49f-3b7629721100"
    private val args get() = mapOf(ExistingFixtureRootCollapseProtocol.MODE to "true", "expectedFreshSessionId" to identity.sessionId,
        "expectedFixtureInstanceId" to identity.instanceId, "expectedFixturePid" to "200", "expectedFixtureTaskId" to "3",
        "expectedFixtureDisplayId" to "0", "expectedFixtureProcessStartTicks" to "45")
    private val state get(): Map<String, Any?> = mapOf("freshSessionId" to identity.sessionId, "instanceId" to identity.instanceId,
        "processId" to 200, "fixtureTaskId" to 3, "fixtureDisplayId" to 0, "fixtureProcessStartTicks" to 45L,
        "ready" to true, "empty" to true, "matches" to false, "fieldId" to 16908291, "commitCalls" to 0)
    private fun good(change: (Sample) -> Sample = { it }): ExistingFixtureRootCollapseProtocol.Observation {
        val evidence = NotificationServiceTransportEvidence.Session(nonce, 100, 101); val owner = Any()
        assertTrue(evidence.created(owner, 100, 101, true)); assertTrue(evidence.received(owner, nonce, true, 100, 101, true))
        assertTrue(evidence.destroyed(owner, 100, 101, true))
        Phase.entries.forEach { phase ->
            val shadeOpen = phase == Phase.SHADE_OPENED || phase == Phase.BEFORE_ACTION
            assertTrue(evidence.record(change(Sample(phase, true, true, true, true, true, true,
                if (shadeOpen) Focus.SYSTEM_UI else Focus.FIXTURE, true, true, if (shadeOpen) Shade.PRESENT else Shade.ABSENT))))
        }
        evidence.close()
        return ExistingFixtureRootCollapseProtocol.Observation(identity, identity, nonce, 100, true, true, "NONE", emptyList(),
            IndependentFreshServiceTransportProtocol.cleanupKeys.associateWith { true }, 0, 0, true, 1, 0, true, true, true,
            FixedStatusBarCollapse.Result(1, "SUCCESS", 0, 0, true, true), true, evidence)
    }
    @Test fun admissionIsClosedExplicitCanonicalSixTupleNotFreshOrReceipt() {
        assertEquals(identity, ExistingFixtureRootCollapseProtocol.admit(args + ("class" to "test"), 100))
        args.keys.forEach { key ->
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args - key, 100) }
            listOf(null, true, 1, "", "01", "-1", " 1").forEach { value ->
                assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args + (key to value), 100) }
            }
        }
        listOf("freshSessionId", "recoveryReceiptId", IndependentFreshServiceTransportProtocol.MODE, "freshFixtureLifecycleDiagnostic").forEach {
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args + (it to "false"), 100) }
        }
        assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args, 200) }
        listOf("0", "999999999999999999999999999999").forEach { invalid ->
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args + ("expectedFixturePid" to invalid), 100) }
        }
        assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.admit(args + ("expectedFixtureDisplayId" to "1"), 100) }
    }
    @Test fun signedStateMustMatchWholeTupleAndOriginalReadyEmptyZeroPredicates() {
        assertEquals(identity, ExistingFixtureRootCollapseProtocol.state(state, identity, 100))
        state.keys.forEach { key ->
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(state - key, identity, 100) }
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(state + (key to null), identity, 100) }
        }
        listOf(identity.copy(sessionId = nonce), identity.copy(instanceId = nonce), identity.copy(pid = 201),
            identity.copy(taskId = 4), identity.copy(displayId = 1), identity.copy(processStartTicks = 46L)).forEach {
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(state, it, 100) }
        }
        mapOf("ready" to false, "empty" to false, "matches" to true, "commitCalls" to 1, "fieldId" to 1,
            "fixtureProcessStartTicks" to 45).forEach { (key, value) ->
            assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(state + (key to value), identity, 100) }
        }
        assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(emptyMap(), identity, 100) }
        assertThrows(Exception::class.java) { ExistingFixtureRootCollapseProtocol.state(state + ("rawText" to "forbidden"), identity, 100) }
    }
    @Test fun distinctClosedReportNeverInventsCreationOrAutomaticSuccess() {
        val report = good().report()
        assertTrue(good().passed()); assertTrue(good().candidate())
        assertEquals("EXISTING_BOUND", report.getValue("source").jsonPrimitive.content)
        assertEquals("EXISTING_BOUND_ROOT_COLLAPSE_SERVICE_TRANSPORT", report.getValue("diagnosticKind").jsonPrimitive.content)
        assertEquals("true", report.getValue("rootAssistedCandidate").jsonPrimitive.content)
        listOf("creationOutcome", "prelaunchFixtureEmpty", "transportCandidate", "automaticShadeClosed", "receiptUnchanged").forEach { assertFalse(it in report) }
        listOf("fixtureLaunchAttempted", "fixtureClosureAttempted", "inputAttempted", "approvalAcceptance", "modelInvoked").forEach {
            assertEquals("false", report.getValue(it).jsonPrimitive.content)
        }
        val absent = good().copy(identity = null, baselineAccepted = null, activityCreated = null, serviceObserved = false).report()
        listOf("observedFixtureIdentity", "baselineAccepted", "activityCreated", "serviceCreated").forEach { assertEquals(JsonNull, absent.getValue(it)) }
    }
    @Test fun rootExitZeroNeverSubstitutesForWindowShadeAndEveryOriginalConnectionGuard() {
        val changes: List<(Sample) -> Sample> = listOf({ it.copy(editorPresent = false) }, { it.copy(sameEditor = false) },
            { it.copy(sameBoundConnection = false) }, { it.copy(currentConnectionConsistent = false) }, { it.copy(fixtureEditorValid = false) },
            { it.copy(focus = Focus.SYSTEM_UI, shade = Shade.PRESENT) }, { it.copy(fixtureEditorVisible = false) },
            { it.copy(sameFixtureWindow = false) }, { it.copy(shade = Shade.UNKNOWN) })
        changes.forEach { change ->
            val bad = good { if (it.phase == Phase.FINAL_MEASURED) change(it) else it }
            assertFalse(bad.passed()); assertFalse(bad.candidate())
            assertEquals("false", bad.report().getValue("rootAssistedCandidate").jsonPrimitive.content)
        }
        val frozen = good { if (it.phase == Phase.FINAL_MEASURED) it.copy(shade = Shade.UNKNOWN) else it }
        val before = frozen.report()
        assertFalse(frozen.evidence.record(frozen.evidence.samples.last().copy(shade = Shade.ABSENT)))
        assertFalse(frozen.copy(cleanup = frozen.cleanup.mapValues { true }).candidate())
        assertEquals(before.getValue("rootAssistedShadeClosed"), frozen.report().getValue("rootAssistedShadeClosed"))
    }
    @Test fun baselineCleanupBindingTimeoutAndUnconfirmedExitAllFailClosed() {
        val valid = good()
        listOf(valid.copy(expected = null), valid.copy(identity = identity.copy(taskId = 9)), valid.copy(baselineAccepted = false),
            valid.copy(baselineAccepted = null), valid.copy(completed = false), valid.copy(reason = "DISPATCH"),
            valid.copy(activityCreated = null), valid.copy(activityResumed = 1), valid.copy(serviceObserved = false),
            valid.copy(sourceTokenless = false), valid.copy(servicePendingIntent = false), valid.copy(measuredBeforeCleanup = false),
            valid.copy(actionClicks = 2), valid.copy(rootWithinDispatchBudget = false),
            valid.copy(root = valid.root.copy(exitConfirmed = false))).forEach { assertFalse(it.passed()) }
        listOf("NOT_STARTED", "START_FAILED", "OUTPUT_LIMIT", "NONZERO", "TIMED_OUT", "CANCELLED", "IO_ERROR").forEach {
            assertFalse(valid.copy(root = valid.root.copy(outcome = it)).candidate())
        }
        valid.cleanup.keys.forEach { key -> listOf(false, null).forEach { value ->
            val bad = valid.copy(cleanup = valid.cleanup + (key to value))
            assertFalse(bad.passed()); assertEquals("UNCONFIRMED", bad.report().getValue("fixtureDisposition").jsonPrimitive.content)
        } }
    }
}
