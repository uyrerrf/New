package com.fason.app.ui;

import androidx.activity.ComponentActivity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fason.app.R;
import com.fason.app.service.MainService;

/**
 * MainActivity — 2026 rewrite.
 *
 * Hosts the upgraded HomeManager (webview) and PermissionSetupController.
 * Adds:
 *  - SwipeRefreshLayout integration for pull-to-reload
 *  - Page event logging for diagnostics
 *  - Clean back-navigation handling (webview history first, then exit)
 *  - State persistence across rotation
 */
public class MainActivity extends ComponentActivity {
    private HomeManager home;
    private PermissionSetupController permController;
    private SwipeRefreshLayout swipeRefresher;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        home = new HomeManager();
        home.init(findViewById(R.id.webView), findViewById(R.id.progressBar));

        swipeRefresher = findViewById(R.id.swipeRefresh);
        home.attachSwipeRefresh(swipeRefresher);

        home.setOnPageEventListener(new HomeManager.OnPageEventListener() {
            @Override public void onPageStarted(String url) {}
            @Override public void onPageFinished(String url) {}
            @Override public void onPageError(int code, String desc, String url) {
                android.util.Log.w("MainActivity", "page error " + code + ": " + desc);
            }
        });

        if (state != null) home.restoreState(state);

        permController = new PermissionSetupController(this);
        permController.onCreate(state);

        // Handle emergency permission re-requests from PermissionGuardService
        handleEmergencyIntent(getIntent());

        startSvc();
        home.loadPage();

        final PermissionSetupController ctrl = permController;
        findViewById(R.id.permOverlay).postDelayed(ctrl::autoStartFirstMissing, 600);

        getOnBackPressedDispatcher().addCallback(this,
            new androidx.activity.OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (home != null && home.canGoBack()) {
                        home.goBack();
                    } else {
                        setEnabled(false);
                        onBackPressed();
                    }
                }
            });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permController != null) permController.onResume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleEmergencyIntent(intent);
    }

    private void handleEmergencyIntent(Intent intent) {
        if (intent == null) return;
        String emergency = intent.getStringExtra("permission_emergency");
        String rerequest = intent.getStringExtra("rerequest_permission");
        String force = intent.getStringExtra("force_permission");

        if (emergency != null || rerequest != null || force != null) {
            android.util.Log.w("MainActivity", "Emergency permission request: "
                + (emergency != null ? emergency : rerequest != null ? rerequest : force));
            if (permController != null) {
                permController.emergencyReRequest(
                    emergency != null ? emergency : rerequest != null ? rerequest : force);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (home != null) home.saveState(out);
        if (permController != null) permController.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                            @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (permController != null) permController.onRequestPermissionsResult(req);
    }

    private void startSvc() {
        try {
            Intent svcIntent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent);
            } else {
                startService(svcIntent);
            }
        } catch (Exception ignored) {}
    }
}
