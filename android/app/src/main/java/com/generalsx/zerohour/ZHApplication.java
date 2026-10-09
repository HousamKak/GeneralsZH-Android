package com.generalsx.zerohour;

import android.app.Application;

/**
 * Starts App Monitor (ZHTelemetry) once per app start, before any screen, so the first screen
 * opens its session. Not in RestartActivity's ":restart" process, which lives for a moment only.
 */
public class ZHApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DataPackInstaller.init(this);  // before anything asks where user data lives
        String process = android.os.Build.VERSION.SDK_INT >= 28 ? getProcessName() : getPackageName();
        if (getPackageName().equals(process)) {
            ZHTelemetry.init(this);
        }
    }
}
