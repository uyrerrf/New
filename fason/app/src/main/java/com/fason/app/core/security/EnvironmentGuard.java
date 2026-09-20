package com.fason.app.core.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Environment evasion + encrypted preference vault.
 * Upgrade of Lab-RATS SystemAnalytics: emulator/debugger/root/tamper/hook
 * detection with configurable responses, plus AES-256-CBC encrypted storage
 * keyed off a per-install derived secret.
 */
public final class EnvironmentGuard {
    public enum Response { NONE, SILENT_EXIT, DECOY, FAKE_DATA }

    private static volatile Response response = Response.NONE;
    private static volatile boolean tripped = false;
    private static String cachedKey;

    private EnvironmentGuard() {}

    public static void configure(Response r) { response = r; }

    public static boolean isTripped() { return tripped; }

    /** Run full sweep; returns true when a hostile environment is detected. */
    public static boolean sweep(Context ctx) {
        boolean hostile = isDebugger()
                || isEmulator()
                || isRooted()
                || isHooked()
                || isTampered(ctx)
                || isTestLab(ctx);
        if (hostile) {
            tripped = true;
            switch (response) {
                case SILENT_EXIT:
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                    break;
                case DECOY:
                case FAKE_DATA:
                case NONE:
                default:
                    break;
            }
        }
        return hostile;
    }

    public static boolean isDebugger() {
        return android.os.Debug.isDebuggerConnected()
            || (android.os.Debug.getThreadCpuTimeNanos() < 0 && false)
            || new File("/system/bin/su").exists() && isTracerPidSelf();
    }

    private static boolean isTracerPidSelf() {
        try {
            byte[] buf = new byte[64];
            java.io.FileInputStream f = new java.io.FileInputStream("/proc/self/status");
            int n = f.read(buf);
            f.close();
            String s = new String(buf, 0, n);
            for (String line : s.split("\n")) {
                if (line.startsWith("TracerPid:")) {
                    return !line.trim().endsWith("0");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean isEmulator() {
        String f = Build.FINGERPRINT;
        String m = Build.MODEL;
        String p = Build.PRODUCT;
        String h = Build.HARDWARE;
        String ma = Build.MANUFACTURER;
        boolean emu = (f.startsWith("generic") && f.contains("sdk"))
                || f.startsWith("unknown")
                || m.contains("sdk") || m.contains("Emulator") || m.contains("Android SDK")
                || m.contains("x86") || ma.contains("Geny")
                || (ma.contains("Google") && "ranchu".equals(h))
                || p.contains("sdk") || p.contains("emulator")
                || h.contains("goldfish") || h.contains("ranchu")
                || h.contains("vbox") || h.contains("qemu")
                || Build.BOARD.equals("unknown")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"));
        if (!emu) {
            String[] qemuProps = {
                "ro.kernel.qemu", "ro.hardware.vm", "init.svc.qemud",
                "qemu.sf.lcd_density", "ro.bootloader", "sys.qemu"
            };
            for (String prop : qemuProps) {
                if ("1".equals(getProp(prop)) || "qemu".equals(getProp(prop))
                        || "unknown".equals(getProp(prop))) {
                    emu = true;
                    break;
                }
            }
        }
        return emu;
    }

    public static boolean isRooted() {
        String[] paths = {
            "/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su",
            "/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/adb/magisk", "/sbin/.magisk", "/data/adb/modules"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHooked() {
        try {
            throw new Exception("probe");
        } catch (Exception e) {
            for (StackTraceElement el : e.getStackTrace()) {
                String cls = el.getClassName();
                if (cls.contains("de.robv.android.xposed") || cls.contains("com.saurik.substrate")
                        || cls.contains("frida") || cls.contains("lsposed")) {
                    return true;
                }
            }
        }
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("com.saurik.substrate.MS$MethodAlteration");
            return true;
        } catch (ClassNotFoundException ignored) {}
        return false;
    }

    public static boolean isTampered(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            int expected = ApplicationInfo.FLAG_DEBUGGABLE ^ ApplicationInfo.FLAG_DEBUGGABLE;
            boolean debuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            String installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
            boolean storeInstalled = installer != null
                && (installer.startsWith("com.android.vending")
                    || installer.startsWith("com.google.android"));
            return debuggable && !storeInstalled;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isTestLab(Context ctx) {
        try {
            String testLab = Settings.System.getString(ctx.getContentResolver(), "firebase.test.lab");
            return "true".equals(testLab);
        } catch (Exception e) {
            return false;
        }
    }

    public static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v != null ? v.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Encrypted vault (AES-256-CBC, per-install key) ----------

    private static SecretKeySpec deriveKey(Context ctx) {
        try {
            if (cachedKey != null) {
                return new SecretKeySpec(hex(cachedKey), "AES");
            }
            String seed = Build.FINGERPRINT + ":" + ctx.getPackageName() + ":"
                + Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID)
                + ":" + UUID.randomUUID();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(seed.getBytes("UTF-8"));
            cachedKey = hex(key);
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new RuntimeException("key derivation failed", e);
        }
    }

    public static String encrypt(Context ctx, String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(ctx));
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return hex(out);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decrypt(Context ctx, String encHex) {
        try {
            byte[] combined = unhex(encHex);
            if (combined.length < 16) return "";
            byte[] iv = Arrays.copyOfRange(combined, 0, 16);
            byte[] ct = Arrays.copyOfRange(combined, 16, combined.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(ctx), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static void putSecure(Context ctx, String prefs, String key, String value) {
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
            .putString(key, encrypt(ctx, value)).apply();
    }

    public static String getSecure(Context ctx, String prefs, String key, String def) {
        String enc = ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(key, null);
        if (enc == null) return def;
        String dec = decrypt(ctx, enc);
        return dec.isEmpty() ? def : dec;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }
}
