// Applies migrations/NNN_*.sql once each (recorded with a SHA-256; an edited applied file aborts).
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { assertConfig } = require('./config');
const db = require('./db');

async function migrate() {
    assertConfig();
    await db.query('CREATE TABLE IF NOT EXISTS schema_migrations (filename text PRIMARY KEY, sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())');
    const dir = path.join(__dirname, '..', 'migrations');
    const files = fs.readdirSync(dir).filter((f) => /^\d+_.+\.sql$/.test(f)).sort();
    const applied = new Map((await db.query('SELECT filename, sha256 FROM schema_migrations')).rows.map((r) => [r.filename, r.sha256]));
    for (const f of files) {
        const sql = fs.readFileSync(path.join(dir, f), 'utf8');
        const sha = crypto.createHash('sha256').update(sql).digest('hex');
        if (applied.has(f)) {
            if (applied.get(f) !== sha) throw new Error(`migration ${f} was edited after it was applied`);
            continue;
        }
        await db.transaction(async (c) => {
            await c.query(sql);
            await c.query('INSERT INTO schema_migrations (filename, sha256) VALUES ($1, $2)', [f, sha]);
        });
        console.log(`applied ${f}`);
    }
}

module.exports = { migrate };

if (require.main === module) {
    migrate().then(() => db.close()).catch((e) => { console.error(e); process.exit(1); });
}
