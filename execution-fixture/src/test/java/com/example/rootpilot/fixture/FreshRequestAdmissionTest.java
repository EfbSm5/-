package com.example.rootpilot.fixture;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class FreshRequestAdmissionTest {
    private static final Set<String> LEGACY = Set.of("freshSessionId", "freshReadinessReceiver");
    private static final Set<String> PROBE = Set.of("freshSessionId", "freshReadinessReceiver",
            "freshLifecycleReceiver", "freshLifecycleDiagnostic");

    @Test public void preservesLegacyAndRequiresExplicitPairedProbe() {
        assertTrue(FreshRequestAdmission.accepts(LEGACY, true, true, false, null));
        assertTrue(FreshRequestAdmission.accepts(PROBE, true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(PROBE, true, true, false, true));
        assertFalse(FreshRequestAdmission.accepts(PROBE, true, true, true, false));
        assertFalse(FreshRequestAdmission.accepts(PROBE, true, true, true, "true"));
        assertFalse(FreshRequestAdmission.accepts(PROBE, true, true, true, null));
    }

    @Test public void arrivalModeOnlyAddsTheExplicitFifthKeyToValidLifecycleShape() {
        Set<String> arrival = Set.of("freshSessionId", "freshReadinessReceiver", "freshLifecycleReceiver",
                "freshLifecycleDiagnostic", "freshArrivalDiagnostic");
        assertTrue(FreshRequestAdmission.accepts(arrival, true, true, true, true, true));
        for (Object bad : new Object[]{null, false, "true", 1}) {
            assertFalse(FreshRequestAdmission.accepts(arrival, true, true, true, true, bad));
            assertFalse(FreshRequestAdmission.accepts(arrival, true, true, true, bad, true));
        }
        assertFalse(FreshRequestAdmission.accepts(arrival, false, true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(arrival, true, false, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(arrival, true, true, false, true, true));
        for (String key : arrival) {
            java.util.HashSet<String> missing = new java.util.HashSet<>(arrival);
            missing.remove(key);
            if (key.equals("freshArrivalDiagnostic")) {
                assertTrue(FreshRequestAdmission.accepts(missing, true, true, true, true, null));
            } else assertFalse(FreshRequestAdmission.accepts(missing, true, true, true, true, true));
        }
        java.util.HashSet<String> extra = new java.util.HashSet<>(arrival);
        extra.add("extra");
        assertFalse(FreshRequestAdmission.accepts(extra, true, true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(Set.of("freshSessionId", "freshReadinessReceiver", "freshArrivalDiagnostic"),
                true, true, false, null, true));
    }

    @Test public void rejectedDiagnosedAdmissionAlwaysPoisonsTheLedger() {
        Set<String> arrival = Set.of("freshSessionId", "freshReadinessReceiver", "freshLifecycleReceiver",
                "freshLifecycleDiagnostic", "freshArrivalDiagnostic");
        for (boolean priorSeen : new boolean[]{false, true}) {
            for (Object flag : new Object[]{null, false, "true", 1}) {
                FreshArrivalState ledger = new FreshArrivalState();
                String session = "a71115f8-402a-4a10-814c-78cd7e128f5a";
                String instance = "0706c94e-f735-487c-92cd-d16601798cf1";
                if (priorSeen) ledger.observe(true, session, instance);
                ledger.observe(FreshRequestAdmission.accepts(arrival, true, true, true, true, flag), session, instance);
                assertEquals("AMBIGUOUS", ledger.snapshot().phase);
                ledger.observe(true, session, instance);
                assertEquals("AMBIGUOUS", ledger.snapshot().phase);
            }
        }
    }

    @Test public void rejectsMissingOrAdditionalKeysAndForeignRequests() {
        assertFalse(FreshRequestAdmission.accepts(null, true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(Set.of("freshLifecycleDiagnostic"), true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(Set.of("freshSessionId", "freshReadinessReceiver", "freshLifecycleReceiver"), true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(Set.of("freshSessionId", "freshReadinessReceiver", "freshLifecycleDiagnostic"), true, true, true, true));
        assertFalse(FreshRequestAdmission.accepts(Set.of("freshSessionId", "freshReadinessReceiver", "extra"), true, true, false, null));
        assertFalse(FreshRequestAdmission.accepts(LEGACY, false, true, false, null));
        assertFalse(FreshRequestAdmission.accepts(LEGACY, true, false, false, null));
    }
}
