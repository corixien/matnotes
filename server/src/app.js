const express = require('express');
const db = require('./db');
const { errorHandler, AppError } = require('./errors');

function createApp() {
    const app = express();
    app.set('trust proxy', 1); // Render terminates TLS in front of the service
    app.disable('x-powered-by');
    app.use((req, res, next) => { res.set({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); next(); });

    app.get('/api/health', async (req, res) => {
        try { await db.query('SELECT 1'); res.json({ ok: true }); } catch (e) { res.status(503).json({ ok: false }); }
    });

    const json = express.json({ limit: '64kb' });
    const mount = (router) => app.use('/api', (req, res, next) => (req.method === 'PUT' && /\/docs\//.test(req.path) ? next() : json(req, res, next)), router);
    mount(require('./auth').router);
    mount(require('./folders').router);
    mount(require('./docs').router);
    mount(require('./sync').router);

    app.use((req, res, next) => next(new AppError(404, 'not_found', 'Not found')));
    app.use(errorHandler);
    return app;
}

module.exports = { createApp };
