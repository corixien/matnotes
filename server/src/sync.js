const express = require('express');
const db = require('./db');
const { AppError, asyncHandler } = require('./errors');
const { authenticate } = require('./auth');

const router = express.Router();
router.use(authenticate);

// Everything that changed after cursor `since` for this user: folders (membership, key, name, members) and document metadata.
// Documents of a folder the user just joined are all returned, whatever their age. Row order is commit order (see db.js),
// so the returned cursor (highest change_seq seen) is safe to resume from.
router.get('/sync/changes', asyncHandler(async (req, res) => {
    const raw = req.query.since === undefined ? '0' : String(req.query.since);
    if (!/^\d{1,18}$/.test(raw)) throw new AppError(400, 'invalid_request', 'since must be a number');
    const since = raw;

    const folders = (await db.query(
        `SELECT f.id, f.owner_id, f.enc_name, f.deleted_at, f.change_seq, m.role, m.status, m.wrapped_key, m.change_seq AS member_seq
         FROM folders f JOIN folder_members m ON m.folder_id = f.id AND m.user_id = $1
         WHERE f.change_seq > $2 OR m.change_seq > $2
            OR EXISTS (SELECT 1 FROM folder_members o WHERE o.folder_id = f.id AND o.change_seq > $2)
         ORDER BY f.id`,
        [req.userId, since],
    )).rows;

    const members = folders.length ? (await db.query(
        `SELECT m.folder_id, m.user_id, u.name, m.role, m.status FROM folder_members m JOIN users u ON u.id = m.user_id
         WHERE m.folder_id = ANY($1::uuid[]) AND m.status IN ('active', 'invited') ORDER BY u.name`,
        [folders.map((f) => f.id)],
    )).rows : [];

    const docs = (await db.query(
        `SELECT d.id, d.folder_id, d.enc_name, d.version, d.size, d.deleted_at, d.updated_at, d.updated_by, d.change_seq
         FROM docs d
         JOIN folders f ON f.id = d.folder_id AND f.deleted_at IS NULL
         JOIN folder_members m ON m.folder_id = f.id AND m.user_id = $1 AND m.status = 'active'
         WHERE d.change_seq > $2 OR m.change_seq > $2
         ORDER BY d.change_seq`,
        [req.userId, since],
    )).rows;

    let cursor = BigInt(since);
    const bump = (n) => { const b = BigInt(n); if (b > cursor) cursor = b; };
    for (const f of folders) { bump(f.change_seq); bump(f.member_seq); }
    for (const d of docs) bump(d.change_seq);
    // member-only changes of other users raise no seq on the rows above: take the max over the folder's members too
    if (folders.length) {
        const m = await db.query('SELECT COALESCE(max(change_seq), 0) AS s FROM folder_members WHERE folder_id = ANY($1::uuid[])', [folders.map((f) => f.id)]);
        bump(m.rows[0].s);
    }

    res.json({
        cursor: cursor.toString(),
        folders: folders.map((f) => ({
            id: f.id,
            ownerId: f.owner_id,
            encName: f.enc_name,
            deleted: !!f.deleted_at,
            role: f.role,
            status: f.status,
            wrappedKey: f.wrapped_key,
            members: members.filter((m) => m.folder_id === f.id).map((m) => ({ userId: m.user_id, name: m.name, role: m.role, status: m.status })),
        })),
        docs: docs.map((d) => ({
            id: d.id,
            folderId: d.folder_id,
            encName: d.enc_name,
            version: d.version,
            size: Number(d.size),
            deleted: !!d.deleted_at,
            updatedAt: d.updated_at,
            updatedBy: d.updated_by,
        })),
    });
}));

module.exports = { router };
