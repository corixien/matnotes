const test = require('node:test');
const assert = require('node:assert');
const h = require('./helper');

test('auth', { skip: h.skip }, async (t) => {
    const s = await h.setup();
    t.after(s.close);
    const authKey = h.rb(32);
    const recoveryAuthKey = h.rb(32);
    const reg = {
        name: 'Anna', email: 'Anna@Example.com', authKey, kdfSalt: h.rb(16), kdfIterations: 600000, publicKey: h.rb(420),
        encPrivateKey: h.rb(1900), recoveryAuthKey, encPrivateKeyRecovery: h.rb(1900),
    };

    await t.test('register, duplicate, validation', async () => {
        const r = await s.call('POST', '/auth/register', { body: reg });
        assert.equal(r.status, 201);
        assert.ok(r.data.accessToken && r.data.refreshToken);
        assert.equal(r.data.user.email, 'anna@example.com');
        assert.equal((await s.call('POST', '/auth/register', { body: { ...reg, email: 'ANNA@example.com' } })).status, 409);
        assert.equal((await s.call('POST', '/auth/register', { body: { ...reg, email: 'x@y.de', kdfIterations: 10 } })).status, 400);
        assert.equal((await s.call('POST', '/auth/register', { body: { ...reg, email: 'x@y.de', name: '' } })).status, 400);
    });

    await t.test('params are stable for unknown e-mails and real for known ones', async () => {
        const known = await s.call('POST', '/auth/params', { body: { email: 'anna@example.com' } });
        assert.equal(known.data.salt, reg.kdfSalt);
        const a = await s.call('POST', '/auth/params', { body: { email: 'nobody@example.com' } });
        const b = await s.call('POST', '/auth/params', { body: { email: 'nobody@example.com' } });
        assert.equal(a.status, 200);
        assert.equal(a.data.salt, b.data.salt);
    });

    await t.test('login, wrong password, me', async () => {
        const bad = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey: h.rb(32) } });
        assert.equal(bad.status, 401);
        assert.equal((await s.call('POST', '/auth/login', { body: { email: 'nobody@example.com', authKey: h.rb(32) } })).status, 401);
        const ok = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey } });
        assert.equal(ok.status, 200);
        assert.equal(ok.data.user.encPrivateKey, reg.encPrivateKey);
        const me = await s.call('GET', '/auth/me', { token: ok.data.accessToken });
        assert.equal(me.data.user.name, 'Anna');
        assert.equal((await s.call('GET', '/auth/me')).status, 401);
        assert.equal((await s.call('GET', '/auth/me', { token: 'garbage' })).status, 401);
    });

    await t.test('refresh rotates; reuse of an old token revokes everything', async () => {
        const login = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey } });
        const r1 = await s.call('POST', '/auth/refresh', { body: { refreshToken: login.data.refreshToken } });
        assert.equal(r1.status, 200);
        assert.notEqual(r1.data.refreshToken, login.data.refreshToken);
        const reuse = await s.call('POST', '/auth/refresh', { body: { refreshToken: login.data.refreshToken } });
        assert.equal(reuse.status, 401);
        assert.equal((await s.call('POST', '/auth/refresh', { body: { refreshToken: r1.data.refreshToken } })).status, 401);
    });

    await t.test('logout revokes the refresh token', async () => {
        const login = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey } });
        await s.call('POST', '/auth/logout', { body: { refreshToken: login.data.refreshToken } });
        assert.equal((await s.call('POST', '/auth/refresh', { body: { refreshToken: login.data.refreshToken } })).status, 401);
    });

    await t.test('password change needs the current key and revokes other sessions', async () => {
        const login = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey } });
        const next = { newAuthKey: h.rb(32), newKdfSalt: h.rb(16), newKdfIterations: 600000, newEncPrivateKey: h.rb(1900) };
        assert.equal((await s.call('POST', '/auth/password', { token: login.data.accessToken, body: { currentAuthKey: h.rb(32), ...next } })).status, 401);
        const ok = await s.call('POST', '/auth/password', { token: login.data.accessToken, body: { currentAuthKey: authKey, ...next } });
        assert.equal(ok.status, 200);
        assert.equal((await s.call('POST', '/auth/refresh', { body: { refreshToken: login.data.refreshToken } })).status, 401);
        assert.equal((await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey } })).status, 401);
        assert.equal((await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey: next.newAuthKey } })).status, 200);
        reg.authKey = next.newAuthKey;
    });

    await t.test('recovery with the recovery key sets a new password', async () => {
        const start = await s.call('POST', '/auth/recover/start', { body: { email: 'anna@example.com' } });
        assert.equal(start.data.encPrivateKeyRecovery, reg.encPrivateKeyRecovery);
        const fakeStart = await s.call('POST', '/auth/recover/start', { body: { email: 'nobody@example.com' } });
        assert.equal(fakeStart.status, 200);
        const next = { newAuthKey: h.rb(32), newKdfSalt: h.rb(16), newKdfIterations: 600000, newEncPrivateKey: h.rb(1900) };
        assert.equal((await s.call('POST', '/auth/recover/finish', { body: { email: 'anna@example.com', recoveryAuthKey: h.rb(32), ...next } })).status, 401);
        assert.equal((await s.call('POST', '/auth/recover/finish', { body: { email: 'anna@example.com', recoveryAuthKey, ...next } })).status, 200);
        assert.equal((await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey: next.newAuthKey } })).status, 200);
        reg.authKey = next.newAuthKey;
    });

    await t.test('account deletion', async () => {
        const login = await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey: reg.authKey } });
        assert.equal((await s.call('POST', '/auth/delete', { token: login.data.accessToken, body: { authKey: h.rb(32) } })).status, 401);
        assert.equal((await s.call('POST', '/auth/delete', { token: login.data.accessToken, body: { authKey: reg.authKey } })).status, 200);
        assert.equal((await s.call('POST', '/auth/login', { body: { email: 'anna@example.com', authKey: reg.authKey } })).status, 401);
    });
});
