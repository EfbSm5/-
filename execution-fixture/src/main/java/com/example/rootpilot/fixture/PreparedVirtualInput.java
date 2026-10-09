package com.example.rootpilot.fixture;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.util.UUID;

/** Main-thread, one-shot fixed seed bound to a live owned display, never to a main-screen launch. */
final class PreparedVirtualInput {
    private static PreparedVirtualInput pending;
    private final int displayId;
    private final String sessionId;
    private final String mode;
    private final long expiresAt;

    private PreparedVirtualInput(int displayId, String sessionId, String mode) {
        this.displayId = displayId;
        this.sessionId = sessionId;
        this.mode = mode;
        expiresAt = SystemClock.elapsedRealtime() + 10_000;
    }

    static void prepare(Context context, int displayId, String sessionId, String mode) {
        if (displayId <= 0 || sessionId == null || !UUID.fromString(sessionId).toString().equals(sessionId)
                || !("CURSOR".equals(mode) || "SELECTION".equals(mode))) {
            throw new IllegalArgumentException("prepared_input_binding_required");
        }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (!matches(display, displayId, sessionId)) throw new IllegalStateException("prepared_input_display_unavailable");
        if (VirtualCapabilityActivity.current != null || pending != null && !pending.expired()) {
            throw new IllegalStateException("prepared_input_busy");
        }
        pending = new PreparedVirtualInput(displayId, sessionId, mode);
    }

    static String consume(Display display) {
        PreparedVirtualInput held = pending;
        if (held == null) return null;
        if (held.expired()) { pending = null; return null; }
        if (!matches(display, held.displayId, held.sessionId)) return null;
        pending = null;
        return held.mode;
    }

    static void clear(int displayId, String sessionId) {
        if (displayId <= 0 || sessionId == null || !UUID.fromString(sessionId).toString().equals(sessionId)) {
            throw new IllegalArgumentException("prepared_input_binding_required");
        }
        if (pending == null) return;
        if (pending.displayId != displayId || !pending.sessionId.equals(sessionId)) {
            throw new IllegalStateException("prepared_input_binding_changed");
        }
        pending = null;
    }

    private boolean expired() { return SystemClock.elapsedRealtime() >= expiresAt; }

    private static boolean matches(Display display, int displayId, String sessionId) {
        return display != null && display.getDisplayId() == displayId
                && ("RootPilot-Private-" + sessionId).equals(display.getName());
    }
}
