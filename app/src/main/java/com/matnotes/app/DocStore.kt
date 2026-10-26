package com.matnotes.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class StoredDoc(val uri: Uri, val name: String, val modified: Long, val size: Long = 0L)

/**
 * App-owned documents live in the shared "Documents/MatNotes" folder (visible in any file manager). MediaStore lets
 * an app create and later read/overwrite its own files there without any storage permission (Android 10+).
 */
object DocStore {
    const val FOLDER = "Documents/MatNotes/"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Folder path of a sub folder ("" = the main folder); shared cloud folders live in their own sub folder. */
    private fun path(dir: String) = if (dir.isEmpty()) FOLDER else "$FOLDER$dir/"

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "odt" -> "application/vnd.oasis.opendocument.text"
        "pdf" -> "application/pdf"
        else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    /** Creates an empty file (MediaStore renames on conflicts, e.g. "Name (1).docx"). */
    fun create(context: Context, displayName: String, mime: String, dir: String = ""): Uri? {
        if (!supported) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path(dir))
        }
        return runCatching { context.contentResolver.insert(collection(), values) }.getOrNull()
    }

    fun read(context: Context, uri: Uri): ByteArray? = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    /** Replaces the content of one of our own files. */
    fun write(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()

    /** Null when the query failed (callers must not read that as "no files"). */
    fun listIn(context: Context, dir: String, extensions: Set<String> = setOf("docx", "odt")): List<StoredDoc>? {
        if (!supported) return emptyList()
        val out = ArrayList<StoredDoc>()
        val ok = runCatching {
            context.contentResolver.query(
                collection(),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(path(dir)),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in extensions) continue
                    out += StoredDoc(ContentUris.withAppendedId(collection(), c.getLong(0)), name, c.getLong(2) * 1000L, c.getLong(3))
                }
            } != null
        }.getOrDefault(false)
        return if (ok) out else null
    }

    fun list(context: Context): List<StoredDoc> = listIn(context, "").orEmpty()
}
