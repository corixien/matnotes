package com.matnotes.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class StoredDoc(val uri: Uri, val name: String, val modified: Long)

/**
 * App-owned documents live in the shared "Documents/MatNotes" folder (visible in any file manager). MediaStore lets
 * an app create and later read/overwrite its own files there without any storage permission (Android 10+).
 */
object DocStore {
    const val FOLDER = "Documents/MatNotes/"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Creates an empty file (MediaStore renames on conflicts, e.g. "Name (1).docx"). */
    fun create(context: Context, displayName: String, mime: String): Uri? {
        if (!supported) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return runCatching { context.contentResolver.insert(collection(), values) }.getOrNull()
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()

    fun list(context: Context): List<StoredDoc> {
        if (!supported) return emptyList()
        val out = ArrayList<StoredDoc>()
        runCatching {
            context.contentResolver.query(
                collection(),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(FOLDER),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".docx", true) && !name.endsWith(".odt", true)) continue
                    out += StoredDoc(ContentUris.withAppendedId(collection(), c.getLong(0)), name, c.getLong(2) * 1000L)
                }
            }
        }
        return out
    }
}
