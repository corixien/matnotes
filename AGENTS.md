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

## Rules
- Never commit secrets, keystores, or private sample docs (`samples/`).
- Do not restructure architecture without being asked.
- No linter/formatter additions unless asked.
- Commands: added with the Gradle project skeleton.
