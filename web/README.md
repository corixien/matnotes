# MatNotes Web

Browser version of MatNotes: LibreOffice as WebAssembly (ZetaOffice), the same end-to-end encrypted sync and shared folders.
Plan and limits: [../docs/web-plan.md](../docs/web-plan.md). Developer notes: "Web version" in [../AGENTS.md](../AGENTS.md).

```
npm install                      # also copies zeta.js and generates strings
node scripts/fetch-dictionaries.mjs   # spell check dictionaries (once)
npm run dev                      # http://localhost:5173, /api is proxied to a local sync server on :3000
npm run build                    # web/dist, served by server/ (see ../server/README.md)
npm test && npm run e2e
```
The first start downloads about 50 MB (the engine, cached afterwards) and needs a recent desktop or phone browser with
`SharedArrayBuffer` (the server sends the required COOP/COEP headers).
