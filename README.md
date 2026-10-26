# MatNotes

Android app (phone + tablet) to open, edit and export Word documents (`.docx`) with maximum layout fidelity and a Word-like mobile UI. Local first, German + English; an optional account adds end-to-end encrypted cloud sync and shared folders. Rendering and editing run on the real LibreOffice engine (LibreOfficeKit), so complex layouts (text boxes, wrapped images, shapes, tables) are displayed as LibreOffice lays them out.

## Features

- **Open:** file picker, "Open with", share target, recent files, `Documents/MatNotes` list on the start screen. New blank document with Word defaults (A4, Calibri 11 pt, 2.54 cm margins, 8 pt paragraph spacing).
- **Edit:** typing (IME + hardware keyboard + Word shortcuts), selection handles, floating cut/copy/paste bar, find and replace, undo/redo, format painter.
- **Account and cloud sync** (profile button, top right): register with name, e-mail and password; your documents sync between your devices. Documents, names and keys are encrypted on the phone, the server only sees encrypted data. You get a recovery key at registration, the only way back in after a forgotten password. **Shared folders:** invite another account by e-mail; everyone in the folder works on the same documents (stored once in the cloud, one copy per phone). Conflicting edits keep both versions.
- **Spelling check** (Review tab): detects the language from the text (about 47 dictionaries: all major European languages plus Turkish, Vietnamese, Russian, ...), you can change it, then step through the mistakes with suggestions; replaces every occurrence. Offline, Hunspell.
- **Ribbon** in Word order (Home, Insert, Layout, References, Review, View, contextual Picture/Shape tab) with grouped icon buttons, editable font and size boxes, colour pickers, styles gallery, change case, paragraph shading, lists, alignment, line spacing.
- **Tables:** speech-bubble buttons at the table: "+" at every column/row border inserts exactly there, "-" on each column/row deletes it; drag a border line to change column width or row height; contextual Table tab with merge/split, cell colour, fit/even rows and columns, vertical alignment, border presets (all, outside, inside, none, single edges; line style, width, colour), 8 table looks (grid, blue, green, orange, striped, minimal, dark, ...) and delete table.
- **Text boxes:** tap enters with the caret at the end (or at the tapped spot), double tap selects a word, hold and drag moves the box. Pages sit in a grey margin, so zooming out works on every side.
- **Insert:** pictures, tables (rows/columns/merge), shapes (about 30, inserted in the middle of the view), text box, symbols, link, header/footer, page number, date, footnote/endnote, page and column break.
- **Pictures and shapes:** drag, resize (corners keep the aspect ratio, edges resize one side), rotate, flip, wrap modes (inline, square, tight, top and bottom, in front, behind), anchoring, arrange, fill and outline colour for shapes.
- **Page setup:** margins (presets and custom), orientation, size (A3, A4, A5, B5, Letter, Legal).
- **Save:** documents live in `Documents/MatNotes` (MediaStore, no permission). Save writes there (new documents ask for a name); "Save a copy" uses the file picker; "Overwrite original" is explicit and warned.
- **Share / export:** `.docx`, `.odt` or `.pdf`, via the system share sheet or as an e-mail attachment (opens the mail app's compose screen). Print through the Android print dialog.
- **View:** pinch zoom 20 %–600 % (100 % = real paper size), fast-scroll handle, page indicator, dark mode for the UI (pages stay white).

## What is not possible (yet)

LibreOffice rewrites the whole file on save, so complex content (SmartArt, charts) can change. Gaps compared with Word:

| Area | Missing |
|---|---|
| Start | multilevel lists, sort, borders, font dialog (character spacing), advanced find |
| Insert | **SmartArt** (LibreOffice cannot create it; existing SmartArt is displayed), charts (insert command has no effect in LibreOffice for Android), WordArt, equations, bookmarks, cross-references, comments, cover page |
| Tables | per-cell borders, Word's built-in table styles, dragging the outer edges; borders and looks reload the document (undo history resets) |
| Layout | columns, hyphenation, line numbers, watermark, page colour |
| References | table of contents, captions, citations, index |
| Review | comments, thesaurus, translate, protect, red wavy underlines while typing (spelling is a separate check) |
| View | ruler, gridlines, navigation pane, multi-page and reading mode |
| Pictures | colour effects, filters, transparency, crop, borders, shadows (the LibreOffice commands do nothing on Android) |
| Fonts | Microsoft fonts cannot be bundled. Metric clones render exactly (Calibri, Cambria, Arial, Arial Narrow, Times New Roman, Courier New); other Word font names are kept in the file but shown with a stand-in |

Only arm64 devices (the LibreOffice engine is bundled for arm64 only).

## Cloud sync server

The backend lives in [`server/`](server/README.md) (Node + Postgres on Neon, deployed on Render with `render.yaml`).

## Stack

Kotlin, Jetpack Compose, LibreOfficeKit (JNI, prebuilt libs taken from the official TDF LibreOffice Android APK), coroutines. No backend. Distribution: signed APK on GitHub Releases (tag `v*`, see `SIGNING.md`).

## Develop

Requirements: full JDK 21 (with `javac`), Android SDK (platform 37, build-tools 35, NDK 29.0.14206865, CMake 3.31.6), `adb` for device runs.

```bash
./scripts/fetch-engine.sh          # once: LibreOffice engine + fonts into app/src/main (gitignored)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set the JDK in `~/.gradle/gradle.properties` (`org.gradle.java.home=...`) and the SDK in `local.properties` (`sdk.dir=...`, gitignored).

Debug builds accept adb intent extras for testing (`uno`, `unoargs`, `insert_image`, `shape`, `link`, `zoom`); see `AGENTS.md`.

## Test documents

Put private test `.docx` files in `samples/` (gitignored). Never commit them.

## License notes

Hunspell (MPL 1.1 / GPL 2 / LGPL 2.1, `app/src/main/cpp/hunspell`) and its dictionaries (each under its own open-source licence, license file next to it in the APK assets), LibreOffice (MPL-2.0) engine and JNI classes (`org.libreoffice.kit`), DejaVu (Bitstream Vera licence), Comic Neue and Anton (SIL OFL), Carlito/Caladea/Liberation (OFL/GPL with font exception) are bundled at build time and not committed.
