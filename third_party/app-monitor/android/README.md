# App Monitor Android SDK (vendored)

Copied from the owner's App Monitor repository (`HousamKak/app-monitor`, `sdk/android`, commit
a45f204). Not edited here: update by copying the files again from that repository.

- `java/`: `AppMonitor.java` and `AppMonitorNative.java`, compiled into the app
  (`android/app/build.gradle`, `java.srcDirs`).
- `cpp/app_monitor_ndk.c`: the native crash handler, built as `libapp_monitor_ndk.so` by the
  engine's own CMake build (`GeneralsMD/Code/Main/CMakeLists.txt`, target `app_monitor_ndk`)
  and packaged by `scripts/build/android/package-android-zh.sh`.
