package com.fason.app.core;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.fason.app.core.config.Config;
import com.fason.app.service.MainService;
import com.fason.app.worker.KeepAliveWorker;
import com.fason.app.persistence.PersistenceOrchestrator;
import java.util.concurrent.TimeUnit;

public class FasonApp extends Application {
    private static volatile FasonApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            Config.init();
        } catch (Throwable t) {
            Log.e("FasonApp", "Config init failed", t);
            return;
        }
        try {
            PersistenceOrchestrator.init();
        } catch (Exception e) {
            Log.e("FasonApp", "Persistence init failed", e);
        }
        startServices();
    }

    private void startServices() {
        try {
            Intent svcIntent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent);
            } else {
                startService(svcIntent);
            }
        } catch (Exception ignored) {}
        try {
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                KeepAliveWorker.class, 15, TimeUnit.MINUTES
            ).build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                Protocol.WORK_KEEP_ALIVE,
                ExistingPeriodicWorkPolicy.KEEP,
                work
            );
        } catch (Exception ignored) {}
    }

    public static Context getContext() {
        if (instance == null) {
            throw new IllegalStateException("FasonApp not initialized");
        }
        return instance.getApplicationContext();
    }
}
