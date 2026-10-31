# MatNotes Web: plan

Goal: a browser version that behaves like the app. Desktop: top bar (menu/ribbon bar) layout. Phone browser: same structure as the app (start screen, viewer, floating toolbars, bottom ribbon). Same account, same end-to-end encrypted sync, same server.

## Decision: engine
| Option | Verdict |
|---|---|
| A. LibreOffice as WebAssembly in the browser (ZetaOffice / zetajs, MPL + MIT) | **Chosen.** Document is opened, edited and saved in the browser. The server never sees plaintext, so the zero-knowledge sync stays intact. Same layout engine as the app (LibreOffice), so layout fidelity is the same. |
| B. LibreOfficeKit tile server (Collabora-style) | Rejected. Server would need plaintext documents (breaks E2EE), needs 1+ GB RAM per session (Render free = 512 MB), and costs money per user. |
| C. Own docx renderer (docx-preview, SuperDoc) | Rejected. Layout fidelity is priority 1; none of them match LibreOffice for shapes, text boxes, SmartArt, charts. |

Spike result (2026-10-31, headless Chromium on this machine): `zetaoffice.net/demos/standalone` loads, `crossOriginIsolated` is true, Writer canvas with caret renders after about 60 s cold (about 50 MB download, cached afterwards). UI chrome (menubar, toolbars, sidebar) can be switched off through UNO configuration, formatting is driven by `.uno:` dispatch, the same command names the app already uses.

Known limits (be honest with the user):
- ZetaOffice renders into its own canvas (Qt/WASM). It is **not** the LibreOfficeKit tile API, so the app's Compose gestures, selection handles and tile cache cannot be copied 1:1. The ribbon and toolbars are rebuilt in HTML and call `.uno:` commands like the app does; selection handles and touch gestures on the canvas are LibreOffice's own.
- About 50 MB first load, about 800 MB RAM while running. Phones with little RAM may fail. Desktop is the primary target, phone layout works on recent phones.
- Needs HTTP headers `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp` on our pages; the WASM files come from `cdn.zetaoffice.net` (send CORP) or are self-hosted later (`soffice_base_url`).
- Fonts: browser has no bundled Anton/Comic Neue/DejaVu unless we load them into the WASM file system; same Roboto-fallback problem as Android. Bundled fonts are added in a later milestone.
- Tablet/phone Safari (iOS) support for SharedArrayBuffer is not guaranteed.

## Stack
- Vite + TypeScript, no UI framework (small, the ribbon is plain DOM/CSS; Compose screens map to a few views). Vitest for logic, Playwright for end-to-end.
- zetajs 1.2 (npm) + ZetaOffice WASM from `cdn.zetaoffice.net/zetaoffice_latest/` (version pinned later).
- WebCrypto (`crypto.subtle`) port of `Crypto.kt`/`Identity.kt`/`Vault`: PBKDF2-SHA256 600k, HKDF/HMAC split, AES-256-GCM with AAD, RSA-OAEP 3072. Wire format identical (cross-checked against the Kotlin tests and the real server).
- Storage: IndexedDB (documents, sync state; same tables as `SyncDb`), refresh token + sealed private key in IndexedDB as a non-extractable `CryptoKey`-wrapped blob (limit: weaker than Android Keystore, state in UI).
- Served by the existing Express server (`web/dist` as static files, same origin, no CORS; one Render service). Health stays `/api/health`.
- `web/src/planner.ts`: port of `SyncPlanner` with the same unit-test table.

## Screens (mirror the app)
1. Home: title, profile button top right, New / Open, recent list, shared folders, invitations.
2. Viewer/editor: document canvas, top bar (desktop) or app-style top bar + floating toolbars (phone), ribbon tabs Start, Insert, Layout, References, Review, View with the same groups and commands as `Ribbon.kt`.
3. Dialogs: account (sign in, register, recovery key page, recover), folders (invite with fingerprint), name dialog with folder choice, spelling panel (Hunspell via LibreOffice's own WASM checker, if it works; else deferred).

Desktop layout: fixed top bar with logo, document name, save/share, profile; ribbon below; canvas centred. Phone layout (<= 700 px): identical to the app (bottom ribbon, floating selection toolbar).

## Milestones (each ends with a test run)
| # | Content | Proof |
|---|---|---|
| W1 | `web/` scaffold, Express serves it with COOP/COEP, engine loader, open/save docx from file picker and drag and drop, new document, UI chrome hidden | Playwright: load, type text, bold, save bytes, reopen, text equal |
| W2 | Home screen, ribbon (Start, Insert, Layout, View first), desktop and phone layout | Playwright screenshots at 1280 and 390 px, command dispatch tests |
| W3 | Crypto port | Vitest vectors + compatibility test against the real server and against Kotlin-created data |
| W4 | Account dialogs, sync engine, planner, personal folder | Playwright with two browser contexts as two devices, conflict copy |
| W5 | Shared folders UI, invitations, fingerprint | Two accounts end to end |
| W6 | Remaining ribbon tabs, tables, shapes, spelling, fonts, PWA manifest | Feature checklist against `README.md` |
| W7 | Weakness review: XSS in names, file size limits, memory, IndexedDB corruption, token storage, offline, headers | Fix list in this file |

## Risks to retire early
1. Selection and editing through UNO on touch devices (W1/W2 spike on a real phone browser).
2. Saving: `storeToURL` into the WASM virtual file system, then read bytes out (W1).
3. Cold-start time (60 s measured headless): show progress, cache with the service worker.
