package com.generalsx.zerohour;

import android.app.Application;
import android.content.pm.PackageInfo;

import com.housamkak.appmonitor.AppMonitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Usage, errors and crashes, sent to the owner's App Monitor (ingest.housamkak.com, SDK vendored
 * in third_party/app-monitor). Anonymous: the install id is LicenseGate's device hash, which never
 * holds anything rawer than a SHA-256, and no names, accounts or locations are sent. The player
 * can turn it off in the game's ZH COMMANDER screen ("Usage data"); the SDK keeps that choice.
 *
 * <p>One event name per action, with what happened in its props, so the dashboard can count and
 * chain them: activation {ok}, data_download {stage}, app_update {stage}, engine_boot,
 * support_report {ref}, settings_restart. Sessions and app_open come from the SDK itself.
 */
final class ZHTelemetry {
    /** Public by design: it names the app to the ingest server, it is not a secret. */
    private static final String APP_KEY = "am_9744dcb336496025978f89b4446a2e8295b0";
    private static final String ENDPOINT = "https://ingest.housamkak.com";

    private ZHTelemetry() {}

    /** From ZHApplication.onCreate, in the app's main process only. */
    static void init(Application app) {
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

    static boolean isEnabled() {
        return AppMonitor.isEnabled();
    }

    static void setEnabled(boolean enabled) {
        AppMonitor.setEnabled(enabled);
    }
}
