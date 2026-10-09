package com.generalsx.zerohour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Runs the game-data download (DataPack) as a foreground service, so it keeps going when the
 * player leaves the app or the screen turns off, with its progress in a notification.
 *
 * <p>DataDownloadActivity starts it and reads its state from the static fields below; the
 * service itself keeps no other state. Whatever finishes is recorded by DataPack as it goes, so
 * even if Android stops the service, the next start resumes where it was.
 */
public class DataDownloadService extends Service {

    private static final String CHANNEL = "zh_data_download";
    private static final int NOTIFICATION_ID = 7101;

    // Read by DataDownloadActivity on the main thread; written by the download thread.
    static volatile boolean sRunning;
    static volatile boolean sFinished;
    static volatile String sError;
    static volatile long sDone;
    static volatile long sTotal;
    static volatile String sVersion;

    static void start(Context ctx) {
        if (sRunning) {
            return;
        }
        sRunning = true;
        sFinished = false;
        sError = null;
        Intent intent = new Intent(ctx, DataDownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification first = notification(getString(R.string.data_notification_starting), 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, first);
        }
        new Thread(this::run, "GXDataService").start();
        return START_NOT_STICKY;
    }

    private void run() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZHCommander:data");
        wake.acquire(6 * 60 * 60 * 1000L);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            DataPack.Manifest m = DataPack.fetchManifest(this);
            if (m == null) {
                throw new java.io.IOException("nothing published");
            }
            sVersion = m.version;
            ZHTelemetry.track("data_download", "stage", "start");
            List<DataPack.Entry> todo = DataPack.missing(this, m);
            sTotal = DataPack.bytesOf(todo);
            sDone = 0;
            final long[] lastPercent = { -1 };
            DataPack.install(this, m, todo, (done, total, file) -> {
                sDone = done;
                long percent = done * 100 / Math.max(1, total);
                if (percent != lastPercent[0]) {
                    lastPercent[0] = percent;
                    nm.notify(NOTIFICATION_ID, notification(
                        getString(R.string.data_progress, done >> 20, total >> 20), (int) percent, 100));
                }
            });
            java.io.File root = getExternalFilesDir(null);
            if (root != null) {
                SetupActivity.copyBundledRuntimeIfMissing(root, DataPack.gameDataDir(this).getPath());
            }
            ZHTelemetry.track("data_download", "stage", "complete");
            sFinished = true;
        } catch (java.io.IOException e) {
            sError = e.getMessage() != null ? e.getMessage() : "error";
            ZHTelemetry.track("data_download", "stage", "fail", "error", sError);
        } finally {
            sRunning = false;
            if (wake.isHeld()) {
                wake.release();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (sFinished) {
                nm.notify(NOTIFICATION_ID, doneNotification(getString(R.string.data_notification_done)));
            } else {
                nm.notify(NOTIFICATION_ID, doneNotification(getString(R.string.data_notification_paused)));
            }
            stopSelf();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.data_notification_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, DataDownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(String text, int progress, int max) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.data_notification_title))
            .setContentText(text)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build();
    }

    private Notification doneNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.data_notification_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
