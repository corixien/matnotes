const crypto = require('crypto');
const express = require('express');
const jwt = require('jsonwebtoken');
const rateLimit = require('express-rate-limit');
const { promisify } = require('util');
const { config } = require('./config');
const db = require('./db');
const { AppError, asyncHandler } = require('./errors');
const v = require('./validate');
const store = require('./store');

const scrypt = promisify(crypto.scrypt);
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

// ---- hashing of the client-derived keys (the password itself never reaches the server) ----

async function hashSecret(secret) {
    const salt = crypto.randomBytes(16);
    const hash = await scrypt(secret, salt, 32, { N: 16384, r: 8, p: 1 });
    return `scrypt1$${salt.toString('base64')}$${hash.toString('base64')}`;
}

async function verifySecret(secret, stored) {
    const [tag, saltB64, hashB64] = (stored || '').split('$');
    if (tag !== 'scrypt1' || !saltB64 || !hashB64) return false;
    const expected = Buffer.from(hashB64, 'base64');
    const actual = await scrypt(secret, Buffer.from(saltB64, 'base64'), expected.length, { N: 16384, r: 8, p: 1 });
    return crypto.timingSafeEqual(actual, expected);
}

const DUMMY_HASH = `scrypt1$${Buffer.alloc(16).toString('base64')}$${Buffer.alloc(32).toString('base64')}`;

// Unknown e-mails get a stable fake salt / recovery blob, so /auth/params does not reveal which accounts exist.
const fake = (label, email, bytes) => crypto.createHmac('sha256', config.jwtSecret).update(`${label}:${email}`).digest().subarray(0, bytes).toString('base64');

// ---- tokens ----

const signAccess = (userId) => jwt.sign({ sub: userId }, config.jwtSecret, { algorithm: 'HS256', expiresIn: config.accessTokenSeconds });

async function issueTokens(client, userId, deviceName) {
    const refresh = crypto.randomBytes(32).toString('base64url');
    await client.query(
        `INSERT INTO refresh_tokens (user_id, token_hash, device_name, expires_at)
         VALUES ($1, $2, $3, now() + make_interval(days => $4))`,
        [userId, sha256(refresh), (deviceName || '').slice(0, 80) || null, config.refreshTokenDays],
    );
    return { accessToken: signAccess(userId), refreshToken: refresh, expiresIn: config.accessTokenSeconds };
}

function authenticate(req, res, next) {
    const h = req.headers.authorization || '';
    const m = /^Bearer (.+)$/.exec(h);
    if (!m) return next(new AppError(401, 'unauthorized', 'Missing token'));
    try {
        req.userId = jwt.verify(m[1], config.jwtSecret, { algorithms: ['HS256'] }).sub;
        return next();
    } catch (e) {
        return next(new AppError(401, 'unauthorized', 'Invalid or expired token'));
    }
}

const limiter = (max, windowMs = 15 * 60 * 1000) => rateLimit({
    windowMs,
    limit: max,
    standardHeaders: true,
    legacyHeaders: false,
    skip: () => process.env.RATE_LIMIT_DISABLED === '1',
    handler: (req, res) => res.status(429).json({ error: 'rate_limited', message: 'Too many attempts, try again later' }),
});

const profile = (u) => ({
    id: u.id,
    name: u.name,
    email: u.email,
    publicKey: u.public_key,
    encPrivateKey: u.enc_private_key,
    kdfSalt: u.kdf_salt,
    kdfIterations: u.kdf_iterations,
});

const router = express.Router();

router.post('/auth/params', limiter(120), asyncHandler(async (req, res) => {
    const email = v.email(req.body.email);
    const { rows } = await db.query('SELECT kdf_salt, kdf_iterations FROM users WHERE lower(email) = $1', [email]);
    if (rows[0]) return res.json({ salt: rows[0].kdf_salt, iterations: rows[0].kdf_iterations });
    res.json({ salt: fake('salt', email, 16), iterations: config.kdfIterations });
}));

router.post('/auth/register', limiter(20), asyncHandler(async (req, res) => {
    const b = req.body;
    const user = {
        name: v.name(b.name),
        email: v.email(b.email),
        authKey: v.b64(b.authKey, 'authKey', 100, 20),
        salt: v.b64(b.kdfSalt, 'kdfSalt', 64, 8),
        iterations: v.iterations(b.kdfIterations),
        publicKey: v.b64(b.publicKey, 'publicKey', 1500, 100),
        encPrivateKey: v.b64(b.encPrivateKey, 'encPrivateKey', 8000, 100),
        recoveryAuthKey: v.b64(b.recoveryAuthKey, 'recoveryAuthKey', 100, 20),
        encPrivateKeyRecovery: v.b64(b.encPrivateKeyRecovery, 'encPrivateKeyRecovery', 8000, 100),
    };
    const authHash = await hashSecret(user.authKey);
    const recoveryHash = await hashSecret(user.recoveryAuthKey);
    const out = await db.transaction(async (c) => {
        const ins = await c.query(
            `INSERT INTO users (name, email, auth_hash, kdf_salt, kdf_iterations, public_key, enc_private_key, recovery_auth_hash, enc_private_key_recovery)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
             ON CONFLICT (lower(email)) DO NOTHING RETURNING *`,
            [user.name, user.email, authHash, user.salt, user.iterations, user.publicKey, user.encPrivateKey, recoveryHash, user.encPrivateKeyRecovery],
        );
        if (!ins.rows[0]) throw new AppError(409, 'email_taken', 'This e-mail is already registered');
        return { user: profile(ins.rows[0]), ...(await issueTokens(c, ins.rows[0].id, b.deviceName)) };
    });
    res.status(201).json(out);
}));

router.post('/auth/login', limiter(30), asyncHandler(async (req, res) => {
    const email = v.email(req.body.email);
    const authKey = v.b64(req.body.authKey, 'authKey', 100, 20);
    const { rows } = await db.query('SELECT * FROM users WHERE lower(email) = $1', [email]);
    const ok = await verifySecret(authKey, rows[0] ? rows[0].auth_hash : DUMMY_HASH);
    if (!rows[0] || !ok) throw new AppError(401, 'invalid_credentials', 'E-mail or password is wrong');
    const tokens = await db.transaction((c) => issueTokens(c, rows[0].id, req.body.deviceName));
    res.json({ user: profile(rows[0]), ...tokens });
}));

// Refresh tokens rotate on every use. A token that was already used and shows up again means it leaked:
// every session of that user is revoked.
router.post('/auth/refresh', limiter(120), asyncHandler(async (req, res) => {
    const token = typeof req.body.refreshToken === 'string' ? req.body.refreshToken : '';
    if (!token || token.length > 200) throw new AppError(401, 'unauthorized', 'Invalid refresh token');
    const out = await db.transaction(async (c) => {
        const { rows } = await c.query('SELECT * FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE', [sha256(token)]);
        const t = rows[0];
        if (!t) return null;
        if (t.revoked_at) {
            await c.query('UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [t.user_id]);
            return 'reuse';
        }
        if (new Date(t.expires_at) < new Date()) return null;
        await c.query('UPDATE refresh_tokens SET revoked_at = now() WHERE id = $1', [t.id]);
        return issueTokens(c, t.user_id, t.device_name);
    });
    if (!out || out === 'reuse') throw new AppError(401, 'unauthorized', 'Invalid refresh token');
    res.json(out);
}));

router.post('/auth/logout', limiter(120), asyncHandler(async (req, res) => {
    const token = typeof req.body.refreshToken === 'string' ? req.body.refreshToken : '';
    if (token) await db.query('UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL', [sha256(token)]);
    res.json({ ok: true });
}));

router.get('/auth/me', authenticate, asyncHandler(async (req, res) => {
    const { rows } = await db.query('SELECT * FROM users WHERE id = $1', [req.userId]);
    if (!rows[0]) throw new AppError(401, 'unauthorized', 'Unknown user');
    res.json({ user: profile(rows[0]) });
}));

async function loadVerified(userId, authKey) {
    const { rows } = await db.query('SELECT * FROM users WHERE id = $1', [userId]);
    if (!rows[0] || !(await verifySecret(authKey, rows[0].auth_hash))) throw new AppError(401, 'invalid_credentials', 'Password is wrong');
    return rows[0];
}

const newSecrets = (b) => ({
    authKey: v.b64(b.newAuthKey, 'newAuthKey', 100, 20),
    salt: v.b64(b.newKdfSalt, 'newKdfSalt', 64, 8),
    iterations: v.iterations(b.newKdfIterations),
    encPrivateKey: v.b64(b.newEncPrivateKey, 'newEncPrivateKey', 8000, 100),
});

router.post('/auth/password', authenticate, limiter(20), asyncHandler(async (req, res) => {
    const current = v.b64(req.body.currentAuthKey, 'currentAuthKey', 100, 20);
    const n = newSecrets(req.body);
    await loadVerified(req.userId, current);
    const hash = await hashSecret(n.authKey);
    const out = await db.transaction(async (c) => {
        await c.query('UPDATE users SET auth_hash = $2, kdf_salt = $3, kdf_iterations = $4, enc_private_key = $5 WHERE id = $1', [req.userId, hash, n.salt, n.iterations, n.encPrivateKey]);
        await c.query('UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [req.userId]);
        return issueTokens(c, req.userId, req.body.deviceName);
    });
    res.json(out);
}));

router.post('/auth/recover/start', limiter(20), asyncHandler(async (req, res) => {
    const email = v.email(req.body.email);
    const { rows } = await db.query('SELECT public_key, enc_private_key_recovery FROM users WHERE lower(email) = $1', [email]);
    if (rows[0]) return res.json({ encPrivateKeyRecovery: rows[0].enc_private_key_recovery, publicKey: rows[0].public_key });
    res.json({ encPrivateKeyRecovery: fake('recovery', email, 32).repeat(40), publicKey: fake('pub', email, 32).repeat(20) });
}));

router.post('/auth/recover/finish', limiter(10), asyncHandler(async (req, res) => {
    const email = v.email(req.body.email);
    const proof = v.b64(req.body.recoveryAuthKey, 'recoveryAuthKey', 100, 20);
    const n = newSecrets(req.body);
    const { rows } = await db.query('SELECT * FROM users WHERE lower(email) = $1', [email]);
    const ok = await verifySecret(proof, rows[0] ? rows[0].recovery_auth_hash : DUMMY_HASH);
    if (!rows[0] || !ok) throw new AppError(401, 'invalid_credentials', 'Recovery key is wrong');
    const hash = await hashSecret(n.authKey);
    const out = await db.transaction(async (c) => {
        await c.query('UPDATE users SET auth_hash = $2, kdf_salt = $3, kdf_iterations = $4, enc_private_key = $5 WHERE id = $1', [rows[0].id, hash, n.salt, n.iterations, n.encPrivateKey]);
        await c.query('UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [rows[0].id]);
        return issueTokens(c, rows[0].id, req.body.deviceName);
    });
    res.json(out);
}));

// Account deletion (required for apps with accounts): owned folders are tombstoned so other members learn about it.
router.post('/auth/delete', authenticate, limiter(10), asyncHandler(async (req, res) => {
    await loadVerified(req.userId, v.b64(req.body.authKey, 'authKey', 100, 20));
    await db.writeTransaction(async (c) => {
        const owned = await c.query('SELECT id FROM folders WHERE owner_id = $1 AND deleted_at IS NULL', [req.userId]);
        for (const f of owned.rows) await store.tombstoneFolder(c, f.id);
        await c.query(`UPDATE folder_members SET status = 'removed', wrapped_key = '', change_seq = nextval('change_seq') WHERE user_id = $1 AND status <> 'removed'`, [req.userId]);
        await c.query('DELETE FROM users WHERE id = $1', [req.userId]);
    });
    res.json({ ok: true });
}));

module.exports = { router, authenticate };
