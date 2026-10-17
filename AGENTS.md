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
- Saving rewrites the whole file through LibreOffice (SmartArt/charts may change). Writes go to a new copy by default; overwriting the original is explicit.

## Engine setup
Run `scripts/fetch-engine.sh` once: downloads the TDF LibreOffice APK from F-Droid (arm64), unpacks libs + assets into `app/src/main`, then `scripts/fetch-fonts.sh` adds DejaVu, Comic Neue and Anton. All of it is gitignored (`engine/`, `app/src/main/jniLibs`, `app/src/main/assets`). Bump `versionCode` when the unpacked assets change (they are copied to the data dir once per version).

## Commands
- Build: `./gradlew :app:assembleDebug` (needs a full JDK via `org.gradle.java.home` in `~/.gradle/gradle.properties`).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Debug-build adb hooks (extras on `com.matnotes.app/.MainActivity`): `--es uno <cmd> [--es unoargs <json>]`, `--es insert_image <path in app files dir>`, `--es shape <.uno:...>`, `--es link "text|url"`, `--es zoom <factor>`. Open a test file with `am start -a android.intent.action.VIEW -d file:///sdcard/Android/data/com.matnotes.app/files/<name> -t <docx mime>`.

## Device testing rules
- The test phone is the user's own phone. Before any scripted taps or `input text`, check the foreground app (`dumpsys activity activities | grep topResumedActivity`) and stop if it is not MatNotes: blind input can land in other apps (chats!).
- Never open the user's personal documents (file picker "recent" list shows them). Use own test files in `/sdcard/Android/data/com.matnotes.app/files/` or new documents.

## Rules
- Never commit secrets, keystores, or private sample docs (`samples/`).
- Do not restructure architecture without being asked. No linter/formatter additions unless asked.
- Push straight to `main` when asked; never open PRs.

## Milestones
Done: engine spike, viewer, editor, ribbon, pictures/shapes, page setup, storage, share. Next ideas: signed release pipeline (GitHub Actions + Releases), table contextual tab, comments, table of contents (needs a dialog-free path), more shape formatting.
