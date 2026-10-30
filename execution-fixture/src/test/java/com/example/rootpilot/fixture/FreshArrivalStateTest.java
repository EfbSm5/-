package com.example.rootpilot.fixture;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreshArrivalStateTest {
    private static final String SESSION = "a71115f8-402a-4a10-814c-78cd7e128f5a";
    private static final String INSTANCE = "0706c94e-f735-487c-92cd-d16601798cf1";
    private static final String OTHER = "681f9942-61f5-4bb2-a74a-89f0ed3368f0";

    private static void emptyIds(FreshArrivalState.Snapshot snapshot, String phase) {
        assertEquals(phase, snapshot.phase);
        assertNull(snapshot.sessionId);
        assertNull(snapshot.instanceId);
    }

    @Test public void epochIsStableForLifetimeAndDiffersAcrossProcessStateHolders() {
        FreshArrivalState state = new FreshArrivalState();
        String epoch = state.snapshot().processEpoch;
        assertTrue(FreshArrivalState.canonical(epoch));
        assertEquals(epoch, state.snapshot().processEpoch);
        assertNotEquals(epoch, new FreshArrivalState().snapshot().processEpoch);
        state.observe(true, SESSION, INSTANCE);
        state.observe(false, null, null);
        assertEquals(epoch, state.snapshot().processEpoch);
    }

    @Test public void exactlyOneQualifiedArrivalProducesIndependentSnapshot() {
        FreshArrivalState state = new FreshArrivalState();
        FreshArrivalState.Snapshot before = state.snapshot();
        emptyIds(before, "EMPTY");
        state.observe(true, SESSION, INSTANCE);
        FreshArrivalState.Snapshot after = state.snapshot();
        assertEquals("SEEN", after.phase);
        assertEquals(SESSION, after.sessionId);
        assertEquals(INSTANCE, after.instanceId);
        assertEquals(before.processEpoch, after.processEpoch);
        emptyIds(before, "EMPTY");
    }

    @Test public void everyAdditionalDiagnosedArrivalIsAmbiguousIncludingSameOrForeignSession() {
        for (String session : new String[]{SESSION, OTHER}) {
            for (String instance : new String[]{INSTANCE, OTHER}) {
                FreshArrivalState state = new FreshArrivalState();
                state.observe(true, SESSION, INSTANCE);
                state.observe(true, session, instance);
                emptyIds(state.snapshot(), "AMBIGUOUS");
                state.observe(true, SESSION, INSTANCE);
                emptyIds(state.snapshot(), "AMBIGUOUS");
            }
        }
    }

    @Test public void invalidShapeOrSessionContaminatesBeforeOrAfterQualifiedArrival() {
        for (boolean qualifiedFirst : new boolean[]{false, true}) {
            FreshArrivalState state = new FreshArrivalState();
            if (qualifiedFirst) state.observe(true, SESSION, INSTANCE);
            state.observe(false, SESSION, INSTANCE);
            emptyIds(state.snapshot(), "AMBIGUOUS");
            state.observe(true, SESSION, INSTANCE);
            emptyIds(state.snapshot(), "AMBIGUOUS");
        }
    }

    @Test public void malformedCanonicalIdsCannotCreateCleanSeenEvenIfCallerClaimsQualified() {
        for (String invalid : new String[]{null, "", "bad", SESSION.toUpperCase(), " a71115f8-402a-4a10-814c-78cd7e128f5a"}) {
            FreshArrivalState session = new FreshArrivalState();
            session.observe(true, invalid, INSTANCE);
            emptyIds(session.snapshot(), "AMBIGUOUS");
            FreshArrivalState instance = new FreshArrivalState();
            instance.observe(true, SESSION, invalid);
            emptyIds(instance.snapshot(), "AMBIGUOUS");
        }
    }

    @Test public void snapshotDoesNotHoldAnActivityAndRemainsStableAfterLaterDuplicate() {
        FreshArrivalState state = new FreshArrivalState();
        state.observe(true, SESSION, INSTANCE);
        FreshArrivalState.Snapshot positiveAtQuery = state.snapshot();
        state.observe(true, SESSION, INSTANCE);
        emptyIds(state.snapshot(), "AMBIGUOUS");
        assertEquals("SEEN", positiveAtQuery.phase);
        assertEquals(INSTANCE, positiveAtQuery.instanceId);
    }
}
