package com.fason.app.persistence.anchors;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;

/**
 * SyncService — binder for FasonSyncAdapter.
 * Required boilerplate for SyncAdapter framework.
 */
public class SyncService extends Service {
    private static volatile FasonSyncAdapter syncAdapter = null;
    private static final Object lock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (lock) {
            if (syncAdapter == null) {
                syncAdapter = new FasonSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return syncAdapter.getSyncAdapterBinder();
    }
}
