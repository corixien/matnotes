// Serves the built web app (web/dist) from the same origin as the API. The engine needs cross-origin isolation
// (SharedArrayBuffer), hence the two isolation headers on every page; the engine files come from a CDN that sends CORP.
const express = require('express');
const compression = require('compression');
const fs = require('fs');
const path = require('path');

const DIST = process.env.WEB_DIR || path.join(__dirname, '..', '..', 'web', 'dist');

function mountWeb(app) {
    if (!fs.existsSync(path.join(DIST, 'index.html'))) return false;
    // The engine (WebAssembly, pthread workers from blob: URLs, Emscripten needs eval) comes from the ZetaOffice CDN; everything else is same-origin.
    const CDN = process.env.ENGINE_CDN || 'https://cdn.zetaoffice.net';
    const csp = [
        "default-src 'self'",
        `script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval' blob: ${CDN}`,
        "worker-src 'self' blob:",
        `connect-src 'self' ${CDN}`,
        "img-src 'self' data: blob:",
        "style-src 'self' 'unsafe-inline'",
        "font-src 'self' data:",
        "object-src 'none'", "base-uri 'none'", "frame-ancestors 'none'", "form-action 'self'",
    ].join('; ');
    const isolate = (res) => res.set({
        'Content-Security-Policy': csp,
        'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
        'Cross-Origin-Opener-Policy': 'same-origin',
        'Cross-Origin-Embedder-Policy': 'require-corp',
        'Cross-Origin-Resource-Policy': 'same-origin',
        'Referrer-Policy': 'no-referrer',
        'X-Frame-Options': 'DENY',
    });
    app.use((req, res, next) => {
        if (req.path.startsWith('/api')) return next();
        isolate(res);
        next();
    });
    app.use(compression()); // text, wasm-free assets and the Hunspell dictionaries (about 3 MB each) shrink a lot
    app.use(express.static(DIST, {
        index: false,
        setHeaders: (res, file) => res.set('Cache-Control', /[\\/]assets[\\/]/.test(file) ? 'public, max-age=31536000, immutable' : 'no-cache'),
    }));
    app.get(/^\/(?!api\/).*/, (req, res) => { res.set('Cache-Control', 'no-cache'); res.sendFile(path.join(DIST, 'index.html')); });
    return true;
}

module.exports = { mountWeb };
