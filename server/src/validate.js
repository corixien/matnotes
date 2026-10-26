const { AppError } = require('./errors');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const B64 = /^[A-Za-z0-9+/_-]+={0,2}$/;
const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,}$/;

const bad = (msg) => new AppError(400, 'invalid_request', msg);

function uuid(v, field) {
    if (typeof v !== 'string' || !UUID.test(v)) throw bad(`${field} must be a UUID`);
    return v.toLowerCase();
}

function b64(v, field, maxLen, minLen = 1) {
    if (typeof v !== 'string' || v.length < minLen || v.length > maxLen || !B64.test(v)) throw bad(`${field} must be base64 (${minLen}-${maxLen} chars)`);
    return v;
}

function email(v) {
    if (typeof v !== 'string' || v.length > 320 || !EMAIL.test(v.trim())) throw bad('email is invalid');
    return v.trim().toLowerCase();
}

function name(v) {
    if (typeof v !== 'string') throw bad('name is required');
    const t = v.trim();
    if (t.length < 1 || t.length > 60) throw bad('name must be 1-60 characters');
    return t;
}

function iterations(v) {
    if (!Number.isInteger(v) || v < 100000 || v > 5000000) throw bad('kdf iterations out of range');
    return v;
}

module.exports = { uuid, b64, email, name, iterations, bad };
