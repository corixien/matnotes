class AppError extends Error {
    constructor(status, code, message, extra) {
        super(message || code);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }
}

const asyncHandler = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

function errorHandler(err, req, res, next) { // eslint-disable-line no-unused-vars
    if (err instanceof AppError) {
        return res.status(err.status).json({ error: err.code, message: err.message, ...(err.extra || {}) });
    }
    if (err && err.type === 'entity.too.large') return res.status(413).json({ error: 'too_large', message: 'Body too large' });
    if (err && err.type === 'entity.parse.failed') return res.status(400).json({ error: 'bad_json', message: 'Invalid JSON' });
    console.error('unhandled', err && err.stack ? err.stack : err);
    return res.status(500).json({ error: 'server_error', message: 'Internal error' });
}

module.exports = { AppError, asyncHandler, errorHandler };
