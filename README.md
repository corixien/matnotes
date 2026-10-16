# MatNotes

Android app (phone + tablet) to open, edit and export `.docx` files with maximum layout fidelity. Simplified Word-like UI. Local only, no accounts. German + English.

## Status

Milestone 1: Hello-World skeleton runs on device. Next: fidelity spike, then viewer.

## Stack

Kotlin, Jetpack Compose, LibreOfficeKit (JNI). Distribution: signed APK via GitHub Releases.

## Develop

Requires a full JDK (21 recommended, with `javac`) and the Android SDK (platform 37, build-tools 35). Set the JDK in `~/.gradle/gradle.properties` (`org.gradle.java.home=...`) and the SDK in `local.properties` (`sdk.dir=...`, gitignored).

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Test documents

Put private test `.docx` files in `samples/` (gitignored).
