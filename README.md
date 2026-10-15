# MatNotes

Android app (phone + tablet) to open, edit and export `.docx` files with maximum layout fidelity. Simplified Word-like UI. Local only, no accounts. German + English.

## Status

Milestone 1: fidelity spike (see `docs/spike.md` once created).

## Stack

Kotlin, Jetpack Compose, LibreOfficeKit (JNI). Distribution: signed APK via GitHub Releases.

## Develop

Requires JDK 17+, Android SDK + NDK. Build commands are added with the Android project skeleton.

## Test documents

Put private test `.docx` files in `samples/` (gitignored).
