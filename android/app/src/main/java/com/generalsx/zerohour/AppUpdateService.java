package com.generalsx.zerohour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads an app update (the APK an UpdateManager offer names) the way game data is downloaded:
 * a foreground service, so it carries on with the screen off or another app in front; into a
 * .part file resumed over HTTP Range after a dropped connection, with retries; checked against the
 * offer's SHA-256 before it becomes the .apk that UpdateActivity installs.
 *
 * <p>Also started on its own (prefetch) when an offer is seen on an unmetered network, so that by
 * the time the player taps Update the APK is usually already here and installs at once. Android
 * still asks the player to confirm the install itself: apps outside a store cannot skip that.
 */
public class AppUpdateService extends Service {
    private static final String CHANNEL = "zh_app_update";
    private static final int NOTIFICATION_ID = 7102;
    private static final long[] RETRY_DELAYS_MS = { 2000, 5000, 15000, 30000 };

    static volatile boolean sRunning;
    static volatile String sError;
    static volatile long sDone;
    static volatile long sTotal;

    private static File dir(Context ctx) {
        return new File(ctx.getCacheDir(), "update");
    }

    private static File apkFile(Context ctx, UpdateManager.AppOffer offer) {
        return new File(dir(ctx), "zh-commander-" + offer.versionCode + ".apk");
    }

    /** The offered version's APK, downloaded and checked; null while it is not. */
    static File readyApk(Context ctx, UpdateManager.AppOffer offer) {
        File apk = apkFile(ctx, offer);
        return apk.isFile() && apk.length() == offer.size ? apk : null;
    }

    static void start(Context ctx) {
        if (sRunning) {
            return;
        }
        UpdateManager.AppOffer offer = UpdateManager.appOffer(ctx);
        if (offer == null || readyApk(ctx, offer) != null) {
            return;
        }
        sRunning = true;
        sError = null;
        sDone = 0;
        sTotal = offer.size;
        Intent intent = new Intent(ctx, AppUpdateService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** On Wi-Fi (or any unmetered network), fetch a waiting update ahead of the player's tap. */
    static void prefetch(Context ctx) {
        // The game asks for offers every couple of seconds; a failing prefetch (a flaky network)
        // waits ten minutes before trying again on its own. The player's tap always starts at once.
        long now = System.currentTimeMillis();
        if (now - sLastPrefetch < 10 * 60 * 1000L) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered()) {
            sLastPrefetch = now;
            start(ctx.getApplicationContext());
        }
    }

    private static volatile long sLastPrefetch;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification first = notification("Starting…", 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, first);
        }
        new Thread(this::run, "GXAppUpdateService").start();
        return START_NOT_STICKY;
    }

    private void run() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZHCommander:update");
        wake.acquire(60 * 60 * 1000L);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            UpdateManager.AppOffer offer = UpdateManager.appOffer(this);
            if (offer == null) {
                return;
            }
            File dir = dir(this);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File apk = apkFile(this, offer);
            File part = new File(apk.getPath() + ".part");
            // Only this version's files stay: older downloads are dead weight.
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (!f.equals(part) && !f.equals(apk)) {
                        f.delete();
                    }
                }
            }
            sTotal = offer.size;
            final int[] lastPercent = { -1 };
            for (int attempt = 0; ; attempt++) {
                try {
                    download(offer, part, (done) -> {
                        sDone = done;
                        int percent = (int) (done * 100 / Math.max(1, offer.size));
                        if (percent != lastPercent[0]) {
                            lastPercent[0] = percent;
                            nm.notify(NOTIFICATION_ID, notification(
                                "ZH Commander " + offer.versionName + ": " + percent + "%", percent, 100));
                        }
                    });
                    break;
                } catch (IOException e) {
                    if (attempt >= RETRY_DELAYS_MS.length || part.length() > offer.size) {
                        throw e;
                    }
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                }
            }
            if (part.length() != offer.size || !offer.sha256.equals(sha256(part))) {
                part.delete();
                throw new IOException("the download did not match; it will start over");
            }
            if (!part.renameTo(apk)) {
                throw new IOException("cannot move " + part);
            }
            sDone = offer.size;
        } catch (Exception e) {
            sError = e.getMessage() != null ? e.getMessage() : "error";
        } finally {
            sRunning = false;
            if (wake.isHeld()) {
                wake.release();
            }
            stopForeground(true);
            stopSelf();
        }
    }

    interface Progress {
        void onProgress(long done);
    }

    // Continues the .part file where it stopped (Range); starts over if the server sends it whole.
    private static void download(UpdateManager.AppOffer offer, File part, Progress progress) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have >= offer.size) {
            return;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(offer.url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "ZHCommander-Updater");
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=" + have + "-");
        }
        try {
            int status = conn.getResponseCode();
            boolean resumed = status == 206;
            if (status != 200 && !resumed) {
                throw new IOException("HTTP " + status);
            }
            if (!resumed) {
                have = 0;
            }
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    if (have > offer.size) {
                        throw new IOException("more data than the update's size");
                    }
                    progress.onProgress(have);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (have != offer.size) {
            throw new IOException("the connection dropped at " + (have >> 20) + " MB");
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "App updates",
                NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text, int progress, int max) {
        Intent open = new Intent(this, UpdateActivity.class)
            .putExtra(UpdateActivity.EXTRA_FROM_SUPPORT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading the ZH Commander update")
            .setContentText(text)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
