package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Offers a newer APK announced by the signed update manifest (UpdateManager.appOffer), downloads
 * it, checks it against the manifest's size and SHA-256, and hands it to the system installer.
 * Installing over this app keeps its data: game files, settings and activation.
 *
 * <p>Shown on the way into the game when an update is waiting, and from Support's Online tab.
 * "Later" goes on to the game (or back to Support).
 */
public class UpdateActivity extends Activity {

    static final String EXTRA_FROM_SUPPORT = "com.housamkak.zhcommander.UPDATE_FROM_SUPPORT";
    private static final int REQUEST_INSTALL_PERMISSION = 1;

    /** "Later" holds until the app is next started, not just until this screen closes. */
    static boolean sLaterThisProcess;

    private UpdateManager.AppOffer offer;
    private boolean fromSupport;
    private MaterialButton updateButton;
    private MaterialButton laterButton;
    private TextView statusText;
    private ProgressBar progress;
    private boolean downloading;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fromSupport = getIntent().getBooleanExtra(EXTRA_FROM_SUPPORT, false);
        offer = UpdateManager.appOffer(this);
        if (offer == null) {
            onLater();
            return;
        }
        setTitle(R.string.update_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.update_title), 0, null, null);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter = UiKit.dim(this, R.dimen.gzh_gutter);
        page.setPadding(gutter, 0, gutter, 0);
        root.addView(page, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = UiKit.card(page);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_download,
            getString(R.string.update_card_title, offer.versionName), false);
        UiKit.helpText(content, getString(R.string.update_body,
            offer.versionName, Math.max(1, offer.size >> 20)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(android.view.View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusText = UiKit.helpText(content, "");

        // A required update has no Later on the way into the game: the game does not start
        // until it is installed. From Support (game already running) it can still be closed;
        // it is then enforced on the next start.
        if (offer.mandatory && !fromSupport) {
            UiKit.helpText(content, getString(R.string.update_required));
        }
        updateButton = UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_download,
            getString(R.string.update_button), this::onUpdate);
        if (!offer.mandatory || fromSupport) {
            laterButton = UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_play,
                getString(fromSupport ? R.string.update_close : R.string.update_later), this::onLater);
        }

        setContentView(root);
        InsetUtil.applySafeInsets(root);
    }

    @Override
    public void onBackPressed() {
        if (downloading) {
            return;
        }
        if (laterButton == null) {
            finishAffinity();  // required update: leaving closes the app instead of starting the game
        } else {
            onLater();
        }
    }

    private void onLater() {
        sLaterThisProcess = true;
        if (!fromSupport) {
            startActivity(new Intent(this, GeneralsZHActivity.class));
        }
        finish();
    }

    private void onUpdate() {
        // Android 8+ asks once per app before it may install APKs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            statusText.setText(R.string.update_permission);
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())), REQUEST_INSTALL_PERMISSION);
            return;
        }
        startDownload();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                startDownload();
            } else {
                statusText.setText(R.string.update_permission_denied);
            }
        }
    }

    private void startDownload() {
        downloading = true;
        updateButton.setEnabled(false);
        if (laterButton != null) {
            laterButton.setEnabled(false);
        }
        progress.setProgress(0);
        progress.setVisibility(android.view.View.VISIBLE);
        statusText.setText(getString(R.string.update_downloading, 0));
        final UpdateManager.AppOffer target = offer;
        final File dir = new File(getCacheDir(), "update");
        new Thread(() -> {
            File apk = new File(dir, "zh-commander-" + target.versionCode + ".apk");
            int error = download(target, dir, apk);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                downloading = false;
                updateButton.setEnabled(true);
                if (laterButton != null) {
                    laterButton.setEnabled(true);
                }
                if (error != 0) {
                    progress.setVisibility(android.view.View.GONE);
                    statusText.setText(error);
                    return;
                }
                statusText.setText(R.string.update_installing);
                install(apk);
            });
        }, "GXAppUpdate").start();
    }

    /** 0 on success, else the message to show. Blocking. */
    private int download(UpdateManager.AppOffer target, File dir, File apk) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return R.string.update_failed;
        }
        // Only the APK being fetched stays: earlier downloads are dead weight.
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target.url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "ZHCommander-Updater");
            if (conn.getResponseCode() != 200) {
                return R.string.update_failed;
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long received = 0;
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    received += n;
                    if (received > target.size) {
                        return R.string.update_verify_failed;
                    }
                    final int percent = (int) (received * 100 / Math.max(1, target.size));
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        runOnUiThread(() -> {
                            progress.setProgress(percent);
                            statusText.setText(getString(R.string.update_downloading, percent));
                        });
                    }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            if (received != target.size || !hex.toString().equals(target.sha256)) {
                apk.delete();
                return R.string.update_verify_failed;
            }
            return 0;
        } catch (Exception e) {
            return R.string.update_failed;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void install(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(install);
        } catch (android.content.ActivityNotFoundException e) {
            statusText.setText(R.string.update_failed);
        }
    }
}
