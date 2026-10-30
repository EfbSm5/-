package com.example.rootpilot.fixture;

import java.util.UUID;

/** Main-thread-only, process-lifetime observation; never owns an Activity or admits readiness. */
final class FreshArrivalState {
    static final FreshArrivalState PROCESS = new FreshArrivalState();
    private final String processEpoch = UUID.randomUUID().toString();
    private String phase = "EMPTY";
    private String sessionId;
    private String instanceId;

    void observe(boolean qualified, String session, String instance) {
        if (!"EMPTY".equals(phase) || !qualified || !canonical(session) || !canonical(instance)) {
            phase = "AMBIGUOUS";
            sessionId = null;
            instanceId = null;
            return;
        }
        phase = "SEEN";
        sessionId = session;
        instanceId = instance;
    }

    Snapshot snapshot() { return new Snapshot(processEpoch, phase, sessionId, instanceId); }

    static boolean canonical(String value) {
        try { return value != null && value.length() == 36 && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static final class Snapshot {
        final String processEpoch;
        final String phase;
        final String sessionId;
        final String instanceId;
        Snapshot(String processEpoch, String phase, String sessionId, String instanceId) {
            this.processEpoch = processEpoch;
            this.phase = phase;
            this.sessionId = sessionId;
            this.instanceId = instanceId;
        }
    }
}
