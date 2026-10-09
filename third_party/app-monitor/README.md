# App Monitor SDK and scripts (vendored)

Copied from the owner's App Monitor repository (`HousamKak/app-monitor`, `sdk/android` and `scripts/`, commit
62a4978). Not edited here: update by copying the files again from that repository.

- `android/java/`: `AppMonitor.java` and `AppMonitorNative.java`, compiled into the app
  (`android/app/build.gradle`, `java.srcDirs`).
- `android/cpp/app_monitor_ndk.c`: the native crash handler, built as `libapp_monitor_ndk.so` by the
  engine's own CMake build (`GeneralsMD/Code/Main/CMakeLists.txt`, target `app_monitor_ndk`)
  and packaged by `scripts/build/android/package-android-zh.sh`.
- `scripts/`: `upload-symbols.mjs` and `lib.mjs`, which CI uses to upload the engines' symbols on release (`.github/workflows/build-android.yml`).
