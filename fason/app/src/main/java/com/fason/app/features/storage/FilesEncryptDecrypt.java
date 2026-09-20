package com.fason.app.features.storage;

import android.os.Build;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class FilesEncryptDecrypt {
    private static final byte MAGIC = 'F';
    private static final byte VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int KEY_LEN_BITS = 256;
    private static final int CHUNK = 64 * 1024;

    private FilesEncryptDecrypt() {}
    public static byte[] encrypt(byte[] plaintext, String password) {
        if (plaintext == null || password == null || password.isEmpty()) return null;
        try {
            byte[] salt = new byte[SALT_LEN];
            new SecureRandom().nextBytes(salt);
            byte[] nonce = new byte[NONCE_LEN];
            new SecureRandom().nextBytes(nonce);
            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(2 + SALT_LEN + NONCE_LEN + ct.length);
            buf.put(MAGIC);
            buf.put(VERSION);
            buf.put(salt);
            buf.put(nonce);
            buf.put(ct);
            return buf.array();
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] decrypt(byte[] blob, String password) {
        if (blob == null || password == null || password.isEmpty()) return null;
        try {
            if (blob.length < 2 + SALT_LEN + NONCE_LEN) return null;
            if (blob[0] != MAGIC) return null;
            if (blob[1] != VERSION) return null;
            byte[] salt = new byte[SALT_LEN];
            System.arraycopy(blob, 2, salt, 0, SALT_LEN);
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(blob, 2 + SALT_LEN, nonce, 0, NONCE_LEN);
            byte[] ct = new byte[blob.length - 2 - SALT_LEN - NONCE_LEN];
            System.arraycopy(blob, 2 + SALT_LEN + NONCE_LEN, ct, 0, ct.length);
            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, nonce));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    public static String encryptBase64(byte[] plaintext, String password) {
        byte[] ct = encrypt(plaintext, password);
        if (ct == null) return null;
        return Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    public static byte[] decryptBase64(String b64, String password) {
        if (b64 == null) return null;
        try {
            return decrypt(Base64.decode(b64, Base64.NO_WRAP), password);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean encryptFile(String srcPath, String password) {
        if (password == null || password.isEmpty()) return false;
        File f = FileManager.safeFile(srcPath);
        if (f == null) return false;
        if (f.isDirectory()) return encryptFolder(f.getAbsolutePath(), password);
        if (isEncrypted(f.getAbsolutePath())) return true;
        return transformInPlace(f.getAbsolutePath(), password, true);
    }

    public static boolean decryptFile(String srcPath, String password) {
        if (password == null || password.isEmpty()) return false;
        File f = FileManager.safeFile(srcPath);
        if (f == null) return false;
        if (f.isDirectory()) return decryptFolder(f.getAbsolutePath(), password);
        if (!isEncrypted(f.getAbsolutePath())) return false;
        return transformInPlace(f.getAbsolutePath(), password, false);
    }

    public static boolean encryptFolder(String dirPath, String password) {
        if (password == null || password.isEmpty()) return false;
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return false;
        boolean allOk = true;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory()) {
                if (!encryptFolder(child.getAbsolutePath(), password)) allOk = false;
            } else if (child.isFile() && !isEncrypted(child.getAbsolutePath())) {
                if (!transformInPlace(child.getAbsolutePath(), password, true)) allOk = false;
            }
        }
        return allOk;
    }

    public static boolean decryptFolder(String dirPath, String password) {
        if (password == null || password.isEmpty()) return false;
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return false;
        boolean allOk = true;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory()) {
                if (!decryptFolder(child.getAbsolutePath(), password)) allOk = false;
            } else if (child.isFile() && isEncrypted(child.getAbsolutePath())) {
                if (!transformInPlace(child.getAbsolutePath(), password, false)) allOk = false;
            }
        }
        return allOk;
    }

    public static boolean isEncrypted(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] header = new byte[2];
            int read = fis.read(header);
            return read == 2 && header[0] == MAGIC && header[1] == VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean transformInPlace(String srcPath, String password, boolean encrypting) {
        if (srcPath == null || srcPath.isEmpty()) return false;
        File src = new File(srcPath);
        if (!src.exists() || !src.canRead() || !src.canWrite()) return false;
        File parent = src.getParentFile();
        if (parent == null) parent = new File(".");
        File tmp = null;
        try {
            tmp = File.createTempFile("fason-tmp-", ".tmp", parent);
            boolean ok = encrypting
                ? encryptFileContents(src, tmp, password)
                : decryptFileContents(src, tmp, password);
            if (!ok) {
                tmp.delete();
                return false;
            }
            if (tmp.renameTo(src)) {
                return true;
            }
            if (copyFile(tmp, src)) {
                tmp.delete();
                return true;
            }
            tmp.delete();
            return false;
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            return false;
        }
    }

    private static boolean encryptFileContents(File src, File dst, String password) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        char[] pass = null;
        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(dst);
            byte[] salt = new byte[SALT_LEN];
            new SecureRandom().nextBytes(salt);
            byte[] nonce = new byte[NONCE_LEN];
            new SecureRandom().nextBytes(nonce);
            pass = password.toCharArray();
            SecretKey key = deriveKey(pass, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, nonce));
            fos.write(MAGIC);
            fos.write(VERSION);
            fos.write(salt);
            fos.write(nonce);
            byte[] buf = new byte[CHUNK];
            int read;
            while ((read = fis.read(buf)) > 0) {
                byte[] out = cipher.update(buf, 0, read);
                if (out != null) fos.write(out);
            }
            byte[] tail = cipher.doFinal();
            if (tail != null) fos.write(tail);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (pass != null) Arrays.fill(pass, '\0');
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean decryptFileContents(File src, File dst, String password) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        char[] pass = null;
        try {
            fis = new FileInputStream(src);
            int magic = fis.read();
            int version = fis.read();
            if (magic != MAGIC || version != VERSION) return false;
            byte[] salt = new byte[SALT_LEN];
            new java.io.DataInputStream(fis).readFully(salt);
            byte[] nonce = new byte[NONCE_LEN];
            new java.io.DataInputStream(fis).readFully(nonce);
            pass = password.toCharArray();
            SecretKey key = deriveKey(pass, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, nonce));
            fos = new FileOutputStream(dst);
            byte[] buf = new byte[CHUNK];
            int read;
            while ((read = fis.read(buf)) > 0) {
                byte[] out = cipher.update(buf, 0, read);
                if (out != null) fos.write(out);
            }
            byte[] tail = cipher.doFinal();
            if (tail != null) fos.write(tail);
            return true;
        } catch (Exception e) {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (dst != null) dst.delete(); } catch (Exception ignored) {}
            return false;
        } finally {
            if (pass != null) Arrays.fill(pass, '\0');
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst);
             FileChannel inCh = in.getChannel();
             FileChannel outCh = out.getChannel()) {
            outCh.transferFrom(inCh, 0, inCh.size());
            return dst.exists() && dst.length() == src.length();
        } catch (Exception e) {
            return false;
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        char[] pass = password.toCharArray();
        try {
            return deriveKey(pass, salt);
        } finally {
            Arrays.fill(pass, '\0');
        }
    }

    private static SecretKey deriveKey(char[] pass, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, PBKDF2_ITERATIONS, KEY_LEN_BITS);
        try {
            String algo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                algo = "PBKDF2WithHmacSHA256";
            } else {
                algo = "PBKDF2WithHmacSHA1";
            }
            SecretKeyFactory skf = SecretKeyFactory.getInstance(algo);
            byte[] keyBytes = skf.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
