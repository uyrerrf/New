package com.fason.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.Protocol;
import com.fason.app.service.MainService;

public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "Watchdog";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (Protocol.BC_KEEP_ALIVE.equals(action) ||
            Protocol.BC_RESPAWN_SERVICE.equals(action) ||
            Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ensureRunning(context);
        }
    }

    private void ensureRunning(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE);
        boolean shouldRun = prefs.getBoolean(Protocol.PREF_SERVICE_ACTIVE, true);
        if (!shouldRun) return;
        if (MainService.getInstance() != null) return;
        try {
            Intent svcIntent = new Intent(ctx, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svcIntent);
            } else {
                ctx.startService(svcIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Start failed, deferring to worker", e);
        }
    }

    public static void setServiceActive(Context ctx, boolean active) {
        SharedPreferences prefs = ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(Protocol.PREF_SERVICE_ACTIVE, active).apply();
    }

    public static boolean isActive(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Protocol.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(Protocol.PREF_SERVICE_ACTIVE, true);
    }
}
