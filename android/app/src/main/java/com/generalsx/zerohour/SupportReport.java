package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
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

    static void share(Activity activity, String summary) {
        try {
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
