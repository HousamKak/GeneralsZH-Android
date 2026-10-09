/*
 * App Monitor Android SDK (android/0.1.0).
 *
 * Single, dependency-free source file: only android.* and java.* (org.json for parsing the config
 * response). Java 8 language level. Implements the wire contract in docs/ingest-api.md.
 *
 * Threading: every disk and network operation runs on one background thread. Public methods are
 * safe to call from any thread and never block the caller on I/O. The only synchronous file write
 * is the crash file written from the uncaught exception handler, while the process is dying.
 *
 * Privacy: sends an anonymous install id (random UUID, or a hash the app supplies), the app version,
 * OS release, device model and locale. Never IP addresses (the server drops them), never hardware ids.
 */
package com.housamkak.appmonitor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public final class AppMonitor {

    public static final String SDK_VERSION = "0.1.0";
    static final String TAG = "AppMonitor";
    static final Charset UTF8 = Charset.forName("UTF-8");

    private AppMonitor() {
    }

    // ------------------------------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------------------------------

    /** Configuration for {@link #init(Context, Options)}. Public fields, plus fluent setters. */
    public static final class Options {
        /** Public ingest key of the app (sent as X-App-Key). Required. */
        public String appKey;
        /** Base URL, e.g. https://ingest.housamkak.com (no trailing /i/v1). */
        public String endpoint = "https://ingest.housamkak.com";
        /** Human version, e.g. "1.4.2". Defaults to the package versionName. */
        public String version;
        /** Build number. Defaults to the package versionCode. */
        public Long build;
        /** Optional app-supplied install id (an opaque hash, 8 to 128 chars). Default: random UUID. */
        public String installId;
        /** Log SDK activity to logcat with tag "AppMonitor". */
        public boolean debug;
        /** Install the NDK signal handler (needs libapp_monitor_ndk.so in the APK). */
        public boolean enableNative;
        /** Install the uncaught Java exception handler. Default true. */
        public boolean captureUncaught = true;
        /** Extra package prefixes whose frames count as in-app (the app package is always one). */
        public String[] inAppPackages;

        public Options() {
        }

        public Options(String appKey, String endpoint, String version) {
            this.appKey = appKey;
            if (endpoint != null) this.endpoint = endpoint;
            this.version = version;
        }

        public Options appKey(String v) { this.appKey = v; return this; }
        public Options endpoint(String v) { this.endpoint = v; return this; }
        public Options version(String v) { this.version = v; return this; }
        public Options build(long v) { this.build = v; return this; }
        public Options installId(String v) { this.installId = v; return this; }
        public Options debug(boolean v) { this.debug = v; return this; }
        public Options enableNative(boolean v) { this.enableNative = v; return this; }
        public Options captureUncaught(boolean v) { this.captureUncaught = v; return this; }
        public Options inAppPackages(String... v) { this.inAppPackages = v; return this; }
    }

    /** Result of {@link #checkConfig(Callback)}. */
    public static final class Config {
        public final String minVersion;
        public final String latestVersion;
        public final boolean enabled;
        public final boolean updateRequired;

        Config(String minVersion, String latestVersion, boolean enabled, boolean updateRequired) {
            this.minVersion = minVersion;
            this.latestVersion = latestVersion;
            this.enabled = enabled;
            this.updateRequired = updateRequired;
        }

        @Override
        public String toString() {
            return "Config{min_version=" + minVersion + ", latest_version=" + latestVersion
                    + ", enabled=" + enabled + ", update_required=" + updateRequired + "}";
        }
    }

    /** Called on the main thread. Exactly one of config / error is non-null. */
    public interface Callback {
        void onResult(Config config, Exception error);
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private static final Object LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean enabled = true;
    /** Set by a 403 or a config with enabled:false. Stops sending until the next process start. */
    private static volatile boolean killed;
    private static volatile Boolean pendingEnabled;
    private static volatile String pendingUserId;

    private static Context appContext;
    private static Options options;
    private static String baseUrl;
    private static String[] inAppPrefixes = new String[0];
    private static volatile File dir;
    private static volatile String installId;
    private static String appVersion;
    private static Long appBuild;
    private static String osName;
    private static String model;
    private static String locale;
    private static volatile String userId;
    private static ScheduledExecutorService exec;
    private static Handler mainHandler;
    private static final Core.Breadcrumbs crumbs = new Core.Breadcrumbs(Core.MAX_CRUMBS);

    // Session state, mutated on the main thread (lifecycle callbacks).
    private static volatile String sessionId;
    private static long sessionStartMs;
    private static long backgroundAtMs;
    private static int startedActivities;
    private static boolean inBackground = true;
    private static volatile String lastScreen;

    // Queue state, touched only on the executor thread.
    private static final List<Core.QueueItem> queue = new ArrayList<Core.QueueItem>();
    private static long backoffMs;
    private static long nextAttemptAtMs;
    private static volatile boolean nativeLoaded;
    private static boolean crashFilesPending;
    private static boolean started;

    // ------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------

    /** Initialise once, ideally from Application.onCreate. Further calls are ignored. */
    public static void init(Context context, Options opts) {
        if (context == null || opts == null) throw new IllegalArgumentException("context and options are required");
        if (opts.appKey == null || opts.appKey.length() == 0) throw new IllegalArgumentException("Options.appKey is required");
        synchronized (LOCK) {
            if (initialized) {
                log("init called twice, ignored");
                return;
            }
            appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            options = opts;
            String ep = opts.endpoint == null ? "https://ingest.housamkak.com" : opts.endpoint.trim();
            while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
            baseUrl = ep;
            mainHandler = new Handler(Looper.getMainLooper());

            List<String> prefixes = new ArrayList<String>();
            prefixes.add(appContext.getPackageName());
            if (opts.inAppPackages != null) Collections.addAll(prefixes, opts.inAppPackages);
            inAppPrefixes = prefixes.toArray(new String[0]);

            osName = Core.truncate("Android " + Build.VERSION.RELEASE, 64);
            model = Core.truncate(Build.MODEL, 64);
            locale = Core.truncate(Locale.getDefault().toLanguageTag(), 16);
            userId = pendingUserId;

            exec = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "AppMonitor");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
            initialized = true;
        }

        if (opts.captureUncaught) installUncaughtHandler();
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new Lifecycle());
        } else {
            log("context is not an Application, sessions are not tracked automatically");
        }

        // All disk work (prefs, queue, crash files) happens on the executor.
        submit(new Runnable() {
            @Override
            public void run() {
                setup();
            }
        });
        exec.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                flushNow();
            }
        }, 30, 30, TimeUnit.SECONDS);

        track("app_open");
    }

    public static void track(String name) {
        track(name, null, null);
    }

    public static void track(String name, Map<String, ?> props) {
        track(name, props, null);
    }

    public static void track(String name, double value) {
        track(name, null, value);
    }

    /** Record a custom event. Props values: String (max 256), Number or Boolean; max 32 keys. */
    public static void track(String name, Map<String, ?> props, Double value) {
        enqueueEvent(name, props, value, null);
    }

    /** Record a screen view (event "screen" with props.name) and a navigation breadcrumb. */
    public static void screen(String name) {
        if (name == null) return;
        lastScreen = name;
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("name", name);
        addBreadcrumb("navigation", name, null);
        enqueueEvent("screen", p, null, null);
    }

    public static void captureError(Throwable error) {
        captureError(error, null);
    }

    /** Report a handled exception (kind "error", handled true) with optional free-form context. */
    public static void captureError(Throwable error, Map<String, ?> context) {
        if (!initialized || !enabled || error == null) return;
        try {
            Map<String, Object> ctx = new LinkedHashMap<String, Object>();
            if (lastScreen != null) ctx.put("screen", lastScreen);
            ctx.put("thread", Thread.currentThread().getName());
            if (context != null) ctx.putAll(context);
            String json = Core.errorJson(error, "error", true, System.currentTimeMillis(), sessionId,
                    crumbs.toJson(), ctx, inAppPrefixes);
            final Core.QueueItem item = new Core.QueueItem(Core.KIND_ERROR, System.currentTimeMillis(),
                    appVersion(), appBuild, json);
            submit(new Runnable() {
                @Override
                public void run() {
                    addItem(item);
                }
            });
        } catch (Throwable t) {
            log("captureError failed: " + t);
        }
    }

    public static void addBreadcrumb(String category, String message) {
        addBreadcrumb(category, message, null);
    }

    /** Append to the ring buffer of the last 50 breadcrumbs, attached to every error. */
    public static void addBreadcrumb(String category, String message, Map<String, ?> data) {
        if (!enabled) return;
        crumbs.add(System.currentTimeMillis(), category, message, data);
    }

    /** Attach an opaque user hash (never an email or a name). Pass null to clear. */
    public static void setUserId(String hash) {
        String v = hash == null ? null : Core.truncate(hash, 128);
        pendingUserId = v;
        userId = v;
        updateNativeContext();
    }

    /** Persisted opt-out. false clears the queue and stops all collection and sending. */
    public static void setEnabled(final boolean value) {
        synchronized (LOCK) {
            if (!initialized) {
                pendingEnabled = value;
                enabled = value;
                return;
            }
        }
        enabled = value;
        if (!value) crumbs.clear();
        submit(new Runnable() {
            @Override
            public void run() {
                applyEnabled(value);
            }
        });
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Send queued items now (respects an active back off). Asynchronous. */
    public static void flush() {
        submit(new Runnable() {
            @Override
            public void run() {
                flushNow();
            }
        });
    }

    /** Fetch the remote config. The callback runs on the main thread. */
    public static void checkConfig(final Callback callback) {
        if (!initialized) {
            if (callback != null) callback.onResult(null, new IllegalStateException("AppMonitor.init was not called"));
            return;
        }
        submit(new Runnable() {
            @Override
            public void run() {
                Config cfg = null;
                Exception err = null;
                try {
                    cfg = fetchConfig();
                    if (!cfg.enabled) {
                        killed = true;
                        log("kill switch active (config enabled=false), sending stopped");
                    }
                } catch (Exception e) {
                    err = e;
                }
                if (callback != null) {
                    final Config c = cfg;
                    final Exception e = err;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(c, e);
                        }
                    });
                }
            }
        });
    }

    /** Current session id, or null before the first foreground. */
    public static String getSessionId() {
        return sessionId;
    }

    /** The install id in use (available shortly after init). */
    public static String getInstallId() {
        return installId;
    }

    // ------------------------------------------------------------------------------------------
    // Internals: setup
    // ------------------------------------------------------------------------------------------

    private static void setup() {
        File d = new File(appContext.getFilesDir(), "app_monitor");
        if (!d.isDirectory() && !d.mkdirs()) log("cannot create " + d);
        dir = d;

        SharedPreferences prefs = prefs();
        if (pendingEnabled != null) {
            prefs.edit().putBoolean("enabled", pendingEnabled).apply();
            pendingEnabled = null;
        }
        boolean en = prefs.getBoolean("enabled", true);
        enabled = en;

        String supplied = options.installId;
        if (supplied != null && supplied.length() >= 8) {
            installId = Core.truncate(supplied, 128);
        } else {
            if (supplied != null) log("Options.installId shorter than 8 chars, using a random id");
            String id = prefs.getString("install_id", null);
            if (id == null) {
                id = UUID.randomUUID().toString();
                prefs.edit().putString("install_id", id).apply();
            }
            installId = id;
        }

        resolveAppVersion();

        if (!en) {
            clearAllData();
            return;
        }

        // Native crash file from the previous run must be moved before the handler is installed.
        File nativeFile = new File(d, "native.crash");
        if (nativeFile.exists()) convertNativeCrash(nativeFile);
        if (options.enableNative) installNative(nativeFile);

        loadQueue();
        started = true;
        sendPendingCrashes();
        flushNow();
    }

    @SuppressWarnings("deprecation")
    private static void resolveAppVersion() {
        String v = options.version;
        Long b = options.build;
        if (v == null || b == null) {
            try {
                PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
                if (v == null) v = pi.versionName;
                if (b == null) b = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : (long) pi.versionCode;
            } catch (Exception e) {
                log("cannot read package info: " + e);
            }
        }
        appVersion = Core.truncate(v == null || v.length() == 0 ? "0" : v, 64);
        appBuild = b;
    }

    private static String appVersion() {
        if (appVersion != null) return appVersion;
        String v = options.version;
        return Core.truncate(v == null || v.length() == 0 ? "0" : v, 64);
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences("app_monitor", Context.MODE_PRIVATE);
    }

    private static void applyEnabled(boolean value) {
        prefs().edit().putBoolean("enabled", value).apply();
        if (!value) {
            clearAllData();
            log("disabled, queue cleared");
        } else {
            if (!started) setup();
            log("enabled");
        }
    }

    private static void clearAllData() {
        queue.clear();
        File d = dir;
        if (d == null) return;
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) log("cannot delete " + f);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals: events and queue
    // ------------------------------------------------------------------------------------------

    private static void enqueueEvent(String name, Map<String, ?> props, Double value, Long durationMs) {
        if (!initialized || !enabled) return;
        String clean = Core.sanitizeEventName(name);
        if (clean == null) {
            log("invalid event name dropped: " + name);
            return;
        }
        long now = System.currentTimeMillis();
        final Core.QueueItem item = new Core.QueueItem(Core.KIND_EVENT, now, appVersion(), appBuild,
                Core.eventJson(clean, now, sessionId, props, value, durationMs));
        submit(new Runnable() {
            @Override
            public void run() {
                addItem(item);
            }
        });
    }

    private static void submit(Runnable r) {
        final Runnable task = r;
        ScheduledExecutorService e = exec;
        if (e == null) return;
        try {
            e.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        log("internal error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("executor rejected task: " + t);
        }
    }

    private static File queueFile() {
        return new File(dir, "queue.jsonl");
    }

    private static void loadQueue() {
        queue.clear();
        File f = queueFile();
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                Core.QueueItem it = Core.QueueItem.decode(line);
                if (it != null) queue.add(it);
            }
        } catch (IOException e) {
            log("cannot read queue: " + e);
        } finally {
            closeQuietly(r);
        }
        Core.prune(queue, System.currentTimeMillis());
        saveQueue();
        log("queue loaded: " + queue.size() + " items");
    }

    private static void addItem(Core.QueueItem recorded) {
        if (!enabled || dir == null) return;
        // Runs after setup(), so the resolved version and build are known here even for events
        // recorded before setup finished (app_open).
        Core.QueueItem item = new Core.QueueItem(recorded.kind, recorded.ts, appVersion(), appBuild, recorded.json);
        queue.add(item);
        if (queue.size() > Core.MAX_QUEUE) {
            Core.prune(queue, System.currentTimeMillis());
            saveQueue();
        } else {
            appendLine(item.encode());
        }
    }

    private static void appendLine(String line) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(queueFile(), true), UTF8);
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            log("cannot append to queue: " + e);
        } finally {
            closeQuietly(w);
        }
    }

    private static void saveQueue() {
        if (dir == null) return;
        StringBuilder sb = new StringBuilder();
        for (Core.QueueItem it : queue) sb.append(it.encode()).append('\n');
        writeAtomically(queueFile(), sb.toString());
    }

    private static void writeAtomically(File target, String content) {
        File tmp = new File(target.getPath() + ".tmp");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(content.getBytes(UTF8));
            os.close();
            os = null;
            if (!tmp.renameTo(target)) {
                if (!target.delete() || !tmp.renameTo(target)) log("cannot replace " + target);
            }
        } catch (IOException e) {
            log("cannot write " + target + ": " + e);
        } finally {
            closeQuietly(os);
        }
    }

    /** Runs on the executor. Sends as many batches as allowed right now. */
    private static void flushNow() {
        try {
            if (!enabled || killed || dir == null) return;
            long now = System.currentTimeMillis();
            if (now < nextAttemptAtMs) return;
            if (crashFilesPending) sendPendingCrashes();
            if (queue.isEmpty() || killed || System.currentTimeMillis() < nextAttemptAtMs) return;
            if (Core.prune(queue, now) > 0) saveQueue();
            int limitOverride = Integer.MAX_VALUE;
            int guard = 0;
            while (!queue.isEmpty() && enabled && !killed && guard++ < 50) {
                List<Core.QueueItem> batch = Core.selectBatch(queue, limitOverride);
                if (batch.isEmpty()) break;
                Core.QueueItem first = batch.get(0);
                String path = first.kind == Core.KIND_ERROR ? "/i/v1/errors" : "/i/v1/events";
                String body = Core.buildBody(envelope(first.version, first.build), batch);
                HttpResult res = post(path, body);
                int action = Core.classify(res.code);
                log("POST " + path + " (" + batch.size() + " items) -> " + res.code);
                if (action == Core.ACTION_REMOVE || action == Core.ACTION_DROP) {
                    removeAll(batch);
                    resetBackoff();
                } else if (action == Core.ACTION_KILL) {
                    removeAll(batch);
                    killed = true;
                    log("app disabled by the server (403), sending stopped until next start");
                } else if (action == Core.ACTION_SPLIT) {
                    if (batch.size() <= 1) {
                        removeAll(batch);
                        log("single item too large, dropped");
                    } else {
                        limitOverride = Math.max(1, batch.size() / 2);
                    }
                } else {
                    scheduleBackoff(res.retryAfterSec);
                    break;
                }
            }
        } catch (Throwable t) {
            log("flush failed: " + t);
        }
    }

    private static void removeAll(List<Core.QueueItem> batch) {
        queue.removeAll(batch);
        saveQueue();
    }

    private static void resetBackoff() {
        backoffMs = 0;
        nextAttemptAtMs = 0;
    }

    private static void scheduleBackoff(long retryAfterSec) {
        backoffMs = Core.nextBackoff(backoffMs);
        long delay = Core.retryDelay(backoffMs, retryAfterSec);
        nextAttemptAtMs = System.currentTimeMillis() + delay;
        log("backing off " + delay + " ms");
        // The 30 s tick picks it up; schedule an extra attempt for short delays.
        if (delay < 30000 && exec != null) {
            try {
                exec.schedule(new Runnable() {
                    @Override
                    public void run() {
                        flushNow();
                    }
                }, delay + 50, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // executor shut down
            }
        }
    }

    private static String envelope(String version, Long build) {
        return Core.envelopeOpen(installId, version, build, osName, model, locale, userId, "android/" + SDK_VERSION);
    }

    // ------------------------------------------------------------------------------------------
    // Internals: HTTP
    // ------------------------------------------------------------------------------------------

    static final class HttpResult {
        final int code; // -1 = network error
        final long retryAfterSec;
        final String body;

        HttpResult(int code, long retryAfterSec, String body) {
            this.code = code;
            this.retryAfterSec = retryAfterSec;
            this.body = body;
        }
    }

    private static HttpResult post(String path, String json) {
        HttpURLConnection c = null;
        try {
            byte[] gz = Core.gzip(json.getBytes(UTF8));
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Content-Encoding", "gzip");
            c.setRequestProperty("X-App-Key", options.appKey);
            c.setFixedLengthStreamingMode(gz.length);
            OutputStream os = c.getOutputStream();
            os.write(gz);
            os.close();
            int code = c.getResponseCode();
            long retryAfter = Core.parseRetryAfter(c.getHeaderField("Retry-After"));
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) log("server said " + code + ": " + body);
            return new HttpResult(code, retryAfter, body);
        } catch (Exception e) {
            log("network error: " + e);
            return new HttpResult(-1, 0, null);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Config fetchConfig() throws Exception {
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/i/v1/config?version=" + URLEncoder.encode(appVersion(), "UTF-8") + "&platform=android";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("X-App-Key", options.appKey);
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 403) {
                killed = true;
                return new Config(null, null, false, false);
            }
            if (code < 200 || code >= 300) throw new IOException("config request failed: HTTP " + code);
            JSONObject o = new JSONObject(body == null ? "{}" : body);
            String min = o.isNull("min_version") ? null : o.optString("min_version", null);
            String latest = o.isNull("latest_version") ? null : o.optString("latest_version", null);
            boolean en = o.optBoolean("enabled", true);
            boolean upd = o.has("update_required") ? o.optBoolean("update_required", false)
                    : (min != null && Core.compareVersions(appVersion(), min) < 0);
            return new Config(min, latest, en, upd);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readBody(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), UTF8);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals: crashes
    // ------------------------------------------------------------------------------------------

    private static void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    if (enabled) writeCrashFile(thread, error);
                } catch (Throwable ignored) {
                    // never let the monitor hide the original crash
                } finally {
                    if (previous != null) {
                        previous.uncaughtException(thread, error);
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid());
                        System.exit(10);
                    }
                }
            }
        });
    }

    /** Synchronous: the process is about to die. */
    private static void writeCrashFile(Thread thread, Throwable error) throws IOException {
        File d = dir;
        if (d == null) {
            d = new File(appContext.getFilesDir(), "app_monitor");
            if (!d.isDirectory() && !d.mkdirs()) return;
        }
        String iid = installId;
        if (iid == null) {
            SharedPreferences p = prefs();
            iid = options.installId != null && options.installId.length() >= 8
                    ? Core.truncate(options.installId, 128) : p.getString("install_id", null);
            if (iid == null) {
                iid = UUID.randomUUID().toString();
                p.edit().putString("install_id", iid).commit();
            }
            installId = iid;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        ctx.put("thread", thread == null ? "?" : thread.getName());
        if (lastScreen != null) ctx.put("screen", lastScreen);
        String err = Core.errorJson(error, "crash", false, now, sessionId, crumbs.toJson(), ctx, inAppPrefixes);
        String body = Core.crashBody(envelope(appVersion(), appBuild), err);
        File f = new File(d, "crash-" + now + ".json");
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(body.getBytes(UTF8));
            os.getFD().sync();
        } finally {
            os.close();
        }
    }

    private static void installNative(File crashFile) {
        try {
            nativeLoaded = AppMonitorNative.isLoaded() && AppMonitorNative.install(crashFile.getAbsolutePath());
            log(nativeLoaded ? "native crash handler installed" : "native crash handler not available");
            updateNativeContext();
        } catch (Throwable t) {
            nativeLoaded = false;
            log("native crash handler failed: " + t);
        }
    }

    /** Gives the native handler a ready-made JSON envelope so the crash report has version and session. */
    private static void updateNativeContext() {
        if (!nativeLoaded || installId == null) return;
        try {
            String ctx = Core.nativeContext(envelope(appVersion(), appBuild), sessionId);
            if (ctx.length() < 1000) AppMonitorNative.setContext(ctx);
        } catch (Throwable t) {
            log("native context update failed: " + t);
        }
    }

    private static void convertNativeCrash(File nativeFile) {
        try {
            String text = readFile(nativeFile, 256 * 1024);
            String body = Core.nativeCrashBody(text, envelope(appVersion(), appBuild));
            if (body != null) {
                writeAtomically(new File(dir, "ncrash-" + System.currentTimeMillis() + ".json"), body);
            } else {
                log("unreadable native crash file dropped");
            }
        } catch (IOException e) {
            log("cannot read native crash file: " + e);
        }
        if (!nativeFile.delete()) log("cannot delete " + nativeFile);
    }

    private static void sendPendingCrashes() {
        crashFilesPending = true;
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            String n = f.getName();
            boolean isNative = n.startsWith("ncrash-") && n.endsWith(".json");
            boolean isJava = n.startsWith("crash-") && n.endsWith(".json");
            if (!isNative && !isJava) continue;
            if (now - f.lastModified() > Core.MAX_AGE_MS) {
                deleteQuietly(f);
                continue;
            }
            if (killed) return;
            String body;
            try {
                body = readFile(f, 1024 * 1024);
            } catch (IOException e) {
                deleteQuietly(f);
                continue;
            }
            HttpResult res = post(isNative ? "/i/v1/crash" : "/i/v1/errors", body);
            int action = Core.classify(res.code);
            log("crash report " + n + " -> " + res.code);
            if (action == Core.ACTION_RETRY) {
                scheduleBackoff(res.retryAfterSec);
                return;
            }
            deleteQuietly(f);
            if (action == Core.ACTION_KILL) {
                killed = true;
                return;
            }
            resetBackoff();
        }
        crashFilesPending = false;
    }

    private static String readFile(File f, int max) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("file too large: " + f);
            }
            return new String(bo.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals: sessions
    // ------------------------------------------------------------------------------------------

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(Activity activity) {
            startedActivities++;
            addBreadcrumb("lifecycle", activity.getClass().getSimpleName() + " started", null);
            if (startedActivities == 1 && inBackground) {
                inBackground = false;
                onForeground();
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
            if (startedActivities > 0) startedActivities--;
            addBreadcrumb("lifecycle", activity.getClass().getSimpleName() + " stopped", null);
            if (startedActivities == 0 && !activity.isChangingConfigurations() && !inBackground) {
                inBackground = true;
                onBackground();
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    private static void onForeground() {
        long now = System.currentTimeMillis();
        if (sessionId == null || Core.isNewSession(backgroundAtMs, now)) {
            sessionId = UUID.randomUUID().toString();
            sessionStartMs = now;
            enqueueEvent("session_start", null, null, null);
            updateNativeContext();
        }
    }

    private static void onBackground() {
        long now = System.currentTimeMillis();
        backgroundAtMs = now;
        if (sessionId != null) {
            // duration_ms is the session length so far (from session_start until now). If the user
            // returns within 30 s the same session continues and a later session_end carries the
            // longer total.
            enqueueEvent("session_end", null, null, now - sessionStartMs);
        }
        flush();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    static void log(String msg) {
        Options o = options;
        if (o != null && o.debug) Log.d(TAG, msg);
    }

    private static void deleteQuietly(File f) {
        if (!f.delete()) log("cannot delete " + f);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ==========================================================================================
    // Core: pure Java logic (no android.*), unit-testable on a plain JVM.
    // ==========================================================================================

    static final class Core {
        static final int MAX_QUEUE = 1000;
        static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
        static final int MAX_EVENTS_PER_BATCH = 100;
        static final int MAX_ERRORS_PER_BATCH = 20;
        static final long BACKOFF_MIN_MS = 5000;
        static final long BACKOFF_MAX_MS = 60L * 60 * 1000;
        static final long SESSION_TIMEOUT_MS = 30000;
        static final int MAX_FRAMES = 100;
        static final int MAX_CRUMBS = 50;
        static final int MAX_RAW_STACK = 32 * 1024 - 64;
        static final int MAX_CONTEXT = 16 * 1024;

        static final char KIND_EVENT = 'e';
        static final char KIND_ERROR = 'x';

        static final int ACTION_REMOVE = 0;
        static final int ACTION_DROP = 1;
        static final int ACTION_KILL = 2;
        static final int ACTION_SPLIT = 3;
        static final int ACTION_RETRY = 4;

        private Core() {
        }

        // ---------------- JSON writing ----------------

        static String quote(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 2);
            quote(sb, s);
            return sb.toString();
        }

        static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        static String number(Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
                return Double.toString(d);
            }
            if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return n.toString();
            }
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            return n.toString();
        }

        /** Generic writer for context and breadcrumb data (nested maps, lists, arrays allowed). */
        static void value(StringBuilder sb, Object v, int depth) {
            if (v == null || depth > 8) {
                sb.append("null");
            } else if (v instanceof String) {
                quote(sb, truncate((String) v, 2048));
            } else if (v instanceof Boolean) {
                sb.append(v.toString());
            } else if (v instanceof Number) {
                sb.append(number((Number) v));
            } else if (v instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (e.getKey() == null) continue;
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, truncate(String.valueOf(e.getKey()), 128));
                    sb.append(':');
                    value(sb, e.getValue(), depth + 1);
                }
                sb.append('}');
            } else if (v instanceof Collection) {
                sb.append('[');
                boolean first = true;
                for (Object o : (Collection<?>) v) {
                    if (!first) sb.append(',');
                    first = false;
                    value(sb, o, depth + 1);
                }
                sb.append(']');
            } else if (v instanceof Object[]) {
                sb.append('[');
                Object[] arr = (Object[]) v;
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(',');
                    value(sb, arr[i], depth + 1);
                }
                sb.append(']');
            } else {
                quote(sb, truncate(String.valueOf(v), 2048));
            }
        }

        static String toJson(Object v) {
            StringBuilder sb = new StringBuilder();
            value(sb, v, 0);
            return sb.toString();
        }

        /** Truncate without splitting a surrogate pair. Null stays null. */
        static String truncate(String s, int max) {
            if (s == null || s.length() <= max) return s;
            int end = max;
            if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
            return s.substring(0, end);
        }

        // ---------------- Events ----------------

        /** Returns a valid event name (1 to 64 chars of [A-Za-z0-9_.:$ -]) or null. */
        static String sanitizeEventName(String name) {
            if (name == null) return null;
            String n = name.trim();
            if (n.length() == 0) return null;
            StringBuilder sb = new StringBuilder(Math.min(n.length(), 64));
            for (int i = 0; i < n.length() && sb.length() < 64; i++) {
                char c = n.charAt(i);
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '.' || c == ':' || c == '$' || c == ' ' || c == '-';
                sb.append(ok ? c : '_');
            }
            // Names starting with $ are reserved for the server.
            if (sb.charAt(0) == '$') sb.setCharAt(0, '_');
            return sb.toString();
        }

        /** props: max 32 keys, keys max 64 chars, values string (max 256), number or boolean. */
        static void props(StringBuilder sb, Map<String, ?> props) {
            sb.append('{');
            int count = 0;
            for (Map.Entry<String, ?> e : props.entrySet()) {
                if (count >= 32) break;
                String k = e.getKey();
                Object v = e.getValue();
                if (k == null || k.length() == 0 || v == null) continue;
                if (count > 0) sb.append(',');
                quote(sb, truncate(k, 64));
                sb.append(':');
                if (v instanceof Boolean) {
                    sb.append(v.toString());
                } else if (v instanceof Number) {
                    sb.append(number((Number) v));
                } else {
                    quote(sb, truncate(String.valueOf(v), 256));
                }
                count++;
            }
            sb.append('}');
        }

        static String eventJson(String name, long ts, String sessionId, Map<String, ?> props, Double value, Long durationMs) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"name\":");
            quote(sb, name);
            sb.append(",\"ts\":").append(ts);
            if (sessionId != null) {
                sb.append(",\"session_id\":");
                quote(sb, truncate(sessionId, 64));
            }
            if (props != null && !props.isEmpty()) {
                sb.append(",\"props\":");
                props(sb, props);
            }
            if (value != null && !value.isNaN() && !value.isInfinite()) {
                sb.append(",\"value\":").append(number(value));
            }
            if (durationMs != null) sb.append(",\"duration_ms\":").append(durationMs.longValue());
            sb.append('}');
            return sb.toString();
        }

        /** Common envelope, left open (no closing brace) so the payload can be appended. */
        static String envelopeOpen(String installId, String version, Long build, String os, String model,
                                   String locale, String userId, String sdk) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"install_id\":");
            quote(sb, installId == null ? "unknown-install" : installId);
            sb.append(",\"platform\":\"android\",\"version\":");
            quote(sb, truncate(version == null ? "0" : version, 64));
            if (build != null) sb.append(",\"build\":").append(build.longValue());
            if (os != null) { sb.append(",\"os\":"); quote(sb, truncate(os, 64)); }
            if (model != null) { sb.append(",\"model\":"); quote(sb, truncate(model, 64)); }
            if (locale != null) { sb.append(",\"locale\":"); quote(sb, truncate(locale, 16)); }
            if (userId != null) { sb.append(",\"user_id\":"); quote(sb, truncate(userId, 128)); }
            if (sdk != null) { sb.append(",\"sdk\":"); quote(sb, truncate(sdk, 32)); }
            return sb.toString();
        }

        static boolean isNewSession(long backgroundAtMs, long now) {
            return backgroundAtMs <= 0 || now - backgroundAtMs >= SESSION_TIMEOUT_MS;
        }

        // ---------------- Errors ----------------

        static boolean isInApp(String className, String[] prefixes) {
            if (className == null || prefixes == null) return false;
            if (className.startsWith("com.housamkak.appmonitor.")) return false;
            for (String p : prefixes) {
                if (p == null || p.length() == 0) continue;
                String pre = p.endsWith(".") ? p : p + ".";
                if (className.startsWith(pre) || className.equals(p)) return true;
            }
            return false;
        }

        /** Frames, top (innermost) first, max 100. */
        static void frames(StringBuilder sb, StackTraceElement[] st, String[] prefixes) {
            sb.append('[');
            int n = st == null ? 0 : Math.min(st.length, MAX_FRAMES);
            for (int i = 0; i < n; i++) {
                StackTraceElement e = st[i];
                if (i > 0) sb.append(',');
                sb.append("{\"function\":");
                quote(sb, e.getClassName() + "." + e.getMethodName());
                if (e.getFileName() != null) {
                    sb.append(",\"file\":");
                    quote(sb, e.getFileName());
                }
                if (e.getLineNumber() > 0) sb.append(",\"line\":").append(e.getLineNumber());
                sb.append(",\"in_app\":").append(isInApp(e.getClassName(), prefixes));
                sb.append('}');
            }
            sb.append(']');
        }

        /** printStackTrace-like text including the cause chain (cycle safe), max ~32 KB. */
        static String rawStack(Throwable t) {
            StringBuilder sb = new StringBuilder(1024);
            List<Throwable> seen = new ArrayList<Throwable>();
            Throwable cur = t;
            boolean first = true;
            while (cur != null && !containsIdentity(seen, cur) && sb.length() < MAX_RAW_STACK) {
                seen.add(cur);
                if (!first) sb.append("Caused by: ");
                first = false;
                sb.append(cur.getClass().getName());
                String m = cur.getMessage();
                if (m != null) sb.append(": ").append(m);
                sb.append('\n');
                StackTraceElement[] st = cur.getStackTrace();
                for (int i = 0; st != null && i < st.length && sb.length() < MAX_RAW_STACK; i++) {
                    sb.append("\tat ").append(st[i].toString()).append('\n');
                }
                cur = cur.getCause();
            }
            return truncate(sb.toString(), MAX_RAW_STACK);
        }

        private static boolean containsIdentity(List<Throwable> list, Throwable t) {
            for (Throwable x : list) if (x == t) return true;
            return false;
        }

        static String errorJson(Throwable t, String kind, boolean handled, long ts, String sessionId,
                                String breadcrumbsJson, Map<String, ?> context, String[] prefixes) {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("{\"type\":");
            quote(sb, truncate(t.getClass().getName(), 128));
            String msg = t.getMessage();
            sb.append(",\"message\":");
            quote(sb, truncate(msg == null ? "" : msg, 2048));
            sb.append(",\"kind\":");
            quote(sb, kind);
            sb.append(",\"handled\":").append(handled);
            sb.append(",\"ts\":").append(ts);
            if (sessionId != null) {
                sb.append(",\"session_id\":");
                quote(sb, truncate(sessionId, 64));
            }
            sb.append(",\"stack\":");
            frames(sb, t.getStackTrace(), prefixes);
            sb.append(",\"raw_stack\":");
            quote(sb, rawStack(t));
            if (breadcrumbsJson != null) sb.append(",\"breadcrumbs\":").append(breadcrumbsJson);
            if (context != null && !context.isEmpty()) {
                String c = toJson(context);
                if (c.length() > MAX_CONTEXT) c = "{\"_truncated\":true}";
                sb.append(",\"context\":").append(c);
            }
            sb.append('}');
            return sb.toString();
        }

        /** Body for /i/v1/errors or /i/v1/crash with a single error. */
        static String crashBody(String envelopeOpen, String errorJson) {
            return envelopeOpen + ",\"error\":" + errorJson + "}";
        }

        // ---------------- Breadcrumbs ----------------

        static final class Breadcrumbs {
            private final String[] ring;
            private int next;
            private int size;

            Breadcrumbs(int capacity) {
                ring = new String[capacity];
            }

            synchronized void add(long ts, String category, String message, Map<String, ?> data) {
                StringBuilder sb = new StringBuilder(96);
                sb.append("{\"ts\":").append(ts).append(",\"category\":");
                quote(sb, truncate(category == null ? "default" : category, 64));
                sb.append(",\"message\":");
                quote(sb, truncate(message == null ? "" : message, 512));
                if (data != null && !data.isEmpty()) {
                    String d = Core.toJson(data);
                    if (d.length() <= 2048) sb.append(",\"data\":").append(d);
                }
                sb.append('}');
                ring[next] = sb.toString();
                next = (next + 1) % ring.length;
                if (size < ring.length) size++;
            }

            synchronized void clear() {
                for (int i = 0; i < ring.length; i++) ring[i] = null;
                next = 0;
                size = 0;
            }

            /** Oldest first. */
            synchronized String toJson() {
                StringBuilder sb = new StringBuilder(size * 96 + 2);
                sb.append('[');
                int start = (next - size + ring.length) % ring.length;
                for (int i = 0; i < size; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ring[(start + i) % ring.length]);
                }
                sb.append(']');
                return sb.toString();
            }
        }

        // ---------------- Queue ----------------

        static final class QueueItem {
            final char kind;
            final long ts;
            final String version;
            final Long build;
            final String json;

            QueueItem(char kind, long ts, String version, Long build, String json) {
                this.kind = kind;
                this.ts = ts;
                this.version = version == null ? "0" : version.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                this.build = build;
                this.json = json;
            }

            /** One line: kind TAB ts TAB version TAB build TAB json (json never contains raw newlines). */
            String encode() {
                return kind + "\t" + ts + "\t" + version + "\t" + (build == null ? "" : build.toString()) + "\t" + json;
            }

            static QueueItem decode(String line) {
                if (line == null) return null;
                String[] p = line.split("\t", 5);
                if (p.length != 5 || p[0].length() != 1) return null;
                char k = p[0].charAt(0);
                if (k != KIND_EVENT && k != KIND_ERROR) return null;
                if (!p[4].startsWith("{") || !p[4].endsWith("}")) return null;
                try {
                    long ts = Long.parseLong(p[1]);
                    Long build = p[3].length() == 0 ? null : Long.valueOf(p[3]);
                    return new QueueItem(k, ts, p[2], build, p[4]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        /** Drops items older than 7 days, then the oldest beyond 1000. Returns how many were removed. */
        static int prune(List<QueueItem> q, long now) {
            int before = q.size();
            Iterator<QueueItem> it = q.iterator();
            while (it.hasNext()) {
                if (now - it.next().ts > MAX_AGE_MS) it.remove();
            }
            int excess = q.size() - MAX_QUEUE;
            if (excess > 0) q.subList(0, excess).clear();
            return before - q.size();
        }

        /** Items sharing the first item's kind, version and build; max 100 events or 20 errors. */
        static List<QueueItem> selectBatch(List<QueueItem> q, int limitOverride) {
            List<QueueItem> out = new ArrayList<QueueItem>();
            if (q.isEmpty()) return out;
            QueueItem first = q.get(0);
            int limit = first.kind == KIND_ERROR ? MAX_ERRORS_PER_BATCH : MAX_EVENTS_PER_BATCH;
            limit = Math.max(1, Math.min(limit, limitOverride));
            for (QueueItem it : q) {
                if (out.size() >= limit) break;
                if (it.kind == first.kind && it.version.equals(first.version) && eq(it.build, first.build)) out.add(it);
            }
            return out;
        }

        private static boolean eq(Long a, Long b) {
            return a == null ? b == null : a.equals(b);
        }

        static String buildBody(String envelopeOpen, List<QueueItem> batch) {
            StringBuilder sb = new StringBuilder(envelopeOpen.length() + batch.size() * 160);
            sb.append(envelopeOpen);
            sb.append(batch.get(0).kind == KIND_ERROR ? ",\"errors\":[" : ",\"events\":[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(batch.get(i).json);
            }
            sb.append("]}");
            return sb.toString();
        }

        // ---------------- Transport policy ----------------

        static int classify(int code) {
            if (code >= 200 && code < 300) return ACTION_REMOVE;
            if (code == 400 || code == 401) return ACTION_DROP;
            if (code == 403) return ACTION_KILL;
            if (code == 413) return ACTION_SPLIT;
            if (code == 429 || code >= 500 || code < 0) return ACTION_RETRY;
            return ACTION_DROP; // other 4xx: never retry a bad request
        }

        static long nextBackoff(long prev) {
            if (prev <= 0) return BACKOFF_MIN_MS;
            return Math.min(prev * 2, BACKOFF_MAX_MS);
        }

        static long retryDelay(long backoff, long retryAfterSec) {
            if (retryAfterSec > 0) return Math.min(Math.max(retryAfterSec * 1000, 1000), BACKOFF_MAX_MS);
            return backoff;
        }

        static long parseRetryAfter(String header) {
            if (header == null) return 0;
            try {
                long v = Long.parseLong(header.trim());
                return v > 0 ? v : 0;
            } catch (NumberFormatException e) {
                return 0; // HTTP-date form is not used by the server
            }
        }

        static byte[] gzip(byte[] data) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 3 + 64);
            GZIPOutputStream gz = new GZIPOutputStream(bo);
            gz.write(data);
            gz.close();
            return bo.toByteArray();
        }

        /** Numeric by dot segments; a "-suffix" pre-release sorts before the plain release. */
        static int compareVersions(String a, String b) {
            if (a == null) a = "0";
            if (b == null) b = "0";
            int da = a.indexOf('-');
            int db = b.indexOf('-');
            String ma = da >= 0 ? a.substring(0, da) : a;
            String mb = db >= 0 ? b.substring(0, db) : b;
            String[] pa = ma.split("\\.");
            String[] pb = mb.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                long x = i < pa.length ? leadingNumber(pa[i]) : 0;
                long y = i < pb.length ? leadingNumber(pb[i]) : 0;
                if (x != y) return x < y ? -1 : 1;
            }
            if (da >= 0 && db < 0) return -1;
            if (da < 0 && db >= 0) return 1;
            if (da >= 0) {
                int c = a.substring(da + 1).compareTo(b.substring(db + 1));
                return c < 0 ? -1 : (c > 0 ? 1 : 0);
            }
            return 0;
        }

        private static long leadingNumber(String s) {
            long v = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') break;
                v = v * 10 + (c - '0');
                if (v > 1_000_000_000_000L) break;
            }
            return v;
        }

        // ---------------- Native crash files ----------------

        /** Context string handed to the native handler: the full envelope plus session_id, closed. */
        static String nativeContext(String envelopeOpen, String sessionId) {
            StringBuilder sb = new StringBuilder(envelopeOpen);
            if (sessionId != null) {
                sb.append(",\"session_id\":");
                quote(sb, truncate(sessionId, 64));
            }
            sb.append('}');
            return sb.toString();
        }

        private static final String[] SYSTEM_PREFIXES = {
                "/system/", "/apex/", "/vendor/", "/product/", "/odm/", "/system_ext/", "/data/dalvik-cache/", "[",
        };

        private static final String[] SYSTEM_LIBS = {
                "libc.so", "libart.so", "libdl.so", "libm.so", "liblog.so", "libbase.so", "libutils.so",
                "libcutils.so", "libandroid_runtime.so", "libandroid.so", "libhwui.so", "libgui.so", "libui.so",
                "libbinder.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so",
                "libc++.so", "libc++_shared.so", "libnativebridge.so", "libnativeloader.so", "libsigchain.so",
                "libunwindstack.so", "libapp_monitor_ndk.so", "linker", "linker64", "app_process", "app_process32",
                "app_process64", "boot.oat", "boot-framework.oat", "libopenjdk.so", "libjavacore.so",
        };

        static String baseName(String path) {
            if (path == null) return null;
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        }

        static boolean isNativeInApp(String path) {
            if (path == null || path.length() == 0 || "?".equals(path)) return false;
            for (String p : SYSTEM_PREFIXES) if (path.startsWith(p)) return false;
            String base = baseName(path);
            for (String s : SYSTEM_LIBS) if (s.equals(base)) return false;
            return !(base.endsWith(".oat") || base.endsWith(".odex") || base.endsWith(".vdex") || base.endsWith(".art"));
        }

        private static final String[][] SIGNALS = {
                {"4", "SIGILL"}, {"5", "SIGTRAP"}, {"6", "SIGABRT"}, {"7", "SIGBUS"}, {"8", "SIGFPE"}, {"11", "SIGSEGV"},
        };

        static String signalName(int sig) {
            for (String[] s : SIGNALS) if (Integer.parseInt(s[0]) == sig) return s[1];
            return "SIG" + sig;
        }

        /**
         * Converts the text file written by app_monitor_ndk.c into a complete /i/v1/crash JSON body.
         * Returns null when the file is not a crash report. fallbackEnvelopeOpen is used when the
         * native side had no context string.
         *
         * File format (one record per line):
         *   AMNC1
         *   sig 11
         *   name SIGSEGV
         *   code 1
         *   fault 0x0
         *   ts 1760000000000
         *   thread RenderThread
         *   ctx {"install_id":...,"session_id":...}
         *   f <pc hex> <offset hex> <symbol or -> <module path or ?>
         *   m <base hex> <module path>
         *   end
         */
        static String nativeCrashBody(String text, String fallbackEnvelopeOpen) {
            if (text == null || !text.startsWith("AMNC1")) return null;
            int sig = 0;
            String name = null;
            String code = null;
            String fault = null;
            long ts = 0;
            String thread = null;
            String ctx = null;
            List<String[]> frames = new ArrayList<String[]>();
            List<String[]> modules = new ArrayList<String[]>();
            String[] lines = text.split("\n");
            for (String raw : lines) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String key = line.substring(0, sp);
                String rest = line.substring(sp + 1);
                try {
                    if (key.equals("sig")) sig = Integer.parseInt(rest.trim());
                    else if (key.equals("name")) name = rest.trim();
                    else if (key.equals("code")) code = rest.trim();
                    else if (key.equals("fault")) fault = rest.trim();
                    else if (key.equals("ts")) ts = Long.parseLong(rest.trim());
                    else if (key.equals("thread")) thread = rest;
                    else if (key.equals("ctx")) ctx = rest;
                    else if (key.equals("f")) {
                        String[] p = rest.split(" ", 4);
                        if (p.length == 4 && frames.size() < MAX_FRAMES) frames.add(p);
                    } else if (key.equals("m")) {
                        String[] p = rest.split(" ", 2);
                        if (p.length == 2 && modules.size() < 200) modules.add(p);
                    }
                } catch (NumberFormatException ignored) {
                    // tolerate a partially written line
                }
            }
            if (sig == 0 && name == null) return null;
            String type = name != null && name.startsWith("SIG") ? name : signalName(sig);
            if (ts <= 0) ts = System.currentTimeMillis();

            StringBuilder msg = new StringBuilder();
            msg.append("Fatal signal ").append(sig).append(" (").append(type).append(')');
            if (code != null) msg.append(", code ").append(code);
            if (fault != null) msg.append(", fault addr ").append(fault);
            if (thread != null) msg.append(" in thread ").append(thread);

            StringBuilder sb = new StringBuilder(4096);
            StringBuilder rawStack = new StringBuilder(msg).append('\n');
            sb.append("{\"type\":");
            quote(sb, type);
            sb.append(",\"message\":");
            quote(sb, truncate(msg.toString(), 2048));
            sb.append(",\"kind\":\"crash\",\"handled\":false,\"ts\":").append(ts);
            String sessionId = extractString(ctx, "session_id");
            if (sessionId != null) {
                sb.append(",\"session_id\":");
                quote(sb, sessionId);
            }
            sb.append(",\"stack\":[");
            for (int i = 0; i < frames.size(); i++) {
                String[] f = frames.get(i);
                String pc = f[0];
                String off = f[1];
                String sym = "-".equals(f[2]) ? null : f[2];
                String path = "?".equals(f[3]) ? null : f[3];
                String mod = baseName(path);
                if (i > 0) sb.append(',');
                sb.append('{');
                boolean any = false;
                if (sym != null) {
                    sb.append("\"function\":");
                    quote(sb, truncate(sym, 512));
                    any = true;
                }
                if (mod != null) {
                    if (any) sb.append(',');
                    sb.append("\"module\":");
                    quote(sb, truncate(mod, 256));
                    sb.append(",\"offset\":");
                    quote(sb, off);
                    any = true;
                }
                if (any) sb.append(',');
                sb.append("\"address\":");
                quote(sb, pc);
                sb.append(",\"in_app\":").append(isNativeInApp(path));
                sb.append('}');
                rawStack.append(String.format(Locale.ROOT, "#%02d pc %s %s", i, mod != null ? off : pc,
                        path != null ? path : "<unknown>"));
                if (sym != null) rawStack.append(" (").append(sym).append(')');
                rawStack.append('\n');
            }
            sb.append(']');
            sb.append(",\"raw_stack\":");
            quote(sb, truncate(rawStack.toString(), MAX_RAW_STACK));
            if (!modules.isEmpty()) {
                sb.append(",\"modules\":[");
                for (int i = 0; i < modules.size(); i++) {
                    String[] m = modules.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"name\":");
                    quote(sb, truncate(baseName(m[1]), 256));
                    sb.append(",\"base\":");
                    quote(sb, m[0]);
                    sb.append('}');
                }
                sb.append(']');
            }
            if (thread != null) {
                sb.append(",\"context\":{\"thread\":");
                quote(sb, truncate(thread, 64));
                sb.append('}');
            }
            sb.append('}');

            String env;
            if (ctx != null && ctx.startsWith("{") && ctx.endsWith("}") && ctx.indexOf("\"install_id\"") > 0) {
                env = ctx.substring(0, ctx.length() - 1);
            } else {
                env = fallbackEnvelopeOpen;
            }
            return crashBody(env, sb.toString());
        }

        /** Minimal lookup of "key":"value" in a JSON object written by this class (no escapes in value). */
        static String extractString(String json, String key) {
            if (json == null) return null;
            String pat = "\"" + key + "\":\"";
            int i = json.indexOf(pat);
            if (i < 0) return null;
            int start = i + pat.length();
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : null;
        }
    }
}
