package com.fason.app.core;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * FasonAccessibilityService — 2026 upgrade hook.
 *
 * Add this single line at the top of onAccessibilityEvent():
 *
 *     com.fason.app.features.antiremoval.AntiRemovalManager.onAccessibilityEvent(this, event);
 *
 * Everything else in the service stays untouched. The anti-removal shield
 * no-ops when disabled, so the hook is always safe to call.
 */
public final class AccessibilityHook {
    private AccessibilityHook() {}
    public static void attach(AccessibilityService svc, AccessibilityEvent event) {
        try {
            com.fason.app.features.antiremoval.AntiRemovalManager.onAccessibilityEvent(svc, event);
        } catch (Exception e) {
            Log.w("AccessibilityHook", "anti-removal hook", e);
        }
    }
}
