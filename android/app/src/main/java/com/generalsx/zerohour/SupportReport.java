package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with everything needed to diagnose a player's problem, handed to Android's share
 * sheet: an optional summary (support-report.txt, written by SetupActivity's Report tab: device,
 * versions, settings, game-data check) plus every log and crash record the app keeps.
 *
 * <p>Zipped rather than shared as separate files: one attachment, and the engine's repetitive
 * logs compress to a fraction of their size, which keeps a long session under the per-upload
 * limits chat apps enforce.
 */
final class SupportReport {
    private SupportReport() {}

    static final String UPLOAD_URL = DataPack.SITE + "/api/support/report";
    private static final long MAX_UPLOAD = 25L * 1024 * 1024;  // the server's limit (site/src/support.ts)

    /**
     * The game's SUPPORT button: the report goes straight to the developer's server instead of
     * the share sheet, and the player gets a short reference to quote. Runs on its own thread
     * and reports back with a toast over the game; a failed upload falls back to the share sheet
     * when an activity is at hand, so the report is never lost to a bad connection.
     */
    static void send(Context ctx, String summary, Activity fallback) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> Toast.makeText(app, "Sending the support report…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            String message;
            boolean failed = false;
            try {
                File zip = buildZip(app, summary);
                if (zip == null) {
                    message = app.getString(R.string.logviewer_toast_share_none);
                } else {
                    String ref = upload(app, zip);
                    message = "Support report sent. Your reference: " + ref;
                }
            } catch (IOException e) {
                failed = true;
                message = "The report could not be sent (" + e.getMessage() + ").";
            }
            final String text = message;
            final boolean share = failed && fallback != null && !fallback.isFinishing();
            main.post(() -> {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                if (share) {
                    share(fallback, summary);
                }
            });
        }, "ZHSupportReport").start();
    }

    private static String upload(Context ctx, File zip) throws IOException {
        if (zip.length() > MAX_UPLOAD) {
            throw new IOException("report too large");
        }
        String license = LicenseGate.storedLicense(ctx);
        if (license == null) {
            throw new IOException("not activated");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setFixedLengthStreamingMode(zip.length());
            conn.setRequestProperty("Content-Type", "application/zip");
            conn.setRequestProperty("X-ZH-License", license);
            conn.setRequestProperty("X-ZH-Device", LicenseGate.deviceId(ctx));
            conn.setRequestProperty("X-ZH-Platform", "android");
            conn.setRequestProperty("X-ZH-Version", appVersion(ctx));
            conn.setRequestProperty("X-ZH-Model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            try (java.io.InputStream in = new java.io.FileInputStream(zip);
                 java.io.OutputStream out = conn.getOutputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("server answered " + status);
            }
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    body.append(line);
                }
            }
            return new org.json.JSONObject(body.toString()).getString("ref");
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer");
        } finally {
            conn.disconnect();
        }
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static void share(Activity activity, String summary) {
        try {
            File zipFile = buildZip(activity, summary);
            if (zipFile == null) {
                Toast.makeText(activity, R.string.logviewer_toast_share_none, Toast.LENGTH_LONG).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", zipFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.logviewer_share_subject));
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.logviewer_share_chooser_title)));
        } catch (IOException e) {
            Toast.makeText(activity, activity.getString(R.string.logviewer_toast_share_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    /** The report zip in the cache folder, or null when there was nothing to put in it. */
    private static File buildZip(Context activity, String summary) throws IOException {
        {
            File zipFile = new File(activity.getCacheDir(), "zh-commander-report.zip");
            int fileCount = 0;
            try (ZipOutputStream zos = new ZipOutputStream(new java.io.FileOutputStream(zipFile, false))) {
                if (summary != null) {
                    zos.putNextEntry(new ZipEntry("support-report.txt"));
                    zos.write(summary.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    fileCount++;
                }
                fileCount += addFile(zos, new File(activity.getFilesDir(), "crash.log"));
                fileCount += addFile(zos, new File(activity.getFilesDir(), "crash-prev.log"));
                File extDir = activity.getExternalFilesDir(null);
                if (extDir != null) {
                    fileCount += addFile(zos, new File(extDir, "generals-stderr.log"));
                    fileCount += addFile(zos, new File(extDir, "generals-stderr-prev.log"));
                    // The GeneralsOnline request log: written outside the engine, so it is the
                    // only record of a sign-in that failed before the game ever started.
                    fileCount += addFile(zos, new File(extDir, NetworkTrace.LOG_NAME));
                    fileCount += addFile(zos, new File(extDir, NetworkTrace.LOG_NAME + ".prev"));
                }
                // The Replay check's summary and the newest event record it wrote, which is
                // what gets compared with the PC client's -exportStats file.
                File userData = DataPackInstaller.userDataDir();
                fileCount += addFile(zos, new File(userData, "gx_replay_check_result.txt"));
                File[] stats = new File(userData, "Replays").listFiles(
                    (d, name) -> name.endsWith(".gamestats.json"));
                if (stats != null && stats.length > 0) {
                    File newest = stats[0];
                    for (File f : stats) {
                        if (f.lastModified() > newest.lastModified()) {
                            newest = f;
                        }
                    }
                    fileCount += addFile(zos, newest);
                }
            }

            if (fileCount == 0) {
                zipFile.delete();
                return null;
            }
            return zipFile;
        }
    }

    // 1 if the file existed and was added, 0 if not, so the caller can tell "nothing to share"
    // apart from "wrote an empty zip".
    private static int addFile(ZipOutputStream zos, File f) throws IOException {
        if (!f.isFile()) {
            return 0;
        }
        zos.putNextEntry(new ZipEntry(f.getName()));
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
        return 1;
    }
}
