package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.List;

/**
 * Downloads the game data (DataPack) into the game folder: on a fresh install before the game
 * can start, and when a newer data version is published. Shows the size, refuses to start
 * without the room for it, reports progress, and goes on to the game when done.
 */
public class DataDownloadActivity extends Activity {

    static final String EXTRA_FROM_SUPPORT = "com.housamkak.zhcommander.DATA_FROM_SUPPORT";

    /** "Later" on a data update holds until the app is next started. */
    static boolean sLaterThisProcess;

    // Room kept free beyond the download itself, so the game can still write saves and logs.
    private static final long HEADROOM = 300L * 1024 * 1024;

    private boolean fromSupport;
    private boolean haveData;
    private TextView bodyText;
    private TextView statusText;
    private ProgressBar progress;
    private MaterialButton downloadButton;
    private MaterialButton laterButton;
    private DataPack.Manifest manifest;
    private List<DataPack.Entry> todo;
    private boolean busy;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fromSupport = getIntent().getBooleanExtra(EXTRA_FROM_SUPPORT, false);
        File dir = DataPack.gameDataDir(this);
        // Half a download is not game data, even if INIZH.big is already in the folder.
        haveData = dir != null && SetupActivity.isValidGameFolder(dir) && !DataPack.downloadIncomplete(this);
        setTitle(R.string.data_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.data_title), 0, null, null);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter = UiKit.dim(this, R.dimen.gzh_gutter);
        page.setPadding(gutter, 0, gutter, 0);
        root.addView(page, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = UiKit.card(page);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_download, getString(R.string.data_card_title), false);
        bodyText = UiKit.helpText(content, getString(R.string.data_checking));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(android.view.View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusText = UiKit.helpText(content, "");
        downloadButton = UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_download,
            getString(R.string.data_button_download), this::startDownload);
        downloadButton.setEnabled(false);
        // Without any game data there is nothing to go on to, so no Later on a first install.
        if (haveData || fromSupport) {
            laterButton = UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_play,
                getString(fromSupport ? R.string.update_close : R.string.update_later), this::onLater);
        }

        setContentView(root);
        InsetUtil.applySafeInsets(root);
        if (DataDownloadService.sRunning) {
            showDownloading();  // already going in the background: just show it
        } else {
            checkManifest();
        }
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            moveTaskToBack(true);  // the download carries on in the background service
        } else if (laterButton != null) {
            onLater();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void checkManifest() {
        busy = true;
        new Thread(() -> {
            DataPack.Manifest m = null;
            String error = null;
            try {
                m = DataPack.fetchManifest(getApplicationContext());
            } catch (java.io.IOException e) {
                error = e.getMessage();
            }
            final DataPack.Manifest result = m;
            final boolean failed = error != null;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) {
                    return;
                }
                if (failed) {
                    bodyText.setText(R.string.data_offline);
                    downloadButton.setText(R.string.data_button_retry);
                    downloadButton.setEnabled(true);
                    downloadButton.setOnClickListener(v -> recreate());
                    return;
                }
                if (result == null) {
                    bodyText.setText(R.string.data_none_published);
                    return;
                }
                manifest = result;
                todo = DataPack.missing(this, result);
                long bytes = DataPack.bytesOf(todo);
                // An interrupted download continues by itself: what finished is kept, the
                // unfinished file resumes where it stopped.
                if (DataPack.downloadIncomplete(this)) {
                    startDownload();
                    return;
                }
                if (todo.isEmpty()) {
                    // Nothing new to fetch, but a release can still withdraw files, and the new
                    // version must be recorded or it would be offered again on every start.
                    if (DataPack.outOfDate(this, result)) {
                        try {
                            DataPack.commit(this, result);
                        } catch (java.io.IOException e) {
                            statusText.setText(getString(R.string.data_failed));
                        }
                    }
                    bodyText.setText(getString(R.string.data_up_to_date, result.version));
                    if (!fromSupport) {
                        goToGame();
                    }
                    return;
                }
                bodyText.setText(getString(haveData ? R.string.data_update_body : R.string.data_first_body,
                    result.version, Math.max(1, bytes >> 20)));
                File dir = DataPack.gameDataDir(this);
                long free = dir != null && dir.getParentFile() != null ? dir.getParentFile().getUsableSpace() : 0;
                if (free < bytes + HEADROOM) {
                    statusText.setText(getString(R.string.data_no_space,
                        (bytes + HEADROOM - free + (1 << 20) - 1) >> 20));
                }
                downloadButton.setEnabled(true);
            });
        }, "GXDataCheck").start();
    }

    // The download runs in DataDownloadService, so it outlives this screen; the screen only
    // shows its progress.
    private void startDownload() {
        // Android 13+ asks before an app may post notifications; the download runs either way,
        // the permission only decides whether its progress shows in the notification shade.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
        DataDownloadService.start(getApplicationContext());
        showDownloading();
    }

    private void showDownloading() {
        busy = true;
        downloadButton.setEnabled(false);
        if (laterButton != null) {
            laterButton.setEnabled(false);
        }
        progress.setVisibility(android.view.View.VISIBLE);
        statusText.setText(R.string.data_background_note);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.post(watcher);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            long done = DataDownloadService.sDone;
            long total = DataDownloadService.sTotal;
            if (total > 0) {
                progress.setProgress((int) (done * 1000 / total));
                bodyText.setText(getString(R.string.data_progress, done >> 20, total >> 20));
            }
            if (DataDownloadService.sRunning) {
                handler.postDelayed(this, 500);
                return;
            }
            busy = false;
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (DataDownloadService.sFinished) {
                if (fromSupport) {
                    statusText.setText(getString(R.string.data_up_to_date, DataDownloadService.sVersion));
                    if (laterButton != null) {
                        laterButton.setEnabled(true);
                    }
                } else {
                    goToGame();
                }
                return;
            }
            // Every retry has failed (no connection for a while): what finished stays, and the
            // next tap -- or the next start of the app -- continues from there.
            statusText.setText(getString(R.string.data_failed));
            downloadButton.setEnabled(true);
            downloadButton.setOnClickListener(v -> startDownload());
        }
    };

    private void onLater() {
        sLaterThisProcess = true;
        if (fromSupport) {
            finish();
        } else {
            goToGame();
        }
    }

    private void goToGame() {
        startActivity(new Intent(this, GeneralsZHActivity.class));
        finish();
    }
}
