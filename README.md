# MatNotes

Android app (phone + tablet) to open, edit and export `.docx` files with maximum layout fidelity. Simplified Word-like UI. Local only, no accounts. German + English.

## Status

Usable Word-like viewer and editor for .docx on Android (arm64):

- Open (picker, "Open with", share), new blank document with Word defaults (A4, Calibri 11 pt), recent files
- Word-style ribbon (Home, Insert, Layout, References, Review, View, Picture), editable font/size boxes, find and replace
- Typing, selection handles, floating cut/copy/paste bar, images with wrap modes and corner/edge resize, tables, links, header/footer, footnotes
- Save a copy (default), explicit "overwrite original", PDF export, print, share
- Pinch zoom from 20 %, fast-scroll handle, Ctrl shortcuts for hardware keyboards

Known limits: LibreOffice rewrites the whole file on save (SmartArt/charts may change); Microsoft fonts cannot be bundled, so only
metric clones (Calibri, Cambria, Arial, Times New Roman, Courier New, Arial Narrow) render exactly.

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
