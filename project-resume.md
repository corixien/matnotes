# Project Resume

> Instruction for Claude: read this whole file first, then every file under "Must read". Do not change anything before you understand the project. Keep this file current (see "Maintenance rules"). Answer the owner in German, short and technical.

Last updated: 2026-10-26

## 1. What this project is
MatNotes: Android app (phone + tablet, arm64 only) to open, edit and export `.docx` with a Word-like mobile UI. Priority 1: layout fidelity. Engine: LibreOfficeKit (prebuilt libs from the official TDF LibreOffice APK). Local only, no accounts, DE/EN. Repo `corixien/matnotes` (public), push straight to `main`, never PRs. Stage: working app on the owner's phone, feature work and polish. Package `com.matnotes.app`, Kotlin + Jetpack Compose.

## 2. Must read
1. `AGENTS.md` - code map, engine facts (hard-won, do not re-learn), commands, adb test hooks, device rules.
2. `README.md` - feature list and the honest list of gaps versus Word.

## 3. Navigate
- `app/src/main/java/com/matnotes/app/` - all app code (table of files in `AGENTS.md`).
- `app/src/main/res/values{,-de}/strings.xml` - keep DE and EN in sync.
- `scripts/fetch-engine.sh` (engine + fonts), `scripts/devtest-helpers.sh` (adb test helpers). Generated and gitignored: `engine/`, `app/src/main/jniLibs`, `app/src/main/assets`. `samples/` = private test docs (gitignored).

## 4. Commands
```
./scripts/fetch-engine.sh                       # once per checkout
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Needs a full JDK 21 (`org.gradle.java.home` in `~/.gradle/gradle.properties`) and the Android SDK (`local.properties`). Debug builds expose adb intent hooks (list in `AGENTS.md`: uno, shape, dtap, key, mousetw, dump, ...). In a Claude Code session with the context-mode plugin, Gradle/adb pipelines must go through `ctx_execute`.

## 5. Invariants and gotchas
- LO input is asynchronous, no tile invalidation: stale-while-revalidate tiles + settle timer.
- No font substitution on Android: only bundled fonts render exactly.
- Page setup, table borders and table looks have no dialog-free LO command: they patch the exported docx and reload (`PageSetup.kt`, `TablePatch.kt`; undo history and caret are lost). The caret's table is found by typing the marker `MNTBLMARK7Q` into its cell first.
- Text boxes/shapes follow LO's context callback 39 (`DrawText`); tables report their column/row borders in callback 44 (`TableGeom`). Details and the key tricks (Enter enters a selected shape, Escape before dragging out of text edit, Alt+arrows resize table borders) are in `AGENTS.md`.
- The view keeps a 28 dp grey margin around the pages (`clamp`), so zooming out works on every side.
- Save writes a copy to `Documents/MatNotes`; overwriting the original is explicit.
- Test phone = the owner's own phone: check the foreground app before any scripted input (`guard`/`chk` in the helpers), never open personal documents, never type blind. Skip testing while another app is in front.
- Never commit secrets, keystores, private samples.
- Git push to GitHub currently fails ("Invalid username or token"): the owner is fixing the credentials. `main` is many commits ahead of `origin/main`; push when asked and when it works.

## 6. Current state (2026-10-18)
Working and verified on the phone: open/new/save/save-as/overwrite, share (docx/odt/pdf, mail), print, ribbon (Start, Insert, Layout, References, Review, View + contextual Picture/Shape/Table tabs), pictures (move, corner resize keeps aspect ratio), shapes (gallery with drawn previews), text boxes (tap enters with caret at end or at the tapped word, double tap selects a word, hold and drag moves the box, selection stays visible), tables ("+" bubbles at every border and "-" bubbles per row/column with tails, drag a border to change column width/row height, Table tab: merge/split, cell colour, fit/even, vertical alignment, borders and 8 table looks, delete), standard selection menu (cut/copy/paste/all), grey scroll margin around pages, page setup, find/replace, fast scroller.
Untested on device: Table-tab border presets (all/outside/inside/none/single edges, line style/width/colour) and the other 7 table looks (only "Dunkel" was applied and worked); left-border drag of a middle column keeps the table width by widening the last column (differs from Word).
Known gaps: no cell-level borders, no table styles from Word templates, no column/row drag for outer edges, comments, table of contents, SmartArt creation, charts, WordArt.

## 7. Next ideas
Apply and verify the remaining border presets, tablet layout polish, comments, table of contents (needs a dialog-free path), more shape formatting, comments, table of contents (needs a dialog-free path).

## 2026-10-19 changes (v0.2.0)
- Release pipeline: tag `v*` -> `.github/workflows/release.yml` -> signed APK on a GitHub Release. Always the same keystore, rules in `SIGNING.md`. Secrets set by the owner. The phone has the signed release app; debug builds are `com.matnotes.app.debug` (installs next to it; helpers in `scripts/devtest-helpers.sh` use it).
- Caret shows in fresh/empty documents (`ensureCursor`), character formats apply from the caret on (no word selection), one pencil button in the viewer, slimmer selection toolbar (hidden during the spelling check), "Seitenbreite" = same grey gap left/right/top (`fitWidth`).
- Spelling check (Review tab): own Hunspell (NDK, `cpp/`) + 47 bundled dictionaries (`scripts/fetch-dictionaries.sh`), language detection, change-language dialog, per-word suggestions, replace all. Details and why not LibreOffice's own check: `AGENTS.md`. Missing languages: Finnish, Indonesian, Maltese, Albanian (no Hunspell package).
- Verified on the phone: caret, bold from caret, fit width, spelling flow (German and English detected, suggestion replaced all occurrences, selection toolbar hidden). Untested: Table-tab border presets, tablet layout.
- Not started: comments, table of contents.

- 2026-10-20 (v0.2.1): cut/copy/paste use the phone clipboard (text + HTML out; picture, HTML, text in), Start-tab paste button works. Details in `AGENTS.md`.

## 2026-10-26: cloud sync (in progress, not released)
Built and pushed to `main`, not yet in a release: `server/` (accounts, folders, sharing, encrypted documents, change feed; 23 integration tests against local Postgres), `sync/` (crypto with JVM unit tests, account, sync engine, planner tests), profile button + account dialog, folders/sharing dialog, shared folders on the start screen, folder choice when saving a new document. Design and invariants: `AGENTS.md` "Cloud sync".
**Open, in this order:**
1. Device test (phone was not connected when this was written): `adb reverse tcp:3000 tcp:3000`, start the server locally (`server/README.md`), build with `-PsyncUrl=http://127.0.0.1:3000/api`; register, recovery key page, login, sync of a new doc, second install (release app and debug app are two "devices": different files), shared folder invite/accept, conflict copy, delete, password change, account deletion.
2. Owner creates the Neon project and the Render service (`server/README.md` "Deploy"), sets `MATNOTES_SYNC_URL` as repository variable.
3. Bump `versionCode`/`versionName`, tag, release (same keystore, `SIGNING.md`).
Not built: PDF export into a folder (the engine syncs `.pdf` files, the app cannot create them in a folder yet), key rotation when a member is removed, restore of older versions (the server keeps 2), background sync with WorkManager (sync runs at sign-in, on resume, after a save and by button).

## Maintenance rules
Update this file when structure, commands, invariants or state change. Bump "Last updated". Keep it short enough to read in one go.
