package com.matnotes.app

internal object HunspellNative {
    init { System.loadLibrary("mnspell") }
    @JvmStatic external fun open(aff: String, dic: String): Long
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun spell(h: Long, word: String): Boolean
    @JvmStatic external fun add(h: Long, word: String)
    @JvmStatic external fun suggest(h: Long, word: String): Array<String>
}

/** One loaded Hunspell dictionary (native/spell_jni.cpp). */
class Hunspell private constructor(private var handle: Long) {
    fun spell(word: String): Boolean = handle != 0L && HunspellNative.spell(handle, word)
    fun suggest(word: String): List<String> = if (handle == 0L) emptyList() else HunspellNative.suggest(handle, word).toList()
    fun add(word: String) { if (handle != 0L) HunspellNative.add(handle, word) }
    fun close() { if (handle != 0L) { HunspellNative.close(handle); handle = 0L } }

    companion object {
        fun open(aff: String, dic: String): Hunspell? = HunspellNative.open(aff, dic).let { if (it == 0L) null else Hunspell(it) }
    }
}
