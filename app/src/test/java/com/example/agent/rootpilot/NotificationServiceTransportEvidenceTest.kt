package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Focus
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Phase
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Sample
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Session
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.ServiceCleanup
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Shade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationServiceTransportEvidenceTest {
    private val owner = Any()
    private fun session() = Session("synthetic-nonce", 71, 81)
    private fun sample(phase: Phase) = Sample(phase, true, true, true, true, true, true,
        if (phase == Phase.SHADE_OPENED || phase == Phase.BEFORE_ACTION) Focus.SYSTEM_UI else Focus.FIXTURE,
        true, true, if (phase == Phase.SHADE_OPENED || phase == Phase.BEFORE_ACTION) Shade.PRESENT else Shade.ABSENT)
    private fun ready(order: List<Phase> = Phase.entries): Session = session().also { state ->
        assertTrue(state.created(owner, 71, 81, true))
        assertTrue(state.received(owner, "synthetic-nonce", true, 71, 81, true))
        assertTrue(state.destroyed(owner, 71, 81, true))
        order.forEach { assertTrue(state.record(sample(it))) }
        state.freeze()
    }

    @Test fun nextTurnMayPrecedeDestruction() { assertTrue(ready().candidate(true)) }
    @Test fun destructionMayPrecedeNextTurn() {
        val phases = Phase.entries.toMutableList().apply { val next = indexOf(Phase.NEXT_MAIN_TURN); val destroy = indexOf(Phase.SERVICE_DESTROYED)
            this[next] = Phase.SERVICE_DESTROYED; this[destroy] = Phase.NEXT_MAIN_TURN }
        assertTrue(ready(phases).candidate(true))
    }
    @Test fun everyOtherPermutationIsRejected() {
        fun permutations(values: List<Phase>): List<List<Phase>> = if (values.isEmpty()) listOf(emptyList()) else
            values.flatMap { head -> permutations(values - head).map { listOf(head) + it } }
        assertEquals(2, permutations(Phase.entries).count(NotificationServiceTransportEvidence::legalPrefix))
    }
    @Test fun duplicateMissingOrPrematureFinalPhaseCannotPass() {
        assertFalse(NotificationServiceTransportEvidence.legalPrefix(listOf(Phase.BASELINE, Phase.BASELINE)))
        assertFalse(NotificationServiceTransportEvidence.legalPrefix(listOf(Phase.SHADE_OPENED)))
        assertFalse(NotificationServiceTransportEvidence.legalPrefix(Phase.entries.filter { it != Phase.SERVICE_DESTROYED }))
        val missing = ready(Phase.entries.dropLast(1))
        assertFalse(missing.complete); assertFalse(missing.candidate(true))
    }
    @Test fun wrongNonceIsRejected() {
        val state = session(); state.created(owner, 71, 81, true)
        assertFalse(state.received(owner, "other", true, 71, 81, true)); assertFalse(state.complete)
    }
    @Test fun wrongSourceAndServiceInstanceAreRejected() {
        val state = session(); state.created(owner, 71, 81, true)
        assertFalse(state.received(owner, "synthetic-nonce", false, 71, 81, true))
        val other = session(); other.created(owner, 71, 81, true)
        assertFalse(other.received(Any(), "synthetic-nonce", true, 71, 81, true))
    }
    @Test fun wrongPidUidOrThreadIsRejected() {
        listOf(Triple(72, 81, true), Triple(71, 82, true), Triple(71, 81, false)).forEach { (pid, uid, main) ->
            assertFalse(session().created(owner, pid, uid, main))
            val state = session(); state.created(owner, 71, 81, true)
            assertFalse(state.received(owner, "synthetic-nonce", true, pid, uid, main))
        }
    }
    @Test fun duplicateDispatchCannotPass() {
        val state = ready(); assertFalse(state.received(owner, "synthetic-nonce", true, 71, 81, true)); assertFalse(state.candidate(true))
    }
    @Test fun duplicateCreationOrDestructionCannotPass() {
        val created = ready(); assertFalse(created.created(Any(), 71, 81, true)); assertFalse(created.candidate(true))
        val destroyed = ready(); assertFalse(destroyed.destroyed(owner, 71, 81, true)); assertFalse(destroyed.candidate(true))
    }
    @Test fun lateRecordAndLaunchAreRejectedAfterClose() {
        val state = ready(); state.close()
        assertFalse(state.record(sample(Phase.FINAL_MEASURED)))
        assertFalse(state.received(owner, "synthetic-nonce", true, 71, 81, true)); assertFalse(state.candidate(true))
    }
    @Test fun everyEditorAndConnectionGuardIsIndependent() {
        val alterations: List<(Sample) -> Sample> = listOf(
            { it.copy(editorPresent = false) }, { it.copy(sameEditor = false) },
            { it.copy(sameBoundConnection = false) }, { it.copy(currentConnectionConsistent = false) },
            { it.copy(fixtureEditorValid = false) },
        )
        alterations.forEach { change ->
            val state = session(); state.created(owner, 71, 81, true); state.received(owner, "synthetic-nonce", true, 71, 81, true)
            state.destroyed(owner, 71, 81, true)
            Phase.entries.forEach { phase -> assertTrue(state.record(sample(phase).let { if (phase == Phase.SERVICE_RECEIVED) change(it) else it })) }
            state.freeze(); assertTrue(state.complete); assertFalse(state.candidate(true))
        }
    }
    @Test fun visibleFixtureWithoutFocusedWindowIsNotAutomaticClosure() {
        val state = finalState(sample(Phase.FINAL_MEASURED).copy(focus = Focus.SYSTEM_UI, shade = Shade.PRESENT))
        assertEquals("false", state.automaticShadeClosed); assertFalse(state.candidate(true))
    }
    @Test fun unknownWindowOrShadeEvidenceRemainsUnknown() {
        listOf(sample(Phase.FINAL_MEASURED).copy(focus = Focus.UNKNOWN), sample(Phase.FINAL_MEASURED).copy(shade = Shade.UNKNOWN),
            sample(Phase.FINAL_MEASURED).copy(fixtureEditorVisible = false), sample(Phase.FINAL_MEASURED).copy(sameFixtureWindow = false)).forEach {
            val state = finalState(it); assertEquals("unknown", state.automaticShadeClosed); assertFalse(state.candidate(true))
        }
    }
    @Test fun anyDiagnosticOrCleanupFailureBlocksCandidate() { assertFalse(ready().candidate(false)) }
    @Test fun frozenMeasurementCannotBecomeSuccessfulDuringCleanup() {
        val state = finalState(sample(Phase.FINAL_MEASURED).copy(focus = Focus.SYSTEM_UI, shade = Shade.PRESENT))
        assertFalse(state.record(sample(Phase.FINAL_MEASURED)))
        assertEquals("false", state.automaticShadeClosed); assertFalse(state.candidate(true))
    }
    @Test fun destructionMustBelongToDispatchOwner() {
        val state = session(); state.created(owner, 71, 81, true); state.received(owner, "synthetic-nonce", true, 71, 81, true)
        assertFalse(state.destroyed(Any(), 71, 81, true)); assertFalse(state.complete)
    }
    @Test fun nonMainSampleAndEmptyEvidenceCannotPass() {
        val state = session(); assertFalse(state.record(sample(Phase.BASELINE).copy(mainThread = false)))
        assertFalse(state.complete); assertEquals("unknown", state.automaticShadeClosed); assertFalse(state.candidate(true))
    }
    @Test fun closePreservesAlreadyMeasuredOutcome() {
        val state = ready(); state.close(); assertTrue(state.candidate(true)); assertEquals("true", state.automaticShadeClosed)
    }
    @Test fun foreignOrUnregisteredObserverNeverPermitsServiceCleanup() {
        listOf(false to false, false to true, true to false).forEach { (registered, owned) ->
            listOf(false, true).forEach { instance -> listOf(false, true).forEach { dispatched ->
                listOf(false, true).forEach { clicked -> listOf(false, true).forEach { destroyed ->
                    assertEquals(ServiceCleanup.UNOWNED, NotificationServiceTransportEvidence.serviceCleanup(
                        registered, owned, instance, dispatched, clicked, destroyed))
                } }
            } }
        }
    }
    @Test fun ownedObserverWithoutAnyStartNeedsNoStopOrWait() {
        assertEquals(ServiceCleanup.NOT_STARTED, NotificationServiceTransportEvidence.serviceCleanup(
            registered = true, currentObserverOwned = true, instanceObserved = false,
            dispatchObserved = false, actionClicked = false, destroyed = false))
    }
    @Test fun clickInFlightBeforeOnCreateStillRequiresStopAndDestruction() {
        assertEquals(ServiceCleanup.STOP_AND_AWAIT, NotificationServiceTransportEvidence.serviceCleanup(
            registered = true, currentObserverOwned = true, instanceObserved = false,
            dispatchObserved = false, actionClicked = true, destroyed = false))
    }
    @Test fun observedInstanceOrDispatchRequiresStopAndDestruction() {
        listOf(true to false, false to true, true to true).forEach { (instance, dispatched) ->
            assertEquals(ServiceCleanup.STOP_AND_AWAIT, NotificationServiceTransportEvidence.serviceCleanup(
                registered = true, currentObserverOwned = true, instanceObserved = instance,
                dispatchObserved = dispatched, actionClicked = false, destroyed = false))
        }
    }
    @Test fun alreadyDestroyedOwnedInstanceDoesNotStopAnotherService() {
        assertEquals(ServiceCleanup.DESTROYED, NotificationServiceTransportEvidence.serviceCleanup(
            registered = true, currentObserverOwned = true, instanceObserved = true,
            dispatchObserved = true, actionClicked = true, destroyed = true))
    }
    @Test fun observerReplacedAfterOwnClickCannotStopOrReportSuccess() {
        assertEquals(ServiceCleanup.UNOWNED, NotificationServiceTransportEvidence.serviceCleanup(
            registered = true, currentObserverOwned = false, instanceObserved = false,
            dispatchObserved = false, actionClicked = true, destroyed = false))
    }
    private fun finalState(final: Sample): Session = session().also { state ->
        state.created(owner, 71, 81, true); state.received(owner, "synthetic-nonce", true, 71, 81, true); state.destroyed(owner, 71, 81, true)
        Phase.entries.forEach { state.record(if (it == Phase.FINAL_MEASURED) final else sample(it)) }; state.freeze()
    }
}
