// Integration helpers. Needs a throwaway local Postgres: TEST_DATABASE_URL=postgres://postgres:test@localhost:54330/mn
const crypto = require('crypto');

const url = process.env.TEST_DATABASE_URL;
if (url && !/@(localhost|127\.0\.0\.1)[:/]/.test(url)) throw new Error('Refusing to run tests against a non-local database');

process.env.DATABASE_URL = url || '';
process.env.JWT_SECRET = 'test-secret-test-secret-test-secret-123456';
process.env.RATE_LIMIT_DISABLED = '1';
process.env.MAX_FILE_BYTES = String(64 * 1024);
process.env.USER_QUOTA_BYTES = String(200 * 1024);

const { createApp } = require('../src/app');
const { migrate } = require('../src/migrate');
const db = require('../src/db');

const rb = (n) => crypto.randomBytes(n).toString('base64');
const uuid = () => crypto.randomUUID();

async function setup() {
    await db.query('DROP SCHEMA public CASCADE');
    await db.query('CREATE SCHEMA public');
    await migrate();
    const server = createApp().listen(0);
    await new Promise((r) => server.once('listening', r));
    const base = `http://127.0.0.1:${server.address().port}/api`;

    async function call(method, path, { token, body, headers = {}, raw } = {}) {
        const h = { ...headers };
        if (token) h.Authorization = `Bearer ${token}`;
        let payload = raw;
        if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
        const res = await fetch(base + path, { method, headers: h, body: payload });
        const type = res.headers.get('content-type') || '';
        const data = type.includes('json') ? await res.json() : Buffer.from(await res.arrayBuffer());
        return { status: res.status, data, headers: res.headers };
    }

    async function register(name, email) {
        const r = await call('POST', '/auth/register', {
            body: {
                name, email, authKey: rb(32), kdfSalt: rb(16), kdfIterations: 600000, publicKey: rb(420),
                encPrivateKey: rb(1900), recoveryAuthKey: rb(32), encPrivateKeyRecovery: rb(1900), deviceName: 'test',
            },
        });
        if (r.status !== 201) throw new Error(`register failed ${r.status} ${JSON.stringify(r.data)}`);
        return { ...r.data, token: r.data.accessToken, authKey: null };
    }

    return { call, register, close: async () => { await new Promise((r) => server.close(r)); await db.close(); } };
}

const skip = url ? false : 'TEST_DATABASE_URL not set';

module.exports = { setup, rb, uuid, skip };
