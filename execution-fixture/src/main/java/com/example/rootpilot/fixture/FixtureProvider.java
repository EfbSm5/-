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

/** Signature protected, fixed commands; never accepts content, paths or coordinates. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call does not enforce read/write permissions automatically.
        getContext().enforceCallingPermission("com.example.rootpilot.fixture.ACCESS", "fixture_access_denied");
        if (arg != null || extras != null || !("state".equals(method)
                || "snapshot".equals(method) || "finish".equals(method)
                || "virtual_state".equals(method) || "virtual_finish".equals(method)
                || "virtual_receipt".equals(method))) {
            throw new IllegalArgumentException("fixture_command_not_allowed");
        }
        FutureTask<Bundle> task = new FutureTask<>(() -> {
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
