package com.matnotes.app

import android.content.Context

data class RecentFile(val uri: String, val name: String, val time: Long)

/** Last opened documents, kept in SharedPreferences (name + uri only). */
class Recents(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull {
        val p = it.split('\t')
        if (p.size == 3) RecentFile(p[1], p[2], p[0].toLongOrNull() ?: 0L) else null
    }.sortedByDescending { it.time }

    fun add(uri: String, name: String) {
        val all = list().filter { it.uri != uri }.toMutableList()
        all.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        prefs.edit().putStringSet(KEY, all.take(20).map { "${it.time}\t${it.uri}\t${it.name}" }.toSet()).apply()
    }

    fun remove(uri: String) {
        prefs.edit().putStringSet(KEY, list().filter { it.uri != uri }.map { "${it.time}\t${it.uri}\t${it.name}" }.toSet()).apply()
    }

    private companion object { const val KEY = "files" }
}
