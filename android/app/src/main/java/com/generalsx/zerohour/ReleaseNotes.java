package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Release notes (release-notes/&lt;version&gt;.md in the repository, English and Arabic) for the
 * game's own dialogs, through ZHBridge:
 *
 * <ul>
 *   <li>"What's new": this version's notes, bundled in the APK as assets/whatsnew.json by
 *   package-android-zh.sh, shown once on the main menu after an update (not after a fresh
 *   install: there is nothing "new" to someone who never had the app).</li>
 *   <li>The update offer: the offered version's notes, fetched from the site's /api/notes and
 *   cached before the offer is shown.</li>
 * </ul>
 *
 * <p>Arabic when the game's text is Arabic (the engine shapes and lays it out right to left),
 * English otherwise. Text is "title\u0001• line\n• line".
 */
final class ReleaseNotes {
    private static final String PREFS = "gx_release_notes";
    private static final String KEY_SHOWN = "whatsnew_shown";
    private static final String KEY_CACHE = "notes_";
    private static final String KEY_TRIED = "tried_";
    static final char SEPARATOR = '\u0001';

    private ReleaseNotes() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String lang(Context ctx) {
        String token = LocaleHelper.getGameTextToken(ctx);
        return token != null && token.toLowerCase(java.util.Locale.ROOT).startsWith("arab") ? "ar" : "en";
    }

    /** The notes as dialog text in the game's language, or null when they lack that language. */
    private static String format(Context ctx, JSONObject notes) {
        JSONObject section = notes.optJSONObject(lang(ctx));
        if (section == null) {
            section = notes.optJSONObject("en");
        }
        if (section == null) {
            return null;
        }
        JSONArray items = section.optJSONArray("items");
        StringBuilder body = new StringBuilder();
        for (int i = 0; items != null && i < items.length(); i++) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append("• ").append(items.optString(i));
        }
        return section.optString("title") + SEPARATOR + body;
    }

    /** This version's notes, once, after an update; null otherwise. */
    static String whatsNew(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String version = info.versionName;
            SharedPreferences p = prefs(ctx);
            if (version.equals(p.getString(KEY_SHOWN, null))) {
                return null;
            }
            p.edit().putString(KEY_SHOWN, version).apply();
            if (info.firstInstallTime == info.lastUpdateTime) {
                return null;  // a fresh install, not an update
            }
            JSONObject notes;
            try (InputStream in = ctx.getAssets().open("whatsnew.json")) {
                notes = new JSONObject(readAll(in));
            }
            return version.equals(notes.optString("version")) ? format(ctx, notes) : null;
        } catch (Exception e) {
            return null;  // no notes bundled (not a release build)
        }
    }

    /** Fetches a version's notes from the site once, in the background, and caches them. */
    static void prefetch(Context ctx, String version) {
        SharedPreferences p = prefs(ctx);
        // Once per hour at most: a failed fetch (offline) is tried again later, not on every poll.
        long now = System.currentTimeMillis();
        if (p.contains(KEY_CACHE + version) || now - p.getLong(KEY_TRIED + version, 0) < 60 * 60 * 1000L) {
            return;
        }
        p.edit().putLong(KEY_TRIED + version, now).remove(KEY_TRIED + version + "_done").apply();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    DataPack.SITE + "/api/notes/" + version).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try {
                    if (conn.getResponseCode() == 200) {
                        String json = readAll(conn.getInputStream());
                        prefs(app).edit().putString(KEY_CACHE + version, json).apply();
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                android.util.Log.i("ReleaseNotes", "notes for " + version + " not fetched: " + e);
            }
            prefs(app).edit().putBoolean(KEY_TRIED + version + "_done", true).apply();
        }, "ZHReleaseNotes").start();
    }

    /**
     * True once a prefetch of this version has finished, with or without notes, or has had 15
     * seconds (a fetch the app was closed in the middle of must not hold the offer back for good).
     */
    static boolean fetched(Context ctx, String version) {
        SharedPreferences p = prefs(ctx);
        long tried = p.getLong(KEY_TRIED + version, 0);
        return p.contains(KEY_CACHE + version) || p.getBoolean(KEY_TRIED + version + "_done", false)
            || (tried > 0 && System.currentTimeMillis() - tried > 15000);
    }

    /** A version's cached notes as dialog text, or null. */
    static String forVersion(Context ctx, String version) {
        String json = prefs(ctx).getString(KEY_CACHE + version, null);
        if (json == null) {
            return null;
        }
        try {
            return format(ctx, new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
