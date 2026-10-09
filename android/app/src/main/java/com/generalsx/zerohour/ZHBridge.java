package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * What the game's own menus ask of the app (GeneralsMD/Code/GameEngine/Include/Common/ZHCommander.h,
 * called through JNI from GeneralsMD/Code/Main/AndroidBridge.cpp): the app version, an update
 * waiting, the support report, the settings kept outside Options.ini, and a restart.
 *
 * <p>Called on the engine's thread, not the UI thread; anything that touches views is posted.
 */
final class ZHBridge {
    private static final String TAG = "ZHBridge";

    private static WeakReference<Activity> sGame = new WeakReference<>(null);

    private ZHBridge() {}

    /** GeneralsZHActivity registers itself before the engine starts. */
    static void attach(Activity game) {
        sGame = new WeakReference<>(game);
    }

    /** The running game, or null when it is not up. */
    static Activity gameActivity() {
        return sGame.get();
    }

    private static Activity game() {
        Activity a = sGame.get();
        if (a == null) {
            throw new IllegalStateException("game activity not attached");
        }
        return a;
    }

    static String appVersion() {
        Activity a = game();
        try {
            PackageInfo info = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** The newer release's version, "!" first when it is required; null when there is none. */
    static String appUpdateOffer() {
        UpdateManager.AppOffer offer = UpdateManager.appOffer(game());
        if (offer == null) {
            return null;
        }
        // Held back until the offered version's notes are fetched (a second or two), so the
        // game's dialog can say what the update brings (releaseNotes below).
        ReleaseNotes.prefetch(game(), offer.versionName);
        AppUpdateService.prefetch(game());  // on Wi-Fi: download now, so Update installs at once
        if (!ReleaseNotes.fetched(game(), offer.versionName)) {
            return null;
        }
        return (offer.mandatory ? "!" : "") + offer.versionName;
    }

    /** A version's notes as "title\u0001body", in the game's language, or null. */
    static String releaseNotes(String version) {
        return ReleaseNotes.forVersion(game(), version);
    }

    /** This version's notes once after an update ("title\u0001body"), or null. */
    static String whatsNew() {
        return ReleaseNotes.whatsNew(game());
    }

    static void startAppUpdate() {
        Activity a = game();
        // From the game: closing the update screen goes back to it rather than starting another.
        a.startActivity(new Intent(a, UpdateActivity.class)
            .putExtra(UpdateActivity.EXTRA_FROM_SUPPORT, true));
    }

    /** The newer game data's version, or null. */
    static String dataUpdateOffer() {
        Activity a = game();
        return DataPack.updateAvailable(a) ? DataPack.latestVersion(a) : null;
    }

    /** The game's own archives are open while it runs, so the download happens in a fresh start. */
    static void startDataUpdate() {
        restartInto(DataDownloadActivity.class);
    }

    static void shareSupportReport() {
        Activity a = game();
        a.startActivity(new Intent(a, SetupActivity.class)
            .putExtra(SetupActivity.EXTRA_SUPPORT, true)
            .putExtra(SetupActivity.EXTRA_SHARE_REPORT, true));
    }

    /** The mods screen (optional packs of the game data). */
    static void openMods() {
        Activity a = game();
        a.startActivity(new Intent(a, ModsActivity.class));
    }

    /** "3 on of 5", or null when no packs are published. */
    static String modsSummary() {
        DataPack.Manifest m = DataPack.lastManifest(game());
        if (m == null || m.packs.isEmpty()) {
            return null;
        }
        return DataPack.installedPacks(game()).size() + " of " + m.packs.size() + " on";
    }

    /** True while an installed pack changes gameplay: online players must have the same data. */
    static String gameplayMods() {
        return DataPack.gameplayPacksOn(game()) ? "yes" : null;
    }

    /** The GeneralsOnline display name this device is signed in as, or null. */
    static String onlineAccount() {
        return GeneralsOnlineActivity.getSignedInDisplayName(game());
    }

    /** Opens the sign-in straight into the browser; back to the game when it is done. */
    static void onlineSignIn() {
        // Browser and back, straight into the game: no account screen in between (GameSignIn).
        GameSignIn.start(game());
    }

    /** Opens GameReplays, the easiest account to sign in to GeneralsOnline with. */
    static void onlineCreateAccount() {
        Activity a = game();
        a.startActivity(new Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse(GeneralsOnlineActivity.GAMEREPLAYS_SIGNUP_URL)));
        ZHTelemetry.track("online_create_account");
    }

    /** Forgets this device's GeneralsOnline session (local only, as the account screen does). */
    static void onlineSignOut() {
        GeneralsOnlineSession.clearSession(game());
        ZHTelemetry.track("online_sign_out");
    }

    /** Something the engine did, for App Monitor (ZHTelemetry), e.g. "engine_boot". */
    static void event(String name) {
        ZHTelemetry.track(name);
    }

    static void openMoreSettings() {
        Activity a = game();
        a.startActivity(new Intent(a, SetupActivity.class).putExtra(SetupActivity.EXTRA_SUPPORT, true));
    }

    static String getSetting(String key) {
        Activity a = game();
        switch (key) {
            case "sim_hz":
                return String.valueOf(SetupActivity.getSimHz(a));
            case "render_backend":
                return SetupActivity.renderBackendChoice(a);
            case "telemetry":
                return ZHTelemetry.isEnabled() ? "on" : "off";
            default:
                return null;
        }
    }

    static void setSetting(String key, String value) {
        Activity a = game();
        switch (key) {
            case "sim_hz":
                SetupActivity.setSimHz(a, "60".equals(value) ? SetupActivity.SIM_HZ_CROSSPLAY : SetupActivity.SIM_HZ_RETAIL);
                break;
            case "telemetry":
                ZHTelemetry.setEnabled("on".equals(value));
                break;
            case "render_backend":
                try (FileWriter w = new FileWriter(new File(a.getFilesDir(), "render_backend.cfg"), false)) {
                    w.write(value);
                    w.write("\n");
                } catch (IOException e) {
                    Log.e(TAG, "render_backend.cfg not written", e);
                }
                break;
            default:
                Log.w(TAG, "unknown setting " + key);
        }
    }

    /** Through SetupActivity, so a start after a restart goes through the same checks as any other. */
    static void restart() {
        restartInto(SetupActivity.class);
    }

    static void restartInto(Class<? extends Activity> target) {
        Activity a = game();
        a.startActivity(new Intent(a, RestartActivity.class)
            .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
            .putExtra(RestartActivity.EXTRA_TARGET, target.getName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // The engine cannot be started twice in one process (SDL and the game keep global
        // state), so this process ends; RestartActivity, in a process of its own, waits for
        // that and opens the target.
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
    }
}
