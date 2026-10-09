package com.housamkak.appmonitor;

/**
 * Optional bridge to libapp_monitor_ndk.so (the NDK signal handler). AppMonitor uses it only when
 * Options.enableNative is true, and only after checking {@link #isLoaded()}. If the library is
 * missing from the APK, isLoaded() is false and nothing native is called.
 *
 * JNI symbols (keep the names, see consumer-rules.pro):
 *   Java_com_housamkak_appmonitor_AppMonitorNative_install(JNIEnv*, jclass, jstring path)
 *   Java_com_housamkak_appmonitor_AppMonitorNative_setContext(JNIEnv*, jclass, jstring json)
 */
final class AppMonitorNative {

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("app_monitor_ndk");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private AppMonitorNative() {
    }

    static boolean isLoaded() {
        return LOADED;
    }

    /** Installs the signal handlers; the crash report is written to path. */
    static native boolean install(String path);

    /** Sets the JSON envelope copied into the crash report (install id, version, session id). */
    static native void setContext(String json);
}
