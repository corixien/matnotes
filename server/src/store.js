// Shared write helpers. Callers hold the global write lock (db.writeTransaction).
const { AppError } = require('./errors');

const nextSeq = "nextval('change_seq')";

/** Folder + all its documents become tombstones and lose their data, so other members learn about it and storage is freed. */
async function tombstoneFolder(c, folderId) {
    await c.query(`UPDATE folders SET deleted_at = now(), change_seq = ${nextSeq} WHERE id = $1`, [folderId]);
    await c.query('DELETE FROM doc_versions WHERE doc_id IN (SELECT id FROM docs WHERE folder_id = $1)', [folderId]);
    await c.query(`UPDATE docs SET deleted_at = now(), size = 0, change_seq = ${nextSeq} WHERE folder_id = $1 AND deleted_at IS NULL`, [folderId]);
}

/** Active membership of an existing, not deleted folder; throws 404 otherwise (no hint whether the folder exists). */
async function requireActive(c, folderId, userId, { owner = false } = {}) {
    const { rows } = await c.query(
        `SELECT f.id, f.owner_id, m.role FROM folders f
         JOIN folder_members m ON m.folder_id = f.id AND m.user_id = $2 AND m.status = 'active'
         WHERE f.id = $1 AND f.deleted_at IS NULL`,
        [folderId, userId],
    );
    if (!rows[0]) throw new AppError(404, 'not_found', 'Folder not found');
    if (owner && rows[0].role !== 'owner') throw new AppError(403, 'forbidden', 'Only the owner can do this');
    return rows[0];
}

async function usedBytes(c, ownerId) {
    const { rows } = await c.query(
        `SELECT COALESCE(sum(v.size), 0)::bigint AS used FROM doc_versions v
         JOIN docs d ON d.id = v.doc_id JOIN folders f ON f.id = d.folder_id WHERE f.owner_id = $1`,
        [ownerId],
    );
    return Number(rows[0].used);
}

module.exports = { nextSeq, tombstoneFolder, requireActive, usedBytes };
