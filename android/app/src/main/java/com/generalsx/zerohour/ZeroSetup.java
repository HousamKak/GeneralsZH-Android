package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;

/**
 * GeneralsX @feature ZH Commander 11/10/2026 Online play with nothing to set up (owner: "how can we
 * make the user do nothing but play").
 *
 * <p>A phone joins the one big GeneralsOnline pool by default, PC players and phones alike. That
 * takes three things, each of which a player used to have to find and set up:
 * <ul>
 *   <li>60 Hz, the PC client's tick rate (SetupActivity.SIM_HZ_CROSSPLAY; libmain60.so);</li>
 *   <li>the GeneralsOnline community patch every PC player mounts (DataPackInstaller), so the INI
 *   checksum is genuinely the PC one;</li>
 *   <li>the cross-play marker (gx_pc_compat.txt in the game folder), so the EXE checksum the
 *   engine reports is the PC client's (GlobalData::init).</li>
 * </ul>
 * {@link #ensure} keeps the marker and the patch switch in step with the game speed at every
 * start; {@link #patchNeeded} tells the screen before the game (DataDownloadActivity) to fetch or
 * update the patch, with no sign-in. A player who chooses 30 Hz (ZH COMMANDER, Game speed) or turns
 * "Play with PC players" off gets retail data and plays with phones that did the same: the marker
 * claims the PC checksum only with the PC's own 60 Hz, never at 30 Hz, where the match would
 * desync.
 */
final class ZeroSetup {
    private static final String PREF_MIGRATED = "zero_setup_v1";
    private static final String PREF_CROSSPLAY_OFF = "crossplay_off";
    static final String MARKER = "gx_pc_compat.txt";

    private ZeroSetup() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Whether this phone plays in the PC pool: 60 Hz, and cross-play not turned off. */
    static boolean crossPlayWanted(Context ctx) {
        return SetupActivity.getSimHz(ctx) == SetupActivity.SIM_HZ_CROSSPLAY
            && !prefs(ctx).getBoolean(PREF_CROSSPLAY_OFF, false);
    }

    /** The "Play with PC players" switch (GeneralsOnlineActivity). On also means 60 Hz. */
    static void setCrossPlay(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(PREF_CROSSPLAY_OFF, !on).apply();
        if (on) {
            SetupActivity.setSimHz(ctx, SetupActivity.SIM_HZ_CROSSPLAY);
        }
        ensure(ctx);
    }

    static File marker(Context ctx) {
        String gamePath = SetupActivity.getSavedGamePath(ctx);
        return gamePath != null ? new File(gamePath, MARKER) : null;
    }

    /**
     * Cheap and synchronous (a few file operations): run at every process start and whenever the
     * game speed or the switch changes. The first run moves every install to 60 Hz once; after
     * that the player's own choice stands.
     */
    static void ensure(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(PREF_MIGRATED, false)) {
            p.edit().putBoolean(PREF_MIGRATED, true).apply();
            SetupActivity.setSimHz(ctx, SetupActivity.SIM_HZ_CROSSPLAY);
            NetworkTrace.write(ctx, "[zero-setup] game speed set to 60 Hz for cross-play");
        }
        boolean wanted = crossPlayWanted(ctx);
        File marker = marker(ctx);
        if (marker != null && marker.getParentFile() != null && marker.getParentFile().isDirectory()) {
            if (wanted && !marker.isFile()) {
                try {
                    marker.createNewFile();
                } catch (IOException e) {
                    NetworkTrace.write(ctx, "[zero-setup] could not write " + marker + ": " + e);
                }
            } else if (!wanted && marker.isFile()) {
                marker.delete();
            }
        }
        if (DataPackInstaller.isEnabled() != wanted) {
            DataPackInstaller.setEnabled(ctx, wanted);
        }
    }

    /** True when the patch should be fetched before the game starts: wanted, and missing or stale. */
    static boolean patchNeeded(Context ctx) {
        if (!crossPlayWanted(ctx)) {
            return false;
        }
        return DataPackInstaller.installedVersion(ctx) == null || UpdateManager.datapackUpdateWanted(ctx);
    }
}
