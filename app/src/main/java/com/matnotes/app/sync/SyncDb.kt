package com.matnotes.app.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FolderRow(
    val id: String, val ownerId: String?, val name: String, val personal: Boolean, val role: String, val status: String,
    val wrappedKey: String, val deleted: Boolean, val localDir: String, val members: String,
) {
    /** Documents of this folder are synced. */
    val active: Boolean get() = status == "active" && !deleted
}

data class DocRow(
    val id: String, val folderId: String, val uri: String?, val name: String?,
    val baseVersion: Int, val baseHash: String?, val localSize: Long, val localModified: Long,
    val remoteVersion: Int, val remoteDeleted: Boolean,
)

/** Local bookkeeping of the sync: cursor, folders (with their wrapped keys) and the link between local files and cloud documents. */
class SyncDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "sync.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE folders (id TEXT PRIMARY KEY, owner_id TEXT, name TEXT NOT NULL, personal INTEGER NOT NULL, role TEXT NOT NULL,
               status TEXT NOT NULL, wrapped_key TEXT NOT NULL, deleted INTEGER NOT NULL, local_dir TEXT NOT NULL, members TEXT NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE docs (id TEXT PRIMARY KEY, folder_id TEXT NOT NULL, uri TEXT, name TEXT, base_version INTEGER NOT NULL,
               base_hash TEXT, local_size INTEGER NOT NULL, local_modified INTEGER NOT NULL, remote_version INTEGER NOT NULL, remote_deleted INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX docs_folder ON docs (folder_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun meta(key: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clear() {
        writableDatabase.apply { delete("meta", null, null); delete("folders", null, null); delete("docs", null, null) }
    }

    // ---- folders ----

    private fun folder(c: android.database.Cursor) = FolderRow(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getString(8), c.getString(9),
    )

    private val folderCols = "id, owner_id, name, personal, role, status, wrapped_key, deleted, local_dir, members"

    fun folders(): List<FolderRow> = readableDatabase.rawQuery("SELECT $folderCols FROM folders ORDER BY personal DESC, name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(folder(c)) }
    }

    fun folder(id: String): FolderRow? = readableDatabase.rawQuery("SELECT $folderCols FROM folders WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) folder(it) else null }

    fun putFolder(f: FolderRow) {
        writableDatabase.insertWithOnConflict(
            "folders", null,
            ContentValues().apply {
                put("id", f.id); put("owner_id", f.ownerId); put("name", f.name); put("personal", if (f.personal) 1 else 0); put("role", f.role)
                put("status", f.status); put("wrapped_key", f.wrappedKey); put("deleted", if (f.deleted) 1 else 0); put("local_dir", f.localDir); put("members", f.members)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- documents ----

    private fun doc(c: android.database.Cursor) = DocRow(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5), c.getLong(6), c.getLong(7), c.getInt(8), c.getInt(9) == 1,
    )

    private val docCols = "id, folder_id, uri, name, base_version, base_hash, local_size, local_modified, remote_version, remote_deleted"

    fun docs(folderId: String): List<DocRow> = readableDatabase.rawQuery("SELECT $docCols FROM docs WHERE folder_id = ?", arrayOf(folderId)).use { c ->
        buildList { while (c.moveToNext()) add(doc(c)) }
    }

    fun doc(id: String): DocRow? = readableDatabase.rawQuery("SELECT $docCols FROM docs WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) doc(it) else null }

    fun docByUri(uri: String): DocRow? = readableDatabase.rawQuery("SELECT $docCols FROM docs WHERE uri = ?", arrayOf(uri)).use { if (it.moveToFirst()) doc(it) else null }

    fun putDoc(d: DocRow) {
        writableDatabase.insertWithOnConflict(
            "docs", null,
            ContentValues().apply {
                put("id", d.id); put("folder_id", d.folderId); put("uri", d.uri); put("name", d.name); put("base_version", d.baseVersion); put("base_hash", d.baseHash)
                put("local_size", d.localSize); put("local_modified", d.localModified); put("remote_version", d.remoteVersion); put("remote_deleted", if (d.remoteDeleted) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteDoc(id: String) { writableDatabase.delete("docs", "id = ?", arrayOf(id)) }
}
