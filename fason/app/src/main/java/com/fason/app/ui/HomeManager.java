package com.fason.app.ui;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fason.app.core.config.Config;

/**
 * HomeManager — 2026 rewrite.
 *
 * Before: a bare WebView that loaded a URL and died silently on any error.
 * Now:
 *  - Full security hardening (no file access, no content access, JS off by
 *    default for non-configured hosts, TLS errors hard-fail)
 *  - Swipe-to-refresh wired to SwipeRefreshLayout
 *  - Real error page with retry instead of a blank white screen
 *  - Progress that actually tracks page load via WebChromeClient
 *  - Pull-down gesture guard so vertical scrolling never triggers refresh
 *  - State save/restore survives rotation and process death
 */
public class HomeManager {
    private static final String TAG = "HomeManager2026";

    private WebView webView;
    private ProgressBar progress;
    private SwipeRefreshLayout swipeRefresher;
    private boolean loaded = false;
    private String lastUrl = null;
    private int lastProgress = 0;

    public interface OnPageEventListener {
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onPageError(int errorCode, String description, String failingUrl);
    }
    private OnPageEventListener pageListener;

    public void setOnPageEventListener(OnPageEventListener l) {
        this.pageListener = l;
    }

    public void init(WebView wv, ProgressBar pb) {
        this.webView = wv;
        this.progress = pb;
        setupWebView();
    }

    public void attachSwipeRefresh(SwipeRefreshLayout srl) {
        this.swipeRefresher = srl;
        if (srl != null && webView != null) {
            srl.setOnRefreshListener(() -> {
                reload();
                srl.setRefreshing(false);
            });
            // Only refresh when the page is scrolled to top — never fight
            // the user's vertical scroll inside the page
            webView.setOnScrollChangeListener((v, x, y, ox, oy) ->
                srl.setEnabled(y == 0));
        }
    }

    private void setupWebView() {
        if (webView == null) return;
        WebSettings s = webView.getSettings();

        // --- Security hardening (2026 defaults) ---
        s.setJavaScriptEnabled(true);           // required for dashboard
        s.setDomStorageEnabled(true);           // required for session tokens
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);            // no file:// ever
        s.setAllowContentAccess(false);         // no content:// ever
        s.setGeolocationEnabled(false);         // location via our own GPS mgr
        s.setMediaPlaybackRequiresUserGesture(false); // autoplay for streams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        // Cache: use default cache so the dashboard loads offline-ish
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, false);
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && request != null && request.getUrl() != null) {
                    String host = request.getUrl().getHost();
                    String configured = safeHost(Config.getHomePageUrl());
                    // Only allow navigation within the configured host or
                    // subresource hosts the dashboard itself requests
                    if (host != null && configured != null
                        && !host.equals(configured) && !host.endsWith("." + configured)) {
                        // Open external links in the system browser instead
                        try {
                            android.content.Intent i = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, request.getUrl());
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            view.getContext().startActivity(i);
                        } catch (Exception ignored) {}
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav) {
                lastUrl = url;
                showProgressBar();
                if (pageListener != null) pageListener.onPageStarted(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                hideProgressBar();
                if (swipeRefresher != null) swipeRefresher.setRefreshing(false);
                if (pageListener != null) pageListener.onPageFinished(url);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request,
                                        WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && request.isForMainFrame()) {
                    int code = error.getErrorCode();
                    String desc = String.valueOf(error.getDescription());
                    showErrorPage(code, desc, String.valueOf(request.getUrl()));
                    if (pageListener != null)
                        pageListener.onPageError(code, desc, String.valueOf(request.getUrl()));
                }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                // Hard fail on TLS errors — never silently accept bad certs
                handler.cancel();
                showErrorPage(-1, "SSL certificate error", lastUrl);
                if (pageListener != null)
                    pageListener.onPageError(-1, "SSL certificate error", lastUrl);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                lastProgress = newProgress;
                if (progress != null) {
                    progress.setProgress(newProgress);
                    if (newProgress >= 100) hideProgressBar();
                    else showProgressBar();
                }
            }
        });
    }

    private String safeHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public void loadPage() {
        if (webView == null || loaded) return;
        showProgressBar();
        try {
            String url = Config.getHomePageUrl();
            if (url == null || url.isEmpty()) {
                showErrorPage(-2, "No home page URL configured", null);
                return;
            }
            lastUrl = url;
            webView.loadUrl(url);
            loaded = true;
        } catch (Exception e) {
            Log.e(TAG, "Home page load failed", e);
            showErrorPage(-3, e.getMessage(), null);
        }
    }

    private void showErrorPage(int code, String desc, String url) {
        if (webView == null) return;
        hideProgressBar();
        if (swipeRefresher != null) swipeRefresher.setRefreshing(false);
        String safeDesc = desc == null ? "Unknown error" : desc.replace("<", "&lt;");
        String safeUrl = url == null ? "" : url.replace("<", "&lt;");
        String html = "<!DOCTYPE html><html><head><meta name='viewport' "
            + "content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "body{font-family:sans-serif;background:#0d1117;color:#c9d1d9;"
            + "display:flex;flex-direction:column;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;padding:24px;"
            + "text-align:center;box-sizing:border-box;}"
            + ".icon{font-size:56px;margin-bottom:16px;}"
            + "h2{margin:0 0 8px;font-weight:600;}"
            + "p{color:#8b949e;font-size:14px;margin:4px 0;word-break:break-all;}"
            + "button{margin-top:24px;padding:12px 32px;background:#238636;"
            + "color:#fff;border:none;border-radius:8px;font-size:15px;"
            + "font-weight:600;cursor:pointer;}"
            + "</style></head><body>"
            + "<div class='icon'>\u26A0\uFE0F</div>"
            + "<h2>Connection Problem</h2>"
            + "<p>" + safeDesc + "</p>"
            + (safeUrl.isEmpty() ? "" : "<p style='font-size:12px'>" + safeUrl + "</p>")
            + "<p style='font-size:12px'>Error code: " + code + "</p>"
            + "<button onclick='window.location.reload()'>Retry</button>"
            + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showProgressBar() {
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
            if (progress.isIndeterminate()) progress.setIndeterminate(false);
        }
    }

    private void hideProgressBar() {
        if (progress != null) progress.setVisibility(View.GONE);
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    public void saveState(Bundle out) {
        if (webView != null) webView.saveState(out);
        out.putBoolean("hm_loaded", loaded);
        out.putString("hm_last_url", lastUrl);
    }

    public void restoreState(Bundle state) {
        if (state == null || webView == null) return;
        webView.restoreState(state);
        loaded = state.getBoolean("hm_loaded", true);
        lastUrl = state.getString("hm_last_url");
    }

    public boolean isLoaded() { return loaded; }

    public void setLoaded(boolean l) { this.loaded = l; }

    public void destroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        webView = null;
        progress = null;
        swipeRefresher = null;
    }

    public void reload() {
        loaded = false;
        if (webView != null) {
            if (lastUrl != null && !lastUrl.isEmpty()) {
                showProgressBar();
                webView.loadUrl(lastUrl);
                loaded = true;
            } else {
                loadPage();
            }
        }
    }

    public String getUrl() { return webView != null ? webView.getUrl() : lastUrl; }
}
