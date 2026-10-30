package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationImeIdentityEvidence.Outcome
import com.example.agent.rootpilot.NotificationImeIdentityEvidence.Phase
import com.example.agent.rootpilot.NotificationImeIdentityEvidence.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationImeIdentityEvidenceTest {
    private fun sample(phase: Phase, editor: Boolean = true, connection: Boolean = true) =
        Sample(phase, true, editor, connection, true, true)
    private val baseline = sample(Phase.BASELINE)

    @Test fun unchangedIsNotAnApprovalOrAProvenRootCause() {
        val values = listOf(baseline, sample(Phase.SHADE), sample(Phase.ACTIVITY_DESTROYED), sample(Phase.FIXTURE_RETURNED))
        assertEquals(Outcome.NO_DRIFT_OBSERVED, NotificationImeIdentityEvidence.outcome(values))
        assertNull(NotificationImeIdentityEvidence.firstBoundDrift(values))
    }
    @Test fun automationInitializationDriftIsNotMisattributedToShade() {
        val values = listOf(baseline, sample(Phase.UI_READY, editor = false), sample(Phase.SHADE, editor = false))
        assertEquals(Outcome.UI_INITIALIZATION_DRIFT, NotificationImeIdentityEvidence.outcome(values))
    }
    @Test fun publicationDriftIsNotMisattributedToShade() {
        val values = listOf(baseline, sample(Phase.UI_READY), sample(Phase.BEFORE_SHADE, connection = false))
        assertEquals(Outcome.BEFORE_SHADE_DRIFT, NotificationImeIdentityEvidence.outcome(values))
    }
    @Test fun shadeDriftIsNotMisattributedToActivity() {
        val values = listOf(baseline, sample(Phase.SHADE, editor = false), sample(Phase.ACTIVITY_POST_CREATED, editor = false))
        assertEquals(Outcome.SHADE_DRIFT, NotificationImeIdentityEvidence.outcome(values))
        assertEquals(Phase.SHADE, NotificationImeIdentityEvidence.firstBoundDrift(values))
    }
    @Test fun driftBeforeClickIsStillPreActivity() {
        assertEquals(Outcome.SHADE_DRIFT, NotificationImeIdentityEvidence.outcome(
            listOf(baseline, sample(Phase.BEFORE_CLICK, connection = false))))
    }
    @Test fun activityCanReplaceEditorEvenWhenTheCurrentEditorIsValid() {
        val drift = sample(Phase.ACTIVITY_RESUMED, editor = false)
        assertTrue(drift.currentConnectionValid)
        assertTrue(drift.boundGuardRejects)
        assertEquals(Outcome.ACTIVITY_DRIFT, NotificationImeIdentityEvidence.outcome(listOf(baseline, drift)))
    }
    @Test fun unchangedEditorWithChangedOwnersCurrentConnectionAlsoRejects() {
        val drift = sample(Phase.ACTIVITY_FOCUS, connection = false)
        assertTrue(drift.sameEditor)
        assertTrue(drift.boundGuardRejects)
        assertEquals(Phase.ACTIVITY_FOCUS, NotificationImeIdentityEvidence.firstBoundDrift(listOf(baseline, drift)))
    }
    @Test fun nullEditorCannotBeAccepted() {
        assertTrue(Sample(Phase.NEXT_MAIN_TURN, false, false, true, false, false).boundGuardRejects)
        assertFalse(baseline.boundGuardRejects)
    }
    @Test fun earliestDriftSurvivesLaterValidSnapshots() {
        assertEquals(Phase.ACTIVITY_DESTROYED, NotificationImeIdentityEvidence.firstBoundDrift(listOf(
            baseline, sample(Phase.SHADE), sample(Phase.ACTIVITY_DESTROYED, editor = false), sample(Phase.FIXTURE_RETURNED))))
    }
    @Test(expected = IllegalArgumentException::class) fun missingBaselineIsNotEvidence() {
        NotificationImeIdentityEvidence.outcome(emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBaselineIsNotEvidence() {
        NotificationImeIdentityEvidence.outcome(listOf(sample(Phase.BASELINE, connection = false)))
    }
    @Test(expected = IllegalArgumentException::class) fun protectedBaselineIsNotEvidence() {
        NotificationImeIdentityEvidence.outcome(listOf(baseline.copy(targetAllowed = false)))
    }
}
