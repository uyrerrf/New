package com.fason.app.core.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public final class RestrictedPermissionHelper {
    private static final String TAG = "RestrictedPerm";

    private RestrictedPermissionHelper() {}
    public static boolean hasRestrictedPerms(Activity act) {
        return !getRestrictedPerms(act).isEmpty();
    }

    public static List<String> getRestrictedPerms(Activity act) {
        List<String> restricted = new ArrayList<>();
        if (act == null) return restricted;
        String[] declaredPerms = getDeclaredPermissions(act);
        if (declaredPerms == null || declaredPerms.length == 0) {
            return restricted;
        }
        for (String perm : declaredPerms) {
            if (perm == null) continue;
            if (!isDangerousPermission(act, perm)) continue;
            if (isDeniedAndNoRationale(act, perm)) {
                String label = getPermissionLabel(act, perm);
                restricted.add(label);
            }
        }
        if (!restricted.isEmpty()) {
            Log.i(TAG, "Restricted permissions: " + restricted);
        }
        return restricted;
    }

    private static String[] getDeclaredPermissions(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            return info.requestedPermissions;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get package info", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error getting declared permissions", e);
            return null;
        }
    }

    private static boolean isDangerousPermission(Context ctx, String perm) {
        try {
            PermissionInfo info = ctx.getPackageManager()
                .getPermissionInfo(perm, 0);
            if (info == null) return false;
            int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            return base == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDeniedAndNoRationale(Activity act, String perm) {
        if (act == null || perm == null) return false;
        try {
            int state = act.checkSelfPermission(perm);
            if (state == PackageManager.PERMISSION_GRANTED) return false;
            return !androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(act, perm);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getPermissionLabel(Context ctx, String perm) {
        try {
            PermissionInfo info = ctx.getPackageManager()
                .getPermissionInfo(perm, 0);
            if (info != null) {
                CharSequence label = info.loadLabel(ctx.getPackageManager());
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Exception ignored) {}
        int lastDot = perm.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < perm.length() - 1) {
            return perm.substring(lastDot + 1);
        }
        return perm;
    }

    public static boolean openAppSettingsForRestricted(Activity act) {
        if (act == null) return false;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            Log.i(TAG, "Opened app settings");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to open app settings", e);
            return false;
        }
    }

    public static String getRestrictedMessage(Activity act) {
        List<String> perms = getRestrictedPerms(act);
        if (perms.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perms.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(perms.get(i));
        }
        sb.append(" \u2014 tap to allow restricted settings");
        return sb.toString();
    }
}
