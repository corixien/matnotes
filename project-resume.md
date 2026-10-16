# Project Resume

> Instruction for Claude: read this whole file first. Then read every file under "Must read before any change". Do not modify anything until you have fully understood the project. Keep this file updated as you work (see "Maintenance rules").

Last updated: 2026-10-16

## 1. What this project is
MatNotes: Android app (phone + tablet) to open, edit and export `.docx`. Priority 1 is layout fidelity (shapes, text boxes, SmartArt, charts, wrapped images); unedited document parts must survive saving unchanged. Local only, no accounts, DE/EN. Repo `corixien/matnotes`. Stage: scaffold only, no code yet.

## 2. Must read before any change
1. `AGENTS.md` - goal, stack, milestones, out of scope, rules.
2. `README.md` - status and develop notes.

## 3. How to navigate
- `samples/` - private test `.docx` (gitignored, only `.gitkeep` tracked).
- `docs/` - not in git yet; `docs/spike.md` is planned for the fidelity spike results.
- No Gradle project yet.
- Stack plan: Kotlin + Jetpack Compose, LibreOfficeKit via JNI, SAF storage, bundled metric-compatible fonts (Carlito, Caladea, Liberation).
- Milestones: 1 fidelity spike (desktop `soffice` headless -> PDF; find LOK Android libs), 2 viewer, 3 editor basics, 4 images + wrap modes + tables, 5 shapes move/scale, 6 signed release pipeline.

## 4. Commands
None yet. Added with the Gradle project skeleton. Needs JDK 17+, Android SDK + NDK. Toolchain for the sibling project lives in `~/matlink-toolchain` (JDK 21, Gradle, SDK) and may be reusable.

## 5. Invariants and gotchas
- Never commit secrets, keystores or private sample docs.
- Out of scope: tracked changes, macros, mail merge, live collaboration, accounts, cloud.
- No architecture restructuring or linter/formatter without being asked.

## 6. Current state
Branch `main`, one commit (initial scaffold). Next: milestone 1 spike.

## Maintenance rules
Update this file when structure, commands, invariants or state change. Bump "Last updated".
