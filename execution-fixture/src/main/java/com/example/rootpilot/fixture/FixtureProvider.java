package com.example.rootpilot.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Signature protected, fixed commands; probe arguments identify an instance, never content. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call does not enforce read/write permissions automatically.
        getContext().enforceCallingPermission("com.example.rootpilot.fixture.ACCESS", "fixture_access_denied");
        boolean probe = "virtual_seed_cursor".equals(method) || "virtual_seed_selection".equals(method)
                || "virtual_change_cursor_source".equals(method)
                || "virtual_show_keyboard".equals(method) || "virtual_hide_keyboard".equals(method)
                || "virtual_redraw".equals(method);
        boolean prepared = "virtual_prepare_cursor".equals(method) || "virtual_prepare_selection".equals(method)
                || "virtual_clear_prepared_input".equals(method);
        if (arg != null || (!probe && !prepared && extras != null) || !("state".equals(method)
                || "snapshot".equals(method) || "finish".equals(method)
                || "virtual_state".equals(method) || "virtual_finish".equals(method)
                || "virtual_receipt".equals(method) || "virtual_seed_cursor".equals(method)
                || "virtual_change_cursor_source".equals(method)
                || "virtual_seed_selection".equals(method) || "virtual_show_keyboard".equals(method)
                || "virtual_hide_keyboard".equals(method) || "virtual_redraw".equals(method) || prepared)) {
            throw new IllegalArgumentException("fixture_command_not_allowed");
        }
        if (prepared && (extras == null || extras.size() != 2 || !extras.containsKey("sessionId")
                || !extras.containsKey("displayId") || extras.getString("sessionId") == null
                || extras.getInt("displayId", -1) <= 0)) {
            throw new IllegalArgumentException("fixture_prepared_binding_required");
        }
        if (probe && (extras == null || extras.size() != 2 || !extras.containsKey("instance")
                || !extras.containsKey("displayId") || extras.getString("instance") == null
                || extras.getInt("displayId", -1) <= 0)) {
            throw new IllegalArgumentException("fixture_probe_binding_required");
        }
        FutureTask<Bundle> task = new FutureTask<>(() -> {
            if (prepared) {
                int displayId = extras.getInt("displayId");
                String sessionId = extras.getString("sessionId");
                if ("virtual_clear_prepared_input".equals(method)) PreparedVirtualInput.clear(displayId, sessionId);
                else PreparedVirtualInput.prepare(getContext(), displayId, sessionId,
                        "virtual_prepare_cursor".equals(method) ? "CURSOR" : "SELECTION");
                Bundle receipt = new Bundle();
                receipt.putInt("displayId", displayId); receipt.putString("sessionId", sessionId);
                receipt.putBoolean("prepared", !"virtual_clear_prepared_input".equals(method));
                return receipt;
            }
            if ("virtual_receipt".equals(method)) {
                return VirtualCapabilityActivity.lastReceipt == null ? Bundle.EMPTY
                        : new Bundle(VirtualCapabilityActivity.lastReceipt);
            }
            if ("virtual_state".equals(method) || "virtual_finish".equals(method)) {
                VirtualCapabilityActivity virtual = VirtualCapabilityActivity.current;
                if ("virtual_finish".equals(method)) {
                    if (virtual != null) virtual.finishAndRemoveTask();
                    return Bundle.EMPTY;
                }
                return virtual == null ? Bundle.EMPTY : virtual.state();
            }
            if (method.startsWith("virtual_")) {
                VirtualCapabilityActivity virtual = VirtualCapabilityActivity.current;
                if (virtual == null) throw new IllegalStateException("virtual_probe_target_unavailable");
                return virtual.probe(method, extras.getString("instance"), extras.getInt("displayId"));
            }
            ExecutionFixtureActivity activity = ExecutionFixtureActivity.current;
            if ("finish".equals(method)) {
                if (activity != null) activity.finishAndRemoveTask();
                return Bundle.EMPTY;
            }
            if (activity == null) return Bundle.EMPTY;
            return activity.snapshot("snapshot".equals(method));
        });
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(2, TimeUnit.SECONDS); }
        catch (Exception ignored) {
            task.cancel(false);
            throw new IllegalStateException("fixture_unavailable");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
