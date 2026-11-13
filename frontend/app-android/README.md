# Kei Android

Requires Android Studio, the Android SDK (API 37), and JDK 21; the Gradle daemon selects JDK 21 automatically.

From this directory, run `gradlew.bat assembleDebug` to build and `gradlew.bat test` to run tests.
Install on a connected phone with `adb install app/build/outputs/apk/debug/app-debug.apk`.
The backend must run with `--host 0.0.0.0`, and the phone must pair with the server.
