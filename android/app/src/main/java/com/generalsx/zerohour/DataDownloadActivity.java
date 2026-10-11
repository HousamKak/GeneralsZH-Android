package com.generalsx.zerohour;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Downloads the game data (DataPack) into the game folder: on a fresh install before the game
 * can start, and when a newer data version is published. Shows the size, refuses to start
 * without the room for it, reports progress, and goes on to the game when done.
 *
 * <p>GeneralsX @feature ZH Commander 11/10/2026 Dressed like a game's loading screen (owner's
 * request): it opens plain and dark, then fetches the game's own artwork from the site (the hero
 * poster and the screenshots, cached after the first time) and fades it in behind everything, one
 * picture after another with a slow zoom. In front: the percentage, a segmented bar with a moving
 * shine, the speed and time left, and tips that change every few seconds.
 */
public class DataDownloadActivity extends Activity {

    static final String EXTRA_FROM_SUPPORT = "com.housamkak.zhcommander.DATA_FROM_SUPPORT";

    /** "Later" on a data update holds until the app is next started. */
    static boolean sLaterThisProcess;

    // Room kept free beyond the download itself, so the game can still write saves and logs.
    private static final long HEADROOM = 300L * 1024 * 1024;

    // The artwork behind the screen, in the order shown (site/public).
    private static final String[] BACKGROUNDS = {
        "/hero/hero_poster.jpg", "/shots/zh_base_wide.jpg", "/shots/zh_army_moves.jpg",
        "/shots/zh_missile_strike.jpg", "/shots/zh_palace_hit.jpg",
    };
    private static final long BACKGROUND_MS = 9000;
    private static final long TIP_MS = 6000;

    private boolean fromSupport;
    private boolean haveData;
    private TextView bodyText;
    private TextView statusText;
    private TextView percentText;
    private TextView tipText;
    private GameBar progress;
    private MaterialButton downloadButton;
    private MaterialButton laterButton;
    private DataPack.Manifest manifest;
    private List<DataPack.Entry> todo;
    private boolean busy;

    private final ImageView[] backgrounds = new ImageView[2];
    private final Bitmap[] loaded = new Bitmap[BACKGROUNDS.length];
    private int shownBackground = -1;
    private int frontImage;
    private int tipIndex;

    // Speed: an average of the bytes per second between the watcher's samples.
    private long lastDone = -1;
    private long lastSampleMs;
    private double bytesPerSecond;

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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 12, 10));
        for (int i = 0; i < 2; ++i) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setAlpha(0f);
            backgrounds[i] = image;
            root.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        // Dark at the bottom, where the text is, lighter at the top so the picture shows.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[] { Color.argb(235, 6, 7, 5), Color.argb(150, 6, 7, 5), Color.argb(70, 6, 7, 5) }));
        root.addView(scrim, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.BOTTOM);
        int pad = dp(24);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = text(content, getString(R.string.setup_window_title).toUpperCase(Locale.ROOT),
            30, Color.WHITE, true);
        title.setLetterSpacing(0.12f);
        TextView subtitle = text(content, getString(R.string.data_card_title).toUpperCase(Locale.ROOT),
            13, Color.rgb(255, 176, 92), true);
        subtitle.setLetterSpacing(0.2f);
        bodyText = text(content, getString(R.string.data_checking), 14, Color.argb(220, 255, 255, 255), false);
        ((LinearLayout.LayoutParams) bodyText.getLayoutParams()).topMargin = dp(10);

        percentText = text(content, "", 46, Color.WHITE, true);
        percentText.setVisibility(View.GONE);
        ((LinearLayout.LayoutParams) percentText.getLayoutParams()).topMargin = dp(14);

        progress = new GameBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(20));
        barParams.topMargin = dp(6);
        content.addView(progress, barParams);

        statusText = text(content, "", 13, Color.argb(210, 255, 255, 255), false);
        ((LinearLayout.LayoutParams) statusText.getLayoutParams()).topMargin = dp(8);
        tipText = text(content, "", 13, Color.rgb(255, 197, 107), false);
        tipText.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        ((LinearLayout.LayoutParams) tipText.getLayoutParams()).topMargin = dp(10);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(14);
        content.addView(buttons, buttonsParams);
        downloadButton = UiKit.button(buttons, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_download,
            getString(R.string.data_button_download), this::startDownload);
        downloadButton.setEnabled(false);
        // Without any game data there is nothing to go on to, so no Later on a first install.
        if (haveData || fromSupport) {
            laterButton = UiKit.button(buttons, UiKit.BTN_TONAL, R.drawable.ic_gzh_play,
                getString(fromSupport ? R.string.update_close : R.string.update_later), this::onLater);
        }

        setContentView(root);
        InsetUtil.applySafeInsets(content);
        loadBackgrounds();
        if (DataDownloadService.sRunning) {
            showDownloading();  // already going in the background: just show it
        } else {
            checkManifest();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics()));
    }

    private TextView text(LinearLayout parent, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setShadowLayer(dp(4), 0, dp(1), Color.argb(200, 0, 0, 0));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        parent.addView(view, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return view;
    }

    // ---- The artwork behind the screen --------------------------------------------------------

    private File backgroundCache(int index) {
        File dir = new File(getCacheDir(), "download_backgrounds");
        String path = BACKGROUNDS[index];
        return new File(dir, path.substring(path.lastIndexOf('/') + 1));
    }

    /** Fetches each picture once (then from the cache), showing the first as soon as it is in. */
    private void loadBackgrounds() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            for (int i = 0; i < BACKGROUNDS.length; ++i) {
                File file = backgroundCache(i);
                if (!file.isFile()) {
                    download(DataPack.SITE + BACKGROUNDS[i], file);
                }
                Bitmap bitmap = file.isFile() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
                if (bitmap == null) {
                    continue;
                }
                final int index = i;
                runOnUiThread(() -> {
                    loaded[index] = bitmap;
                    if (shownBackground < 0) {
                        showBackground(index);
                        handler.postDelayed(backgroundCycler, BACKGROUND_MS);
                    }
                });
            }
        }, "GXDataBackground").start();
    }

    private static void download(String url, File dest) {
        HttpURLConnection conn = null;
        File part = new File(dest.getPath() + ".part");
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) {
                return;
            }
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            part.renameTo(dest);
        } catch (Exception e) {
            part.delete();  // a picture is decoration: no picture is fine
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void showBackground(int index) {
        ImageView incoming = backgrounds[1 - frontImage];
        ImageView outgoing = backgrounds[frontImage];
        incoming.setImageBitmap(loaded[index]);
        incoming.setScaleX(1.0f);
        incoming.setScaleY(1.0f);
        incoming.animate().alpha(1f).setDuration(1200).start();
        android.animation.ObjectAnimator zoom = android.animation.ObjectAnimator.ofPropertyValuesHolder(incoming,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.12f));
        zoom.setDuration(BACKGROUND_MS + 1500);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();
        // The two pictures stay under the scrim and the text; only their opacity changes.
        outgoing.animate().alpha(0f).setDuration(1200).start();
        frontImage = 1 - frontImage;
        shownBackground = index;
    }

    private final Runnable backgroundCycler = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            for (int step = 1; step <= BACKGROUNDS.length; ++step) {
                int next = (shownBackground + step) % BACKGROUNDS.length;
                if (loaded[next] != null) {
                    if (next != shownBackground) {
                        showBackground(next);
                    }
                    break;
                }
            }
            handler.postDelayed(this, BACKGROUND_MS);
        }
    };

    private final Runnable tipCycler = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            String[] tips = getResources().getStringArray(R.array.data_tips);
            if (tips.length > 0) {
                final String tip = tips[tipIndex++ % tips.length];
                tipText.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    tipText.setText(tip);
                    tipText.animate().alpha(1f).setDuration(250).start();
                }).start();
            }
            handler.postDelayed(this, TIP_MS);
        }
    };

    // ---- The download ----------------------------------------------------------------------

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
        downloadButton.setVisibility(View.GONE);
        if (laterButton != null) {
            laterButton.setVisibility(View.GONE);
        }
        progress.setVisibility(View.VISIBLE);
        percentText.setVisibility(View.VISIBLE);
        bodyText.setText(R.string.data_background_note);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tipCycler);
        handler.post(tipCycler);
        handler.post(watcher);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** "812 / 2134 MB  ·  6.2 MB/s  ·  3 min left", with the speed averaged over a few samples. */
    private void showProgress(long done, long total) {
        if (total <= 0) {
            return;
        }
        progress.setIndeterminate(false);
        progress.setProgress((int) (done * 1000 / total));
        percentText.setText(String.format(Locale.ROOT, "%d%%", (int) (done * 100 / total)));
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastDone >= 0 && now > lastSampleMs && done >= lastDone) {
            double sample = (done - lastDone) * 1000.0 / (now - lastSampleMs);
            bytesPerSecond = bytesPerSecond <= 0 ? sample : bytesPerSecond * 0.8 + sample * 0.2;
        }
        lastDone = done;
        lastSampleMs = now;
        String speed = String.format(Locale.ROOT, "%.1f", bytesPerSecond / (1 << 20));
        String left;
        if (bytesPerSecond > 1024) {
            long seconds = (long) ((total - done) / bytesPerSecond);
            left = seconds >= 90 ? getString(R.string.data_eta_min, (seconds + 30) / 60)
                                 : getString(R.string.data_eta_sec, Math.max(1, seconds));
        } else {
            left = "…";
        }
        statusText.setText(getString(R.string.data_stats, done >> 20, total >> 20, speed, left));
    }

    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            showProgress(DataDownloadService.sDone, DataDownloadService.sTotal);
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
                        laterButton.setVisibility(View.VISIBLE);
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
            downloadButton.setVisibility(View.VISIBLE);
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
        // GeneralsX @feature ZH Commander 11/10/2026 The GeneralsOnline data cross-play needs, fetched
        // or updated here with the game data, so a player never has to (ZeroSetup). A failure does
        // not hold the game back: it starts anyway, and the next start tries again.
        if (ZeroSetup.patchNeeded(this) && !sPatchTriedThisProcess) {
            sPatchTriedThisProcess = true;
            busy = true;
            downloadButton.setVisibility(View.GONE);
            if (laterButton != null) {
                laterButton.setVisibility(View.GONE);
            }
            bodyText.setText(R.string.data_crossplay_body);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            percentText.setVisibility(View.VISIBLE);
            percentText.setText("");
            lastDone = -1;
            bytesPerSecond = 0;
            handler.removeCallbacks(tipCycler);
            handler.post(tipCycler);
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                DataPackInstaller.Result result = DataPackInstaller.install(app, new DataPackInstaller.Progress() {
                    @Override public void onChecking() {}

                    @Override public void onDownloading(long done, long total) {
                        runOnUiThread(() -> showProgress(done, total));
                    }

                    @Override public void onInstalling() {
                        runOnUiThread(() -> progress.setIndeterminate(true));
                    }
                });
                ZeroSetup.ensure(app);
                runOnUiThread(() -> {
                    busy = false;
                    if (!result.ok) {
                        android.widget.Toast.makeText(this, getString(R.string.online_crossplay_failed, result.error),
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                    if (!isFinishing()) {
                        goToGame();
                    }
                });
            }, "GXCrossPlayData").start();
            return;
        }
        startActivity(new Intent(this, GeneralsZHActivity.class));
        finish();
    }

    private static boolean sPatchTriedThisProcess;

    /**
     * A loading bar in the game's colours: a dark track with a thin frame, the filled part in
     * segments from deep orange to amber, and a shine that sweeps across it. Indeterminate, a lit
     * block travels along the track instead.
     */
    static final class GameBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gap = new Paint();
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ValueAnimator sweep = ValueAnimator.ofFloat(0f, 1f);
        private float fraction;
        private float phase;
        private boolean indeterminate;

        GameBar(Context context) {
            super(context);
            track.setColor(Color.argb(150, 0, 0, 0));
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.2f);
            frame.setColor(Color.argb(140, 255, 196, 120));
            gap.setColor(Color.argb(140, 10, 8, 4));
            sweep.setDuration(1600);
            sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.setInterpolator(new LinearInterpolator());
            sweep.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        void setProgress(int permille) {
            fraction = Math.max(0f, Math.min(1f, permille / 1000f));
            invalidate();
        }

        void setIndeterminate(boolean on) {
            indeterminate = on;
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            sweep.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            sweep.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float radius = h / 4f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, radius, radius, track);

            float left = 0, right = w * fraction;
            if (indeterminate) {
                float block = w * 0.28f;
                left = (w + block) * phase - block;
                right = left + block;
            }
            left = Math.max(0, left);
            right = Math.min(w, right);
            if (right > left) {
                fill.setShader(new LinearGradient(0, 0, w, 0, Color.rgb(170, 52, 18), Color.rgb(255, 176, 72),
                    Shader.TileMode.CLAMP));
                rect.set(left, 0, right, h);
                canvas.drawRoundRect(rect, radius, radius, fill);
                // Segments, like an armour bar.
                float segment = w / 32f;
                float gapWidth = Math.max(2f, segment * 0.14f);
                for (float x = segment; x < right; x += segment) {
                    if (x > left) {
                        canvas.drawRect(x - gapWidth / 2, 0, x + gapWidth / 2, h, gap);
                    }
                }
                // The shine, sweeping across the filled part.
                if (!indeterminate) {
                    float band = Math.max(h * 2.5f, (right - left) * 0.18f);
                    float cx = left + (right - left + band) * phase - band;
                    shine.setShader(new LinearGradient(cx, 0, cx + band, 0,
                        new int[] { Color.argb(0, 255, 255, 255), Color.argb(110, 255, 255, 255),
                                    Color.argb(0, 255, 255, 255) },
                        null, Shader.TileMode.CLAMP));
                    canvas.save();
                    canvas.clipRect(left, 0, right, h);
                    canvas.drawRect(cx, 0, cx + band, h, shine);
                    canvas.restore();
                }
            }
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, radius, radius, frame);
        }
    }
}
