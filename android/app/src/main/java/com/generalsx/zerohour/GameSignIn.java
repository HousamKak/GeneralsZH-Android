package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * GeneralsOnline sign-in started from the game (the Online button, the ZH COMMANDER screen): the
 * same steps as GeneralsOnlineActivity's Sign In (a one-time code from the server, the browser,
 * then asking the server until the player finishes there), but with no screen of its own. The
 * player goes to the browser and comes straight back to the game; the wait runs on its own
 * thread and a toast says how it ended. The account screen (ADVANCED, Online) still has the full
 * sign-in with its explanations.
 */
final class GameSignIn {
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int POLL_MAX_ATTEMPTS = 180; // about three minutes, as the account screen
    private static volatile boolean sRunning;

    private GameSignIn() {}

    static void start(Activity game) {
        if (sRunning) {
            return;
        }
        sRunning = true;
        final Context app = game.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                NetworkTrace.section(app, "sign-in from the game");
                String code = GeneralsOnlineSession.fetchLoginCode(app);
                if (code == null) {
                    // GeneralsX @feature ZH Commander 10/10/2026 Sign-in failures by result, for the usage monitor.
                    ZHTelemetry.track("online_sign_in", "result", "no_network");
                    toast(main, app, "Could not reach GeneralsOnline. Check the connection and press Online again.");
                    return;
                }
                main.post(() -> {
                    try {
                        game.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(String.format(GeneralsOnlineActivity.LOGIN_URL_FMT, code))));
                    } catch (Exception e) {
                        ZHTelemetry.track("online_sign_in", "result", "no_browser");
                        Toast.makeText(app, "No browser to sign in with: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
                for (int attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    GeneralsOnlineSession.AuthResult result = checkLogin(app, code);
                    if (result == null) {
                        continue;  // a dropped request: the next one may get through
                    }
                    if (result.state == 1) {
                        GeneralsOnlineSession.saveSession(app, result);
                        ZHTelemetry.track("online_sign_in", "result", "ok");
                        toast(main, app, "Signed in to GeneralsOnline as " + result.displayName
                            + ". Press Online to play.");
                        return;
                    }
                    if (result.httpStatus == 423) {
                        ZHTelemetry.track("online_sign_in", "result", "rejected");
                        toast(main, app, "This GeneralsOnline account is banned.");
                        return;
                    }
                    // Anything else means "not yet": the player is still in the browser.
                }
                ZHTelemetry.track("online_sign_in", "result", "timeout");
                toast(main, app, "The sign-in did not finish. Press Online to try again.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sRunning = false;
            }
        }, "ZHGameSignIn").start();
    }

    private static GeneralsOnlineSession.AuthResult checkLogin(Context ctx, String code) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("client_id", GeneralsOnlineSession.clientId(ctx));
            body.put("machine_guid", NetworkDiagnostics.installId(ctx));
            body.put("mac_addr", NetworkDiagnostics.syntheticMac(ctx));
            body.put("vol_serial", NetworkDiagnostics.syntheticVolumeSerial(ctx));
            body.put("exe_crc", 0);
            body.put("ini_crc", 0);
        } catch (Exception e) {
            return null;
        }
        return GeneralsOnlineSession.postJson(ctx, "CheckLogin", body, null);
    }

    private static void toast(Handler main, Context app, String text) {
        main.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
