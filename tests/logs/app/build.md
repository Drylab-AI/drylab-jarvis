# Android app build (Phase 5)

`./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL (36 tasks).

Output: app/build/outputs/apk/debug/app-debug.apk (~14 MB)

aapt2 badging confirms:
- package: com.jarvis.voicerec, versionName 0.1.0, compileSdk 35
- permissions: RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
  POST_NOTIFICATIONS, WAKE_LOCK, INTERNET, ACCESS_NETWORK_STATE, VIBRATE
- label: "Jarvis Voice Recorder"

Toolchain: JDK 17 (Temurin), Android SDK 35, build-tools 35.0.0, Gradle 8.11.1,
AGP 8.7.2, Kotlin 2.0.21.
