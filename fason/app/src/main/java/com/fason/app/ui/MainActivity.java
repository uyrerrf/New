package com.fason.app.ui;

import androidx.activity.ComponentActivity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fason.app.R;
import com.fason.app.core.permissions.PermissionWizardController;
import com.fason.app.service.MainService;

/**
 * MainActivity — 2026 rewrite.
 *
 * Hosts the hardened HomeManager (WebView) and the staged
 * PermissionWizardController. The wizard presents one permission wave
 * per screen with a single contextual action — no walls, no cat.
 */
public class MainActivity extends ComponentActivity {
    private HomeManager home;
    private PermissionWizardController permWizard;
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

        permWizard = new PermissionWizardController(this);
        permWizard.onCreate(state);

        startSvc();
        home.loadPage();

        final PermissionWizardController wizard = permWizard;
        findViewById(R.id.permOverlay).postDelayed(wizard::autoStartFirstMissing, 600);

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
        if (permWizard != null) permWizard.onResume();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (home != null) home.saveState(out);
        if (permWizard != null) permWizard.onSaveInstanceState(out);
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
        if (permWizard != null) permWizard.onRequestPermissionsResult(req);
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
