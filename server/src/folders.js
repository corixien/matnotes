const express = require('express');
const db = require('./db');
const { AppError, asyncHandler } = require('./errors');
const v = require('./validate');
const store = require('./store');
const { authenticate } = require('./auth');

const MAX_MEMBERS = 20;
const MAX_OWNED_FOLDERS = 50;

const router = express.Router();
router.use(authenticate);

// Public key of another user, needed to wrap a folder key for them. 404 for unknown e-mails.
router.get('/users/lookup', asyncHandler(async (req, res) => {
    const email = v.email(req.query.email);
    const { rows } = await db.query('SELECT id, name, public_key FROM users WHERE lower(email) = $1', [email]);
    if (!rows[0]) throw new AppError(404, 'not_found', 'No account with this e-mail');
    res.json({ id: rows[0].id, name: rows[0].name, publicKey: rows[0].public_key });
}));

router.post('/folders', asyncHandler(async (req, res) => {
    const id = v.uuid(req.body.id, 'id');
    const encName = v.b64(req.body.encName, 'encName', 2000);
    const wrappedKey = v.b64(req.body.wrappedKey, 'wrappedKey', 1000);
    const personal = req.body.personal === true;
    const cursor = await db.writeTransaction(async (c) => {
        if (personal) {
            const p = await c.query('SELECT 1 FROM folders WHERE owner_id = $1 AND personal AND deleted_at IS NULL', [req.userId]);
            if (p.rows[0]) throw new AppError(409, 'personal_exists', 'The personal folder already exists');
        }
        const n = await c.query('SELECT count(*)::int AS n FROM folders WHERE owner_id = $1 AND deleted_at IS NULL', [req.userId]);
        if (n.rows[0].n >= MAX_OWNED_FOLDERS) throw new AppError(409, 'limit', 'Too many folders');
        const f = await c.query('INSERT INTO folders (id, owner_id, enc_name, personal) VALUES ($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING RETURNING change_seq', [id, req.userId, encName, personal]);
        if (!f.rows[0]) throw new AppError(409, 'exists', 'Folder id already exists');
        await c.query(`INSERT INTO folder_members (folder_id, user_id, role, status, wrapped_key) VALUES ($1, $2, 'owner', 'active', $3)`, [id, req.userId, wrappedKey]);
        return f.rows[0].change_seq;
    });
    res.status(201).json({ cursor: String(cursor) });
}));

router.patch('/folders/:fid', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const encName = v.b64(req.body.encName, 'encName', 2000);
    await db.writeTransaction(async (c) => {
        await store.requireActive(c, fid, req.userId, { owner: true });
        await c.query(`UPDATE folders SET enc_name = $2, change_seq = ${store.nextSeq} WHERE id = $1`, [fid, encName]);
    });
    res.json({ ok: true });
}));

router.delete('/folders/:fid', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    await db.writeTransaction(async (c) => {
        await store.requireActive(c, fid, req.userId, { owner: true });
        await store.tombstoneFolder(c, fid);
    });
    res.json({ ok: true });
}));

// Invitation: the inviter wraps the folder key with the invitee's public key (fetched via /users/lookup) right away,
// so the invitee can accept later without the inviter being online.
router.post('/folders/:fid/members', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const userId = v.uuid(req.body.userId, 'userId');
    const wrappedKey = v.b64(req.body.wrappedKey, 'wrappedKey', 1000);
    if (userId === req.userId) throw new AppError(400, 'invalid_request', 'Cannot invite yourself');
    await db.writeTransaction(async (c) => {
        await store.requireActive(c, fid, req.userId, { owner: true });
        const u = await c.query('SELECT id FROM users WHERE id = $1', [userId]);
        if (!u.rows[0]) throw new AppError(404, 'not_found', 'User not found');
        const count = await c.query(`SELECT count(*)::int AS n FROM folder_members WHERE folder_id = $1 AND status IN ('active', 'invited')`, [fid]);
        if (count.rows[0].n >= MAX_MEMBERS) throw new AppError(409, 'limit', 'Too many members');
        const cur = await c.query('SELECT status FROM folder_members WHERE folder_id = $1 AND user_id = $2 FOR UPDATE', [fid, userId]);
        if (cur.rows[0] && cur.rows[0].status !== 'removed') throw new AppError(409, 'already_member', 'Already invited or a member');
        if (cur.rows[0]) {
            await c.query(`UPDATE folder_members SET status = 'invited', role = 'editor', wrapped_key = $3, invited_by = $4, change_seq = ${store.nextSeq} WHERE folder_id = $1 AND user_id = $2`, [fid, userId, wrappedKey, req.userId]);
        } else {
            await c.query(`INSERT INTO folder_members (folder_id, user_id, role, status, wrapped_key, invited_by) VALUES ($1, $2, 'editor', 'invited', $3, $4)`, [fid, userId, wrappedKey, req.userId]);
        }
    });
    res.status(201).json({ ok: true });
}));

async function answerInvite(req, res, accept) {
    const fid = v.uuid(req.params.fid, 'folder');
    await db.writeTransaction(async (c) => {
        const { rows } = await c.query(
            `SELECT m.status FROM folder_members m JOIN folders f ON f.id = m.folder_id
             WHERE m.folder_id = $1 AND m.user_id = $2 AND f.deleted_at IS NULL FOR UPDATE OF m`,
            [fid, req.userId],
        );
        if (!rows[0] || rows[0].status !== 'invited') throw new AppError(404, 'not_found', 'No invitation');
        await c.query(
            `UPDATE folder_members SET status = $3, wrapped_key = CASE WHEN $3 = 'active' THEN wrapped_key ELSE '' END, change_seq = ${store.nextSeq}
             WHERE folder_id = $1 AND user_id = $2`,
            [fid, req.userId, accept ? 'active' : 'removed'],
        );
    });
    res.json({ ok: true });
}

router.post('/folders/:fid/accept', asyncHandler((req, res) => answerInvite(req, res, true)));
router.post('/folders/:fid/decline', asyncHandler((req, res) => answerInvite(req, res, false)));

// Owner removes a member (or cancels an invitation); a member removes themself. The owner leaves by deleting the folder.
// Note: the removed user keeps the folder key they already had and any data they downloaded; the server just stops serving them.
router.delete('/folders/:fid/members/:uid', asyncHandler(async (req, res) => {
    const fid = v.uuid(req.params.fid, 'folder');
    const uid = v.uuid(req.params.uid, 'user');
    await db.writeTransaction(async (c) => {
        const f = await c.query('SELECT owner_id FROM folders WHERE id = $1 AND deleted_at IS NULL', [fid]);
        if (!f.rows[0]) throw new AppError(404, 'not_found', 'Folder not found');
        const isOwner = f.rows[0].owner_id === req.userId;
        if (!isOwner && uid !== req.userId) throw new AppError(403, 'forbidden', 'Only the owner can remove others');
        if (uid === f.rows[0].owner_id) throw new AppError(400, 'invalid_request', 'The owner cannot be removed; delete the folder instead');
        const r = await c.query(
            `UPDATE folder_members SET status = 'removed', wrapped_key = '', change_seq = ${store.nextSeq}
             WHERE folder_id = $1 AND user_id = $2 AND status <> 'removed'`,
            [fid, uid],
        );
        if (!r.rowCount) throw new AppError(404, 'not_found', 'Not a member');
    });
    res.json({ ok: true });
}));

module.exports = { router };
