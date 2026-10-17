# Project Resume

> Instruction for Claude: read this whole file first. Then read every file under "Must read before any change". Do not modify anything until you have fully understood the project. Keep this file updated as you work (see "Maintenance rules").

Last updated: 2026-10-17

## 1. What this project is
MatNotes: Android app (phone + tablet, arm64) to open, edit and export `.docx` with a Word-like mobile UI. Priority 1: layout fidelity. Engine: LibreOfficeKit (prebuilt libs from the official TDF LibreOffice Android APK). Local only, no accounts, DE/EN. Repo `corixien/matnotes` (public), push straight to `main`, no PRs. Stage: working app, installed on the owner's phone; feature work and polishing.

## 2. Must read before any change
1. `AGENTS.md` - code map, engine facts, commands, device-testing rules.
2. `README.md` - feature list and the honest list of gaps versus Word.

## 3. How to navigate
- `app/src/main/java/com/matnotes/app/` - all app code (see code map in `AGENTS.md`).
- `app/src/main/res/` - strings (DE + EN, keep both in sync), raw blank template `res/raw/blank.docx` (A4, Calibri 11).
- `scripts/` - `fetch-engine.sh` (engine, calls `fetch-fonts.sh`). Generated and gitignored: `engine/`, `app/src/main/jniLibs`, `app/src/main/assets`.
- `samples/` - private test docs (gitignored).

## 4. Commands
```
./scripts/fetch-engine.sh                       # once per checkout
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Needs a full JDK (the project uses a Temurin 21 in `~/.local/jdks`, configured through `org.gradle.java.home` in `~/.gradle/gradle.properties`) and the Android SDK in `~/Android/Sdk` (`local.properties`, gitignored). Debug builds have adb intent hooks (`uno`, `unoargs`, `insert_image`, `shape`, `link`, `zoom`).

## 5. Invariants and gotchas
- LibreOfficeKit input is asynchronous and sends no tile invalidations: rendering relies on stale-while-revalidate tiles plus a settle timer. Do not render right after posting an event and assume it is applied.
- LibreOffice on Android ignores font substitution tables: Microsoft fonts cannot be bundled; menu groups: Word (exact metric clones), other Word names (stand-in display), free fonts (DejaVu, Comic Neue, Anton, Gentium).
- Corner handles keep the aspect ratio (LibreOffice behaviour, wanted); edges resize one side.
- Page setup, margins and orientation have no dialog-free command: implemented by patching the exported docx and reloading (undo history resets).
- Many LibreOffice commands do nothing on Android (picture filters/transparency, chart insert, comments, line tool); see `AGENTS.md` for the verified list. SmartArt cannot be created.
- Save defaults to a copy in `Documents/MatNotes`; overwriting the original is explicit and warned.
- The test phone is the user's own phone: check the foreground app before scripted taps or text input, and never open their personal documents. (An earlier test run sent input into another app because the user was using the phone.)
- Never commit secrets, keystores or private sample docs.

## 6. Current state
Branch `main`. Working: open/new/save/save-as/overwrite, share as docx/odt/pdf (system sheet or e-mail attachment), print, ribbon with Home/Insert/Layout/References/Review/View and contextual Picture/Shape tab, shapes/text box/symbols, page setup, find/replace, fast scroller, Documents/MatNotes list. Open ideas: release pipeline with signed APK, table tab, comments, table of contents, more shape formatting, tablet layout polish.

## Maintenance rules
Update this file when structure, commands, invariants or state change. Bump "Last updated".
