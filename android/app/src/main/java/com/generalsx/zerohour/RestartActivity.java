package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.File;

/**
 * Starts the app again after ZHBridge ends the game's process. Runs in a process of its own
 * (":restart" in the manifest), waits until the game's process is gone, opens the target
 * screen in a fresh task and ends itself. Nothing is drawn: the theme is translucent.
 */
public class RestartActivity extends Activity {
    static final String EXTRA_PID = "com.housamkak.zhcommander.RESTART_PID";
    static final String EXTRA_TARGET = "com.housamkak.zhcommander.RESTART_TARGET";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int pid = getIntent().getIntExtra(EXTRA_PID, -1);
        final String target = getIntent().getStringExtra(EXTRA_TARGET);
        new Thread(() -> {
            File proc = new File("/proc/" + pid);
            for (int i = 0; i < 50 && pid > 0 && proc.exists(); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            runOnUiThread(() -> {
                Intent next = new Intent();
                next.setClassName(this, target != null ? target : SetupActivity.class.getName());
                next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(next);
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            });
        }, "ZHRestart").start();
    }
}
