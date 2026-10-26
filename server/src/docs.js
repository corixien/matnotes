const express = require('express');
const { config } = require('./config');
const db = require('./db');
const { AppError, asyncHandler } = require('./errors');
const v = require('./validate');
const store = require('./store');
const { authenticate } = require('./auth');

const MIN_CIPHERTEXT = 12 + 16 + 1; // AES-GCM nonce + tag + at least one byte

const router = express.Router();
router.use(authenticate);

const expectedVersion = (req) => {
    const raw = req.headers['if-match'];
    if (raw === undefined || !/^\d{1,9}$/.test(String(raw).replace(/"/g, ''))) throw new AppError(428, 'precondition_required', 'If-Match: <version> is required (0 creates)');
    return Number.parseInt(String(raw).replace(/"/g, ''), 10);
};

// Create (If-Match: 0) or replace (If-Match: <current version>) a document. The body is the ciphertext as it will be stored.
router.put('/folders/:fid/docs/:did', express.raw({ type: '*/*', limit: config.maxFileBytes + 1024 }), asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const did = v.uuid(req.params.did, 'doc');
    const expected = expectedVersion(req);
    const encName = v.b64(req.headers['x-enc-name'], 'X-Enc-Name', 2000);
    const wrappedKey = v.b64(req.headers['x-wrapped-key'], 'X-Wrapped-Key', 1000);
    const data = req.body;
    if (!Buffer.isBuffer(data) || data.length < MIN_CIPHERTEXT) throw new AppError(400, 'invalid_request', 'Body must be the encrypted file');
    if (data.length > config.maxFileBytes) throw new AppError(413, 'too_large', 'File too large');

    const out = await db.writeTransaction(async (c) => {
        const folder = await store.requireActive(c, fid, req.userId);
        let version;
        let seq;
        if (expected === 0) {
            const ins = await c.query(
                `INSERT INTO docs (id, folder_id, enc_name, version, size, created_by, updated_by) VALUES ($1, $2, $3, 1, $4, $5, $5)
                 ON CONFLICT (id) DO NOTHING RETURNING change_seq`,
                [did, fid, encName, data.length, req.userId],
            );
            if (!ins.rows[0]) throw new AppError(409, 'conflict', 'Document already exists', { serverVersion: null });
            version = 1;
            seq = ins.rows[0].change_seq;
        } else {
            const cur = await c.query('SELECT version, deleted_at FROM docs WHERE id = $1 AND folder_id = $2 FOR UPDATE', [did, fid]);
            if (!cur.rows[0]) throw new AppError(404, 'not_found', 'Document not found');
            if (cur.rows[0].version !== expected) throw new AppError(409, 'conflict', 'Document changed on the server', { serverVersion: cur.rows[0].version, deleted: !!cur.rows[0].deleted_at });
            version = expected + 1;
            const upd = await c.query(
                `UPDATE docs SET enc_name = $2, version = $3, size = $4, updated_by = $5, updated_at = now(), deleted_at = NULL, change_seq = ${store.nextSeq}
                 WHERE id = $1 RETURNING change_seq`,
                [did, encName, version, data.length, req.userId],
            );
            seq = upd.rows[0].change_seq;
        }
        await c.query('INSERT INTO doc_versions (doc_id, version, wrapped_key, data, size) VALUES ($1, $2, $3, $4, $5)', [did, version, wrappedKey, data, data.length]);
        await c.query('DELETE FROM doc_versions WHERE doc_id = $1 AND version <= $2', [did, version - config.keepVersions]);
        if (folder.owner_id && (await store.usedBytes(c, folder.owner_id)) > config.userQuotaBytes) {
            throw new AppError(413, 'quota', 'Storage quota of the folder owner is used up');
        }
        return { version, cursor: String(seq) };
    });
    res.json(out);
}));

router.get('/folders/:fid/docs/:did/blob', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const did = v.uuid(req.params.did, 'doc');
    const { rows } = await db.query(
        `SELECT d.version AS latest, d.deleted_at, v.version, v.wrapped_key, v.data
         FROM folders f
         JOIN folder_members m ON m.folder_id = f.id AND m.user_id = $3 AND m.status = 'active'
         JOIN docs d ON d.folder_id = f.id AND d.id = $2
         JOIN doc_versions v ON v.doc_id = d.id AND v.version = COALESCE($4::int, d.version)
         WHERE f.id = $1 AND f.deleted_at IS NULL`,
        [fid, did, req.userId, req.query.version ? Number.parseInt(req.query.version, 10) : null],
    );
    const r = rows[0];
    if (!r || r.deleted_at) throw new AppError(404, 'not_found', 'Document not found');
    const etag = `"${r.version}"`;
    if (req.headers['if-none-match'] === etag) return res.status(304).end();
    res.set({ 'Content-Type': 'application/octet-stream', ETag: etag, 'X-Version': String(r.version), 'X-Wrapped-Key': r.wrapped_key });
    return res.send(r.data);
}));

router.delete('/folders/:fid/docs/:did', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const did = v.uuid(req.params.did, 'doc');
    const expected = expectedVersion(req);
    const cursor = await db.writeTransaction(async (c) => {
        await store.requireActive(c, fid, req.userId);
        const cur = await c.query('SELECT version, deleted_at FROM docs WHERE id = $1 AND folder_id = $2 FOR UPDATE', [did, fid]);
        if (!cur.rows[0]) throw new AppError(404, 'not_found', 'Document not found');
        if (cur.rows[0].deleted_at) return null;
        if (cur.rows[0].version !== expected) throw new AppError(409, 'conflict', 'Document changed on the server', { serverVersion: cur.rows[0].version, deleted: false });
        await c.query('DELETE FROM doc_versions WHERE doc_id = $1', [did]);
        const u = await c.query(`UPDATE docs SET deleted_at = now(), size = 0, updated_by = $2, change_seq = ${store.nextSeq} WHERE id = $1 RETURNING change_seq`, [did, req.userId]);
        return u.rows[0].change_seq;
    });
    res.json({ cursor: cursor === null ? null : String(cursor) });
}));

module.exports = { router };
