package com.generalsx.zerohour;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Activation gate: the launcher and the game refuse to start until this device holds a license
 * from the owner's license server (server/license).
 *
 * <p>The player types a one-time key the owner minted; {@link #activate} sends it with a hash of
 * this device's id, and the server answers with a license signed by a key that never leaves it.
 * The license is verified here against {@link #PUBLIC_KEY_B64} on every start, offline, and must
 * name this device -- so it can be neither forged nor copied to another phone. Once activated,
 * nothing is checked online again.
 */
final class LicenseGate {
    private LicenseGate() {}

    static final String ACTIVATE_URL = "https://gzh-license.housam-kak20.workers.dev/v1/activate";

    /** SubjectPublicKeyInfo (DER, base64) of the license server's signing key. */
    static final String PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhE861NJAUCPcAoeya8LDyNmUux2QKucHdX3ndZ+IjYbkPYVqQU/GXBmn5tsuKg1Wp3b70Xx6aLSbsvJEfjOmg==";

    private static final String PREFS = "gx_license";
    private static final String KEY_LICENSE = "license";

    static boolean isActivated(Context ctx) {
        String license = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LICENSE, null);
        return license != null && isValid(license, deviceId(ctx));
    }

    /** Outcome of a redeem attempt: null error means success. */
    static final class Result {
        final String error;
        Result(String error) { this.error = error; }
    }

    /** Blocks on the network: call off the main thread. */
    static Result activate(Context ctx, String key) {
        String device = deviceId(ctx);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ACTIVATE_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("code", key);
            body.put("device", device);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            JSONObject reply = new JSONObject(in != null ? readAll(in) : "{}");
            if (status != 200) {
                return new Result(reply.optString("error", "server_error"));
            }
            String license = reply.optString("license", "");
            // Never store what would not pass the check on the next start anyway.
            if (!isValid(license, device)) {
                return new Result("bad_license");
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LICENSE, license).apply();
            return new Result(null);
        } catch (IOException e) {
            return new Result("network");
        } catch (Exception e) {
            return new Result("server_error");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // Settings.Secure.ANDROID_ID is per device, user and app signing key, and survives a
    // reinstall. Only its hash leaves the phone.
    static String deviceId(Context ctx) {
        String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return sha256Hex("gzh-license:" + (androidId != null ? androidId : ""));
    }

    private static boolean isValid(String license, String device) {
        int dot = license.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        try {
            byte[] payload = Base64.decode(license.substring(0, dot), Base64.DEFAULT);
            byte[] signature = Base64.decode(license.substring(dot + 1), Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                return false;
            }
            JSONObject claims = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return claims.optInt("v") == 1 && device.equals(claims.optString("device"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
