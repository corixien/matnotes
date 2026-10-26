package com.matnotes.app.sync

import android.content.Context
import android.net.Uri
import com.matnotes.app.DocStore
import com.matnotes.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SyncResult(var uploaded: Int = 0, var downloaded: Int = 0, var deleted: Int = 0, var conflicts: Int = 0, val errors: MutableList<String> = mutableListOf())

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    class Done(val time: Long, val result: SyncResult) : SyncStatus
    class Failed(val time: Long, val message: String, val offline: Boolean) : SyncStatus
}

/**
 * Keeps the local folders (Documents/MatNotes and its sub folders) and the encrypted cloud folders in step.
 *
 * Per cycle: read the change feed -> update folders (keys, names, members) and the known cloud state of each document ->
 * per folder compare the local files with the state of the last sync (hash) and the cloud (version) and act on the
 * result of [SyncPlanner]. Files are encrypted before the upload and decrypted after the download; only ciphertext and
 * a version number are exchanged. Whole files are synced, so a conflict keeps both versions (see [Action.CONFLICT]).
 */
class SyncEngine(private val context: Context) {
    private val db = SyncDb(context)
    private val lock = Mutex()
    private val keys = HashMap<String, ByteArray>()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> get() = _status

    companion object {
        const val MAX_FILE = 25 * 1024 * 1024
        val EXTENSIONS = setOf("docx", "odt", "pdf")
        private const val UNKNOWN = -1L
    }

    fun folders(): List<FolderRow> = db.folders()

    /** Forgets everything local about the account (keys, cursor, links). Files stay on the phone. */
    fun reset() {
        keys.clear()
        db.clear()
        _status.value = SyncStatus.Idle
    }

    /** Runs one sync cycle. [isOpen] tells which local files are open in the editor: those are never replaced or deleted. */
    suspend fun sync(isOpen: (Uri) -> Boolean = { false }): SyncResult = lock.withLock {
        val res = SyncResult()
        _status.value = SyncStatus.Running
        try {
            check(Account.loggedIn) { "not signed in" }
            pullFeed()
            ensurePersonalFolder()
            var pass = 0
            var again: Boolean
            do { // conflict copies are new files: a second pass uploads them
                again = false
                for (f in db.folders().filter { it.active }) {
                    try { if (syncFolder(f, isOpen, res)) again = true } catch (e: Exception) { res.errors += "${f.name}: ${e.message}" }
                }
                pass++
            } while (again && pass < 2)
            _status.value = SyncStatus.Done(System.currentTimeMillis(), res)
        } catch (e: Exception) {
            val offline = e is java.io.IOException && e !is ApiException
            res.errors += e.message ?: e.javaClass.simpleName
            _status.value = SyncStatus.Failed(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName, offline)
        }
        res
    }

    // ---- folders and the change feed ----

    private fun folderKey(f: FolderRow): ByteArray? {
        keys[f.id]?.let { return it }
        if (f.wrappedKey.isEmpty()) return null
        val priv = Account.privateKey ?: return null
        return runCatching { Vault.unwrapFolderKey(priv, f.wrappedKey) }.getOrNull()?.also { keys[f.id] = it }
    }

    private fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(60)
        return s.ifEmpty { "Folder" }
    }

    private fun uniqueDir(name: String, taken: Set<String>): String {
        val base = sanitize(name)
        var candidate = base
        var n = 2
        while (taken.any { it.equals(candidate, ignoreCase = true) }) candidate = "$base ($n)".also { n++ }
        return candidate
    }

    private suspend fun pullFeed() {
        val since = db.meta("cursor") ?: "0"
        val feed = Account.call("GET", "/sync/changes?since=$since")
        applyFolders(feed.getJSONArray("folders"))
        applyDocs(feed.getJSONArray("docs"))
        db.setMeta("cursor", feed.getString("cursor"))
    }

    private fun applyFolders(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val id = j.getString("id")
            val old = db.folder(id)
            val status = j.getString("status")
            val wrapped = j.getString("wrappedKey")
            val deleted = j.getBoolean("deleted")
            var f = FolderRow(
                id, j.optString("ownerId").ifEmpty { null }, old?.name ?: "…", j.optBoolean("personal"), j.getString("role"), status, wrapped,
                deleted, old?.localDir ?: "", j.getJSONArray("members").toString(),
            )
            keys.remove(id)
            if (status != "removed" && wrapped.isNotEmpty()) {
                val key = folderKey(f)
                if (key != null) runCatching { f = f.copy(name = Vault.openFolderName(key, id, j.getString("encName"))) }
            }
            if (f.active && f.localDir.isEmpty() && !f.personal) {
                f = f.copy(localDir = uniqueDir(f.name, db.folders().filter { it.id != id }.map { it.localDir }.toSet()))
            }
            db.putFolder(f)
        }
    }

    private fun applyDocs(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val folder = db.folder(j.getString("folderId")) ?: continue
            val id = j.getString("id")
            val old = db.doc(id)
            val key = folderKey(folder)
            val name = key?.let { runCatching { Vault.openDocName(it, id, j.getString("encName")) }.getOrNull() } ?: old?.name
            db.putDoc(
                old?.copy(name = name, remoteVersion = j.getInt("version"), remoteDeleted = j.getBoolean("deleted"))
                    ?: DocRow(id, folder.id, null, name, 0, null, UNKNOWN, UNKNOWN, j.getInt("version"), j.getBoolean("deleted")),
            )
        }
    }

    /** Every account has one personal folder; it maps to the main folder of the app. The first device creates it. */
    private suspend fun ensurePersonalFolder() {
        if (db.folders().any { it.personal && it.active }) return
        val pub = Account.publicKey ?: return
        val id = UUID.randomUUID().toString()
        val key = Vault.newFolderKey()
        try {
            Account.call(
                "POST", "/folders",
                JSONObject().put("id", id).put("personal", true)
                    .put("encName", Vault.sealFolderName(key, id, "Personal")).put("wrappedKey", Vault.wrapFolderKey(pub, key)),
            )
        } catch (e: ApiException) {
            if (e.code != "personal_exists") throw e // another device was faster: its folder arrives with the next feed
        }
        pullFeed()
    }

    // ---- one folder ----

    /** Returns true when files were created that need another pass (conflict copies). */
    private suspend fun syncFolder(folder: FolderRow, isOpen: (Uri) -> Boolean, res: SyncResult): Boolean {
        val key = folderKey(folder) ?: throw IllegalStateException(context.getString(R.string.sync_err_key))
        val local = DocStore.listIn(context, folder.localDir, EXTENSIONS) ?: throw IllegalStateException(context.getString(R.string.sync_err_list))
        val byUri = local.associateBy { it.uri.toString() }
        var again = false

        for (d in db.docs(folder.id)) {
            try { if (handleDoc(folder, key, d, byUri[d.uri], isOpen, res)) again = true } catch (e: Exception) {
                res.errors += "${d.name ?: d.id}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        // files that are not linked to a cloud document yet
        val linked = db.docs(folder.id).mapNotNull { it.uri }.toSet()
        for (f in local) {
            if (f.uri.toString() in linked) continue
            try { upload(folder, key, f, res) } catch (e: Exception) { res.errors += "${f.name}: ${e.message ?: e.javaClass.simpleName}" }
        }
        return again
    }

    private suspend fun handleDoc(folder: FolderRow, key: ByteArray, d: DocRow, file: com.matnotes.app.StoredDoc?, isOpen: (Uri) -> Boolean, res: SyncResult): Boolean {
        if (d.uri == null) { // known in the cloud, not on this phone yet
            if (d.remoteDeleted) { db.deleteDoc(d.id); return false }
            download(folder, key, d, createNew = true, res)
            return false
        }
        val uri = Uri.parse(d.uri)
        val exists = file != null
        var localChanged = false
        var hash: String? = null
        var bytes: ByteArray? = null
        if (file != null) {
            if (file.size == d.localSize && file.modified == d.localModified) {
                hash = d.baseHash
            } else {
                bytes = DocStore.read(context, uri) ?: throw IllegalStateException(context.getString(R.string.sync_err_read))
                hash = Crypto.b64(Crypto.sha256(bytes))
                localChanged = hash != d.baseHash
                if (!localChanged) db.putDoc(d.copy(localSize = file.size, localModified = file.modified))
            }
        }
        val remoteChanged = d.remoteVersion != d.baseVersion
        val action = SyncPlanner.plan(exists, localChanged, d.remoteDeleted, remoteChanged)
        // an open document is neither replaced nor deleted; it is picked up again on the next cycle
        if (isOpen(uri) && action in setOf(Action.PULL, Action.CONFLICT, Action.DELETE_LOCAL, Action.REDOWNLOAD)) return false
        when (action) {
            Action.NOTHING -> {}
            Action.FORGET -> db.deleteDoc(d.id)
            Action.DETACH -> db.deleteDoc(d.id) // the file stays and is uploaded as a new document right after
            Action.DELETE_LOCAL -> { DocStore.delete(context, uri); db.deleteDoc(d.id); res.deleted++ }
            Action.DELETE_REMOTE -> {
                try {
                    Account.call("DELETE", "/folders/${folder.id}/docs/${d.id}", headers = mapOf("If-Match" to d.baseVersion.toString()))
                    db.deleteDoc(d.id); res.deleted++
                } catch (e: ApiException) { if (e.status == 409) db.putDoc(d.copy(remoteVersion = e.serverVersion ?: d.remoteVersion, remoteDeleted = e.deleted)) else throw e }
            }
            Action.PUSH -> push(folder, key, d, file!!, bytes ?: DocStore.read(context, uri)!!, hash!!, res)
            Action.PULL -> download(folder, key, d, createNew = false, res)
            Action.REDOWNLOAD -> download(folder, key, d.copy(uri = null), createNew = true, res)
            Action.CONFLICT -> return resolveConflict(folder, key, d, file!!, bytes ?: DocStore.read(context, uri)!!, hash!!, res)
        }
        return false
    }

    private suspend fun push(folder: FolderRow, key: ByteArray, d: DocRow, file: com.matnotes.app.StoredDoc, bytes: ByteArray, hash: String, res: SyncResult) {
        if (bytes.size > MAX_FILE) throw IllegalStateException(context.getString(R.string.sync_err_too_large))
        val version = d.baseVersion + 1
        val sealed = Vault.sealDoc(key, d.id, version, bytes)
        try {
            Account.bytes(
                "PUT", "/folders/${folder.id}/docs/${d.id}",
                mapOf("If-Match" to d.baseVersion.toString(), "X-Enc-Name" to Vault.sealDocName(key, d.id, file.name), "X-Wrapped-Key" to sealed.wrappedKey),
                sealed.data,
            )
        } catch (e: ApiException) {
            // someone else wrote first: remember the new cloud version, the next cycle resolves it as a conflict
            if (e.status == 409) { db.putDoc(d.copy(remoteVersion = e.serverVersion ?: d.remoteVersion, remoteDeleted = e.deleted)); return }
            throw e
        }
        db.putDoc(d.copy(name = file.name, baseVersion = version, baseHash = hash, remoteVersion = version, localSize = file.size, localModified = file.modified))
        res.uploaded++
    }

    private suspend fun upload(folder: FolderRow, key: ByteArray, file: com.matnotes.app.StoredDoc, res: SyncResult) {
        if (file.size > MAX_FILE) throw IllegalStateException(context.getString(R.string.sync_err_too_large))
        val bytes = DocStore.read(context, file.uri) ?: throw IllegalStateException(context.getString(R.string.sync_err_read))
        val id = UUID.randomUUID().toString()
        val sealed = Vault.sealDoc(key, id, 1, bytes)
        Account.bytes(
            "PUT", "/folders/${folder.id}/docs/$id",
            mapOf("If-Match" to "0", "X-Enc-Name" to Vault.sealDocName(key, id, file.name), "X-Wrapped-Key" to sealed.wrappedKey),
            sealed.data,
        )
        db.putDoc(DocRow(id, folder.id, file.uri.toString(), file.name, 1, Crypto.b64(Crypto.sha256(bytes)), file.size, file.modified, 1, false))
        res.uploaded++
    }

    private class Remote(val version: Int, val plain: ByteArray)

    private suspend fun fetch(folder: FolderRow, key: ByteArray, id: String): Remote {
        val r = Account.bytes("GET", "/folders/${folder.id}/docs/$id/blob")
        val version = r.headers["x-version"]!!.toInt()
        return Remote(version, Vault.openDoc(key, id, version, r.headers["x-wrapped-key"]!!, r.body))
    }

    private suspend fun download(folder: FolderRow, key: ByteArray, d: DocRow, createNew: Boolean, res: SyncResult) {
        val remote = fetch(folder, key, d.id)
        val hash = Crypto.b64(Crypto.sha256(remote.plain))
        val uri: String = if (createNew || d.uri == null) {
            val name = safeName(d.name)
            val u = DocStore.create(context, name, DocStore.mimeFor(name), folder.localDir) ?: throw IllegalStateException(context.getString(R.string.sync_err_write))
            if (!DocStore.write(context, u, remote.plain)) { DocStore.delete(context, u); throw IllegalStateException(context.getString(R.string.sync_err_write)) }
            u.toString()
        } else {
            if (!DocStore.write(context, Uri.parse(d.uri), remote.plain)) throw IllegalStateException(context.getString(R.string.sync_err_write))
            d.uri
        }
        // size/modified are unknown after our own write: forces one hash check next cycle, which finds "unchanged"
        db.putDoc(d.copy(uri = uri, baseVersion = remote.version, baseHash = hash, remoteVersion = remote.version, localSize = UNKNOWN, localModified = UNKNOWN))
        res.downloaded++
    }

    /** The cloud version takes the original name; this phone's version survives as "<name> (conflict <date>)" and is uploaded as its own document. */
    private suspend fun resolveConflict(folder: FolderRow, key: ByteArray, d: DocRow, file: com.matnotes.app.StoredDoc, localBytes: ByteArray, localHash: String, res: SyncResult): Boolean {
        val remote = fetch(folder, key, d.id)
        val remoteHash = Crypto.b64(Crypto.sha256(remote.plain))
        if (remoteHash == localHash) { // both sides made the same change
            db.putDoc(d.copy(baseVersion = remote.version, baseHash = localHash, remoteVersion = remote.version, localSize = file.size, localModified = file.modified))
            return false
        }
        val copyName = conflictName(file.name)
        val copy = DocStore.create(context, copyName, DocStore.mimeFor(copyName), folder.localDir) ?: throw IllegalStateException(context.getString(R.string.sync_err_write))
        if (!DocStore.write(context, copy, localBytes)) { DocStore.delete(context, copy); throw IllegalStateException(context.getString(R.string.sync_err_write)) }
        if (!DocStore.write(context, Uri.parse(d.uri), remote.plain)) throw IllegalStateException(context.getString(R.string.sync_err_write))
        db.putDoc(d.copy(baseVersion = remote.version, baseHash = remoteHash, remoteVersion = remote.version, localSize = UNKNOWN, localModified = UNKNOWN))
        res.conflicts++
        return true
    }

    private fun conflictName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        return "$stem (${context.getString(R.string.sync_conflict_word)} $date)$ext"
    }

    private fun safeName(name: String?): String {
        val n = (name ?: "document.docx").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "document.docx" }
        return if (n.substringAfterLast('.', "").lowercase() in EXTENSIONS) n else "$n.docx"
    }
}
