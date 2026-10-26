# MatNotes sync server

Zero-knowledge sync backend (Express + Postgres on Neon). It stores only ciphertext and public keys: file contents, file
and folder names are encrypted on the phone (see `../AGENTS.md`, "Cloud sync"). Passwords never reach the server; it only sees
a key derived from them.

## Run

```
npm install
DATABASE_URL=... JWT_SECRET=<32+ chars> npm start      # migrates, then serves /api on PORT (default 3000)
```

Env: `DATABASE_URL`, `JWT_SECRET` (required); `DATABASE_SSL_REJECT_UNAUTHORIZED=false` on Neon; optional `MAX_FILE_BYTES` (25 MB),
`USER_QUOTA_BYTES` (200 MB, counted against the folder owner), `KEEP_VERSIONS` (2), `ACCESS_TOKEN_SECONDS`, `REFRESH_TOKEN_DAYS`.

## Deploy (Neon + Render)

1. Neon: create a **new** project (not the productivity-tracker one), copy the *pooled* connection string.
2. Render: New, Blueprint, pick this repo (`render.yaml`); set `DATABASE_URL` to the Neon string, `JWT_SECRET` is generated. Deploys on every push that touches `server/`; `npm start` migrates first.
3. Check `https://<service>.onrender.com/api/health`.
4. App: set the GitHub repository variable `MATNOTES_SYNC_URL` to `https://<service>.onrender.com/api` (Settings, Secrets and variables, Actions, Variables) before the next release tag; local builds use `-PsyncUrl=...`.

Render's free plan sleeps when idle: the first request afterwards takes up to a minute (the app waits 30 s to connect, 90 s to read).

## Test

Needs a throwaway local Postgres, never Neon:

```
podman run -d --name matnotes-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=mn -p 54330:5432 docker.io/library/postgres:16-alpine
TEST_DATABASE_URL=postgres://postgres:test@localhost:54330/mn npm test
```

## API (all under `/api`, JSON unless noted)

| Route | Purpose |
|---|---|
| `POST /auth/params`, `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` | account and sessions |
| `POST /auth/password`, `/auth/recover/start`, `/auth/recover/finish`, `/auth/delete` | password change, recovery with the recovery key, account deletion |
| `GET /users/lookup?email=` | public key of another user (to wrap a folder key) |
| `POST /folders`, `PATCH/DELETE /folders/:fid` | folders (client chooses the id) |
| `POST /folders/:fid/members`, `/accept`, `/decline`, `DELETE /folders/:fid/members/:uid` | invite, answer, remove |
| `PUT /folders/:fid/docs/:did` (octet-stream, `If-Match`, `X-Enc-Name`, `X-Wrapped-Key`), `GET .../blob`, `DELETE .../docs/:did` | documents |
| `GET /sync/changes?since=N` | everything changed after cursor N |

## Invariants

- Every writer that bumps `change_seq` runs inside `db.writeTransaction` (global advisory lock), so sequence order equals commit
  order and the sync cursor never skips a row.
- Documents change only with `If-Match: <current version>` (409 otherwise); `0` creates.
- Migrations are append-only (`schema_migrations` stores a SHA-256 per applied file).
