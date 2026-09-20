package com.fason.app.persistence;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.fason.app.service.MainService;

/**
 * Ghost watchdog — Lab-RATS IO_Persistence_Manager ghost protocol, upgraded.
 * Reanimates MainService when the core goes silent, survives doze via
 * HandlerThread (not alarm-driven), and escalates to a foreground restart
 * when the process is under memory pressure.
 */
public final class GhostWatchdog {
    private static final String TAG = "GhostWatchdog";
    private static final long PULSE_MS = 60_000;
    private static volatile GhostWatchdog instance;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean running = false;
    private volatile int silentPulses = 0;

    private GhostWatchdog() {
        thread = new HandlerThread("ghost-watchdog");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static synchronized GhostWatchdog get() {
        if (instance == null) instance = new GhostWatchdog();
        return instance;
    }

    public synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        silentPulses = 0;
        handler.post(() -> pulse(ctx.getApplicationContext()));
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void pulse(Context ctx) {
        if (!running) return;
        try {
            boolean alive = isServiceAlive(ctx);
            if (!alive) {
                silentPulses++;
                Log.w(TAG, "core silent, pulse #" + silentPulses);
                Intent i = new Intent(ctx, MainService.class);
                i.setAction("com.fason.app.REANIMATE");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } else {
                silentPulses = 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "pulse failed", e);
        }
        handler.postDelayed(() -> pulse(ctx), PULSE_MS);
    }

    private boolean isServiceAlive(Context ctx) {
        try {
            android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            for (android.app.ActivityManager.RunningServiceInfo rsi :
                    am.getRunningServices(Integer.MAX_VALUE)) {
                if (MainService.class.getName().equals(rsi.service.getClassName())
                        && rsi.pid > 0) {
                    return rsi.pid == android.os.Process.myPid() || rsi.pid > 0;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
