package com.example.rootpilot.fixture;

import java.util.Set;

/** Exact, test-fixture-only request shapes; diagnostics never authorize readiness. */
final class FreshRequestAdmission {
    private FreshRequestAdmission() {}

    static boolean accepts(Set<String> keys, boolean validSession, boolean readinessReceiver,
                           boolean lifecycleReceiver, Object lifecycleFlag) {
        return accepts(keys, validSession, readinessReceiver, lifecycleReceiver, lifecycleFlag, null);
    }

    static boolean accepts(Set<String> keys, boolean validSession, boolean readinessReceiver,
                           boolean lifecycleReceiver, Object lifecycleFlag, Object arrivalFlag) {
        if (keys == null || !validSession || !readinessReceiver) return false;
        if (keys.equals(Set.of("freshSessionId", "freshReadinessReceiver"))) return true;
        if (keys.equals(Set.of("freshSessionId", "freshReadinessReceiver",
                "freshLifecycleReceiver", "freshLifecycleDiagnostic"))) {
            return lifecycleReceiver && Boolean.TRUE.equals(lifecycleFlag);
        }
        return keys.equals(Set.of("freshSessionId", "freshReadinessReceiver",
                "freshLifecycleReceiver", "freshLifecycleDiagnostic", "freshArrivalDiagnostic"))
                && lifecycleReceiver && Boolean.TRUE.equals(lifecycleFlag) && Boolean.TRUE.equals(arrivalFlag);
    }
}
