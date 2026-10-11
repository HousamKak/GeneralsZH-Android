package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * GeneralsX @feature ZH Commander 11/10/2026 The license's standing, and the owner's messages to
 * players (owner: "can I revoke or send alerts to specific users, can I stop them from playing").
 *
 * <p>The license itself is still verified offline (LicenseGate). On top of that the app asks the
 * license server at every start, in the background ({@link #refresh}): "ok", "revoked" (the owner
 * revoked the key) or "moved" (the key was reset to another phone). The answer is kept, so a
 * revoked phone is stopped at its next start, and at once in a running game (the main menu's
 * notice, MainMenu.cpp). A check that cannot happen (no network, a server error) changes nothing,
 * but a phone that has not reached the server for {@link #OFFLINE_LIMIT_DAYS} days must go online
 * once: otherwise switching the internet off for good would dodge a revoke.
 *
 * <p>The same answer carries messages for everyone, this key or this device; each is shown once.
 */
final class LicenseStatus {
    private LicenseStatus() {}

    static final String STATUS_URL = "https://gzh-license.housam-kak20.workers.dev/v1/status";
    static final int OFFLINE_LIMIT_DAYS = 30;

    private static final String PREFS = "gx_license_status";
    private static final String KEY_STATUS = "status";
    private static final String KEY_REASON = "reason";
    private static final String KEY_LAST_OK = "last_ok_ms";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_SHOWN = "shown_ids";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Asks the server, off the calling thread. Safe to call often; does nothing without a license. */
    static void refreshAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> refresh(app), "GXLicenseStatus").start();
    }

    // When the game last asked (refreshIfStale); per process.
    private static volatile long sLastAskMs;

    /**
     * Asks again in the background when the last ask is older than {@code maxAgeMs}: the game's
     * main menu calls this as it polls, so a message reaches a game that was reopened from the
     * recent apps (no start-up check) or that stays open for hours.
     */
    static void refreshIfStale(Context ctx, long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (now - sLastAskMs < maxAgeMs) {
            return;
        }
        sLastAskMs = now;
        refreshAsync(ctx);
    }

    /** Asks the server and keeps the answer. Blocking; false when the server could not be reached. */
    static boolean refresh(Context ctx) {
        String license = LicenseGate.storedLicense(ctx);
        if (license == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("license", license).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            if (conn.getResponseCode() != 200) {
                return false;
            }
            JSONObject answer = new JSONObject(readAll(conn.getInputStream()));
            String status = answer.optString("status", "ok");
            SharedPreferences.Editor edit = prefs(ctx).edit()
                .putString(KEY_STATUS, status)
                .putString(KEY_REASON, answer.isNull("reason") ? null : answer.optString("reason", null))
                .putLong(KEY_LAST_OK, System.currentTimeMillis());
            JSONArray messages = answer.optJSONArray("messages");
            edit.putString(KEY_MESSAGES, messages != null ? messages.toString() : "[]");
            edit.apply();
            NetworkTrace.write(ctx, "[license] status " + status
                + (messages != null ? ", " + messages.length() + " message(s)" : ""));
            return true;
        } catch (Exception e) {
            NetworkTrace.write(ctx, "[license] status check failed: " + e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Why this phone may not play, or null. "revoked" and "moved" come from the server; "offline"
     * means no successful check for OFFLINE_LIMIT_DAYS days.
     */
    static String blockReason(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String status = p.getString(KEY_STATUS, "ok");
        if ("revoked".equals(status) || "moved".equals(status)) {
            return status;
        }
        long lastOk = p.getLong(KEY_LAST_OK, 0);
        if (lastOk == 0) {
            // First start with this check: the limit counts from now, not from the activation.
            p.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply();
            return null;
        }
        long days = (System.currentTimeMillis() - lastOk) / (24L * 60 * 60 * 1000);
        return days >= OFFLINE_LIMIT_DAYS ? "offline" : null;
    }

    /** The owner's note with a revoke, if any. */
    static String reason(Context ctx) {
        return prefs(ctx).getString(KEY_REASON, null);
    }

    /** The first message not shown yet, or null; it is marked shown. */
    static JSONObject takeMessage(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Set<String> shown = new HashSet<>(p.getStringSet(KEY_SHOWN, new HashSet<>()));
        try {
            JSONArray messages = new JSONArray(p.getString(KEY_MESSAGES, "[]"));
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.getJSONObject(i);
                String id = String.valueOf(m.optLong("id"));
                if (!shown.contains(id)) {
                    shown.add(id);
                    p.edit().putStringSet(KEY_SHOWN, shown).apply();
                    return m;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
