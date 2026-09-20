package com.fason.app.features.firewall;

import android.content.Context;
import android.content.SharedPreferences;

import com.fason.app.core.FasonApp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * FirewallManager — per-app block/allow with persisted rules.
 * Rules are consumed by the accessibility service (blocks launches)
 * and the app manager (hides blocked apps).
 */
public final class FirewallManager {
    private static final String PREFS = "fason_firewall";
    private static volatile FirewallManager instance;
    private final SharedPreferences prefs;
    private final Map<String, Rule> rules = new HashMap<>();

    public static final class Rule {
        public String packageName;
        public boolean blocked;
        public long addedAt;
        public String note;
    }

    private FirewallManager() {
        prefs = FasonApp.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized FirewallManager getInstance() {
        if (instance == null) instance = new FirewallManager();
        return instance;
    }

    private void load() {
        synchronized (rules) {
            rules.clear();
            try {
                JSONArray arr = new JSONArray(prefs.getString("rules", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Rule r = new Rule();
                    r.packageName = o.optString("packageName");
                    r.blocked = o.optBoolean("blocked", true);
                    r.addedAt = o.optLong("addedAt");
                    r.note = o.optString("note", "");
                    rules.put(r.packageName, r);
                }
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (rules) {
                for (Rule r : rules.values()) {
                    JSONObject o = new JSONObject();
                    o.put("packageName", r.packageName);
                    o.put("blocked", r.blocked);
                    o.put("addedAt", r.addedAt);
                    o.put("note", r.note);
                    arr.put(o);
                }
            }
            prefs.edit().putString("rules", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public JSONObject block(String pkg, String note) {
        Rule r = new Rule();
        r.packageName = pkg;
        r.blocked = true;
        r.addedAt = System.currentTimeMillis();
        r.note = note == null ? "" : note;
        synchronized (rules) { rules.put(pkg, r); }
        save();
        return status();
    }

    public JSONObject allow(String pkg) {
        synchronized (rules) { rules.remove(pkg); }
        save();
        return status();
    }

    public boolean isBlocked(String pkg) {
        synchronized (rules) {
            Rule r = rules.get(pkg);
            return r != null && r.blocked;
        }
    }

    public JSONObject list() {
        JSONObject r = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            synchronized (rules) {
                for (Rule rule : rules.values()) {
                    JSONObject o = new JSONObject();
                    o.put("packageName", rule.packageName);
                    o.put("blocked", rule.blocked);
                    o.put("addedAt", rule.addedAt);
                    o.put("note", rule.note);
                    arr.put(o);
                }
            }
            r.put("rules", arr);
            r.put("count", rules.size());
        } catch (Exception ignored) {}
        return r;
    }

    public JSONObject status() {
        JSONObject s = new JSONObject();
        try {
            int blocked = 0;
            synchronized (rules) {
                for (Rule r : rules.values()) if (r.blocked) blocked++;
            }
            s.put("total", rules.size());
            s.put("blocked", blocked);
        } catch (Exception ignored) {}
        return s;
    }
}
