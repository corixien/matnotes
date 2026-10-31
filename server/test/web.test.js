const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

test('web app is served with isolation headers, SPA fallback, and the API is untouched', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mnweb-'));
    fs.mkdirSync(path.join(dir, 'assets'));
    fs.writeFileSync(path.join(dir, 'index.html'), '<!doctype html><title>x</title>');
    fs.writeFileSync(path.join(dir, 'assets', 'a.js'), '1');
    process.env.WEB_DIR = dir;
    delete require.cache[require.resolve('../src/web')];
    delete require.cache[require.resolve('../src/app')];
    const { createApp } = require('../src/app');
    const server = createApp().listen(0);
    const base = `http://127.0.0.1:${server.address().port}`;
    try {
        const home = await fetch(base + '/');
        assert.equal(home.status, 200);
        assert.equal(home.headers.get('cross-origin-opener-policy'), 'same-origin');
        assert.equal(home.headers.get('cross-origin-embedder-policy'), 'require-corp');
        assert.equal(home.headers.get('cache-control'), 'no-cache');
        const spa = await fetch(base + '/some/route');
        assert.equal(spa.status, 200);
        assert.match(await spa.text(), /<title>x<\/title>/);
        const asset = await fetch(base + '/assets/a.js');
        assert.match(asset.headers.get('cache-control'), /immutable/);
        const api = await fetch(base + '/api/users/lookup?email=a@b.c');
        assert.equal(api.status, 401);
        assert.equal(api.headers.get('cache-control'), 'no-store');
        assert.equal(api.headers.get('cross-origin-opener-policy'), null);
    } finally { server.close(); delete process.env.WEB_DIR; }
});
