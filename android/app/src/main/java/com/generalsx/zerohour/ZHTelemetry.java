package com.generalsx.zerohour;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.housamkak.appmonitor.AppMonitor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Usage, errors and crashes, sent to the owner's App Monitor (ingest.housamkak.com, SDK vendored
 * in third_party/app-monitor). Anonymous: the install id is LicenseGate's device hash, which never
 * holds anything rawer than a SHA-256, and no names, accounts or locations are sent. The player
 * can turn it off in the game's ZH COMMANDER screen ("Usage data"); the SDK keeps that choice.
 *
 * <p>One event name per action, with what happened in its props, so the dashboard can count and
 * chain them: activation {ok}, data_download {stage}, app_update {stage}, engine_boot,
 * support_report {ref}, settings_restart. Sessions and app_open come from the SDK itself.
 *
 * <p>GeneralsX @feature ZH Commander 10/10/2026 The engine's events arrive as
 * "name|key=value|key=value" (ZHCommander::Event): match_start, match_end, settings and
 * online_join_refused. Only the keys listed for each are kept, values are cut to 64 characters,
 * and values that read as numbers are sent as numbers. The app adds what it knows better than the
 * engine: renderer and sim_hz to match_* and settings, its own language to settings.
 */
final class ZHTelemetry {
    /** Public by design: it names the app to the ingest server, it is not a secret. */
    private static final String APP_KEY = "am_9744dcb336496025978f89b4446a2e8295b0";
    private static final String ENDPOINT = "https://ingest.housamkak.com";

    private static final Set<String> MATCH_KEYS = keys("mode", "map", "side", "players", "ai_max");
    private static final Map<String, Set<String>> ENGINE_KEYS = new HashMap<>();
    static {
        ENGINE_KEYS.put("engine_boot", keys());
        ENGINE_KEYS.put("match_start", MATCH_KEYS);
        Set<String> end = new HashSet<>(MATCH_KEYS);
        end.addAll(keys("result", "duration_s", "avg_fps", "slow_frame_pct", "p95_frame_ms"));
        ENGINE_KEYS.put("match_end", end);
        ENGINE_KEYS.put("settings", keys("upscale", "ui_scale", "text_size", "game_lang"));
        ENGINE_KEYS.put("online_join_refused", keys("reason", "sim_hz"));
    }

    private static Application sApp;

    private ZHTelemetry() {}

    private static Set<String> keys(String... k) {
        return new HashSet<>(Arrays.asList(k));
    }

    /** From ZHBridge.event: an engine event, "name|key=value|...". Unknown names are dropped. */
    static void trackEngine(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String[] parts = raw.split("\\|");
        String name = parts[0];
        Set<String> allowed = ENGINE_KEYS.get(name);
        if (allowed == null) return;
        Map<String, Object> props = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            String key = parts[i].substring(0, eq);
            if (!allowed.contains(key)) continue;
            String value = parts[i].substring(eq + 1);
            props.put(key, number(value.length() > 64 ? value.substring(0, 64) : value));
        }
        if (sApp != null && (name.startsWith("match_") || name.equals("settings"))) {
            props.put("renderer", SetupActivity.renderBackendChoice(sApp));
            props.put("sim_hz", SetupActivity.getSimHz(sApp));
            if (name.equals("settings")) {
                String tag = LocaleHelper.getSavedLanguageTag(sApp);
                props.put("app_lang", tag == null || tag.isEmpty() ? Locale.getDefault().getLanguage() : tag);
            }
        }
        if (props.isEmpty()) AppMonitor.track(name);
        else AppMonitor.track(name, props);
    }

    private static Object number(String v) {
        try {
            if (v.matches("-?\\d{1,15}")) return Long.parseLong(v);
            if (v.matches("-?\\d{1,15}\\.\\d{1,6}")) return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            // keep the string
        }
        return v;
    }

    /** From ZHApplication.onCreate, in the app's main process only. */
    static void init(Application app) {
        sApp = app;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            long build = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            AppMonitor.init(app, new AppMonitor.Options()
                .appKey(APP_KEY)
                .endpoint(ENDPOINT)
                .version(info.versionName)
                .build(build)
                .installId(LicenseGate.deviceId(app))
                .inAppPackages("com.generalsx.zerohour")
                .enableNative(true));
        } catch (Exception e) {
            android.util.Log.w("ZHTelemetry", "App Monitor not started: " + e);
        }
    }

    static void track(String name) {
        AppMonitor.track(name);
    }

    static void track(String name, String key, Object value) {
        Map<String, Object> props = new HashMap<>();
        props.put(key, value);
        AppMonitor.track(name, props);
    }

    static void track(String name, String key, Object value, String key2, Object value2) {
        Map<String, Object> props = new HashMap<>();
        props.put(key, value);
        props.put(key2, value2);
        AppMonitor.track(name, props);
    }

    static void track(String name, Map<String, Object> props) {
        AppMonitor.track(name, props);
    }

    static boolean isEnabled() {
        return AppMonitor.isEnabled();
    }

    static void setEnabled(boolean enabled) {
        AppMonitor.setEnabled(enabled);
    }
}
