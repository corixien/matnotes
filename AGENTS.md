# AGENTS.md

## Goal
MatNotes: Android app to open/edit/export .docx with a Word-like mobile UI. Priority 1: layout fidelity (shapes, text boxes, SmartArt display, charts, wrapped images). Local only, no accounts, DE/EN, phone + tablet, arm64 only.

## Stack
Kotlin + Jetpack Compose, LibreOfficeKit (JNI) as render/edit engine, coroutines, MediaStore storage in `Documents/MatNotes`. Package `com.matnotes.app`, compileSdk 37, minSdk 26.

## Code map (`app/src/main/java/com/matnotes/app/`)
| File | Role |
|---|---|
| `Engine.kt` | LibreOfficeKit wrapper. All native calls run on one thread (`Engine.dispatcher`); `LoDocument` = one loaded document (tiles, key/mouse, uno commands, saveAs). `Keys` = awt key codes. |
| `DocumentView.kt` | `DocumentViewState` (zoom/scroll, tile cache, edit actions, selection/graphic overlays, drag handles, shape placement) and the `DocumentView` composable (gestures). |
| `KeyInputView.kt` | Invisible view: IME + hardware keyboard to LibreOffice, Ctrl shortcuts. |
| `Ribbon.kt` | Word-style ribbon (tabs, groups, combo boxes, icons drawn with Canvas). |
| `Screens.kt` | Home, viewer, top bar, floating selection/graphic toolbars, fast scroller, share and name dialogs. |
| `MainActivity.kt` | Flows: open, new, save, save as, overwrite, share/export, print, page setup, intents. |
| `PageSetup.kt` | Page size/orientation/margins by patching the exported docx (`w:sectPr`) and reloading. |
| `TablePatch.kt` | Table borders (`w:tblBorders`) and table looks (header/band shading, bold header) by patching the exported docx and reloading; finds the caret's table through a typed marker. |
| `ShapeThumbs.kt` | Drawn previews for the shape gallery. |
| `Spelling.kt`, `SpellPanel.kt`, `Hunspell.kt`, `src/main/cpp/` | Spelling check: Hunspell 1.7.2 (vendored in `cpp/hunspell`, built with the NDK through CMake, JNI wrapper `spell_jni.cpp`) on the exported document text. `Spelling` = language list, dictionary copy out of the APK (`assets/dict`, Hunspell needs real files) into `filesDir/dict`, tokenizer, language detection (script + stopwords, then known-word share per dictionary, document language and device language as prior). `SpellPanel` = bottom panel instead of the ribbon: detected language (changeable) -> mistakes one by one with suggestions, replace-all via `.uno:ExecuteSearch`. |
| `DocStore.kt` | `Documents/MatNotes` via MediaStore. `Recents.kt`, `Printing.kt`, `Theme.kt`. |
| `org/libreoffice/kit/*` | JNI classes copied from LibreOffice core (MPL-2.0). Package names must stay. |

## Engine facts (hard-won, do not re-learn)
- LibreOfficeKit applies input (keys, mouse, uno) **asynchronously** and sends **no tile invalidation** here. Rendering uses stale-while-revalidate tiles: after an edit mark tiles near the cursor stale, again on the cursor callback, everything stale after 300 ms (`edit`/`markStale`/`scheduleSettle`).
- Callbacks (cursor, selection, graphic selection, state changes like `.uno:Bold=true`) arrive on a LO thread and are posted to the main thread.
- LibreOffice on Android does **not** apply font substitution tables: unknown font names fall back to Roboto. Only bundled fonts render exactly.
- Programmatic style names: normal text is `Standard`, headings `Heading 1`, `Title`.
- New shapes (`.uno:BasicShapes.*`, `.uno:DrawText`, ...) are inserted around the page origin; `insertShape` moves them to the middle of the view via `setGraphicSelection(START/END)`.
- Image/shape corner handles keep the aspect ratio (LibreOffice behaviour), edge handles resize one side. Drag = `setGraphicSelection(START at handle, END at handle+delta)` after `setClientZoom`.
- Verified working: rotate/flip (`.uno:RotateLeft/Right`, `.uno:FlipHorizontal/Vertical`), wrap/anchor/arrange commands, `.uno:FillColor`, `.uno:XLineColor`, `.uno:BackgroundColor`, case changes, hyperlink on a selection, header/footer, footnote, table insert/rows/columns, find/replace via `.uno:ExecuteSearch` (`SearchItem.Command` 0 find, 2 replace, 3 replace all).
- No effect on Android: `.uno:GrafMode`, `.uno:GrafTransparence`, `GrafLuminance/Contrast`, `GrafFilter*`, `.uno:InsertObjectChart`, `.uno:InsertAnnotation`, `.uno:Line`, `.uno:InsertHorizontalRule` (no visible result).
- Text boxes/shapes: LibreOffice reports `CALLBACK_CONTEXT_CHANGED` (39) with `...TextDocument DrawText` while the caret is inside a shape's text (`Draw` = shape merely selected); `inTextBox` mirrors that. A double click on the empty inside of a selected shape does not enter it, but `Enter` (key 1280) on a selected shape starts the text edit with the caret at the end (never send it when no shape is selected). A double click needs a first click at the same spot (`selectWordAt`). In shape text edit LO sends the new word selection and then an empty one: the overlay keeps the selection until the next tap/key (`selHoldUntil`). `setGraphicSelection` drags inside a text edit act as text selection: leave the edit with Escape (1281) first, wait ~200 ms, then drag.
- Tables: callback 44 delivers `{"columns":{left,right,tableOffset,entries[{position}]},"rows":{...}}` (inner borders, relative to `tableOffset`; `"{ }"` outside tables) -> `TableGeom` (outer edges included). Context 39 reads `Table` inside tables. Insert/delete at a border: click a neighbouring cell with a mouse event, then `.uno:InsertColumnsBefore/After`, `InsertRowsBefore/After`, `DeleteColumns`, `DeleteRows`. Alt+arrow keys (`Keys.ALT` = 0x4000 OR'ed into the key code) resize the caret cell's column/row border in steps of ~283 twips; Alt+Left shrinks the whole table (the app widens the last column back). Mouse drags on borders do nothing in LOK. Working commands: `.uno:BackgroundColor` (cell), `SplitCell` (args Amount, HorizontalFlag), `SetOptimalColumnWidth/RowHeight`, `DistributeRows`, `SelectTable`; `.uno:SetBorderStyle` and `BorderOuter` do nothing without struct args (borders go through `TablePatch`).
- Table bubbles hide while zoomed so far out that they would be larger than the cells (radius < 5 dp).
- Saving rewrites the whole file through LibreOffice (SmartArt/charts may change). Writes go to a new copy by default; overwriting the original is explicit.

- Spelling: LibreOffice's own spell check does nothing here (`.uno:SpellOnline` is on by default, but the Hunspell service never opens a dictionary and no squiggles render), so the app checks the text itself. Text comes from `saveAs(txt)`; replacing uses `.uno:ExecuteSearch` with `SearchItem.AlgorithmType2` as **short** 2 (regex, long is ignored), `SearchItem.TransliterateFlags` 0 (= case-sensitive), pattern `\bword\b`.
- LibreOffice sends a cursor callback only when the caret moves, so a fresh/empty document had no caret: `ensureCursor` nudges (CharRight/CharLeft; in an empty document types a character, waits, undoes it and hides the resulting redo entry with `ghostRedo`).
- Clipboard: cut/copy/paste go through the phone's `ClipboardManager` (`DocumentViewState.copy/paste`): copy puts plain text + HTML there (read via `getTextSelection` before the uno command) and also runs LibreOffice's own Copy/Cut; paste uses LibreOffice's clipboard when the phone clipboard is still our own copy (`ownClipStamp` = clip timestamp, keeps tables/pictures exact), else picture (`onPasteImage` -> `insertImage`), HTML (`Document.paste("text/html")`, a lone `<p>` is unwrapped, else it leaves an empty paragraph) or plain text. Debug hooks: `--es setclip <text> [--es setclip_html <html>]`, `--ez setclip_image true`, `--ez paste true`, `--ez copy true [--ez cut true]`, `--ez getclip true`.
- Character formatting without a selection applies to what is typed next (no `.uno:SelectWord`).
- Document language is reported in `.uno:LanguageStatus` state (`German (Germany);de-DE`).

## Engine setup
Run `scripts/fetch-engine.sh` once (it also runs `scripts/fetch-dictionaries.sh`: 47 Hunspell dictionaries from the npm `dictionary-*` packages into `assets/dict`, about 145 MB unpacked): downloads the TDF LibreOffice APK from F-Droid (arm64), unpacks libs + assets into `app/src/main`, then `scripts/fetch-fonts.sh` adds DejaVu, Comic Neue and Anton. All of it is gitignored (`engine/`, `app/src/main/jniLibs`, `app/src/main/assets`). Bump `versionCode` when the unpacked assets change (they are copied to the data dir once per version).

## Commands
- Build: `./gradlew :app:assembleDebug` (needs a full JDK via `org.gradle.java.home` in `~/.gradle/gradle.properties`, plus NDK `29.0.14206865` and CMake `3.31.6` from the SDK manager for the spelling library). The debug build has the application id `com.matnotes.app.debug` so it installs next to the signed release app (adb component: `com.matnotes.app.debug/com.matnotes.app.MainActivity`, data dir `/sdcard/Android/data/com.matnotes.app.debug/files`).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Debug-build adb hooks (extras on `com.matnotes.app/.MainActivity`): `--es uno <cmd> [--es unoargs <json>]`, `--es insert_image <path in app files dir>`, `--es shape <.uno:...>`, `--es link "text|url"`, `--es zoom <factor>`, `--es dtap x,y` (double tap, view px), `--ei key <awt code>`, `--es mousetw type,x,y,count,buttons,modifier` (document twips), `--ez dump true` (logs state to tag `MatNotesDump`; LO callbacks are logged to tag `MatNotesCb`). Open a test file with `am start -a android.intent.action.VIEW -d file:///sdcard/Android/data/com.matnotes.app/files/<name> -t <docx mime>`.

## Device testing rules
- The test phone is the user's own phone. Before any scripted taps or `input text`, check the foreground app (`dumpsys activity activities | grep topResumedActivity`) and stop if it is not MatNotes: blind input can land in other apps (chats!).
- Never open the user's personal documents (file picker "recent" list shows them). Use own test files in `/sdcard/Android/data/com.matnotes.app/files/` or new documents.

## Releases and signing
Tag `v*` runs `.github/workflows/release.yml` (signed APK as GitHub Release). **Always the same keystore** (see `SIGNING.md`): a different signature makes updates fail with "App not installed". Bump `versionCode` for every release.

## Rules
- Never commit secrets, keystores, or private sample docs (`samples/`).
- Do not restructure architecture without being asked. No linter/formatter additions unless asked.
- Push straight to `main` when asked; never open PRs.

## Milestones
Done: engine spike, viewer, editor, ribbon, pictures/shapes, page setup, storage, share. Also done: text boxes, tables (bubbles, drag, Table tab, borders/looks). Next ideas: signed release pipeline (GitHub Actions + Releases), comments, table of contents (needs a dialog-free path), more shape formatting.
