package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationInputReadinessEvidence.Baseline
import com.example.agent.rootpilot.NotificationInputReadinessEvidence.ObserverCleanup
import com.example.agent.rootpilot.NotificationInputReadinessEvidence.Reason
import com.example.agent.rootpilot.NotificationInputReadinessEvidence.ServiceCleanup
import com.example.agent.rootpilot.NotificationInputReadinessEvidence.Wait
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Focus
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Phase
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Sample
import com.example.agent.rootpilot.NotificationServiceTransportEvidence.Shade
import org.junit.Assert.*
import org.junit.Test

class NotificationInputReadinessEvidenceTest {
    private fun sample() = Sample(Phase.BASELINE, true, true, true, true, true, true, Focus.FIXTURE, true, true, Shade.UNKNOWN)
    private fun accepted() = NotificationInputReadinessEvidence().apply {
        wait = Wait.SATISFIED; sampleStarted = true; sample = sample()
        serviceObserverWasRegistered = true; activityGuardWasRegistered = true
        serviceCleanup = ServiceCleanup.NOT_STARTED
        serviceObserverCleanup = ObserverCleanup.UNREGISTERED_OWNED
        activityGuardCleanup = ObserverCleanup.UNREGISTERED_OWNED
    }
    private fun NotificationInputReadinessEvidence.passes(
        completed: Boolean = true, source: String = "NONE", cleanup: Boolean = false,
        closed: Boolean = true, forbidden: Boolean = false, service: Int = 0, activity: Int = 0,
    ) = passed(completed, source, cleanup, closed, forbidden, service, activity)

    @Test fun acceptedBaselineAndOwnedCleanupCanPass() {
        val s = accepted()
        assertEquals(Baseline.ACCEPTED, s.baseline("NONE")); assertTrue(s.failureFields.isEmpty())
        assertEquals(Reason.NONE, s.reason("NONE", false, false)); assertTrue(s.passes())
    }
    @Test fun exactlyTheOriginalSevenFieldsDefineRejection() {
        val changes: List<Pair<String, (Sample) -> Sample>> = listOf(
            "sameEditor" to { it.copy(sameEditor = false) },
            "sameBoundConnection" to { it.copy(sameBoundConnection = false) },
            "currentConnectionConsistent" to { it.copy(currentConnectionConsistent = false) },
            "fixtureEditorValid" to { it.copy(fixtureEditorValid = false) },
            "focus" to { it.copy(focus = Focus.OTHER) },
            "fixtureEditorVisible" to { it.copy(fixtureEditorVisible = false) },
            "sameFixtureWindow" to { it.copy(sameFixtureWindow = false) },
        )
        changes.forEach { (name, change) ->
            val s = accepted().apply { sample = change(sample!!) }
            assertEquals(listOf(name), s.failureFields)
            assertEquals(Baseline.REJECTED, s.baseline("EDITOR_READY"))
            assertEquals(Reason.BASELINE_REJECTED, s.reason("EDITOR_READY", true, false))
            assertFalse(s.passes(source = "EDITOR_READY"))
        }
    }
    @Test fun falseFieldsHaveOriginalStableOrder() {
        val s = accepted().apply { sample = sample!!.copy(sameEditor = false, fixtureEditorValid = false, focus = Focus.UNKNOWN, sameFixtureWindow = false) }
        assertEquals(listOf("sameEditor", "fixtureEditorValid", "focus", "sameFixtureWindow"), s.failureFields)
    }
    @Test fun absenceAndPartialSampleAreNotFalseSamples() {
        val s = NotificationInputReadinessEvidence()
        assertNull(s.sample); assertTrue(s.failureFields.isEmpty()); assertTrue(s.details.values().values.all { it == null })
        assertEquals(Baseline.NOT_REACHED, s.baseline("IME_SELECT"))
        s.sampleStarted = true; s.details.windowsPresent = false
        assertEquals(Baseline.SAMPLE_ERROR, s.baseline("WINDOW_SAMPLE"))
        assertEquals(false, s.details.windowsPresent); assertNull(s.details.rootsComplete)
        assertTrue(s.failureFields.isEmpty())
    }
    @Test fun timeoutAndOtherWaitErrorsAreDistinctAndNeverPass() {
        val s = NotificationInputReadinessEvidence()
        s.wait = Wait.TIMED_OUT
        assertEquals(Reason.WAIT_TIMEOUT, s.reason("EDITOR_READY", false, false)); assertFalse(s.passes())
        s.wait = Wait.ERROR
        assertEquals(Reason.WAIT_ERROR, s.reason("EDITOR_READY", false, false)); assertFalse(s.passes())
    }
    @Test fun preparationUiAndSampleErrorsAreFixedCodes() {
        val s = NotificationInputReadinessEvidence()
        assertEquals(Reason.PREPARATION_REJECTED, s.reason("IME_SELECT", false, false))
        s.wait = Wait.SATISFIED
        assertEquals(Reason.UI_INITIALIZATION_ERROR, s.reason("UI_INITIALIZE", true, false))
        s.sampleStarted = true
        assertEquals(Reason.BASELINE_SAMPLE_ERROR, s.reason("WINDOW_SAMPLE", true, false))
        s.sample = sample()
        assertEquals(Baseline.SAMPLE_ERROR, s.baseline("PHASE_ORDER"))
    }
    @Test fun cleanupOnlyFailureRetainsAcceptedSampleButBlocksPass() {
        val s = accepted()
        assertEquals(Baseline.ACCEPTED, s.baseline("NONE"))
        assertEquals(Reason.CLEANUP_REJECTED, s.reason("NONE", false, true))
        assertFalse(s.passes(cleanup = true))
    }
    @Test fun unexpectedLifecycleAndForbiddenEffectsAlwaysBlockPass() {
        val s = accepted()
        assertFalse(s.passes(service = 1)); assertFalse(s.passes(activity = 1))
        assertFalse(s.passes(forbidden = true)); assertFalse(s.passes(closed = false))
        assertFalse(s.passes(completed = false)); assertFalse(s.passes(source = "SERVICE_LIFECYCLE"))
    }
    @Test fun unregisteredCleanupIsNotStartedNotDestroyedAndDoesNotNeedOthersOwnership() {
        assertEquals(ServiceCleanup.NOT_STARTED, NotificationInputReadinessEvidence.serviceCleanup(false, false, false, false, false))
        assertEquals(ObserverCleanup.NOT_REGISTERED, NotificationInputReadinessEvidence.observerCleanup(false, false))
        // Shared transport ownership protection remains strict; readiness never relaxes it.
        assertEquals(NotificationServiceTransportEvidence.ServiceCleanup.UNOWNED,
            NotificationServiceTransportEvidence.serviceCleanup(false, false, false, false, false, false))
    }
    @Test fun lostOwnershipOrAnyUnexpectedStartIsUnsafe() {
        assertEquals(ServiceCleanup.UNSAFE, NotificationInputReadinessEvidence.serviceCleanup(true, false, false, false, false))
        listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)).forEach { (instance, dispatch, action) ->
            assertEquals(ServiceCleanup.UNSAFE, NotificationInputReadinessEvidence.serviceCleanup(false, false, instance, dispatch, action))
        }
        assertEquals(ObserverCleanup.UNSAFE, NotificationInputReadinessEvidence.observerCleanup(true, false))
    }
    @Test fun historicalRegistrationCannotBeReplacedByUnobservedOrUnsafeCleanup() {
        val s = accepted()
        s.serviceObserverCleanup = ObserverCleanup.UNSAFE; assertTrue(s.serviceObserverWasRegistered); assertFalse(s.passes())
        s.serviceObserverCleanup = ObserverCleanup.UNREGISTERED_OWNED
        s.activityGuardCleanup = ObserverCleanup.NOT_REGISTERED; assertTrue(s.activityGuardWasRegistered); assertFalse(s.passes())
        s.activityGuardCleanup = ObserverCleanup.UNREGISTERED_OWNED
        s.serviceObserverWasRegistered = false; assertFalse(s.passes())
    }
    @Test fun unobservedOrUnsafeZeroCannotMasqueradeAsWholePeriodZero() {
        assertNull(NotificationInputReadinessEvidence.observedCount(false, true, 0))
        assertNull(NotificationInputReadinessEvidence.observedCount(true, false, 0))
        assertEquals(0, NotificationInputReadinessEvidence.observedCount(true, true, 0))
        assertEquals(2, NotificationInputReadinessEvidence.observedCount(true, false, 2))
    }
    @Test fun emptyReturnedListCompletesFalseWithoutFakeNulls() {
        val d = NotificationInputReadinessEvidence.Details()
        d.nodesReturned(true); d.nodesCompleted()
        assertEquals(true, d.fixtureNodesQueried); assertEquals(false, d.fixtureNodePresent)
        assertEquals(false, d.anyFixtureNodeVisible); assertEquals(false, d.anyVisibleFixtureNodeEditable)
        assertEquals(false, d.anyVisibleEditableFixtureNodeFocused)
    }
    @Test fun nonemptyInvisibleNodesDoNotExecuteEditableOrFocusedComparisons() {
        val d = NotificationInputReadinessEvidence.Details()
        d.nodesReturned(false); d.visible(false); d.nodesCompleted()
        assertEquals(false, d.anyFixtureNodeVisible)
        assertNull(d.anyVisibleFixtureNodeEditable); assertNull(d.anyVisibleEditableFixtureNodeFocused)
    }
    @Test fun visibleNoneditableNodesDoNotExecuteFocusedComparison() {
        val d = NotificationInputReadinessEvidence.Details()
        d.nodesReturned(false); d.visible(true); d.editable(false); d.nodesCompleted()
        assertEquals(true, d.anyFixtureNodeVisible); assertEquals(false, d.anyVisibleFixtureNodeEditable)
        assertNull(d.anyVisibleEditableFixtureNodeFocused)
    }
    @Test fun throwingQueryOrGetterLeavesUncompletedNegativesUnknown() {
        val d = NotificationInputReadinessEvidence.Details()
        assertNull(d.fixtureNodesQueried); assertNull(d.fixtureNodePresent)
        d.nodesReturned(false); d.visible(false)
        assertNull(d.anyFixtureNodeVisible); assertNull(d.anyVisibleFixtureNodeEditable)
        d.visible(true); d.editable(true)
        assertEquals(true, d.anyFixtureNodeVisible); assertEquals(true, d.anyVisibleFixtureNodeEditable)
        assertNull(d.anyVisibleEditableFixtureNodeFocused)
    }
    @Test fun sameNodeShortCircuitCanCompleteAllTrue() {
        val d = NotificationInputReadinessEvidence.Details()
        d.nodesReturned(false)
        val result = listOf(Triple(true, false, true), Triple(true, true, true), Triple(false, true, true)).any { (v, e, f) ->
            v.also(d::visible) && e.also(d::editable) && f.also(d::focused)
        }
        d.nodesCompleted()
        assertTrue(result); assertEquals(true, d.anyFixtureNodeVisible)
        assertEquals(true, d.anyVisibleFixtureNodeEditable); assertEquals(true, d.anyVisibleEditableFixtureNodeFocused)
    }
    @Test fun windowDetailsAreObservationsNotAdditionalOrRelaxedBaselineGates() {
        val s = accepted()
        s.details.uniqueFocusedWindow = false; s.details.rootsComplete = false
        assertTrue(s.passes())
        s.sample = s.sample!!.copy(focus = Focus.UNKNOWN)
        assertFalse(s.passes())
    }
}
