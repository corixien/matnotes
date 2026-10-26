const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');
const h = require('./helper');

const blob = (n) => crypto.randomBytes(n);

test('folders, documents, sync feed', { skip: h.skip }, async (t) => {
    const s = await h.setup();
    t.after(s.close);
    const anna = await s.register('Anna', 'anna@example.com');
    const ben = await s.register('Ben', 'ben@example.com');
    const cara = await s.register('Cara', 'cara@example.com');
    const fid = h.uuid();

    const put = (u, folder, doc, ifMatch, data, extra = {}) => s.call('PUT', `/folders/${folder}/docs/${doc}`, {
        token: u.token, raw: data, headers: { 'Content-Type': 'application/octet-stream', 'If-Match': String(ifMatch), 'X-Enc-Name': h.rb(40), 'X-Wrapped-Key': h.rb(384), ...extra },
    });
    const feed = (u, since = 0) => s.call('GET', `/sync/changes?since=${since}`, { token: u.token });

    await t.test('create folder; only the owner is a member', async () => {
        const r = await s.call('POST', '/folders', { token: anna.token, body: { id: fid, encName: h.rb(40), wrappedKey: h.rb(384) } });
        assert.equal(r.status, 201);
        assert.equal((await s.call('POST', '/folders', { token: anna.token, body: { id: fid, encName: h.rb(40), wrappedKey: h.rb(384) } })).status, 409);
        const f = (await feed(anna)).data.folders;
        assert.equal(f.length, 1);
        assert.equal(f[0].role, 'owner');
        assert.equal(f[0].members[0].name, 'Anna');
        assert.equal((await feed(ben)).data.folders.length, 0);
    });

    const doc = h.uuid();
    const data1 = blob(1000);

    await t.test('upload, download, strangers get 404', async () => {
        const r = await put(anna, fid, doc, 0, data1);
        assert.equal(r.status, 200);
        assert.equal(r.data.version, 1);
        const g = await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: anna.token });
        assert.equal(g.status, 200);
        assert.deepEqual(g.data, data1);
        assert.equal(g.headers.get('x-version'), '1');
        assert.ok(g.headers.get('x-wrapped-key'));
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: anna.token, headers: { 'If-None-Match': '"1"' } })).status, 304);
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: ben.token })).status, 404);
        assert.equal((await put(ben, fid, h.uuid(), 0, data1)).status, 404);
    });

    await t.test('optimistic concurrency and version retention', async () => {
        assert.equal((await put(anna, fid, doc, 0, data1)).status, 409);
        const stale = await put(anna, fid, doc, 5, data1);
        assert.equal(stale.status, 409);
        assert.equal(stale.data.serverVersion, 1);
        assert.equal((await put(anna, fid, doc, 1, blob(900))).data.version, 2);
        assert.equal((await put(anna, fid, doc, 2, blob(800))).data.version, 3);
        // KEEP_VERSIONS = 2: version 1 is gone, 2 and 3 remain
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob?version=1`, { token: anna.token })).status, 404);
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob?version=2`, { token: anna.token })).status, 200);
        assert.equal((await put(anna, fid, doc, undefined, data1).catch(() => ({ status: 428 }))).status, 428);
    });

    await t.test('two writers with the same base version: exactly one wins', async () => {
        const d = h.uuid();
        await put(anna, fid, d, 0, blob(500));
        const rs = await Promise.all([put(anna, fid, d, 1, blob(500)), put(anna, fid, d, 1, blob(600)), put(anna, fid, d, 1, blob(700))]);
        assert.deepEqual(rs.map((r) => r.status).sort(), [200, 409, 409]);
    });

    await t.test('size limits and malformed input', async () => {
        assert.equal((await put(anna, fid, h.uuid(), 0, blob(65 * 1024))).status, 413);
        assert.equal((await put(anna, fid, h.uuid(), 0, blob(5))).status, 400);
        assert.equal((await put(anna, fid, 'not-a-uuid', 0, blob(100))).status, 400);
        assert.equal((await put(anna, fid, h.uuid(), 0, blob(100), { 'X-Enc-Name': '' })).status, 400);
    });

    await t.test('invite, accept: the new member sees old documents', async () => {
        const before = (await feed(anna)).data.cursor;
        assert.equal((await s.call('POST', `/folders/${fid}/members`, { token: ben.token, body: { userId: cara.user.id, wrappedKey: h.rb(384) } })).status, 404);
        assert.equal((await s.call('POST', `/folders/${fid}/members`, { token: anna.token, body: { userId: anna.user.id, wrappedKey: h.rb(384) } })).status, 400);
        assert.equal((await s.call('GET', '/users/lookup?email=ben@example.com', { token: anna.token })).data.id, ben.user.id);
        assert.equal((await s.call('GET', '/users/lookup?email=none@example.com', { token: anna.token })).status, 404);
        const inv = await s.call('POST', `/folders/${fid}/members`, { token: anna.token, body: { userId: ben.user.id, wrappedKey: h.rb(384) } });
        assert.equal(inv.status, 201);
        assert.equal((await s.call('POST', `/folders/${fid}/members`, { token: anna.token, body: { userId: ben.user.id, wrappedKey: h.rb(384) } })).status, 409);

        const pending = (await feed(ben)).data;
        assert.equal(pending.folders[0].status, 'invited');
        assert.ok(pending.folders[0].wrappedKey);
        assert.equal(pending.docs.length, 0, 'no documents before accepting');
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: ben.token })).status, 404);

        assert.equal((await s.call('POST', `/folders/${fid}/accept`, { token: ben.token })).status, 200);
        const after = (await feed(ben, pending.cursor)).data;
        assert.equal(after.folders[0].status, 'active');
        assert.ok(after.docs.length >= 2, 'documents older than the cursor are delivered on join');
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: ben.token })).status, 200);
        const annaView = (await feed(anna, before)).data;
        assert.equal(annaView.folders[0].members.length, 2);
    });

    await t.test('editors write; others see it through the cursor', async () => {
        const cur = (await feed(anna)).data.cursor;
        const r = await put(ben, fid, doc, 3, blob(300));
        assert.equal(r.status, 200);
        const ch = (await feed(anna, cur)).data;
        assert.deepEqual(ch.docs.map((d) => [d.id, d.version]), [[doc, 4]]);
        assert.equal(ch.cursor, r.data.cursor);
        assert.equal((await feed(anna, ch.cursor)).data.docs.length, 0);
    });

    await t.test('editors cannot manage the folder; decline and removal', async () => {
        assert.equal((await s.call('POST', `/folders/${fid}/members`, { token: ben.token, body: { userId: cara.user.id, wrappedKey: h.rb(384) } })).status, 403);
        assert.equal((await s.call('PATCH', `/folders/${fid}`, { token: ben.token, body: { encName: h.rb(40) } })).status, 403);
        assert.equal((await s.call('DELETE', `/folders/${fid}`, { token: ben.token })).status, 403);
        await s.call('POST', `/folders/${fid}/members`, { token: anna.token, body: { userId: cara.user.id, wrappedKey: h.rb(384) } });
        assert.equal((await s.call('POST', `/folders/${fid}/decline`, { token: cara.token })).status, 200);
        assert.equal((await feed(cara)).data.folders[0].status, 'removed');
        assert.equal((await s.call('POST', `/folders/${fid}/accept`, { token: cara.token })).status, 404);
        // re-invite after decline works
        assert.equal((await s.call('POST', `/folders/${fid}/members`, { token: anna.token, body: { userId: cara.user.id, wrappedKey: h.rb(384) } })).status, 201);
        assert.equal((await s.call('DELETE', `/folders/${fid}/members/${cara.user.id}`, { token: anna.token })).status, 200);

        const cur = (await feed(ben)).data.cursor;
        assert.equal((await s.call('DELETE', `/folders/${fid}/members/${anna.user.id}`, { token: ben.token })).status, 403);
        assert.equal((await s.call('DELETE', `/folders/${fid}/members/${ben.user.id}`, { token: anna.token })).status, 200);
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: ben.token })).status, 404);
        assert.equal((await put(ben, fid, doc, 4, blob(100))).status, 404);
        const f = (await feed(ben, cur)).data.folders[0];
        assert.equal(f.status, 'removed');
        assert.equal(f.wrappedKey, '');
    });

    await t.test('delete a document: tombstone, data freed, other clients learn it', async () => {
        const d = h.uuid();
        await put(anna, fid, d, 0, blob(400));
        const cur = (await feed(anna)).data.cursor;
        assert.equal((await s.call('DELETE', `/folders/${fid}/docs/${d}`, { token: anna.token, headers: { 'If-Match': '9' } })).status, 409);
        assert.equal((await s.call('DELETE', `/folders/${fid}/docs/${d}`, { token: anna.token, headers: { 'If-Match': '1' } })).status, 200);
        const ch = (await feed(anna, cur)).data.docs;
        assert.equal(ch[0].deleted, true);
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${d}/blob`, { token: anna.token })).status, 404);
        assert.equal((await s.call('DELETE', `/folders/${fid}/docs/${d}`, { token: anna.token, headers: { 'If-Match': '1' } })).status, 200);
    });

    await t.test('quota counts the folder owner, versions included', async () => {
        const q = await s.register('Quinn', 'quinn@example.com');
        const qf = h.uuid();
        await s.call('POST', '/folders', { token: q.token, body: { id: qf, encName: h.rb(40), wrappedKey: h.rb(384) } });
        for (let i = 0; i < 3; i++) assert.equal((await put(q, qf, h.uuid(), 0, blob(60 * 1024))).status, 200);
        const over = await put(q, qf, h.uuid(), 0, blob(60 * 1024));
        assert.equal(over.status, 413);
        assert.equal(over.data.error, 'quota');
        const ch = (await feed(q)).data.docs;
        assert.equal(ch.length, 3, 'the rejected upload left no row behind');
    });

    await t.test('delete a folder: members learn it, data gone', async () => {
        const cur = (await feed(anna)).data.cursor;
        assert.equal((await s.call('DELETE', `/folders/${fid}`, { token: anna.token })).status, 200);
        const ch = (await feed(anna, cur)).data;
        assert.equal(ch.folders[0].deleted, true);
        assert.equal((await s.call('GET', `/folders/${fid}/docs/${doc}/blob`, { token: anna.token })).status, 404);
        assert.equal((await put(anna, fid, h.uuid(), 0, blob(100))).status, 404);
    });

    await t.test('deleting an account tombstones its folders for the others', async () => {
        const d = await s.register('Dora', 'dora@example.com');
        const e = await s.register('Emil', 'emil@example.com');
        const f2 = h.uuid();
        await s.call('POST', '/folders', { token: d.token, body: { id: f2, encName: h.rb(40), wrappedKey: h.rb(384) } });
        await s.call('POST', `/folders/${f2}/members`, { token: d.token, body: { userId: e.user.id, wrappedKey: h.rb(384) } });
        await s.call('POST', `/folders/${f2}/accept`, { token: e.token });
        const cur = (await feed(e)).data.cursor;
        // account deletion needs the real auth key: register a user with a known one
        const authKey = h.rb(32);
        const reg = await s.call('POST', '/auth/register', { body: { name: 'Fred', email: 'fred@example.com', authKey, kdfSalt: h.rb(16), kdfIterations: 600000, publicKey: h.rb(420), encPrivateKey: h.rb(1900), recoveryAuthKey: h.rb(32), encPrivateKeyRecovery: h.rb(1900) } });
        const f3 = h.uuid();
        await s.call('POST', '/folders', { token: reg.data.accessToken, body: { id: f3, encName: h.rb(40), wrappedKey: h.rb(384) } });
        await s.call('POST', `/folders/${f3}/members`, { token: reg.data.accessToken, body: { userId: e.user.id, wrappedKey: h.rb(384) } });
        await s.call('POST', `/folders/${f3}/accept`, { token: e.token });
        const cur2 = (await feed(e, cur)).data.cursor;
        assert.equal((await s.call('POST', '/auth/delete', { token: reg.data.accessToken, body: { authKey } })).status, 200);
        const ch = (await feed(e, cur2)).data.folders;
        assert.equal(ch.length, 1);
        assert.equal(ch[0].id, f3);
        assert.equal(ch[0].deleted, true);
    });
});
