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
        // Leaving is fine while downloading: AppUpdateService carries on, with its notification,
        // and the next Update tap installs what it fetched.
        if (laterButton == null && downloading) {
            moveTaskToBack(true);  // required update still downloading: out of the way, not into the game
        } else if (laterButton == null) {
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

    // GeneralsX @feature ZH Commander 10/10/2026 The download runs in AppUpdateService (foreground,
    // resumable, retried, often already done in the background on Wi-Fi); this screen only shows
    // where it is and installs the APK once it is complete and checked.
    private final android.os.Handler poll = new android.os.Handler(android.os.Looper.getMainLooper());

    private void startDownload() {
        File ready = AppUpdateService.readyApk(this, offer);
        if (ready != null) {
            statusText.setText(R.string.update_installing);
            install(ready);
            return;
        }
        ZHTelemetry.track("app_update", "stage", "download", "to", offer.versionName);
        downloading = true;
        updateButton.setEnabled(false);
        if (laterButton != null) {
            laterButton.setEnabled(false);
        }
        progress.setVisibility(android.view.View.VISIBLE);
        AppUpdateService.start(this);
        poll.post(this::showProgress);
    }

    private void showProgress() {
        if (isFinishing()) {
            return;
        }
        File ready = AppUpdateService.readyApk(this, offer);
        if (ready != null) {
            downloading = false;
            progress.setProgress(100);
            statusText.setText(R.string.update_installing);
            updateButton.setEnabled(true);
            if (laterButton != null) {
                laterButton.setEnabled(true);
            }
            install(ready);
            return;
        }
        if (!AppUpdateService.sRunning && AppUpdateService.sError != null) {
            downloading = false;
            progress.setVisibility(android.view.View.GONE);
            statusText.setText(getString(R.string.update_failed) + " (" + AppUpdateService.sError + ")");
            updateButton.setEnabled(true);
            if (laterButton != null) {
                laterButton.setEnabled(true);
            }
            return;
        }
        int percent = (int) (AppUpdateService.sDone * 100 / Math.max(1, offer.size));
        progress.setProgress(percent);
        statusText.setText(getString(R.string.update_downloading, percent));
        poll.postDelayed(this::showProgress, 500);
    }

    @Override
    protected void onDestroy() {
        poll.removeCallbacksAndMessages(null);
        super.onDestroy();
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
