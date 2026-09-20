package com.fason.app.core.permissions;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public final class OemAutoStartHelper {
    private static final String TAG = "OemAutoStart";

    private OemAutoStartHelper() {}
    public enum AutoStartResult {
        OPENED_AUTOSTART,
        OPENED_APP_DETAILS,
        FAILED
    }

    private static final String[] STOCK_NAMESPACES = {
        "com.android.",
        "com.google.android.",
        "com.google.",
        "android.",
        "com.verizon.",
        "com.att.",
        "com.tmobile.",
        "com.sprint.",
    };
    private static volatile Boolean cachedModifiedRom = null;

    private static final OemDeepLink[] OEM_DEEP_LINKS = {
        new OemDeepLink(new String[]{"xiaomi", "redmi", "poco"}, new ComponentPair[]{
            new ComponentPair("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            new ComponentPair("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            new ComponentPair("com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
        }),
        new OemDeepLink(new String[]{"huawei", "honor"}, new ComponentPair[]{
            new ComponentPair("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new ComponentPair("com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            new ComponentPair("com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        }),
        new OemDeepLink(new String[]{"oppo", "realme"}, new ComponentPair[]{
            new ComponentPair("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            new ComponentPair("com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            new ComponentPair("com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"),
            new ComponentPair("com.coloros.safecenter",
                "com.coloros.safecenter.foreground.app.ForegroundAppListActivity"),
        }),
        new OemDeepLink(new String[]{"oneplus"}, new ComponentPair[]{
            new ComponentPair("com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            new ComponentPair("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            new ComponentPair("com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        }),
        new OemDeepLink(new String[]{"vivo", "iqoo"}, new ComponentPair[]{
            new ComponentPair("com.vivo.abe",
                "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            new ComponentPair("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            new ComponentPair("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            new ComponentPair("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        }),
        new OemDeepLink(new String[]{"samsung"}, new ComponentPair[]{
            new ComponentPair("com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"),
            new ComponentPair("com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"),
            new ComponentPair("com.samsung.android.sm",
                "com.samsung.android.sm.ui.sleepingapps.SleepingAppsActivity"),
            new ComponentPair("com.samsung.android.sm",
                "com.samsung.android.sm.ui.cycleswitch.CycleSwitchActivity"),
        }),
        new OemDeepLink(new String[]{"meizu"}, new ComponentPair[]{
            new ComponentPair("com.meizu.safe",
                "com.meizu.safe.security.SHOW_APPSEC"),
            new ComponentPair("com.meizu.safe",
                "com.meizu.safe.permission.PermissionActivity"),
        }),
        new OemDeepLink(new String[]{"asus"}, new ComponentPair[]{
            new ComponentPair("com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity"),
            new ComponentPair("com.asus.mobilemanager",
                "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
            new ComponentPair("com.asus.mobilemanager",
                "com.asus.mobilemanager.entry.FunctionActivity"),
        }),
        new OemDeepLink(new String[]{"lenovo"}, new ComponentPair[]{
            new ComponentPair("com.lenovo.security",
                "com.lenovo.security.purebackground.PureBackgroundActivity"),
            new ComponentPair("com.lenovo.safecenter",
                "com.lenovo.safecenter.StartupAppControlActivity"),
        }),
        new OemDeepLink(new String[]{"zte"}, new ComponentPair[]{
            new ComponentPair("com.zte.heartyservice",
                "com.zte.heartyservice.autorun.AutoRunManagerActivity"),
            new ComponentPair("com.zte.zpass",
                "com.zte.zpass.ui.autorun.AutoRunManagerActivity"),
        }),
        new OemDeepLink(new String[]{"htc"}, new ComponentPair[]{
            new ComponentPair("com.htc.cs.pns",
                "com.htc.cs.pns.settings.Preferences"),
            new ComponentPair("com.htc.usage",
                "com.htc.usage.ui.PowerUsageActivity"),
        }),
        new OemDeepLink(new String[]{"nokia", "hmd global"}, new ComponentPair[]{
            new ComponentPair("com.evenwell.PowerSaver",
                "com.evenwell.PowerSaver.activity.PowerSaverActivity"),
            new ComponentPair("com.evenwell.powersaving",
                "com.evenwell.powersaving.PowerSavingActivity"),
        }),
    };
    private static final class OemDeepLink {
        final String[] mfrKeywords;
        final ComponentPair[] pairs;
        OemDeepLink(String[] mfrKeywords, ComponentPair[] pairs) {
            this.mfrKeywords = mfrKeywords;
            this.pairs = pairs;
        }
        boolean matchesManufacturer(String mfrLower) {
            for (String kw : mfrKeywords) {
                if (mfrLower.contains(kw)) return true;
            }
            return false;
        }
    }

    private static final class ComponentPair {
        final String pkg;
        final String cls;
        ComponentPair(String pkg, String cls) {
            this.pkg = pkg;
            this.cls = cls;
        }
    }

    private static final String[] SECURITY_PKG_KEYWORDS = {
        "security", "safe", "permission", "protect", "manager",
        "powersaver", "permcenter", "safecenter", "systemmanager"
    };
    private static final String[][] ACTIVITY_KEYWORD_TIERS = {
        {"autostart", "auto_start", "autorun", "startup", "start_up",
         "chainlaunch", "autolaunch", "bgstartup", "bg_start"},
        {"launch", "boot", "background", "purebackground"},
        {"powersaver", "power_saver", "optimize", "battery", "protect"},
    };
    private static final int[] TIER_SCORES = {30, 20, 10};
    private static final int MIN_ACCEPT_SCORE = 20;

    public static boolean isAutoStartNeeded(Context ctx) {
        if (ctx == null) return false;
        if (cachedModifiedRom != null) return cachedModifiedRom;
        boolean result = scanForOemSystemApps(ctx);
        cachedModifiedRom = result;
        if (result) {
            Log.i(TAG, "OEM ROM detected");
        }
        return result;
    }

    public static AutoStartResult requestAutoStart(Activity act) {
        if (act == null) return AutoStartResult.FAILED;
        Intent deepLink = findOemDeepLink(act);
        if (deepLink != null) {
            try {
                act.startActivity(deepLink);
                Log.i(TAG, "Opened auto-start: " + deepLink.getComponent());
                return AutoStartResult.OPENED_AUTOSTART;
            } catch (Exception e) {
                Log.w(TAG, "Deep-link failed, trying discovery", e);
            }
        }
        Intent discovered = discoverAutoStartActivity(act);
        if (discovered != null) {
            try {
                act.startActivity(discovered);
                Log.i(TAG, "Opened auto-start (discovery): "
                           + discovered.getComponent());
                return AutoStartResult.OPENED_AUTOSTART;
            } catch (Exception e) {
                Log.w(TAG, "Discovery failed, trying app-details", e);
            }
        }
        return openAppDetails(act);
    }

    private static Intent findOemDeepLink(Context ctx) {
        String mfr = Build.MANUFACTURER;
        if (mfr == null) return null;
        mfr = mfr.toLowerCase(java.util.Locale.ROOT);
        for (OemDeepLink entry : OEM_DEEP_LINKS) {
            if (!entry.matchesManufacturer(mfr)) continue;
            for (ComponentPair pair : entry.pairs) {
                Intent i = new Intent();
                i.setComponent(new ComponentName(pair.pkg, pair.cls));
                if (canResolve(ctx, i)) {
                    Log.d(TAG, "Resolved deep-link: " + pair.pkg + "/" + pair.cls);
                    return i;
                }
            }
        }
        return null;
    }

    private static Intent discoverAutoStartActivity(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Exception e) {
            Log.w(TAG, "getInstalledPackages failed", e);
            return null;
        }
        ActivityInfo bestActivity = null;
        int bestScore = 0;
        for (PackageInfo pi : all) {
            if (pi.applicationInfo == null) continue;
            int flags = pi.applicationInfo.flags;
            boolean isSystem =
                (flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (!isSystem) continue;
            String pkg = pi.packageName;
            if (pkg == null || isStockNamespace(pkg)) continue;
            String pkgLower = pkg.toLowerCase(java.util.Locale.ROOT);
            boolean isSecurityPkg = false;
            for (String kw : SECURITY_PKG_KEYWORDS) {
                if (pkgLower.contains(kw)) { isSecurityPkg = true; break; }
            }
            if (!isSecurityPkg) continue;
            try {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                if (info.activities == null) continue;
                for (ActivityInfo ai : info.activities) {
                    if (!ai.exported) continue;
                    int score = scoreActivityRelevance(ai.name);
                    if (score > bestScore && score >= MIN_ACCEPT_SCORE) {
                        Intent probe = new Intent();
                        probe.setComponent(new ComponentName(ai.packageName, ai.name));
                        if (canResolve(ctx, probe)) {
                            bestScore = score;
                            bestActivity = ai;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        if (bestActivity == null) return null;
        Log.d(TAG, "Discovered auto-start activity: " + bestActivity.packageName
                   + "/" + bestActivity.name + " (score=" + bestScore + ")");
        Intent i = new Intent();
        i.setComponent(new ComponentName(bestActivity.packageName, bestActivity.name));
        return i;
    }

    private static int scoreActivityRelevance(String className) {
        if (className == null) return 0;
        String lower = className.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (int tier = 0; tier < ACTIVITY_KEYWORD_TIERS.length; tier++) {
            for (String kw : ACTIVITY_KEYWORD_TIERS[tier]) {
                if (lower.contains(kw)) {
                    score += TIER_SCORES[tier] + kw.length();
                }
            }
        }
        return score;
    }

    private static AutoStartResult openAppDetails(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            Log.w(TAG, "Fell back to app-details");
            return AutoStartResult.OPENED_APP_DETAILS;
        } catch (Exception e) {
            Log.w(TAG, "App-details failed, trying battery", e);
            return openBatterySettings(act);
        }
    }

    private static AutoStartResult openBatterySettings(Activity act) {
        try {
            act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return AutoStartResult.OPENED_APP_DETAILS;
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SETTINGS));
                return AutoStartResult.OPENED_APP_DETAILS;
            } catch (Exception ignored) {
                return AutoStartResult.FAILED;
            }
        }
    }

    private static boolean scanForOemSystemApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Exception e) {
            Log.w(TAG, "getInstalledPackages failed", e);
            return false;
        }
        for (PackageInfo pi : all) {
            if (pi.applicationInfo == null) continue;
            int flags = pi.applicationInfo.flags;
            boolean isSystem =
                (flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (!isSystem) continue;
            String pkg = pi.packageName;
            if (pkg == null || isStockNamespace(pkg)) continue;
            return true;
        }
        return false;
    }

    private static boolean isStockNamespace(String pkg) {
        for (String prefix : STOCK_NAMESPACES) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean canResolve(Context ctx, Intent i) {
        if (ctx == null || i == null) return false;
        try {
            return ctx.getPackageManager().resolveActivity(i, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
