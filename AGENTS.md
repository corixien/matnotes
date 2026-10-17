# AGENTS.md

## Goal
MatNotes: Android app to open/edit/export .docx. Priority 1: layout fidelity (shapes, text boxes, SmartArt, charts, wrapped images). Unedited document parts must survive saving unchanged.

## Stack
Kotlin + Jetpack Compose, LibreOfficeKit via JNI, local storage (SAF), no backend, i18n DE/EN, phone + tablet. Metric-compatible fonts bundled (Carlito, Caladea, Liberation).

## Milestones
1. Fidelity spike (desktop `soffice` headless -> PDF; find LOK Android libs)
2. Viewer  3. Editor basics  4. Images + wrap modes, tables  5. Shapes move/scale  6. Signed release pipeline

## Out of scope
Tracked changes, macros, mail merge, live collaboration, accounts, cloud.

## Engine
Run `scripts/fetch-engine.sh` once (downloads the TDF LibreOffice APK from F-Droid, unpacks libs + assets into `app/src/main`, all gitignored). `scripts/fetch-fonts.sh` (called by it) adds DejaVu, Comic Neue, Anton (gitignored). LibreOffice on Android ignores font substitution tables: Microsoft fonts cannot be bundled, so the font menu offers Word-compatible metric clones (Calibri=Carlito, Cambria=Caladea, Arial/Times New Roman/Courier New/Arial Narrow=Liberation) plus free fonts under their real names. JNI classes in `org.libreoffice.kit` are copied from LibreOffice core (MPL-2.0); package names must stay.

## Rules
- Never commit secrets, keystores, or private sample docs (`samples/`).
- Do not restructure architecture without being asked.
- No linter/formatter additions unless asked.
- Commands: `./gradlew :app:assembleDebug`, install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Needs a full JDK (not JRE-only) via `org.gradle.java.home` in `~/.gradle/gradle.properties`. Package `com.matnotes.app`, compileSdk 37, minSdk 26.
